"""
NaviDroid-Helfer: holt Alben aus YouTube-Playlists, wandelt sie in MP3 und übergibt sie Lidarr.

Ablauf eines Imports:
  1. Lidarr-Album und Titelliste laden, Playlist-Videos den Titeln zuordnen (matching.py)
  2. Audiospuren mit yt-dlp laden und per ffmpeg in MP3 umwandeln (Staging-Ordner)
  3. ID3-Tags und Cover setzen (inkl. MusicBrainz-IDs aus Lidarr)
  4. Lidarr per "ManualImport" importieren lassen – Lidarr benennt und verschiebt die Dateien
     selbst in den Stammordner, das Album gilt danach als vorhanden.
  Optional: Musikvideos als MP4 in einen eigenen Ordner laden, die App spielt sie im Player ab.
"""
from __future__ import annotations

import glob
import hashlib
import json
import subprocess
import urllib.parse
import logging
import os
import queue
import re
import shutil
import threading
import time
import uuid
from pathlib import Path
from typing import Any

import requests
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse
from mutagen.id3 import APIC, ID3, TALB, TCON, TDRC, TIT2, TPE1, TPE2, TPOS, TRCK, TXXX, ID3NoHeaderError
from pydantic import BaseModel
from yt_dlp import YoutubeDL
from yt_dlp.version import __version__ as YTDLP_VERSION

import ai
import audiobooks
import library
import ma
import covers
import history as history_mod
import suggestions
import youtube_releases as ytr
from matching import clean_title, is_youtube_url, match, normalize, safe_name

VERSION = "1.18.0"

TOKEN = os.environ.get("HELPER_TOKEN", "")
LIDARR_URL = os.environ.get("LIDARR_URL", "").rstrip("/")
LIDARR_KEY = os.environ.get("LIDARR_API_KEY", "")
# Staging-Ordner, wie der Helfer ihn sieht, und wie Lidarr ihn sieht (bei unterschiedlichen Mounts).
STAGING = Path(os.environ.get("STAGING_DIR", "/downloads/navidroid"))
LIDARR_STAGING = os.environ.get("LIDARR_STAGING_DIR", str(STAGING)).rstrip("/")
VIDEO_DIR = Path(os.environ.get("VIDEO_DIR", "/videos"))
# Musikordner für den Import ohne Lidarr (Navidrome liest ihn ein). Leer = Funktion aus.
MUSIC_DIR = Path(os.environ["MUSIC_DIR"]) if os.environ.get("MUSIC_DIR") else None
# Hörspiele/Hörbücher: eigener Ordner = eigene Navidrome-Bibliothek (nicht unter MUSIC_DIR)
AUDIOBOOK_DIR = Path(os.environ["AUDIOBOOK_DIR"]) if os.environ.get("AUDIOBOOK_DIR") else None
DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
AUDIO_QUALITY = os.environ.get("AUDIO_QUALITY", "320")
VIDEO_MAX_HEIGHT = int(os.environ.get("VIDEO_MAX_HEIGHT", "720"))
VIDEO_TIMEOUT = int(os.environ.get("VIDEO_TIMEOUT", "900"))  # Sekunden je Video, danach überspringen

if not TOKEN or not LIDARR_URL or not LIDARR_KEY:
    raise SystemExit("HELPER_TOKEN, LIDARR_URL und LIDARR_API_KEY müssen gesetzt sein")

for d in (STAGING, VIDEO_DIR, DATA_DIR):
    d.mkdir(parents=True, exist_ok=True)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("navidroid-helper")

app = FastAPI(title="NaviDroid Helper", version=VERSION)

# Optional: YouTube-Cookies (Netscape-Format) für altersbeschränkte Videos, siehe README.
COOKIES_FILE = Path(os.environ.get("COOKIES_FILE", str(DATA_DIR / "cookies.txt")))


def ydl_opts(**extra: Any) -> dict:
    opts: dict[str, Any] = {"quiet": True, "noprogress": True, "retries": 5, "fragment_retries": 5, "extractor_retries": 3}
    if COOKIES_FILE.exists():
        opts["cookiefile"] = str(COOKIES_FILE)
    opts.update(extra)
    return opts


def friendly_error(e: Exception) -> str:
    msg = e.detail if isinstance(e, HTTPException) else str(e)
    low = msg.lower()
    if "confirm your age" in low:
        return ("Altersbeschränktes Video – YouTube verlangt eine Anmeldung. "
                "Lege YouTube-Cookies als cookies.txt im Datenordner des Helfers ab (siehe README).")
    if "403" in low:
        return "YouTube hat den Download blockiert (HTTP 403). Später erneut versuchen oder Helfer-Image aktualisieren."
    if "private video" in low or "video unavailable" in low or "not available" in low:
        return "Video ist nicht (mehr) verfügbar."
    return msg.replace("ERROR: ", "")


def require_token(x_helper_token: str = Header(default="")) -> None:
    if not x_helper_token or not _const_eq(x_helper_token, TOKEN):
        raise HTTPException(401, "Ungültiges Token")


def _const_eq(a: str, b: str) -> bool:
    import hmac

    return hmac.compare_digest(a.encode(), b.encode())


# ---- Lidarr ----

def lidarr(method: str, path: str, **kwargs: Any) -> Any:
    try:
        r = requests.request(
            method,
            f"{LIDARR_URL}/api/v1/{path}",
            headers={"X-Api-Key": LIDARR_KEY},
            timeout=60,
            **kwargs,
        )
    except requests.RequestException as e:
        raise HTTPException(502, f"Lidarr nicht erreichbar: {e}") from e
    if r.status_code >= 400:
        raise HTTPException(502, f"Lidarr antwortet mit HTTP {r.status_code}: {r.text[:200]}")
    return r.json() if r.content else None


def album_info(album_id: int) -> tuple[dict, list[dict], dict | None]:
    album = lidarr("GET", f"album/{album_id}")
    tracks = lidarr("GET", "track", params={"albumId": album_id}) or []
    releases = album.get("releases") or []
    release = next((r for r in releases if r.get("monitored")), releases[0] if releases else None)
    tracks.sort(key=lambda t: (t.get("mediumNumber") or 1, t.get("absoluteTrackNumber") or 0))
    return album, tracks, release


def album_year(album: dict) -> int | None:
    date = album.get("releaseDate") or ""
    return int(date[:4]) if date[:4].isdigit() and date[:4] != "0001" else None


def cover_url(album: dict) -> str | None:
    images = album.get("images") or []
    image = next((i for i in images if i.get("coverType") == "cover"), images[0] if images else None)
    return image and (image.get("remoteUrl") or None)


# ---- YouTube ----

def playlist_entries(url: str) -> tuple[dict, list[dict]]:
    if not is_youtube_url(url):
        raise HTTPException(400, "Bitte einen Link von youtube.com oder youtu.be angeben")
    opts = ydl_opts(skip_download=True, extract_flat="in_playlist")
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:  # yt-dlp wirft viele eigene Fehlertypen
        raise HTTPException(400, f"YouTube-Link konnte nicht gelesen werden: {e}") from e
    is_playlist = info.get("_type") == "playlist"
    chapters = [] if is_playlist else (info.get("chapters") or [])
    entries = []
    if len(chapters) >= 2:
        # Ein "Full Album"-Video mit Kapiteln: jedes Kapitel wird ein eigener Titel ("videoId#n").
        for i, c in enumerate(chapters, 1):
            start, end = float(c.get("start_time") or 0), float(c.get("end_time") or 0)
            entries.append({"id": f"{info['id']}#{i}", "index": i, "title": c.get("title") or f"Kapitel {i}",
                            "seconds": round(end - start, 2) if end > start else None})
    else:
        raw = info.get("entries") if is_playlist else [info]
        for i, e in enumerate(raw or [], 1):
            if not e or not e.get("id"):
                continue
            entries.append({"id": e["id"], "index": i, "title": e.get("title") or "", "seconds": e.get("duration")})
    thumbs = info.get("thumbnails") or []
    meta = {
        "kind": "chapters" if len(chapters) >= 2 else ("playlist" if is_playlist else "video"),
        "title": info.get("title") or "",
        "uploader": info.get("uploader") or info.get("channel"),
        "count": len(entries),
        "durationSec": int(sum(e["seconds"] or 0 for e in entries)),
        "thumbnail": thumbs[-1]["url"] if thumbs else None,
    }
    return meta, entries


def video_url(video_id: str) -> str:
    return f"https://www.youtube.com/watch?v={video_id}"


# ---- Jobs ----

STEP_LABELS = {
    "download": "Herunterladen",
    "convert": "In MP3 umwandeln",
    "tag": "Tags & Cover setzen",
    "import": "In Lidarr importieren",
    "library": "In Bibliothek ablegen",
    "videos": "Musikvideos laden",
}

_jobs: dict[str, dict] = {}
_lock = threading.Lock()
_queue: "queue.Queue[str]" = queue.Queue()
_JOBS_FILE = DATA_DIR / "jobs.json"
_VIDEOS_FILE = DATA_DIR / "videos.json"


def _save_jobs() -> None:
    with _lock:
        data = sorted(_jobs.values(), key=lambda j: j["createdAt"], reverse=True)[:50]
    tmp = _JOBS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False))
    tmp.replace(_JOBS_FILE)


def _load_jobs() -> None:
    if not _JOBS_FILE.exists():
        return
    for j in json.loads(_JOBS_FILE.read_text()):
        steps = {s["key"]: s for s in j.get("steps", [])}
        if "videos" in steps and steps["videos"]["state"] == "active":
            steps["videos"]["state"] = "failed"  # Videos wurden durch den Neustart unterbrochen
        if j["status"] in ("queued", "running"):
            if steps.get("import", {}).get("state") == "done":
                j["status"] = "done"  # Musik ist bereits importiert
            else:
                j["status"] = "failed"
                j["error"] = "Durch Neustart des Helfers abgebrochen"
        _jobs[j["id"]] = j


def _update(job: dict, step: str | None = None, **changes: Any) -> None:
    with _lock:
        if step:
            s = next(s for s in job["steps"] if s["key"] == step)
            s.update(changes)
        else:
            job.update(changes)
    _save_jobs()


def _videos_index() -> dict:
    return json.loads(_VIDEOS_FILE.read_text()) if _VIDEOS_FILE.exists() else {}


def _video_key(artist: str, title: str) -> str:
    return f"{normalize(artist)}|{normalize(title, artist)}"


def _video_id(key: str) -> str:
    return hashlib.sha1(key.encode()).hexdigest()[:16]


def run_job(job: dict) -> None:
    if job.get("manual"):
        return run_manual_job(job)
    album, tracks, release = album_info(job["albumId"])
    artist = (album.get("artist") or {}).get("artistName") or job["artist"]
    by_track = {t["id"]: t for t in tracks}
    plan = [(by_track[m["trackId"]], m["videoId"]) for m in job["mapping"] if m.get("videoId") and m["trackId"] in by_track]
    if not plan:
        raise RuntimeError("Keine Titel zugeordnet")
    total_tracks = len(tracks)
    workdir = STAGING / job["id"]
    workdir.mkdir(parents=True, exist_ok=True)

    # 1+2: Laden und in MP3 umwandeln – je Titel bis zu 3 Versuche, Fehlschläge werden übersprungen
    _update(job, "download", state="active")
    files: dict[int, Path] = {}
    sources: dict[str, tuple[Path, list[dict]] | Exception] = {}
    skipped: list[dict] = []
    done = 0
    for track, vid in plan:
        number = track.get("absoluteTrackNumber") or 0
        base = f"{number:02d} - {safe_name(track['title'])}"
        mp3 = workdir / f"{base}.mp3"
        error: Exception | None = None
        for attempt in range(3):
            try:
                if "#" in vid:
                    source_id, chapter = vid.split("#", 1)
                    if source_id not in sources or isinstance(sources[source_id], Exception):
                        try:
                            sources[source_id] = _download_source(source_id, workdir)
                        except Exception as e:
                            sources[source_id] = e
                            raise
                    src, chapters = sources[source_id]  # type: ignore[misc]
                    c = chapters[int(chapter) - 1]
                    cmd = ["ffmpeg", "-loglevel", "error", "-y", "-ss", str(c.get("start_time") or 0)]
                    if c.get("end_time"):
                        cmd += ["-to", str(c["end_time"])]
                    cmd += ["-i", str(src), "-vn", "-c:a", "libmp3lame", "-b:a", f"{AUDIO_QUALITY}k", str(mp3)]
                    subprocess.run(cmd, check=True)
                else:
                    opts = ydl_opts(
                        noplaylist=True,
                        format="bestaudio/best",
                        outtmpl=str(workdir / f"{base}.%(ext)s"),
                        postprocessors=[{"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": AUDIO_QUALITY}],
                    )
                    with YoutubeDL(opts) as ydl:
                        ydl.download([video_url(vid)])
                if not mp3.exists():
                    raise RuntimeError("Umwandlung in MP3 fehlgeschlagen")
                error = None
                break
            except Exception as e:  # yt-dlp/ffmpeg
                error = e
                if "confirm your age" in str(e).lower():
                    break  # Wiederholen hilft hier nicht
                time.sleep(5 * (attempt + 1))
        done += 1
        _update(job, "download", done=done)
        if error is not None:
            log.warning("Titel %s übersprungen: %s", track["title"], error)
            skipped.append({"title": track["title"], "reason": friendly_error(error)})
            _update(job, skipped=list(skipped))
            continue
        files[track["id"]] = mp3
        _update(job, "convert", state="active", done=len(files))
    if not files:
        raise RuntimeError(skipped[0]["reason"] if skipped else "Kein Titel konnte geladen werden")
    _update(job, "download", state="done")
    _update(job, "convert", state="done")

    # 3: Tags und Cover
    _update(job, "tag", state="active")
    cover = None
    if url := cover_url(album):
        try:
            cover = requests.get(url, timeout=30).content
        except requests.RequestException:
            cover = None
    year = album_year(album)
    discs = max((t.get("mediumNumber") or 1) for t in tracks) if tracks else 1
    for i, (track_id, path) in enumerate(files.items(), 1):
        track = by_track[track_id]
        try:
            ID3(path).delete()  # Tags von YouTube verwerfen
        except ID3NoHeaderError:
            pass
        tags = ID3()
        tags.add(TIT2(encoding=3, text=track["title"]))
        tags.add(TPE1(encoding=3, text=artist))
        tags.add(TPE2(encoding=3, text=artist))
        tags.add(TALB(encoding=3, text=album["title"]))
        tags.add(TRCK(encoding=3, text=f"{track.get('trackNumber') or track.get('absoluteTrackNumber')}/{total_tracks}"))
        tags.add(TPOS(encoding=3, text=f"{track.get('mediumNumber') or 1}/{discs}"))
        if year:
            tags.add(TDRC(encoding=3, text=str(year)))
        if release and release.get("foreignReleaseId"):
            tags.add(TXXX(encoding=3, desc="MusicBrainz Album Id", text=release["foreignReleaseId"]))
        if album.get("foreignAlbumId"):
            tags.add(TXXX(encoding=3, desc="MusicBrainz Release Group Id", text=album["foreignAlbumId"]))
        if (album.get("artist") or {}).get("foreignArtistId"):
            tags.add(TXXX(encoding=3, desc="MusicBrainz Artist Id", text=album["artist"]["foreignArtistId"]))
        if track.get("foreignTrackId"):
            tags.add(TXXX(encoding=3, desc="MusicBrainz Release Track Id", text=track["foreignTrackId"]))
        if cover:
            tags.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Cover", data=cover))
        tags.save(path, v2_version=3)
        _update(job, "tag", done=i)
    _update(job, "tag", state="done")

    for p in workdir.glob("_src_*"):
        p.unlink(missing_ok=True)

    # 4: Lidarr importiert und benennt die Dateien selbst
    _update(job, "import", state="active")
    lidarr_folder = f"{LIDARR_STAGING}/{job['id']}"
    try:
        items = lidarr("GET", "manualimport", params={"folder": lidarr_folder, "filterExistingFiles": "false"}) or []
        by_name = {p.name: tid for tid, p in files.items()}
        import_files = []
        for item in items:
            tid = by_name.get(Path(item["path"]).name)
            if tid is None:
                continue
            import_files.append({
                "path": item["path"],
                "artistId": album["artistId"],
                "albumId": album["id"],
                "albumReleaseId": release["id"] if release else item.get("albumReleaseId"),
                "trackIds": [tid],
                "quality": item.get("quality"),
                "indexerFlags": 0,
                "disableReleaseSwitching": True,
            })
        if not import_files:
            raise RuntimeError("Lidarr hat die Dateien im Staging-Ordner nicht gefunden – stimmt LIDARR_STAGING_DIR?")
        cmd = lidarr("POST", "command", json={
            "name": "ManualImport",
            "files": import_files,
            "importMode": "move",
            "replaceExistingFiles": False,
        })
    except HTTPException:
        # Rückfallebene: Lidarr den Ordner selbst prüfen lassen (Zuordnung über die MusicBrainz-Tags).
        cmd = lidarr("POST", "command", json={"name": "DownloadedAlbumsScan", "path": lidarr_folder, "importMode": "Move"})
    deadline = time.time() + 600
    while time.time() < deadline:
        status = lidarr("GET", f"command/{cmd['id']}")
        if status.get("status") in ("completed", "failed", "aborted", "cancelled"):
            if status["status"] != "completed":
                raise RuntimeError(f"Lidarr-Import fehlgeschlagen: {status.get('message') or status['status']}")
            break
        time.sleep(3)
    else:
        raise RuntimeError("Lidarr-Import dauert ungewöhnlich lange")
    _update(job, "import", state="done", done=1)
    shutil.rmtree(workdir, ignore_errors=True)

    # Optional: Musikvideos – laufen in einer eigenen Warteschlange, damit der nächste Import nicht wartet.
    if job["videos"]:
        _update(job, "videos", state="active", done=0)
        tasks = [{
            "artist": artist,
            "album": album["title"],
            "title": track["title"],
            "base": f"{(track.get('absoluteTrackNumber') or 0):02d} - {safe_name(track['title'])}",
            "vid": vid.split("#", 1)[0],
        } for track, vid in plan]
        # Bei Kapitel-Videos gibt es nur ein Video für das ganze Album
        if all("#" in vid for _, vid in plan):
            tasks = tasks[:0]
        _video_queue.put((job["id"], tasks))


def _square_cover(url: str | None, workdir: Path) -> bytes | None:
    """Vorschaubild der Playlist laden und mittig quadratisch zuschneiden."""
    if not url:
        return None
    try:
        raw = workdir / "_thumb"
        raw.write_bytes(requests.get(url, timeout=30).content)
        out = workdir / "_cover.jpg"
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(raw), "-vf", "crop='min(iw,ih)':'min(iw,ih)',scale=600:600",
                        "-q:v", "3", str(out)], check=True)
        return out.read_bytes()
    except Exception as e:  # ohne Cover geht es auch
        log.warning("Cover konnte nicht erstellt werden: %s", e)
        return None


def run_manual_job(job: dict) -> None:
    """Import ohne Lidarr: MP3s mit Tags direkt im Musikordner (bzw. Hörspiel-Ordner) ablegen."""
    m = job["manual"]
    audiobook = m.get("root") == "audiobook"
    root = AUDIOBOOK_DIR if audiobook else MUSIC_DIR
    if root is None:
        raise RuntimeError("Hörspiel-Ordner ist im Helfer nicht eingerichtet (AUDIOBOOK_DIR fehlt)" if audiobook
                           else "Import ohne Lidarr ist im Helfer nicht eingerichtet (MUSIC_DIR fehlt)")
    artist, album_name, year = m["artist"].strip(), m["album"].strip(), m.get("year")
    tracks = sorted(m["tracks"], key=lambda t: t["number"])
    total = len(tracks)
    workdir = STAGING / job["id"]
    workdir.mkdir(parents=True, exist_ok=True)

    _update(job, "download", state="active")
    files: list[tuple[dict, Path]] = []
    skipped: list[dict] = []
    sources: dict[str, tuple[Path, list[dict]] | Exception] = {}
    for i, t in enumerate(tracks, 1):
        base = f"{t['number']:02d} - {safe_name(t['title'])}"
        mp3 = workdir / f"{base}.mp3"
        error: Exception | None = None
        for attempt in range(3):
            try:
                vid = t["videoId"]
                if "#" in vid:
                    source_id, chapter = vid.split("#", 1)
                    if source_id not in sources or isinstance(sources[source_id], Exception):
                        try:
                            sources[source_id] = _download_source(source_id, workdir)
                        except Exception as e:
                            sources[source_id] = e
                            raise
                    src, chapters = sources[source_id]  # type: ignore[misc]
                    c = chapters[int(chapter) - 1]
                    cmd = ["ffmpeg", "-loglevel", "error", "-y", "-ss", str(c.get("start_time") or 0)]
                    if c.get("end_time"):
                        cmd += ["-to", str(c["end_time"])]
                    subprocess.run(cmd + ["-i", str(src), "-vn", "-c:a", "libmp3lame", "-b:a", f"{AUDIO_QUALITY}k", str(mp3)], check=True)
                else:
                    with YoutubeDL(ydl_opts(
                        noplaylist=True, format="bestaudio/best", outtmpl=str(workdir / f"{base}.%(ext)s"),
                        postprocessors=[{"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": AUDIO_QUALITY}],
                    )) as ydl:
                        ydl.download([video_url(vid)])
                if not mp3.exists():
                    raise RuntimeError("Umwandlung in MP3 fehlgeschlagen")
                error = None
                break
            except Exception as e:
                error = e
                if "confirm your age" in str(e).lower():
                    break
                time.sleep(5 * (attempt + 1))
        _update(job, "download", done=i)
        if error is not None:
            skipped.append({"title": t["title"], "reason": friendly_error(error)})
            _update(job, skipped=list(skipped))
            continue
        files.append((t, mp3))
        _update(job, "convert", state="active", done=len(files))
    if not files:
        raise RuntimeError(skipped[0]["reason"] if skipped else "Kein Titel konnte geladen werden")
    _update(job, "download", state="done")
    _update(job, "convert", state="done")

    _update(job, "tag", state="active")
    cover = _square_cover(m.get("thumbnail"), workdir)
    for i, (t, path) in enumerate(files, 1):
        try:
            ID3(path).delete()
        except ID3NoHeaderError:
            pass
        tags = ID3()
        tags.add(TIT2(encoding=3, text=t["title"]))
        tags.add(TPE1(encoding=3, text=artist))
        tags.add(TPE2(encoding=3, text=artist))
        tags.add(TALB(encoding=3, text=album_name))
        tags.add(TRCK(encoding=3, text=f"{t['number']}/{total}"))
        tags.add(TPOS(encoding=3, text="1/1"))
        if year:
            tags.add(TDRC(encoding=3, text=str(year)))
        if audiobook:
            tags.add(TCON(encoding=3, text="Hörspiel"))
        if cover:
            tags.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Cover", data=cover))
        tags.save(path, v2_version=3)
        _update(job, "tag", done=i)
    _update(job, "tag", state="done")

    # Ablegen – vorhandene Dateien werden nie überschrieben
    _update(job, "library", state="active")
    folder = root / safe_name(artist) / safe_name(f"{album_name} ({year})" if year else album_name)
    folder.mkdir(parents=True, exist_ok=True)
    if cover and not (folder / "cover.jpg").exists():
        (folder / "cover.jpg").write_bytes(cover)
    for t, path in files:
        target = folder / path.name
        if target.exists():
            skipped.append({"title": t["title"], "reason": "Datei existiert bereits – nicht überschrieben"})
            continue
        shutil.move(str(path), str(target))
    _update(job, skipped=list(skipped))
    _update(job, "library", state="done", done=1)
    shutil.rmtree(workdir, ignore_errors=True)


_video_queue: "queue.Queue[tuple[str, list[dict]]]" = queue.Queue()
_video_lock = threading.Lock()


def video_worker() -> None:
    while True:
        job_id, tasks = _video_queue.get()
        job = _jobs.get(job_id)
        if not job:
            continue
        failed = 0
        for i, t in enumerate(tasks, 1):
            target_dir = VIDEO_DIR / safe_name(t["artist"]) / safe_name(t["album"])
            target_dir.mkdir(parents=True, exist_ok=True)
            out = target_dir / f"{t['base']}.mp4"
            h = VIDEO_MAX_HEIGHT
            cmd = [
                "yt-dlp", "-q", "--no-progress", "--no-playlist",
                "-f", f"bv*[height<={h}][ext=mp4]+ba[ext=m4a]/b[height<={h}][ext=mp4]/bv*[height<={h}]+ba/b",
                "--merge-output-format", "mp4", "-o", str(target_dir / f"{t['base']}.%(ext)s"),
                "--socket-timeout", "30", "--retries", "3", "--fragment-retries", "3",
            ]
            if COOKIES_FILE.exists():
                cmd += ["--cookies", str(COOKIES_FILE)]
            cmd.append(video_url(t["vid"]))
            try:
                subprocess.run(cmd, check=True, timeout=VIDEO_TIMEOUT, capture_output=True, text=True)
            except subprocess.TimeoutExpired:
                log.warning("Video %s nach %ss abgebrochen", t["vid"], VIDEO_TIMEOUT)
            except subprocess.CalledProcessError as e:
                log.warning("Video %s fehlgeschlagen: %s", t["vid"], (e.stderr or "").strip()[-200:])
            for leftover in target_dir.glob(f"{glob.escape(t['base'])}.*"):
                if leftover.suffix in (".part", ".ytdl") or ".f" in leftover.stem[len(t["base"]):]:
                    leftover.unlink(missing_ok=True)
            if out.exists():
                with _video_lock:
                    index = _videos_index()
                    index[_video_key(t["artist"], t["title"])] = {"path": str(out), "artist": t["artist"], "title": t["title"], "album": t["album"]}
                    tmp = _VIDEOS_FILE.with_suffix(".tmp")
                    tmp.write_text(json.dumps(index, ensure_ascii=False))
                    tmp.replace(_VIDEOS_FILE)
            else:
                failed += 1
            _update(job, "videos", done=i)
        _update(job, "videos", state="done" if failed < len(tasks) or not tasks else "failed")


def _download_source(video_id: str, workdir: Path) -> tuple[Path, list[dict]]:
    """Lädt die Tonspur eines Kapitel-Videos einmal herunter und liefert sie mit den Kapiteln."""
    opts = ydl_opts(noplaylist=True, format="bestaudio/best", outtmpl=str(workdir / f"_src_{video_id}.%(ext)s"))
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(video_url(video_id), download=True)
    chapters = info.get("chapters") or []
    found = sorted(glob.glob(str(workdir / f"_src_{video_id}.*")))
    if not found or not chapters:
        raise RuntimeError("Video ohne Kapitel konnte nicht aufgeteilt werden")
    return Path(found[0]), chapters


def worker() -> None:
    while True:
        job_id = _queue.get()
        job = _jobs[job_id]
        _update(job, status="running")
        try:
            run_job(job)
            _update(job, status="done", finishedAt=int(time.time() * 1000))
        except Exception as e:
            log.exception("Job %s fehlgeschlagen", job_id)
            msg = friendly_error(e)
            for s in job["steps"]:
                if s["state"] == "active":
                    _update(job, s["key"], state="failed")
            _update(job, status="failed", error=msg, finishedAt=int(time.time() * 1000))


_load_jobs()
threading.Thread(target=worker, daemon=True).start()
threading.Thread(target=video_worker, daemon=True).start()
if ai.configured():
    ai.ensure_key(DATA_DIR)


# ---- API ----

class PreviewRequest(BaseModel):
    url: str
    albumId: int | None = None


class MappingItem(BaseModel):
    trackId: int
    videoId: str | None = None


class JobRequest(BaseModel):
    url: str
    albumId: int
    videos: bool = False
    mapping: list[MappingItem]


@app.get("/api/health", dependencies=[Depends(require_token)])
def health() -> dict:
    try:
        lidarr_version = lidarr("GET", "system/status").get("version")
        lidarr_error = None
    except HTTPException as e:
        lidarr_version, lidarr_error = None, e.detail
    return {"version": VERSION, "ytDlp": YTDLP_VERSION, "lidarr": lidarr_version, "lidarrError": lidarr_error,
            "ai": ai.configured(), "manual": MUSIC_DIR is not None, "library": MUSIC_DIR is not None,
            "audiobooks": AUDIOBOOK_DIR is not None}


@app.post("/api/preview", dependencies=[Depends(require_token)])
def preview(req: PreviewRequest) -> dict:
    meta, entries = playlist_entries(req.url)
    result: dict[str, Any] = {"playlist": meta, "videos": entries}
    if req.albumId is None:
        return result
    album, tracks, _ = album_info(req.albumId)
    artist = (album.get("artist") or {}).get("artistName") or ""
    rows, unmatched = match(
        [{"id": t["id"], "number": t.get("absoluteTrackNumber") or 0, "title": t["title"],
          "seconds": (t.get("duration") or 0) / 1000 or None} for t in tracks],
        entries,
        artist,
    )
    year = album_year(album)
    artist_path = (album.get("artist") or {}).get("path") or ""
    result.update({
        "album": {"id": album["id"], "title": album["title"], "artist": artist, "year": year, "trackCount": len(tracks)},
        "mapping": rows,
        "unmatchedVideos": unmatched,
        "targetFolder": f"{artist_path}/{album['title']}" + (f" ({year})" if year else ""),
    })
    return result


class SearchRequest(BaseModel):
    query: str
    albumId: int | None = None


def _search_flat(url: str, limit: int) -> list[dict]:
    opts = ydl_opts(skip_download=True, extract_flat=True, playlistend=limit)
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    return [e for e in (info.get("entries") or []) if e][:limit]


@app.post("/api/search", dependencies=[Depends(require_token)])
def search(req: SearchRequest) -> list[dict]:
    query = req.query.strip()
    if not query:
        raise HTTPException(400, "Suchbegriff fehlt")
    tracks: list[dict] = []
    artist = ""
    if req.albumId is not None:
        album, album_tracks, _ = album_info(req.albumId)
        artist = (album.get("artist") or {}).get("artistName") or ""
        tracks = [{"id": t["id"], "number": t.get("absoluteTrackNumber") or 0, "title": t["title"],
                   "seconds": (t.get("duration") or 0) / 1000 or None} for t in album_tracks]

    candidates: list[str] = []
    try:
        # Playlists (Filter "Playlist" der YouTube-Suche)
        q = urllib.parse.quote(query)
        for e in _search_flat(f"https://www.youtube.com/results?search_query={q}&sp=EgIQAw%253D%253D", 4):
            url = e.get("url") or ""
            if "list=" in url:
                candidates.append(url)
        # Einzelne "Full Album"-Videos – nur sinnvoll, wenn sie Kapitel haben
        for e in _search_flat(f"ytsearch4:{query} full album", 4):
            if (e.get("duration") or 0) >= 600 and e.get("id"):
                candidates.append(video_url(e["id"]))
    except Exception as e:  # yt-dlp-Fehler
        raise HTTPException(502, f"YouTube-Suche fehlgeschlagen: {e}") from e

    hits = []
    for url in candidates:
        try:
            meta, entries = playlist_entries(url)
        except HTTPException:
            continue
        if meta["kind"] == "video" or not entries:
            continue  # Video ohne Kapitel lässt sich nicht in Titel teilen
        matched = 0
        if tracks:
            rows, _ = match(tracks, entries, artist)
            matched = sum(1 for r in rows if r["videoId"])
        hits.append({**meta, "url": url, "matched": matched, "total": len(tracks), "extra": max(0, len(entries) - matched)})
    # Abweichende Fassungen nach hinten, außer man sucht ausdrücklich danach.
    noise = ("instrumental", "karaoke", "live", "cover", "reaction", "remix", "slowed", "sped up", "8d", "nightcore")
    q_lower = query.lower()

    def penalty(h: dict) -> int:
        t = h["title"].lower()
        return int(any(w in t and w not in q_lower for w in noise))

    # Beste Übereinstimmung zuerst: Originalfassung, meiste Titel, wenigste überzählige Videos, Playlists vor Kapiteln
    hits.sort(key=lambda h: (penalty(h), -h["matched"], h["extra"], h["kind"] != "playlist"))
    return hits


@app.post("/api/jobs", dependencies=[Depends(require_token)])
def create_job(req: JobRequest) -> dict:
    if not is_youtube_url(req.url):
        raise HTTPException(400, "Bitte einen Link von youtube.com oder youtu.be angeben")
    mapped = [m.model_dump() for m in req.mapping if m.videoId]
    if not mapped:
        raise HTTPException(400, "Keine Titel zugeordnet")
    album = lidarr("GET", f"album/{req.albumId}")
    steps = [{"key": k, "label": STEP_LABELS[k], "done": 0, "total": len(mapped), "state": "todo"}
             for k in ("download", "convert", "tag")]
    steps.append({"key": "import", "label": STEP_LABELS["import"], "done": 0, "total": 1, "state": "todo"})
    if req.videos:
        steps.append({"key": "videos", "label": STEP_LABELS["videos"], "done": 0, "total": len(mapped), "state": "todo"})
    job = {
        "id": uuid.uuid4().hex[:12],
        "url": req.url,
        "albumId": req.albumId,
        "title": album.get("title") or "",
        "artist": (album.get("artist") or {}).get("artistName") or "",
        "videos": req.videos,
        "mapping": mapped,
        "steps": steps,
        "status": "queued",
        "error": None,
        "skipped": [],
        "createdAt": int(time.time() * 1000),
    }
    with _lock:
        _jobs[job["id"]] = job
    _save_jobs()
    _queue.put(job["id"])
    return _public(job)


def _public(job: dict) -> dict:
    return {k: v for k, v in job.items() if k not in ("mapping", "url", "manual")}


@app.get("/api/jobs", dependencies=[Depends(require_token)])
def list_jobs() -> list[dict]:
    with _lock:
        jobs = sorted(_jobs.values(), key=lambda j: j["createdAt"], reverse=True)[:20]
        return [_public(j) for j in jobs]


@app.post("/api/jobs/{job_id}/retry", dependencies=[Depends(require_token)])
def retry_job(job_id: str) -> dict:
    with _lock:
        job = _jobs.get(job_id)
        if not job:
            raise HTTPException(404, "Job nicht gefunden")
        if job["status"] in ("queued", "running"):
            raise HTTPException(409, "Job läuft bereits")
        for s in job["steps"]:
            s.update(done=0, state="todo")
        job.update(status="queued", error=None, skipped=[], createdAt=int(time.time() * 1000))
    shutil.rmtree(STAGING / job_id, ignore_errors=True)
    _save_jobs()
    _queue.put(job_id)
    return _public(job)


@app.delete("/api/jobs/{job_id}", dependencies=[Depends(require_token)])
def delete_job(job_id: str) -> dict:
    with _lock:
        job = _jobs.get(job_id)
        if not job:
            raise HTTPException(404, "Job nicht gefunden")
        if job["status"] in ("queued", "running"):
            raise HTTPException(409, "Laufende Jobs können nicht entfernt werden")
        del _jobs[job_id]
    shutil.rmtree(STAGING / job_id, ignore_errors=True)
    _save_jobs()
    return {"ok": True}


class NavidromeAuth(BaseModel):
    url: str
    user: str
    token: str
    salt: str


class AiPlaylistRequest(BaseModel):
    navidrome: NavidromeAuth
    prompt: str
    count: int = 40
    preferRare: bool = False
    suggestMissing: bool = False
    previous: list[str] | None = None
    refine: str | None = None


@app.get("/api/ai/status", dependencies=[Depends(require_token)])
def ai_status() -> dict:
    return {"configured": ai.configured(), "publicKey": ai.ensure_key(DATA_DIR) if ai.configured() else None}


@app.post("/api/ai/playlist", dependencies=[Depends(require_token)])
def ai_playlist(req: AiPlaylistRequest) -> dict:
    if not ai.configured():
        raise HTTPException(400, "Für KI-Playlists ist im Helfer kein Claude-Worker eingerichtet (CLAUDE_WORKER)")
    if not req.prompt.strip():
        raise HTTPException(400, "Beschreibe kurz, was du hören möchtest")
    try:
        return ai.create_playlist(DATA_DIR, req.navidrome.model_dump(), req.prompt, req.count, req.preferRare,
                                  req.suggestMissing, req.previous, req.refine)
    except ai.AiError as e:
        raise HTTPException(502, str(e)) from e


class DaylistRequest(BaseModel):
    navidrome: NavidromeAuth
    slotKey: str  # z. B. "2026-10-17-vormittag" (Datum + Zeitfenster in der Zeitzone des Handys)
    moment: str  # z. B. "Samstagvormittag, Wochenende"
    count: int = 40


_daylist_lock = threading.Lock()
DAYLIST_FILE = DATA_DIR / "daylists.json"


@app.post("/api/ai/daylist", dependencies=[Depends(require_token)])
def ai_daylist(req: DaylistRequest) -> dict:
    """Daylist je Zeitfenster; einmal erstellt, bekommen alle Anfragen im selben Fenster dieselbe Liste."""
    if not ai.configured():
        raise HTTPException(400, "Für die Daylist ist im Helfer kein Claude-Worker eingerichtet (CLAUDE_WORKER)")
    return _daylist_for(req.navidrome.user, req.slotKey, req.moment, req.count, nav=req.navidrome.model_dump())


def _daylist_for(username: str, slot_key: str, moment: str, count: int, nav: dict | None = None, songs: list[dict] | None = None) -> dict:
    """Gemeinsamer Speicher für App und Navidrome-Plugin: gleiche Daylist pro Benutzer und Zeitfenster."""
    user = username.strip().lower()
    with _daylist_lock:
        try:
            store = json.loads(DAYLIST_FILE.read_text())
        except (OSError, ValueError):
            store = {}
        mine = store.get(user) or {}
        if slot_key in mine:
            return mine[slot_key]
        day = slot_key[:10]
        avoid = [t["id"] for key, d in mine.items() if key.startswith(day) for t in d.get("tracks", [])]
        try:
            result = ai.daylist(DATA_DIR, nav, moment, artist_genres(), avoid, count, songs=songs, play_counts=_history.counts(user))
        except ai.AiError as e:
            raise HTTPException(502, str(e)) from e
        result["slotKey"] = slot_key
        mine[slot_key] = result
        store[user] = dict(sorted(mine.items())[-12:])
        tmp = DAYLIST_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(store))
        tmp.replace(DAYLIST_FILE)
        return result


class PluginSong(BaseModel):
    id: str
    title: str = ""
    artist: str | None = None
    album: str | None = None
    albumId: str | None = None
    coverArt: str | None = None
    duration: int | None = None
    year: int | None = None
    genre: str | None = None
    playCount: int | None = None


class PluginDaylistRequest(BaseModel):
    user: str
    songs: list[PluginSong]
    count: int = 40


@app.post("/api/plugin/daylist", dependencies=[Depends(require_token)])
def plugin_daylist(req: PluginDaylistRequest) -> dict:
    """Für das Navidrome-Plugin: Zeitfenster bestimmt der Helfer (Zeitzone des Servers)."""
    if not ai.configured():
        raise HTTPException(400, "Kein Claude-Worker eingerichtet (CLAUDE_WORKER)")
    import datetime as _dt
    m = ai.day_moment(_dt.datetime.now())
    user = req.user.strip().lower()
    cached = (_read_json(DAYLIST_FILE, {}).get(user) or {}).get(m["slotKey"])
    if cached:
        return {**cached, "start": m["start"], "end": m["end"], "pending": False}
    # Claude braucht länger, als Navidrome einem Plugin-Aufruf Zeit gibt: im Hintergrund erstellen,
    # das Plugin fragt ein paar Minuten später noch einmal nach.
    key = f"{user}|{m['slotKey']}"
    if key not in _plugin_daylist_running:
        _plugin_daylist_running.add(key)
        songs = [{k: v for k, v in s.model_dump().items() if v is not None} for s in req.songs]

        def run() -> None:
            try:
                _daylist_for(req.user, m["slotKey"], m["prompt"], req.count, songs=songs)
            except Exception as e:  # noqa: BLE001
                log.warning("Daylist für das Plugin fehlgeschlagen: %s", e)
            finally:
                _plugin_daylist_running.discard(key)

        threading.Thread(target=run, daemon=True).start()
    return {"pending": True, "slotKey": m["slotKey"], "start": m["start"], "end": m["end"]}


_plugin_daylist_running: set[str] = set()


@app.get("/api/plugin/artist-genres", dependencies=[Depends(require_token)])
def plugin_artist_genres() -> dict:
    return artist_genres()


# ---- Über diesen Song / Übersetzung (Claude) ----

SONG_INFO_FILE = DATA_DIR / "song_info.json"
TRANSLATIONS_FILE = DATA_DIR / "translations.json"
_ai_cache_lock = threading.Lock()


def _cached(file: Path, key: str, make) -> dict:
    with _ai_cache_lock:
        cache = _read_json(file, {})
        if key in cache:
            return cache[key]
    result = make()
    with _ai_cache_lock:
        cache = _read_json(file, {})
        cache[key] = result
        tmp = file.with_suffix(".tmp")
        tmp.write_text(json.dumps(cache))
        tmp.replace(file)
    return result


class SongInfoRequest(BaseModel):
    artist: str
    title: str
    album: str | None = None
    year: int | None = None


@app.post("/api/ai/song-info", dependencies=[Depends(require_token)])
def ai_song_info(req: SongInfoRequest) -> dict:
    if not ai.configured():
        raise HTTPException(400, "Kein Claude-Worker eingerichtet (CLAUDE_WORKER)")
    key = f"{ytr.norm(req.artist)}|{ytr.norm(req.title)}"
    try:
        return _cached(SONG_INFO_FILE, key, lambda: ai.song_info(DATA_DIR, req.artist, req.title, req.album, req.year))
    except ai.AiError as e:
        raise HTTPException(502, str(e)) from e


class TranslateRequest(BaseModel):
    songId: str
    artist: str = ""
    title: str = ""
    lines: list[str]


@app.post("/api/ai/translate-lyrics", dependencies=[Depends(require_token)])
def ai_translate(req: TranslateRequest) -> dict:
    if not ai.configured():
        raise HTTPException(400, "Kein Claude-Worker eingerichtet (CLAUDE_WORKER)")
    if not req.lines or len(req.lines) > 400:
        raise HTTPException(400, "Kein oder zu langer Songtext")
    key = req.songId + "|" + hashlib.sha1("\n".join(req.lines).encode()).hexdigest()[:12]
    try:
        return _cached(TRANSLATIONS_FILE, key, lambda: ai.translate_lyrics(DATA_DIR, req.artist, req.title, req.lines))
    except ai.AiError as e:
        raise HTTPException(502, str(e)) from e


# ---- Einstellungen zwischen Handy und Desktop abgleichen ----

USER_SETTINGS_FILE = DATA_DIR / "user_settings.json"
_settings_lock = threading.Lock()


class SettingsPut(BaseModel):
    user: str
    data: dict
    updatedAt: int


@app.get("/api/settings", dependencies=[Depends(require_token)])
def get_user_settings(user: str = Query(...)) -> dict:
    return (_read_json(USER_SETTINGS_FILE, {}).get(user.strip().lower())) or {"data": {}, "updatedAt": 0}


@app.put("/api/settings", dependencies=[Depends(require_token)])
def put_user_settings(req: SettingsPut) -> dict:
    """Zusammenführen: neuere Werte gewinnen, Felder, die ein Gerät nicht kennt, bleiben erhalten."""
    with _settings_lock:
        store = _read_json(USER_SETTINGS_FILE, {})
        key = req.user.strip().lower()
        cur = store.get(key) or {"data": {}, "updatedAt": 0}
        if req.updatedAt >= cur.get("updatedAt", 0):
            cur = {"data": {**cur.get("data", {}), **req.data}, "updatedAt": req.updatedAt}
        else:
            cur = {"data": {**req.data, **cur.get("data", {})}, "updatedAt": cur["updatedAt"]}
        store[key] = cur
        tmp = USER_SETTINGS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(store))
        tmp.chmod(0o600)
        tmp.replace(USER_SETTINGS_FILE)
    return cur


# ---- Hörverlauf ----

_history = history_mod.History(DATA_DIR / "history.db")


class PlayRequest(BaseModel):
    user: str
    song: PluginSong
    ts: int | None = None


@app.post("/api/history/play", dependencies=[Depends(require_token)])
def history_play(req: PlayRequest) -> dict:
    _history.record(req.user, {k: v for k, v in req.song.model_dump().items() if v is not None}, req.ts)
    return {"ok": True}


@app.get("/api/history/summary", dependencies=[Depends(require_token)])
def history_summary(user: str = Query(...)) -> dict:
    return _history.summary(user)


class ManualPreviewRequest(BaseModel):
    url: str
    artist: str = ""


class ManualTrack(BaseModel):
    videoId: str
    title: str
    number: int


class ManualJobRequest(BaseModel):
    url: str
    artist: str
    album: str
    year: int | None = None
    thumbnail: str | None = None
    tracks: list[ManualTrack]
    root: str = "music"  # "audiobook" = Hörspiel-Ordner


@app.post("/api/manual/preview", dependencies=[Depends(require_token)])
def manual_preview(req: ManualPreviewRequest) -> dict:
    if MUSIC_DIR is None:
        raise HTTPException(400, "Import ohne Lidarr ist im Helfer nicht eingerichtet (MUSIC_DIR)")
    meta, entries = playlist_entries(req.url)
    return {
        "playlist": meta,
        "tracks": [{"videoId": e["id"], "index": e["index"], "title": clean_title(e["title"], req.artist),
                    "source": e["title"], "seconds": e["seconds"]} for e in entries],
    }


@app.post("/api/manual/jobs", dependencies=[Depends(require_token)])
def create_manual_job(req: ManualJobRequest) -> dict:
    if req.root == "audiobook" and AUDIOBOOK_DIR is None:
        raise HTTPException(400, "Hörspiel-Ordner ist im Helfer nicht eingerichtet (AUDIOBOOK_DIR)")
    if req.root != "audiobook" and MUSIC_DIR is None:
        raise HTTPException(400, "Import ohne Lidarr ist im Helfer nicht eingerichtet (MUSIC_DIR)")
    if not is_youtube_url(req.url):
        raise HTTPException(400, "Bitte einen Link von youtube.com oder youtu.be angeben")
    if not req.artist.strip() or not req.album.strip():
        raise HTTPException(400, "Künstler und Album angeben")
    if not req.tracks:
        raise HTTPException(400, "Keine Titel ausgewählt")
    n = len(req.tracks)
    steps = [{"key": k, "label": STEP_LABELS[k], "done": 0, "total": n, "state": "todo"} for k in ("download", "convert", "tag")]
    steps.append({"key": "library", "label": STEP_LABELS["library"], "done": 0, "total": 1, "state": "todo"})
    job = {
        "id": uuid.uuid4().hex[:12],
        "url": req.url,
        "albumId": 0,
        "title": req.album.strip(),
        "artist": req.artist.strip(),
        "videos": False,
        "mapping": [],
        "manual": {"artist": req.artist, "album": req.album, "year": req.year, "thumbnail": req.thumbnail,
                   "tracks": [t.model_dump() for t in req.tracks], "root": req.root},
        "steps": steps,
        "status": "queued",
        "error": None,
        "skipped": [],
        "createdAt": int(time.time() * 1000),
    }
    with _lock:
        _jobs[job["id"]] = job
    _save_jobs()
    _queue.put(job["id"])
    return _public(job)


# ---- YouTube-Veröffentlichungen ----

YT_RELEASES_FILE = DATA_DIR / "youtube_releases.json"
_yt_lock = threading.Lock()
_yt_working = threading.Event()


def _yt_store() -> dict:
    return _read_json(YT_RELEASES_FILE, {})


def _yt_save(store: dict) -> None:
    tmp = YT_RELEASES_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(store))
    tmp.replace(YT_RELEASES_FILE)


def _library_artists(files: list[dict]) -> list[str]:
    """Künstler der Bibliothek, die mit den meisten Titeln zuerst."""
    counts: dict[str, int] = {}
    names: dict[str, str] = {}
    for f in files:
        a = f.get("albumartist") or f.get("artist")
        if not a or ytr.norm(a) in ("various artists", "verschiedene interpreten", ""):
            continue
        k = ytr.norm(a)
        counts[k] = counts.get(k, 0) + 1
        names.setdefault(k, a)
    return [names[k] for k in sorted(counts, key=lambda k: -counts[k])]


def _yt_refresh(only: str | None = None) -> None:
    try:
        files = _need_library().files()
        artists = [only] if only else _library_artists(files)[:40]
        with _yt_lock:
            channels = _yt_store().get("channels") or {}
            covers = dict(_yt_store().get("covers") or {})
        found, channels = ytr.scan(artists, files, channels, covers=covers)
        with _yt_lock:
            store = _yt_store()
            store["covers"] = {**(store.get("covers") or {}), **covers}
            per = store.get("perArtist") or {}
            if not only:
                per = {}
            per.update(found)
            store.update({"perArtist": per, "channels": channels})
            if not only:
                store["date"] = time.strftime("%Y-%m-%d")
            _yt_save(store)
    except Exception:
        log.exception("YouTube-Veröffentlichungen: Suche fehlgeschlagen")
    finally:
        if not only:
            _yt_working.clear()


def _yt_items(store: dict) -> list[dict]:
    dismissed = set(store.get("dismissed") or [])
    per = {a: [i for i in items if i["key"] not in dismissed] for a, items in (store.get("perArtist") or {}).items()}
    # Ausblenden gilt auch für zusammengefasste Doppelte
    return [i for i in ytr.interleave(per) if not dismissed.intersection(i.get("alsoKeys") or [])]


@app.get("/api/youtube/releases", dependencies=[Depends(require_token)])
def youtube_releases(artist: str | None = None, refresh: bool = False) -> dict:
    _need_library()
    store = _yt_store()
    stale = store.get("date") != time.strftime("%Y-%m-%d")
    if (stale or refresh) and not _yt_working.is_set():
        _yt_working.set()
        threading.Thread(target=_yt_refresh, daemon=True).start()
    if artist:
        k = ytr.norm(artist)
        dismissed = set(store.get("dismissed") or [])
        per = store.get("perArtist") or {}
        items = next((v for a, v in per.items() if ytr.norm(a) == k), None)
        ch = (store.get("channels") or {}).get(k)
        if items is None and ch is None and not _yt_working.is_set():
            _yt_refresh(only=artist)  # Künstler noch nie gesucht: jetzt (dauert ein paar Sekunden)
            store = _yt_store()
            items = next((v for a, v in (store.get("perArtist") or {}).items() if ytr.norm(a) == k), None)
            ch = (store.get("channels") or {}).get(k)
        return {"working": _yt_working.is_set(), "channel": ch or None,
                "items": [i for i in (items or []) if i["key"] not in dismissed]}
    return {"working": _yt_working.is_set(), "items": _yt_items(store)}


class KeyRequest(BaseModel):
    key: str


@app.post("/api/youtube/releases/dismiss", dependencies=[Depends(require_token)])
def youtube_release_dismiss(req: KeyRequest) -> dict:
    with _yt_lock:
        store = _yt_store()
        store["dismissed"] = list(dict.fromkeys((store.get("dismissed") or []) + [req.key]))[-2000:]
        _yt_save(store)
    return {"ok": True}


class ChannelRequest(BaseModel):
    artist: str
    channel: str  # Kanal-Link (…/channel/UC…, …/@name) oder leer = automatisch suchen


def _channel_from_link(link: str, name: str) -> dict:
    """Kanal aus einem Link (…/channel/UC…, …/@name) oder einem @Namen."""
    m = re.search(r"(UC[0-9A-Za-z_-]{22})", link)
    if m:
        return {"id": m.group(1), "name": name}
    if not is_youtube_url(link):
        link = "https://www.youtube.com/@" + link.lstrip("@")
    try:
        info = ytr._ydl_extract(link, {"playlistend": 1})
    except Exception as e:
        raise HTTPException(400, f"Kanal nicht gefunden: {e}") from e
    if not info.get("channel_id"):
        raise HTTPException(400, "Kanal nicht gefunden")
    return {"id": info["channel_id"], "name": info.get("channel") or name}


@app.post("/api/youtube/channel", dependencies=[Depends(require_token)])
def youtube_set_channel(req: ChannelRequest) -> dict:
    k = ytr.norm(req.artist)
    link = req.channel.strip()
    ch: Any = _channel_from_link(link, req.artist) if link else None
    with _yt_lock:
        store = _yt_store()
        channels = store.get("channels") or {}
        if ch is None:
            channels.pop(k, None)
        else:
            channels[k] = ch
        store["channels"] = channels
        _yt_save(store)
    _yt_refresh(only=req.artist)
    return youtube_releases(artist=req.artist)


@app.post("/api/youtube/releases/import", dependencies=[Depends(require_token)])
def youtube_release_import(req: KeyRequest) -> dict:
    _need_library()
    item = next((i for items in (_yt_store().get("perArtist") or {}).values() for i in items
                 if i["key"] == req.key or req.key in (i.get("alsoKeys") or [])), None)
    if item is None:
        raise HTTPException(404, "Veröffentlichung nicht mehr gefunden")
    meta, entries = playlist_entries(item["url"])
    if not entries:
        raise HTTPException(400, "Die Playlist ist leer")
    tracks = [ManualTrack(videoId=e["id"], title=clean_title(e["title"], item["artist"]) or e["title"], number=e["index"]) for e in entries]
    return create_manual_job(ManualJobRequest(
        url=item["url"], artist=(item.get("artists") or [item["artist"]])[0], album=item["title"], year=None,
        thumbnail=item.get("cover") or meta.get("thumbnail"), tracks=tracks,
    ))


# ---- Hörspiel-Serien: neue Folgen automatisch von YouTube laden ----

AUDIOBOOKS_FILE = DATA_DIR / "audiobooks.json"
AUDIOBOOK_CHECK_HOURS = float(os.environ.get("AUDIOBOOK_CHECK_HOURS", "6"))
_ab_lock = threading.Lock()
_ab_working = threading.Event()


def _ab_store() -> dict:
    return _read_json(AUDIOBOOKS_FILE, {"series": []})


def _ab_save(store: dict) -> None:
    tmp = AUDIOBOOKS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(store))
    tmp.replace(AUDIOBOOKS_FILE)


def _need_audiobooks() -> Path:
    if AUDIOBOOK_DIR is None:
        raise HTTPException(400, "Hörspiel-Ordner ist im Helfer nicht eingerichtet (AUDIOBOOK_DIR)")
    return AUDIOBOOK_DIR


def _ab_import(series: dict, item: dict) -> None:
    meta, entries = playlist_entries(item["url"])
    if not entries:
        raise RuntimeError("Die Playlist ist leer")
    name = series["name"]
    tracks = [ManualTrack(videoId=e["id"], title=clean_title(e["title"], name) or e["title"], number=e["index"]) for e in entries]
    create_manual_job(ManualJobRequest(
        url=item["url"], artist=name, album=item["title"], year=None,
        thumbnail=item.get("cover") or meta.get("thumbnail"), tracks=tracks, root="audiobook",
    ))


def _ab_check(only: str | None = None) -> None:
    """Alle (oder eine) Serien prüfen und Neues in die Warteschlange stellen."""
    if AUDIOBOOK_DIR is None:
        return
    try:
        with _ab_lock:
            all_series = [x for x in _ab_store().get("series") or [] if only is None or x["id"] == only]
        for series in all_series:
            error = None
            started: list[str] = []
            try:
                items = audiobooks.releases(series["channel"]["id"])
                titles, numbers = audiobooks.present(AUDIOBOOK_DIR, series["name"], safe_name)
                for item in audiobooks.due(series, items, titles, numbers):
                    try:
                        _ab_import(series, item)
                        started.append(item["key"])
                        log.info("Hörspiel: „%s“ – %s wird geladen", series["name"], item["title"])
                    except Exception as e:
                        error = f"{item['title']}: {friendly_error(e)}"
            except Exception as e:
                error = friendly_error(e)
            with _ab_lock:
                store = _ab_store()
                for x in store.get("series") or []:
                    if x["id"] == series["id"]:
                        x["imported"] = list(dict.fromkeys((x.get("imported") or []) + started))
                        x["lastCheck"] = int(time.time() * 1000)
                        x["lastError"] = error
                _ab_save(store)
    except Exception:
        log.exception("Hörspiele: Prüfung fehlgeschlagen")
    finally:
        if only is None:
            _ab_working.clear()


def _ab_loop() -> None:
    time.sleep(60)
    while True:
        if not _ab_working.is_set():
            _ab_working.set()
            _ab_check()
        time.sleep(AUDIOBOOK_CHECK_HOURS * 3600)


if AUDIOBOOK_DIR:
    threading.Thread(target=_ab_loop, daemon=True).start()


@app.get("/api/audiobooks", dependencies=[Depends(require_token)])
def audiobook_series() -> dict:
    return {"enabled": AUDIOBOOK_DIR is not None, "working": _ab_working.is_set(),
            "series": [audiobooks.public(x) for x in _ab_store().get("series") or []]}


class SeriesRequest(BaseModel):
    name: str
    channel: str = ""             # Kanal-Link oder @Name; leer = nach dem Namen suchen
    importExisting: bool = False  # True = auch die schon erschienenen Folgen laden


@app.post("/api/audiobooks/series", dependencies=[Depends(require_token)])
def audiobook_add_series(req: SeriesRequest) -> dict:
    _need_audiobooks()
    name = req.name.strip()
    if not name:
        raise HTTPException(400, "Namen der Serie angeben")
    link = req.channel.strip()
    ch = _channel_from_link(link, name) if link else ytr.find_channel(name)
    if not ch:
        raise HTTPException(400, f"Kein offizieller YouTube-Kanal für „{name}“ gefunden – bitte Link angeben")
    try:
        items = audiobooks.releases(ch["id"])
    except Exception as e:
        raise HTTPException(400, f"Veröffentlichungen nicht lesbar: {friendly_error(e)}") from e
    if not items:
        raise HTTPException(400, f"Der Kanal „{ch['name']}“ hat keinen Reiter „Veröffentlichungen“")
    series = audiobooks.new_series(name, ch, items, req.importExisting, uuid.uuid4().hex[:10], int(time.time() * 1000))
    with _ab_lock:
        store = _ab_store()
        store["series"] = [x for x in store.get("series") or [] if ytr.norm(x["name"]) != ytr.norm(name)] + [series]
        _ab_save(store)
    if req.importExisting:
        threading.Thread(target=_ab_check, args=(series["id"],), daemon=True).start()
    return audiobooks.public(series)


@app.delete("/api/audiobooks/series/{series_id}", dependencies=[Depends(require_token)])
def audiobook_remove_series(series_id: str) -> dict:
    with _ab_lock:
        store = _ab_store()
        store["series"] = [x for x in store.get("series") or [] if x["id"] != series_id]
        _ab_save(store)
    return {"ok": True}


@app.post("/api/audiobooks/check", dependencies=[Depends(require_token)])
def audiobook_check_now() -> dict:
    _need_audiobooks()
    if not _ab_working.is_set():
        _ab_working.set()
        threading.Thread(target=_ab_check, daemon=True).start()
    return audiobook_series()


class MaAuth(BaseModel):
    url: str
    token: str


class MaPlayRequest(BaseModel):
    ma: MaAuth
    playerId: str
    navidrome: NavidromeAuth
    songIds: list[str]


class MaCommandRequest(BaseModel):
    ma: MaAuth
    playerId: str
    command: str
    value: float | None = None


class MaStateRequest(BaseModel):
    ma: MaAuth
    playerId: str


def _ma(fn, *args):
    try:
        return fn(*args)
    except ma.MaError as e:
        raise HTTPException(502, str(e)) from e


@app.post("/api/ma/players", dependencies=[Depends(require_token)])
def ma_players(req: MaAuth) -> list[dict]:
    return _ma(ma.players, req.url, req.token)


@app.post("/api/ma/play", dependencies=[Depends(require_token)])
def ma_play(req: MaPlayRequest) -> dict:
    _ma(ma.play, req.ma.url, req.ma.token, req.playerId, req.navidrome.model_dump(), req.songIds)
    return {"ok": True}


@app.post("/api/ma/command", dependencies=[Depends(require_token)])
def ma_command(req: MaCommandRequest) -> dict:
    _ma(ma.command, req.ma.url, req.ma.token, req.playerId, req.command, req.value)
    return {"ok": True}


@app.post("/api/ma/state", dependencies=[Depends(require_token)])
def ma_state(req: MaStateRequest) -> dict:
    return _ma(ma.state, req.ma.url, req.ma.token, req.playerId)


# ---- Bibliothek bearbeiten (braucht MUSIC_DIR) ----

_index = library.Index(MUSIC_DIR) if MUSIC_DIR else None
_trash = library.Trash(MUSIC_DIR, DATA_DIR, _index) if MUSIC_DIR else None
_library_lock = threading.Lock()


def _need_library() -> library.Index:
    if _index is None:
        raise HTTPException(400, "Der Helfer hat keinen Zugriff auf den Musikordner (MUSIC_DIR)")
    return _index


def _purge_trash_daily() -> None:
    while True:
        try:
            if _trash:
                n = _trash.purge()
                if n:
                    log.info("Papierkorb: %d alte Einträge endgültig gelöscht", n)
        except Exception:
            log.exception("Papierkorb aufräumen fehlgeschlagen")
        time.sleep(6 * 3600)


if _trash:
    threading.Thread(target=_purge_trash_daily, daemon=True).start()


class LibSong(BaseModel):
    title: str = ""
    track: int = 0
    disc: int = 1


class AlbumRef(BaseModel):
    artist: str
    album: str
    songs: list[LibSong] = []


class TrackEdit(BaseModel):
    index: int
    title: str | None = None
    track: int | None = None


class AlbumEdit(BaseModel):
    ref: AlbumRef
    album: str | None = None
    albumArtist: str | None = None
    year: int | None = None
    genre: str | None = None
    tracks: list[TrackEdit] = []


class AlbumDelete(BaseModel):
    ref: AlbumRef
    unmonitor: bool = True


def _album_files(ref: AlbumRef) -> list[dict]:
    files = library.album_files(_need_library(), ref.artist, ref.album)
    if not files:
        raise HTTPException(404, f"Keine Dateien zu „{ref.album}“ im Musikordner gefunden")
    return files


@app.post("/api/library/album/files", dependencies=[Depends(require_token)])
def library_album_files(ref: AlbumRef) -> dict:
    files = _album_files(ref)
    size = sum(Path(f["path"]).stat().st_size for f in files if Path(f["path"]).exists())
    return {"tracks": len(files), "bytes": size, "matched": len(library.assign(files, [s.model_dump() for s in ref.songs]))}


@app.post("/api/library/album/edit", dependencies=[Depends(require_token)])
def library_album_edit(req: AlbumEdit) -> dict:
    with _library_lock:
        files = _album_files(req.ref)
        mapping = library.assign(files, [s.model_dump() for s in req.ref.songs])
        per_file: dict[str, dict] = {f["path"]: {} for f in files}
        for f in files:
            v = per_file[f["path"]]
            if req.album is not None and req.album.strip():
                v["album"] = req.album.strip()
            if req.albumArtist is not None and req.albumArtist.strip():
                v["albumartist"] = req.albumArtist.strip()
                # Tippfehler im Künstlernamen stehen meist auch im Titel-Künstler
                if library.norm(f["artist"]) == library.norm(req.ref.artist):
                    v["artist"] = req.albumArtist.strip()
            if req.year is not None:
                v["date"] = str(req.year) if req.year > 0 else ""
            if req.genre is not None:
                v["genre"] = req.genre.strip()
        for t in req.tracks:
            f = mapping.get(t.index)
            if f is None:
                continue
            if t.title is not None and t.title.strip():
                per_file[f["path"]]["title"] = t.title.strip()
            if t.track is not None and t.track > 0:
                per_file[f["path"]]["tracknumber"] = str(t.track)
        changed = 0
        errors = []
        for path, values in per_file.items():
            if not values:
                continue
            try:
                library.write_tags(path, values)
                changed += 1
            except library.LibraryError as e:
                errors.append(str(e))
        _index.forget(list(per_file))
    if errors and not changed:
        raise HTTPException(500, errors[0])
    return {"changed": changed, "errors": errors}


@app.post("/api/library/album/cover", dependencies=[Depends(require_token)])
async def library_album_cover(request: Request, artist: str = Query(...), album: str = Query(...)) -> dict:
    image = await request.body()
    if not image or len(image) > 15 * 1024 * 1024:
        raise HTTPException(400, "Kein oder zu großes Bild")
    mime = "image/png" if image[:8] == b"\x89PNG\r\n\x1a\n" else "image/jpeg"
    files = _album_files(AlbumRef(artist=artist, album=album))
    with _library_lock:
        library.set_cover(_index, files, image, mime)
    return {"tracks": len(files)}


def _unmonitor_in_lidarr(artist: str, album: str) -> bool:
    try:
        artists = lidarr("GET", "artist") or []
        a = next((x for x in artists if library.norm(x.get("artistName")) == library.norm(artist)), None)
        if not a:
            return False
        albums = lidarr("GET", "album", params={"artistId": a["id"]}) or []
        hit = next((x for x in albums if library.norm(x.get("title")) == library.norm(album)), None)
        if not hit:
            return False
        lidarr("PUT", "album/monitor", json={"albumIds": [hit["id"]], "monitored": False})
        return True
    except HTTPException:
        return False


@app.post("/api/library/album/delete", dependencies=[Depends(require_token)])
def library_album_delete(req: AlbumDelete) -> dict:
    with _library_lock:
        files = _album_files(req.ref)
        result = _trash.delete(req.ref.artist, req.ref.album, files)
    result["lidarr"] = _unmonitor_in_lidarr(req.ref.artist, req.ref.album) if req.unmonitor else False
    return result


@app.get("/api/covers/search", dependencies=[Depends(require_token)])
def covers_search(artist: str = Query(""), album: str = Query(...)) -> list[dict]:
    return covers.search(artist, album)


class CoverUrlRequest(BaseModel):
    artist: str
    album: str
    url: str


@app.post("/api/library/album/cover-url", dependencies=[Depends(require_token)])
def library_album_cover_url(req: CoverUrlRequest) -> dict:
    try:
        image, mime = covers.download(req.url)
    except (ValueError, requests.RequestException) as e:
        raise HTTPException(400, f"Cover konnte nicht geladen werden: {e}") from e
    files = _album_files(AlbumRef(artist=req.artist, album=req.album))
    with _library_lock:
        library.set_cover(_index, files, image, mime)
    return {"tracks": len(files)}


COVERS_LOG = DATA_DIR / "covers_filled.json"


def fill_missing_covers(apply: bool) -> dict:
    """Fehlende Albumcover von Deezer setzen – nur bei eindeutigem Treffer (Künstler und Album gleich)."""
    index = _need_library()
    found, skipped = [], []
    for alb in library.albums_without_cover(index):
        try:
            hit = covers.exact_deezer(alb["artist"], alb["album"])
        except Exception as e:  # noqa: BLE001
            skipped.append({"artist": alb["artist"], "album": alb["album"], "reason": f"Deezer: {e}"})
            continue
        if not hit:
            skipped.append({"artist": alb["artist"], "album": alb["album"], "reason": "kein eindeutiger Treffer"})
            continue
        entry = {"artist": alb["artist"], "album": alb["album"], "cover": hit["url"]}
        if apply:
            try:
                image, mime = covers.download(hit["url"])
                with _library_lock:
                    library.set_cover(index, alb["files"], image, mime)
                entry["done"] = True
            except Exception as e:  # noqa: BLE001
                entry["error"] = str(e)
        found.append(entry)
    result = {"matched": found, "skipped": skipped, "applied": apply, "at": time.strftime("%Y-%m-%d %H:%M")}
    if apply:
        tmp = COVERS_LOG.with_suffix(".tmp")
        tmp.write_text(json.dumps(result))
        tmp.replace(COVERS_LOG)
    return result


@app.post("/api/library/covers/fill", dependencies=[Depends(require_token)])
def library_fill_covers(apply: bool = False) -> dict:
    return fill_missing_covers(apply)


def _fill_covers_daily() -> None:
    time.sleep(600)  # nicht direkt beim Start
    while True:
        try:
            r = fill_missing_covers(apply=True)
            n = sum(1 for m in r["matched"] if m.get("done"))
            if n:
                log.info("Fehlende Cover: %d Alben von Deezer ergänzt", n)
        except Exception:
            log.exception("Fehlende Cover ergänzen fehlgeschlagen")
        time.sleep(24 * 3600)


if MUSIC_DIR:
    threading.Thread(target=_fill_covers_daily, daemon=True).start()


@app.get("/api/library/duplicates", dependencies=[Depends(require_token)])
def library_duplicates() -> list[dict]:
    return library.duplicates(_need_library())


class ResolveDuplicates(BaseModel):
    keep: str
    remove: list[str]
    unmonitor: bool = True
    merge: bool = True  # fehlende Titel übernehmen statt die ganze Version zu löschen


@app.post("/api/library/duplicates/resolve", dependencies=[Depends(require_token)])
def library_resolve_duplicates(req: ResolveDuplicates) -> dict:
    """Behält eine Variante; die anderen kommen in den Papierkorb (30 Tage wiederherstellbar)."""
    index = _need_library()
    if req.keep in req.remove:
        raise HTTPException(400, "Die behaltene Version kann nicht gelöscht werden")
    if not library.variant_files(index, req.keep):
        raise HTTPException(404, "Die behaltene Version wurde nicht gefunden")
    if req.merge:
        results = []
        for key in req.remove:
            with _library_lock:
                try:
                    results.append(library.merge_into(index, _trash, req.keep, key))
                except library.LibraryError as e:
                    raise HTTPException(409, str(e)) from e
        return {"merged": results}
    trashed = []
    for key in req.remove:
        files = library.variant_files(index, key)
        if not files:
            continue
        artist = files[0]["albumartist"] or files[0]["artist"]
        with _library_lock:
            r = _trash.delete(artist, files[0]["album"], files)
        if req.unmonitor:
            _unmonitor_in_lidarr(artist, files[0]["album"])
        trashed.append({"album": files[0]["album"], "id": r["id"], "tracks": r["tracks"]})
    return {"trashed": trashed}


# ---- Qualität verbessern (Lidarr) ----

LOSSY = ("mp3", "aac", "ogg", "opus", "wma", "lossy")


@app.get("/api/library/quality", dependencies=[Depends(require_token)])
def library_quality() -> dict:
    """Alben, die in Lidarr nur verlustbehaftet vorliegen (MP3 usw.), schlechteste zuerst."""
    artists = lidarr("GET", "artist") or []
    names = {a["id"]: a["artistName"] for a in artists}
    albums = {a["id"]: a for a in (lidarr("GET", "album") or [])}
    by_album: dict[int, list[str]] = {}
    for a in artists:
        for f in lidarr("GET", "trackfile", params={"artistId": a["id"]}) or []:
            by_album.setdefault(f["albumId"], []).append(f["quality"]["quality"]["name"])
    out = []
    for album_id, qualities in by_album.items():
        if any(not any(x in q.lower() for x in LOSSY) for q in qualities):
            continue  # mindestens eine verlustfreie Datei → nicht aufführen
        al = albums.get(album_id) or {}
        cover = next((i.get("remoteUrl") for i in al.get("images") or [] if i.get("coverType") == "cover"), None)
        worst = sorted(set(qualities))[0]
        out.append({"albumId": album_id, "title": al.get("title", ""), "artist": names.get(al.get("artistId"), ""),
                    "year": (al.get("releaseDate") or "")[:4], "quality": worst, "tracks": len(qualities), "cover": cover})
    profiles = lidarr("GET", "qualityprofile") or []
    upgrades = any(p.get("upgradeAllowed") for p in profiles)
    return {"albums": sorted(out, key=lambda a: (a["artist"].lower(), a["title"].lower())), "upgradesAllowed": upgrades}


@app.get("/api/library/quality/{album_id}/releases", dependencies=[Depends(require_token)])
def library_quality_releases(album_id: int) -> list[dict]:
    """Interaktive Suche in Lidarr, nur verlustfreie Treffer."""
    releases = lidarr("GET", "release", params={"albumId": album_id}) or []
    out = []
    for r in releases:
        q = ((r.get("quality") or {}).get("quality") or {}).get("name", "")
        if any(x in q.lower() for x in LOSSY) or not q:
            continue
        out.append({"guid": r["guid"], "indexerId": r["indexerId"], "title": r.get("title", ""), "quality": q,
                    "size": r.get("size", 0), "seeders": r.get("seeders"), "indexer": r.get("indexer", ""),
                    "rejections": r.get("rejections") or []})
    out.sort(key=lambda r: (-(r["seeders"] or 0)))
    return out[:12]


class GrabRequest(BaseModel):
    guid: str
    indexerId: int


@app.post("/api/library/quality/grab", dependencies=[Depends(require_token)])
def library_quality_grab(req: GrabRequest) -> dict:
    lidarr("POST", "release", json={"guid": req.guid, "indexerId": req.indexerId})
    return {"ok": True}


@app.get("/api/library/trash", dependencies=[Depends(require_token)])
def library_trash() -> list[dict]:
    _need_library()
    return _trash.list()


@app.post("/api/library/trash/{entry_id}/restore", dependencies=[Depends(require_token)])
def library_trash_restore(entry_id: str) -> dict:
    _need_library()
    try:
        return _trash.restore(entry_id)
    except library.LibraryError as e:
        raise HTTPException(409, str(e)) from e


@app.post("/api/library/upload", dependencies=[Depends(require_token)])
async def library_upload(
    request: Request,
    filename: str = Query(...),
    artist: str = Query(...),
    album: str = Query(...),
    title: str = Query(...),
    track: int = Query(0),
    disc: int = Query(1),
    discs: int = Query(1),
    year: int | None = Query(None),
    genre: str | None = Query(None),
    trackArtist: str | None = Query(None),
) -> dict:
    index = _need_library()
    ext = Path(filename).suffix.lower()
    if ext not in library.AUDIO:
        raise HTTPException(400, f"Dateityp {ext or '?'} wird nicht unterstützt")
    target = library.upload_target(MUSIC_DIR, artist, album, year, track, disc, title, ext, discs)
    if library.already_there(index, target, artist, album, title):
        return {"status": "exists"}
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_name("." + target.name + ".part")
    size = 0
    try:
        with open(part, "wb") as out:
            async for chunk in request.stream():
                size += len(chunk)
                if size > 2 * 1024 * 1024 * 1024:
                    raise HTTPException(413, "Datei zu groß")
                out.write(chunk)
        if size == 0:
            raise HTTPException(400, "Leere Datei")
        part.replace(target)
    finally:
        part.unlink(missing_ok=True)
    try:
        library.write_tags(str(target), {
            "album": album, "albumartist": artist, "artist": trackArtist or artist, "title": title,
            "tracknumber": str(track) if track else None, "discnumber": str(disc) if discs > 1 else None,
            "date": str(year) if year else None, "genre": genre or None,
        })
    except library.LibraryError as e:
        log.warning("Tags für %s nicht geschrieben: %s", target, e)
    return {"status": "ok", "bytes": size}


@app.post("/api/library/upload/cover", dependencies=[Depends(require_token)])
async def library_upload_cover(request: Request, artist: str = Query(...), album: str = Query(...), year: int | None = Query(None)) -> dict:
    image = await request.body()
    if not image or len(image) > 15 * 1024 * 1024:
        raise HTTPException(400, "Kein oder zu großes Bild")
    mime = "image/png" if image[:8] == b"\x89PNG\r\n\x1a\n" else "image/jpeg"
    folder = library.upload_target(MUSIC_DIR, artist, album, year, 1, 1, "x", ".mp3").parent if MUSIC_DIR else None
    if folder is None or not folder.exists():
        raise HTTPException(404, "Album-Ordner noch nicht vorhanden – zuerst die Titel hochladen")
    files = [{"path": str(p)} for p in folder.iterdir() if p.suffix.lower() in library.AUDIO and not p.name.startswith(".")]
    with _library_lock:
        library.set_cover(_need_library(), files, image, mime)
    return {"tracks": len(files)}


# ---- Vorschläge zum Herunterladen ----

SUGGESTIONS_FILE = DATA_DIR / "suggestions.json"
_suggest_lock = threading.Lock()


def _read_json(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return default


@app.get("/api/suggestions", dependencies=[Depends(require_token)])
def get_suggestions(refresh: bool = False) -> dict:
    """Einmal am Tag neu berechnet; ausgeblendete Vorschläge bleiben weg."""
    with _suggest_lock:
        store = _read_json(SUGGESTIONS_FILE, {})
        dismissed = set(store.get("dismissed") or [])
        today = time.strftime("%Y-%m-%d")
        cached = store.get("result")
        if cached and cached.get("date") == today and not refresh:
            result = cached
        else:
            artists = lidarr("GET", "artist") or []
            albums = lidarr("GET", "album") or []
            import datetime as _dt
            result = suggestions.build(artists, albums, dismissed, _dt.date.today())
            store["result"] = result
            tmp = SUGGESTIONS_FILE.with_suffix(".tmp")
            tmp.write_text(json.dumps(store))
            tmp.replace(SUGGESTIONS_FILE)
    return {k: [i for i in v if i["key"] not in dismissed] if isinstance(v, list) else v for k, v in result.items()}


class DismissRequest(BaseModel):
    key: str


@app.post("/api/suggestions/dismiss", dependencies=[Depends(require_token)])
def dismiss_suggestion(req: DismissRequest) -> dict:
    with _suggest_lock:
        store = _read_json(SUGGESTIONS_FILE, {})
        dismissed = list(dict.fromkeys((store.get("dismissed") or []) + [req.key]))[-1000:]
        store["dismissed"] = dismissed
        tmp = SUGGESTIONS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(store))
        tmp.replace(SUGGESTIONS_FILE)
    return {"ok": True}


@app.get("/api/artist-genres", dependencies=[Depends(require_token)])
def artist_genres() -> dict:
    """Genres je Künstler aus Lidarr (MusicBrainz) – Grundlage für die Mixe, wenn Dateien keine Genres haben."""
    try:
        artists = lidarr("GET", "artist") or []
    except HTTPException:
        return {}
    return {a["artistName"].lower(): a.get("genres") or [] for a in artists if a.get("artistName")}


@app.get("/api/videos/find", dependencies=[Depends(require_token)])
def find_video(artist: str = Query(...), title: str = Query(...)) -> dict:
    key = _video_key(artist, title)
    entry = _videos_index().get(key)
    if not entry or not Path(entry["path"]).exists():
        raise HTTPException(404, "Kein Video vorhanden")
    return {"id": _video_id(key), "url": f"/api/videos/{_video_id(key)}"}


@app.get("/api/videos/{video_id}", dependencies=[Depends(require_token)])
def stream_video(video_id: str) -> FileResponse:
    for key, entry in _videos_index().items():
        if _video_id(key) == video_id and Path(entry["path"]).exists():
            return FileResponse(entry["path"], media_type="video/mp4")
    raise HTTPException(404, "Kein Video vorhanden")
