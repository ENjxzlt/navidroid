"""
Album-Cover online suchen: Deezer, Apple Music (iTunes) und Cover Art Archive (MusicBrainz).
Heruntergeladen wird nur von diesen Bildservern – keine beliebigen URLs.
"""
from __future__ import annotations

import re
from typing import Any, Callable
from urllib.parse import urlparse

import requests

UA = {"User-Agent": "NaviDroid-Helper/1.0 (https://github.com/ENjxzlt/navidroid)"}
ALLOWED_HOSTS = re.compile(r"(^|\.)(dzcdn\.net|mzstatic\.com|coverartarchive\.org|archive\.org|ytimg\.com)$")
MAX_BYTES = 15 * 1024 * 1024

Get = Callable[..., Any]


def _json(url: str, params: dict) -> Any:
    r = requests.get(url, params=params, headers=UA, timeout=15)
    r.raise_for_status()
    return r.json()


def _norm(s: str | None) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


def deezer(artist: str, album: str, get: Get = _json) -> list[dict]:
    data = get("https://api.deezer.com/search/album", {"q": f'artist:"{artist}" album:"{album}"', "limit": 8}).get("data") or []
    if not data:
        data = get("https://api.deezer.com/search/album", {"q": f"{artist} {album}", "limit": 8}).get("data") or []
    return [{"url": a["cover_xl"], "source": "Deezer", "title": a.get("title", ""), "artist": (a.get("artist") or {}).get("name", ""), "size": 1000}
            for a in data if a.get("cover_xl")]


def itunes(artist: str, album: str, get: Get = _json) -> list[dict]:
    data = get("https://itunes.apple.com/search", {"term": f"{artist} {album}", "entity": "album", "limit": 8}).get("results") or []
    out = []
    for a in data:
        art = a.get("artworkUrl100")
        if art:
            out.append({"url": re.sub(r"/\d+x\d+bb\.", "/1400x1400bb.", art), "source": "Apple Music",
                        "title": a.get("collectionName", ""), "artist": a.get("artistName", ""), "size": 1400})
    return out


def cover_art_archive(artist: str, album: str, get: Get = _json) -> list[dict]:
    q = f'releasegroup:"{album}" AND artist:"{artist}"'
    groups = get("https://musicbrainz.org/ws/2/release-group", {"query": q, "fmt": "json", "limit": 5}).get("release-groups") or []
    return [{"url": f"https://coverartarchive.org/release-group/{g['id']}/front-1200", "source": "Cover Art Archive",
             "title": g.get("title", ""), "artist": ((g.get("artist-credit") or [{}])[0]).get("name", ""), "size": 1200}
            for g in groups if g.get("id") and int(g.get("score") or 0) >= 80]


def search(artist: str, album: str, get: Get = _json) -> list[dict]:
    """Beste Treffer zuerst: passender Titel und Künstler vor dem Rest, ohne doppelte Bilder."""
    results: list[dict] = []
    for source in (deezer, itunes, cover_art_archive):
        try:
            results += source(artist, album, get)
        except Exception:
            continue
    a, b = _norm(artist), _norm(album)

    def score(r: dict) -> int:
        s = 0
        if _norm(r["title"]) == b:
            s += 3
        elif b and b in _norm(r["title"]):
            s += 1
        if _norm(r["artist"]) == a:
            s += 2
        return s

    seen = set()
    out = []
    for r in sorted(results, key=lambda r: -score(r)):
        if r["url"] in seen or (b and b not in _norm(r["title"]) and _norm(r["title"]) not in b):
            continue  # anderes Album desselben Künstlers
        seen.add(r["url"])
        out.append(r)
    return out[:24]


def download(url: str) -> tuple[bytes, str]:
    host = urlparse(url).hostname or ""
    if urlparse(url).scheme not in ("http", "https") or not ALLOWED_HOSTS.search(host):
        raise ValueError("Bilder nur von Deezer, Apple Music oder dem Cover Art Archive")
    with requests.get(url, headers=UA, timeout=30, stream=True) as r:
        r.raise_for_status()
        # Weiterleitungen (Cover Art Archive → archive.org) ebenfalls prüfen
        final = urlparse(r.url).hostname or ""
        if not ALLOWED_HOSTS.search(final):
            raise ValueError("Unerwartete Weiterleitung")
        mime = (r.headers.get("Content-Type") or "").split(";")[0].strip()
        if mime not in ("image/jpeg", "image/png"):
            raise ValueError(f"Kein Bild ({mime or 'unbekannt'})")
        data = b""
        for chunk in r.iter_content(64 * 1024):
            data += chunk
            if len(data) > MAX_BYTES:
                raise ValueError("Bild zu groß")
    return data, mime
