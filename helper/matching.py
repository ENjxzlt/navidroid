"""Ordnet Videos einer YouTube-Playlist den Titeln eines Lidarr-Albums zu."""
from __future__ import annotations

import difflib
import re
import unicodedata

# Klammerzusätze wie "(Official Audio)", "[HD]" oder "(Lyric Video)" stören beim Vergleich.
_NOISE = re.compile(
    r"[\(\[][^\)\]]*\b(official|audio|video|lyrics?|visuali[sz]er|hd|hq|4k|explicit|remaster(ed)?|full album|topic|mv)\b[^\)\]]*[\)\]]",
    re.I,
)
_FEAT = re.compile(r"\b(feat\.?|ft\.?|featuring)\b.*$", re.I)


def _ascii(text: str) -> str:
    return unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()


def normalize(title: str, artist: str = "") -> str:
    s = _ascii(title)
    s = _NOISE.sub(" ", s)
    if artist:
        a = _ascii(artist).strip()
        if a:
            # "Künstler - Titel" am Anfang entfernen
            s = re.sub(rf"^\s*{re.escape(a)}\s*[-–—:|~]+\s*", " ", s)
    s = _FEAT.sub(" ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def score(track_title: str, track_seconds: float | None, video_title: str, video_seconds: float | None, artist: str) -> float:
    t = normalize(track_title, artist)
    v = normalize(video_title, artist)
    if not t or not v:
        return 0.0
    ratio = difflib.SequenceMatcher(None, t, v).ratio()
    if len(t) > 2 and (f" {t} " in f" {v} " or f" {v} " in f" {t} "):
        ratio = max(ratio, 0.9)
    if track_seconds and video_seconds:
        diff = abs(track_seconds - video_seconds)
        if diff <= 3:
            ratio += 0.1
        elif diff > 45:
            ratio -= 0.15
    return max(0.0, min(1.0, ratio))


def match(tracks: list[dict], videos: list[dict], artist: str, threshold: float = 0.6) -> tuple[list[dict], list[dict]]:
    """
    tracks: [{id, number, title, seconds}], videos: [{id, index, title, seconds}]
    Liefert (Zuordnung je Titel, nicht zugeordnete Videos).
    """
    pairs = []
    for ti, t in enumerate(tracks):
        for vi, v in enumerate(videos):
            pairs.append((score(t["title"], t.get("seconds"), v["title"], v.get("seconds"), artist), ti, vi))
    pairs.sort(reverse=True)

    track_to_video: dict[int, tuple[int, float]] = {}
    used_videos: set[int] = set()
    for s, ti, vi in pairs:
        if s < threshold:
            break
        if ti in track_to_video or vi in used_videos:
            continue
        track_to_video[ti] = (vi, s)
        used_videos.add(vi)

    # Gleiche Anzahl und kaum Treffer: vermutlich eine sauber sortierte Playlist ohne brauchbare Titel.
    if len(tracks) == len(videos) and len(track_to_video) < len(tracks) / 2:
        track_to_video = {i: (i, 0.5) for i in range(len(tracks))}
        used_videos = set(range(len(videos)))

    rows = []
    for ti, t in enumerate(tracks):
        hit = track_to_video.get(ti)
        v = videos[hit[0]] if hit else None
        rows.append({
            "trackId": t["id"],
            "trackNumber": t["number"],
            "title": t["title"],
            "videoId": v["id"] if v else None,
            "videoTitle": v["title"] if v else None,
            "videoIndex": v["index"] if v else None,
            "score": round(hit[1], 2) if hit else 0.0,
        })
    unmatched = [v for i, v in enumerate(videos) if i not in used_videos]
    return rows, unmatched


_UNSAFE = re.compile(r'[\\/:*?"<>|\x00-\x1f]')


def safe_name(text: str, limit: int = 120) -> str:
    s = _UNSAFE.sub("_", text).strip().strip(".")
    return (s or "_")[:limit]


ALLOWED_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"}


def is_youtube_url(url: str) -> bool:
    m = re.match(r"^https?://([^/?#]+)", url.strip(), re.I)
    return bool(m) and m.group(1).lower() in ALLOWED_HOSTS
