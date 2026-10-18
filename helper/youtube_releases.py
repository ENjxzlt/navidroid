"""
YouTube-Veröffentlichungen entdecken: zu jedem Künstler der Bibliothek den offiziellen Kanal finden
(Häkchen-Kanal mit passendem Namen) und dessen „Releases“-Reiter lesen (Alben, EPs, Singles als
YouTube-Music-Playlists). Was schon in der Bibliothek ist, fällt raus.
"""
from __future__ import annotations

import hashlib
import re
import unicodedata
from typing import Any, Callable

Extract = Callable[[str, dict], dict]


def norm(text: str | None) -> str:
    s = unicodedata.normalize("NFKD", text or "").encode("ascii", "ignore").decode().lower()
    s = re.sub(r"[\(\[][^\)\]]*\b(clean|explicit|deluxe)\b[^\)\]]*[\)\]]", " ", s)
    s = re.sub(r"\b(feat|ft|featuring)\b.*$", " ", s)
    s = re.sub(r"[\(\[][^\)\]]*\b(single|ep|version|edit|remaster(ed)?|explicit)\b[^\)\]]*[\)\]]", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


# Live-Mitschnitte, Remixe, Cover, B-Seiten, einzelne Discs u. Ä. sind keine neuen Veröffentlichungen
NOISE = re.compile(
    r"\b(live|remix(es)?|cover|b[- ]?sides?|dis[ck] ?\d|instrumentals?|karaoke|sped up|slowed|acoustic|demo|tour|commentary)\b|\bmix\)|\bmix\]",
    re.I,
)
NEWEST_ON_HOME = 6


def key_for(playlist_id: str) -> str:
    return "yt:" + playlist_id


def _ydl_extract(url: str, opts: dict) -> dict:
    from yt_dlp import YoutubeDL

    base = {"quiet": True, "no_warnings": True, "skip_download": True, "extract_flat": True}
    with YoutubeDL({**base, **opts}) as ydl:
        return ydl.extract_info(url, download=False) or {}


def find_channel(artist: str, extract: Extract = _ydl_extract) -> dict | None:
    """Offizieller Kanal: verifiziert und Name passt (auch „Künstler - Topic“ wird akzeptiert)."""
    want = norm(artist)
    if not want:
        return None
    res = extract(f"ytsearch10:{artist}", {"playlistend": 10})
    best = None
    for e in res.get("entries") or []:
        name = norm(e.get("channel") or e.get("uploader"))
        cid = e.get("channel_id")
        if not cid or not name:
            continue
        topic = name.endswith(" topic") and name[: -len(" topic")].strip() == want
        if name == want and e.get("channel_is_verified"):
            return {"id": cid, "name": e.get("channel") or artist}
        if (name == want or topic) and best is None:
            best = {"id": cid, "name": e.get("channel") or artist}
    return best


def releases(channel_id: str, extract: Extract = _ydl_extract, limit: int = 12) -> list[dict]:
    res = extract(f"https://www.youtube.com/channel/{channel_id}/releases", {"playlistend": limit})
    out = []
    for i, e in enumerate(res.get("entries") or []):
        pid = e.get("id") or ""
        if not pid or not e.get("title") or NOISE.search(e["title"]):
            continue
        thumbs = e.get("thumbnails") or []
        out.append({
            "key": key_for(pid),
            "playlistId": pid,
            "url": e.get("url") or f"https://www.youtube.com/playlist?list={pid}",
            "title": e.get("title"),
            "cover": thumbs[-1]["url"] if thumbs else None,
            "order": i,  # 0 = neueste
        })
    return out


def missing(items: list[dict], artist: str, library: list[dict]) -> list[dict]:
    """
    library: Einträge mit album/albumartist/artist/title (Tags aus dem Musikordner).
    Ein Release gilt als vorhanden, wenn es ein Album gleichen Namens vom Künstler gibt
    oder – bei Singles – ein Titel gleichen Namens.
    """
    a = norm(artist)
    mine = [f for f in library if a in (norm(f.get("albumartist")), norm(f.get("artist"))) or a in norm(f.get("artist"))]
    albums = {norm(f.get("album")) for f in mine}
    titles = {norm(f.get("title")) for f in mine}
    return [i for i in items if norm(i["title"]) not in albums and norm(i["title"]) not in titles]


def combine(items: list[dict]) -> list[dict]:
    """
    Dasselbe Album doppelt (z. B. Feature auf zwei Kanälen, Clean- und Explicit-Version) wird zu einem
    Eintrag zusammengefasst; die Künstler werden zusammengeführt, die übrigen Schlüssel gemerkt.
    """
    out: list[dict] = []
    by_title: dict[str, dict] = {}
    for i in items:
        k = norm(i["title"])
        first = by_title.get(k)
        if first is None:
            merged = {**i, "artists": [i.get("artist")] if i.get("artist") else [], "alsoKeys": []}
            by_title[k] = merged
            out.append(merged)
            continue
        if i.get("artist") and norm(i["artist"]) not in {norm(a) for a in first["artists"]}:
            first["artists"].append(i["artist"])
        if i["key"] != first["key"]:
            first["alsoKeys"].append(i["key"])
    for m in out:
        if len(m["artists"]) > 1:
            m["artistLine"] = " & ".join(m["artists"])
    return out


def interleave(per_artist: dict[str, list[dict]], limit: int = 24) -> list[dict]:
    """Neueste zuerst: reihum je Künstler das nächste Release (nur die neuesten je Kanal); Doppelte zusammengefasst."""
    recent = {a: [i for i in items if i.get("order", 0) < NEWEST_ON_HOME] for a, items in per_artist.items()}
    return combine(_interleave(recent, limit * 2))[:limit]


def _interleave(per_artist: dict[str, list[dict]], limit: int) -> list[dict]:
    out: list[dict] = []
    lists = [list(v) for v in per_artist.values() if v]
    while lists and len(out) < limit:
        for lst in list(lists):
            if lst:
                out.append(lst.pop(0))
            if not lst:
                lists.remove(lst)
            if len(out) >= limit:
                break
    return out


def stable_hash(text: str) -> str:
    return hashlib.sha1(text.encode()).hexdigest()[:10]


def cover_for(playlist_id: str, extract: Extract = _ydl_extract) -> str | None:
    """
    Die Release-Liste enthält keine Vorschaubilder. Das Bild des ersten Titels ist bei YouTube Music
    das Albumcover (16:9 mit dem quadratischen Cover in der Mitte – in der App mittig zugeschnitten).
    """
    res = extract(f"https://www.youtube.com/playlist?list={playlist_id}", {"playlistend": 1})
    first = next((e for e in res.get("entries") or [] if e and e.get("id")), None)
    return f"https://i.ytimg.com/vi/{first['id']}/maxresdefault.jpg" if first else None


def scan(artists: list[str], library: list[dict], channels: dict[str, Any], extract: Extract = _ydl_extract,
         covers: dict[str, str] | None = None) -> tuple[dict[str, list[dict]], dict[str, Any]]:
    """Liefert (fehlende Releases je Künstler, aktualisierte Kanal-Zuordnung); [covers] wird ergänzt."""
    covers = covers if covers is not None else {}
    found: dict[str, list[dict]] = {}
    channels = dict(channels)
    for artist in artists:
        k = norm(artist)
        ch = channels.get(k)
        if ch is None:
            try:
                ch = find_channel(artist, extract) or False
            except Exception:
                continue
            channels[k] = ch
        if not ch:
            continue
        try:
            rel = releases(ch["id"], extract)
        except Exception:
            continue
        items = combine(missing(rel, artist, library))
        for i in items:
            if not i.get("cover"):
                if i["playlistId"] not in covers:
                    try:
                        covers[i["playlistId"]] = cover_for(i["playlistId"], extract) or ""
                    except Exception:
                        pass
                i["cover"] = covers.get(i["playlistId"]) or None
            i["artist"] = artist
            i["channel"] = ch.get("name")
        found[artist] = items
    return found, channels
