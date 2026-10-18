"""
Bibliothek bearbeiten: Alben finden (über die Tags der Dateien), Tags ändern, Cover setzen,
in den Papierkorb verschieben/wiederherstellen und eigene Dateien ablegen.

Navidrome liefert keine echten Dateipfade, deshalb sucht der Helfer die Dateien eines Albums über
Album- und Künstler-Tags im Musikordner. Ordner werden nie als Ganzes gelöscht, weil manche Künstler-
ordner mehrere Alben flach enthalten – es werden genau die Dateien des Albums bewegt.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import threading
import time
import uuid
from pathlib import Path
from typing import Any

import mutagen
from mutagen.flac import FLAC, Picture
from mutagen.id3 import APIC, ID3, ID3NoHeaderError
from mutagen.mp4 import MP4, MP4Cover

from matching import _ascii, safe_name

AUDIO = {".mp3", ".flac", ".m4a", ".mp4", ".ogg", ".opus", ".oga", ".wav", ".aac", ".wma", ".aiff", ".aif"}
TRASH_NAME = ".navidroid-papierkorb"
TRASH_DAYS = 30
COVER_NAMES = ("cover", "folder", "front")


class LibraryError(RuntimeError):
    pass


def norm(text: str | None) -> str:
    s = _ascii(text or "")
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def _first(tags: Any, key: str) -> str:
    try:
        v = tags.get(key)
    except Exception:
        return ""
    if isinstance(v, list):
        v = v[0] if v else ""
    return str(v or "").strip()


def _num(value: str) -> int:
    m = re.match(r"\s*(\d+)", value or "")
    return int(m.group(1)) if m else 0


def read_tags(path: Path) -> dict:
    try:
        f = mutagen.File(path, easy=True)
    except Exception:
        f = None
    tags = getattr(f, "tags", None) or {}
    return {
        "path": str(path),
        "album": _first(tags, "album"),
        "albumartist": _first(tags, "albumartist"),
        "artist": _first(tags, "artist"),
        "title": _first(tags, "title") or path.stem,
        "track": _num(_first(tags, "tracknumber")),
        "disc": _num(_first(tags, "discnumber")) or 1,
    }


class Index:
    """Tags aller Dateien im Musikordner, nach Änderungszeit zwischengespeichert."""

    def __init__(self, root: Path):
        self.root = root
        self._cache: dict[str, tuple[float, dict]] = {}
        self._lock = threading.Lock()

    def files(self) -> list[dict]:
        out = []
        seen = set()
        with self._lock:
            for dirpath, dirnames, filenames in os.walk(self.root):
                dirnames[:] = [d for d in dirnames if not d.startswith(".")]
                for name in filenames:
                    p = Path(dirpath) / name
                    if p.suffix.lower() not in AUDIO or name.startswith("."):
                        continue
                    key = str(p)
                    seen.add(key)
                    try:
                        mtime = p.stat().st_mtime
                    except OSError:
                        continue
                    hit = self._cache.get(key)
                    if not hit or hit[0] != mtime:
                        hit = (mtime, read_tags(p))
                        self._cache[key] = hit
                    out.append(hit[1])
            for key in list(self._cache):
                if key not in seen:
                    del self._cache[key]
        return out

    def forget(self, paths: list[str]) -> None:
        with self._lock:
            for p in paths:
                self._cache.pop(p, None)


def _artist_matches(want: str, f: dict) -> bool:
    w = norm(want)
    if not w:
        return True
    for a in (f["albumartist"], f["artist"]):
        n = norm(a)
        if n and (n == w or n.startswith(w) or w.startswith(n) or w in n):
            return True
    return False


def album_files(index: Index, artist: str, album: str) -> list[dict]:
    a = norm(album)
    if not a:
        raise LibraryError("Albumname fehlt")
    hits = [f for f in index.files() if norm(f["album"]) == a and _artist_matches(artist, f)]
    return sorted(hits, key=lambda f: (f["disc"], f["track"], f["title"]))


def assign(files: list[dict], songs: list[dict]) -> dict[int, dict]:
    """Ordnet Navidrome-Titel (Index in [songs]) Dateien zu: erst Disc+Nummer+Titel, dann Titel, dann Nummer."""
    result: dict[int, dict] = {}
    free = list(files)

    def take(i: int, pred) -> None:
        for f in free:
            if pred(f):
                result[i] = f
                free.remove(f)
                return

    for i, s in enumerate(songs):
        take(i, lambda f, s=s: f["track"] == (s.get("track") or 0) and f["disc"] == (s.get("disc") or 1) and norm(f["title"]) == norm(s.get("title")))
    for i, s in enumerate(songs):
        if i not in result:
            take(i, lambda f, s=s: norm(f["title"]) == norm(s.get("title")))
    for i, s in enumerate(songs):
        if i not in result and s.get("track"):
            take(i, lambda f, s=s: f["track"] == s.get("track") and f["disc"] == (s.get("disc") or 1))
    return result


# ---- Tags schreiben ----

def write_tags(path: str, values: dict[str, str | None]) -> None:
    """values: album, albumartist, artist, date, genre, title, tracknumber, discnumber (None = nicht ändern, "" = löschen)."""
    try:
        f = mutagen.File(path, easy=True)
    except Exception as e:
        raise LibraryError(f"{Path(path).name}: nicht lesbar ({e})") from e
    if f is None:
        raise LibraryError(f"{Path(path).name}: Format wird nicht unterstützt")
    if f.tags is None:
        f.add_tags()
    for key, value in values.items():
        if value is None:
            continue
        try:
            if value == "":
                if key in f.tags:
                    del f.tags[key]
            else:
                f.tags[key] = [value]
        except Exception:
            pass  # Feld wird vom Format nicht unterstützt (z. B. WAV ohne ID3)
    f.save()


def embed_cover(path: str, image: bytes, mime: str = "image/jpeg") -> None:
    p = Path(path)
    ext = p.suffix.lower()
    try:
        if ext == ".mp3":
            try:
                tags = ID3(p)
            except ID3NoHeaderError:
                tags = ID3()
            tags.delall("APIC")
            tags.add(APIC(encoding=3, mime=mime, type=3, desc="Cover", data=image))
            tags.save(p)
        elif ext == ".flac":
            f = FLAC(p)
            f.clear_pictures()
            pic = Picture()
            pic.type, pic.mime, pic.data = 3, mime, image
            f.add_picture(pic)
            f.save()
        elif ext in (".m4a", ".mp4"):
            f = MP4(p)
            f["covr"] = [MP4Cover(image, MP4Cover.FORMAT_PNG if mime == "image/png" else MP4Cover.FORMAT_JPEG)]
            f.save()
    except Exception:
        pass  # Einbetten ist Zusatz; cover.jpg im Ordner reicht Navidrome


def folder_is_exclusive(index: Index, folder: Path, album_paths: set[str]) -> bool:
    """True, wenn im Ordner nur Dateien dieses Albums liegen (dann darf cover.jpg hinein)."""
    for f in folder.iterdir():
        if f.is_file() and f.suffix.lower() in AUDIO and str(f) not in album_paths:
            return False
    return True


def set_cover(index: Index, files: list[dict], image: bytes, mime: str) -> None:
    paths = {f["path"] for f in files}
    for folder in {Path(p).parent for p in paths}:
        if folder_is_exclusive(index, folder, paths):
            for old in folder.iterdir():
                if old.is_file() and old.stem.lower() in COVER_NAMES and old.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp", ".gif"):
                    old.unlink()
            (folder / ("cover.png" if mime == "image/png" else "cover.jpg")).write_bytes(image)
    for p in paths:
        embed_cover(p, image, mime)
    index.forget(list(paths))


# ---- Papierkorb ----

class Trash:
    def __init__(self, music: Path, data_dir: Path, index: Index):
        self.music = music
        self.dir = music / TRASH_NAME
        self.file = data_dir / "trash.json"
        self.index = index
        self._lock = threading.Lock()

    def _load(self) -> list[dict]:
        try:
            return json.loads(self.file.read_text())
        except (OSError, ValueError):
            return []

    def _save(self, entries: list[dict]) -> None:
        tmp = self.file.with_suffix(".tmp")
        tmp.write_text(json.dumps(entries))
        tmp.replace(self.file)

    def list(self) -> list[dict]:
        now = time.time()
        return [
            {k: e[k] for k in ("id", "artist", "album", "deletedAt", "bytes")} | {
                "tracks": len([m for m in e["moves"] if Path(m["from"]).suffix.lower() in AUDIO]),
                "daysLeft": max(0, TRASH_DAYS - int((now - e["deletedAt"]) // 86400)),
            }
            for e in sorted(self._load(), key=lambda e: -e["deletedAt"])
        ]

    def delete(self, artist: str, album: str, files: list[dict]) -> dict:
        if not files:
            raise LibraryError("Keine Dateien zu diesem Album gefunden")
        entry_id = uuid.uuid4().hex[:12]
        base = self.dir / entry_id
        moves = []
        paths = {f["path"] for f in files}
        folders = {Path(p).parent for p in paths}
        total = 0
        for p in sorted(paths):
            src = Path(p)
            dst = base / src.relative_to(self.music)
            dst.parent.mkdir(parents=True, exist_ok=True)
            total += src.stat().st_size
            shutil.move(str(src), str(dst))
            moves.append({"from": str(src), "to": str(dst)})
        # Ordner ohne Musik: Reste (Cover, .cue, .log) mitnehmen und leere Ordner entfernen
        for folder in sorted(folders, key=lambda f: -len(f.parts)):
            if folder == self.music or not folder.exists():
                continue
            if any(f.is_file() and f.suffix.lower() in AUDIO for f in folder.iterdir()) or any(f.is_dir() for f in folder.iterdir()):
                continue
            for rest in list(folder.iterdir()):
                dst = base / rest.relative_to(self.music)
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(rest), str(dst))
                moves.append({"from": str(rest), "to": str(dst)})
            folder.rmdir()
            parent = folder.parent
            if parent != self.music and parent.exists() and not any(parent.iterdir()):
                parent.rmdir()
        self.index.forget(list(paths))
        entry = {"id": entry_id, "artist": artist, "album": album, "deletedAt": time.time(), "bytes": total, "moves": moves}
        with self._lock:
            entries = self._load()
            entries.append(entry)
            self._save(entries)
        return {"id": entry_id, "tracks": len(paths), "bytes": total}

    def restore(self, entry_id: str) -> dict:
        with self._lock:
            entries = self._load()
            entry = next((e for e in entries if e["id"] == entry_id), None)
            if entry is None:
                raise LibraryError("Nicht mehr im Papierkorb")
            for m in entry["moves"]:
                src, dst = Path(m["to"]), Path(m["from"])
                if not src.exists():
                    continue
                if dst.exists():
                    raise LibraryError(f"„{dst.name}“ gibt es inzwischen wieder – nichts verschoben")
            for m in entry["moves"]:
                src, dst = Path(m["to"]), Path(m["from"])
                if src.exists():
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(str(src), str(dst))
            shutil.rmtree(self.dir / entry_id, ignore_errors=True)
            self._save([e for e in entries if e["id"] != entry_id])
        return {"artist": entry["artist"], "album": entry["album"]}

    def purge(self, older_than_days: int = TRASH_DAYS) -> int:
        cutoff = time.time() - older_than_days * 86400
        with self._lock:
            entries = self._load()
            old = [e for e in entries if e["deletedAt"] < cutoff]
            for e in old:
                shutil.rmtree(self.dir / e["id"], ignore_errors=True)
            if old:
                self._save([e for e in entries if e["deletedAt"] >= cutoff])
        return len(old)


# ---- Eigene Dateien ----

def upload_target(music: Path, artist: str, album: str, year: int | None, track: int, disc: int, title: str, ext: str, discs: int = 1) -> Path:
    folder = music / safe_name(artist or "Unbekannt") / safe_name(f"{album} ({year})" if year else (album or "Unbekannt"))
    number = f"{disc}{track:02d}" if discs > 1 else f"{track:02d}"
    return folder / f"{number} - {safe_name(title or 'Titel')}{ext.lower()}"


def already_there(index: Index, target: Path, artist: str, album: str, title: str) -> bool:
    if target.exists():
        return True
    t, a = norm(title), norm(album)
    return any(norm(f["title"]) == t and norm(f["album"]) == a and _artist_matches(artist, f) for f in index.files())


# ---- Fehlende Cover ----

IMAGE_EXT = (".jpg", ".jpeg", ".png", ".webp", ".gif")


def has_embedded_cover(path: str) -> bool:
    ext = Path(path).suffix.lower()
    try:
        if ext == ".mp3":
            return bool(ID3(path).getall("APIC"))
        if ext == ".flac":
            return bool(FLAC(path).pictures)
        if ext in (".m4a", ".mp4"):
            return bool(MP4(path).tags and MP4(path).tags.get("covr"))
        f = mutagen.File(path)
        return bool(getattr(f, "pictures", None))
    except Exception:
        return False


def folder_has_image(folder: Path) -> bool:
    try:
        return any(p.is_file() and p.suffix.lower() in IMAGE_EXT for p in folder.iterdir())
    except OSError:
        return False


def albums_without_cover(index: Index) -> list[dict]:
    """Alben (Albumkünstler + Album), bei denen weder eine Datei ein Bild enthält noch ein Bild im Ordner liegt."""
    groups: dict[tuple[str, str], list[dict]] = {}
    for f in index.files():
        if not f["album"]:
            continue
        artist = f["albumartist"] or f["artist"]
        groups.setdefault((norm(artist), norm(f["album"])), []).append(f)
    out = []
    for files in groups.values():
        if any(folder_has_image(Path(f["path"]).parent) for f in files):
            continue
        if any(has_embedded_cover(f["path"]) for f in files[:3]):
            continue
        first = files[0]
        out.append({"artist": first["albumartist"] or first["artist"], "album": first["album"], "files": files})
    return out
