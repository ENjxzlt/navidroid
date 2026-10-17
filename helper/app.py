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
import shutil
import threading
import time
import uuid
from pathlib import Path
from typing import Any

import requests
from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import FileResponse
from mutagen.id3 import APIC, ID3, TALB, TDRC, TIT2, TPE1, TPE2, TPOS, TRCK, TXXX, ID3NoHeaderError
from pydantic import BaseModel
from yt_dlp import YoutubeDL
from yt_dlp.version import __version__ as YTDLP_VERSION

from matching import is_youtube_url, match, normalize, safe_name

VERSION = "1.0.0"

TOKEN = os.environ.get("HELPER_TOKEN", "")
LIDARR_URL = os.environ.get("LIDARR_URL", "").rstrip("/")
LIDARR_KEY = os.environ.get("LIDARR_API_KEY", "")
# Staging-Ordner, wie der Helfer ihn sieht, und wie Lidarr ihn sieht (bei unterschiedlichen Mounts).
STAGING = Path(os.environ.get("STAGING_DIR", "/downloads/navidroid"))
LIDARR_STAGING = os.environ.get("LIDARR_STAGING_DIR", str(STAGING)).rstrip("/")
VIDEO_DIR = Path(os.environ.get("VIDEO_DIR", "/videos"))
DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
AUDIO_QUALITY = os.environ.get("AUDIO_QUALITY", "320")
VIDEO_MAX_HEIGHT = int(os.environ.get("VIDEO_MAX_HEIGHT", "1080"))

if not TOKEN or not LIDARR_URL or not LIDARR_KEY:
    raise SystemExit("HELPER_TOKEN, LIDARR_URL und LIDARR_API_KEY müssen gesetzt sein")

for d in (STAGING, VIDEO_DIR, DATA_DIR):
    d.mkdir(parents=True, exist_ok=True)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("navidroid-helper")

app = FastAPI(title="NaviDroid Helper", version=VERSION)


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
    opts = {"quiet": True, "skip_download": True, "extract_flat": "in_playlist", "noprogress": True}
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
        if j["status"] in ("queued", "running"):
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
    album, tracks, release = album_info(job["albumId"])
    artist = (album.get("artist") or {}).get("artistName") or job["artist"]
    by_track = {t["id"]: t for t in tracks}
    plan = [(by_track[m["trackId"]], m["videoId"]) for m in job["mapping"] if m.get("videoId") and m["trackId"] in by_track]
    if not plan:
        raise RuntimeError("Keine Titel zugeordnet")
    total_tracks = len(tracks)
    workdir = STAGING / job["id"]
    workdir.mkdir(parents=True, exist_ok=True)

    # 1+2: Laden und in MP3 umwandeln
    _update(job, "download", state="active")
    files: dict[int, Path] = {}
    sources: dict[str, tuple[Path, list[dict]]] = {}
    for track, vid in plan:
        number = track.get("absoluteTrackNumber") or 0
        base = f"{number:02d} - {safe_name(track['title'])}"
        if "#" in vid:
            source_id, chapter = vid.split("#", 1)
            if source_id not in sources:
                sources[source_id] = _download_source(source_id, workdir)
            src, chapters = sources[source_id]
            c = chapters[int(chapter) - 1]
            mp3 = workdir / f"{base}.mp3"
            cmd = ["ffmpeg", "-loglevel", "error", "-y", "-ss", str(c.get("start_time") or 0)]
            if c.get("end_time"):
                cmd += ["-to", str(c["end_time"])]
            cmd += ["-i", str(src), "-vn", "-c:a", "libmp3lame", "-b:a", f"{AUDIO_QUALITY}k", str(mp3)]
            subprocess.run(cmd, check=True)
            _update(job, "download", done=next(s for s in job["steps"] if s["key"] == "download")["done"] + 1)
            files[track["id"]] = mp3
            _update(job, "convert", state="active", done=len(files))
            continue
        opts = {
            "quiet": True,
            "noprogress": True,
            "noplaylist": True,
            "format": "bestaudio/best",
            "outtmpl": str(workdir / f"{base}.%(ext)s"),
            "postprocessors": [{"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": AUDIO_QUALITY}],
        }
        with YoutubeDL(opts) as ydl:
            ydl.download([video_url(vid)])
        _update(job, "download", done=next(s for s in job["steps"] if s["key"] == "download")["done"] + 1)
        mp3 = workdir / f"{base}.mp3"
        if not mp3.exists():
            raise RuntimeError(f"Umwandlung fehlgeschlagen: {track['title']}")
        files[track["id"]] = mp3
        _update(job, "convert", state="active", done=len(files))
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

    # Optional: Musikvideos
    if job["videos"]:
        _update(job, "videos", state="active")
        index = _videos_index()
        target_dir = VIDEO_DIR / safe_name(artist) / safe_name(album["title"])
        target_dir.mkdir(parents=True, exist_ok=True)
        for i, (track, vid) in enumerate(plan, 1):
            base = f"{(track.get('absoluteTrackNumber') or 0):02d} - {safe_name(track['title'])}"
            h = VIDEO_MAX_HEIGHT
            opts = {
                "quiet": True,
                "noprogress": True,
                "noplaylist": True,
                "format": f"bv*[height<={h}][ext=mp4]+ba[ext=m4a]/b[height<={h}][ext=mp4]/bv*[height<={h}]+ba/b",
                "merge_output_format": "mp4",
                "outtmpl": str(target_dir / f"{base}.%(ext)s"),
            }
            try:
                with YoutubeDL(opts) as ydl:
                    ydl.download([video_url(vid)])
                path = target_dir / f"{base}.mp4"
                if path.exists():
                    key = _video_key(artist, track["title"])
                    index[key] = {"path": str(path), "artist": artist, "title": track["title"], "album": album["title"]}
            except Exception as e:  # ein fehlendes Video soll den Import nicht scheitern lassen
                log.warning("Video %s fehlgeschlagen: %s", vid, e)
            _update(job, "videos", done=i)
        tmp = _VIDEOS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(index, ensure_ascii=False))
        tmp.replace(_VIDEOS_FILE)
        _update(job, "videos", state="done")


def _download_source(video_id: str, workdir: Path) -> tuple[Path, list[dict]]:
    """Lädt die Tonspur eines Kapitel-Videos einmal herunter und liefert sie mit den Kapiteln."""
    opts = {
        "quiet": True,
        "noprogress": True,
        "noplaylist": True,
        "format": "bestaudio/best",
        "outtmpl": str(workdir / f"_src_{video_id}.%(ext)s"),
    }
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
            msg = e.detail if isinstance(e, HTTPException) else str(e)
            for s in job["steps"]:
                if s["state"] == "active":
                    _update(job, s["key"], state="failed")
            _update(job, status="failed", error=msg, finishedAt=int(time.time() * 1000))


_load_jobs()
threading.Thread(target=worker, daemon=True).start()


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
    return {"version": VERSION, "ytDlp": YTDLP_VERSION, "lidarr": lidarr_version, "lidarrError": lidarr_error}


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
    opts = {"quiet": True, "skip_download": True, "extract_flat": True, "noprogress": True, "playlistend": limit}
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
        "createdAt": int(time.time() * 1000),
    }
    with _lock:
        _jobs[job["id"]] = job
    _save_jobs()
    _queue.put(job["id"])
    return _public(job)


def _public(job: dict) -> dict:
    return {k: v for k, v in job.items() if k not in ("mapping", "url")}


@app.get("/api/jobs", dependencies=[Depends(require_token)])
def list_jobs() -> list[dict]:
    with _lock:
        jobs = sorted(_jobs.values(), key=lambda j: j["createdAt"], reverse=True)[:20]
        return [_public(j) for j in jobs]


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
