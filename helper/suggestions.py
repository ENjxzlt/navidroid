"""
Vorschläge zum Herunterladen:
- Alben deiner Künstler, die Lidarr kennt, die aber noch fehlen (neu erschienen oder ältere Lücken),
- Alben ähnlicher Künstler über die frei zugängliche Deezer-API (ohne Konto).
Was schon in der Bibliothek ist oder ausgeblendet wurde, fällt raus.
"""
from __future__ import annotations

import datetime as dt
import re
import unicodedata
from collections import defaultdict
from typing import Any, Callable

import requests

DEEZER = "https://api.deezer.com"
SKIP_WORDS = re.compile(r"\b(live|deluxe|remaster(ed)?|edition|instrumentals?|acoustic|demo|karaoke|remix(es)?|anniversary|expanded|bonus|commentary|best of|greatest hits|hits|collection|essentials|anthology|sessions|complete)\b", re.I)


def norm(text: str | None) -> str:
    s = unicodedata.normalize("NFKD", text or "").encode("ascii", "ignore").decode().lower()
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def _cover(album: dict) -> str | None:
    for img in album.get("images") or []:
        if img.get("coverType") == "cover":
            return img.get("remoteUrl") or img.get("url")
    return None


def from_lidarr(artists: list[dict], albums: list[dict], today: dt.date) -> tuple[list[dict], list[dict]]:
    """(neu erschienen, fehlt in der Sammlung) – nur Studioalben ohne Zusatzversionen."""
    names = {a["id"]: a.get("artistName", "") for a in artists}
    have_files = {a["id"] for a in artists if ((a.get("statistics") or {}).get("trackFileCount") or 0) > 0}
    new, missing = [], []
    for al in albums:
        if al.get("albumType") != "Album" or al.get("secondaryTypes"):
            continue
        if al.get("artistId") not in have_files:
            continue  # Künstler ohne eine einzige Datei sind eher Anfragen, keine Lücken
        stats = al.get("statistics") or {}
        if (stats.get("trackFileCount") or 0) > 0:
            continue
        if SKIP_WORDS.search(al.get("title") or ""):
            continue
        try:
            released = dt.date.fromisoformat((al.get("releaseDate") or "")[:10])
        except ValueError:
            continue
        if released > today:
            continue
        item = {
            "key": f"mb:{al.get('foreignAlbumId')}",
            "title": al.get("title") or "",
            "artist": names.get(al.get("artistId"), ""),
            "year": released.year,
            "cover": _cover(al),
            "foreignAlbumId": al.get("foreignAlbumId"),
            "source": "lidarr",
        }
        if (today - released).days <= 548:
            item["reason"] = f"Erschienen {released.year}"
            new.append((released, item))
        else:
            item["reason"] = "Fehlt in deiner Sammlung"
            missing.append((released, item))
    new.sort(key=lambda x: x[0], reverse=True)
    missing.sort(key=lambda x: x[0], reverse=True)
    return [i for _, i in new], [i for _, i in missing]


def _get(path: str, **params: Any) -> dict:
    r = requests.get(f"{DEEZER}/{path}", params=params, timeout=15)
    r.raise_for_status()
    data = r.json()
    if isinstance(data, dict) and data.get("error"):
        raise RuntimeError(str(data["error"]))
    return data


def similar(library_artists: list[str], have_albums: set[tuple[str, str]], get: Callable[..., dict] = _get, seeds: int = 12, limit: int = 12) -> list[dict]:
    """Ähnliche Künstler über Deezer; je Künstler das bekannteste Album, das noch nicht da ist."""
    mine = {norm(a) for a in library_artists}
    votes: dict[int, list[str]] = defaultdict(list)
    info: dict[int, dict] = {}
    for name in library_artists[:seeds]:
        try:
            hits = get("search/artist", q=name, limit=3).get("data") or []
            hit = next((h for h in hits if norm(h.get("name")) == norm(name)), None)
            if not hit:
                continue
            for rel in get(f"artist/{hit['id']}/related", limit=15).get("data") or []:
                if norm(rel.get("name")) in mine:
                    continue
                votes[rel["id"]].append(name)
                info[rel["id"]] = rel
        except Exception:
            continue
    ranked = sorted(votes, key=lambda i: (-len(votes[i]), -(info[i].get("nb_fan") or 0)))
    out = []
    for artist_id in ranked:
        if len(out) >= limit:
            break
        try:
            top = get(f"artist/{artist_id}/top", limit=15).get("data") or []
            own = get(f"artist/{artist_id}/albums", limit=100).get("data") or []
        except Exception:
            continue
        # Nur echte Studioalben dieses Künstlers (keine Singles, Compilations, Best-of)
        studio = {a["id"]: a for a in own if a.get("record_type") == "album" and not SKIP_WORDS.search(a.get("title") or "")}
        if not studio:
            continue
        counts: dict[int, int] = defaultdict(int)
        for t in top:
            al_id = (t.get("album") or {}).get("id")
            if al_id in studio:
                counts[al_id] += 1
        best = studio[max(studio, key=lambda i: (counts.get(i, 0), studio[i].get("fans") or 0))]
        artist = info[artist_id].get("name", "")
        if (norm(artist), norm(best.get("title"))) in have_albums:
            continue
        out.append({
            "key": f"dz:{best['id']}",
            "title": best.get("title") or "",
            "artist": artist,
            "year": None,
            "cover": best.get("cover_xl") or best.get("cover_big"),
            "reason": f"Weil du {votes[artist_id][0]} hörst",
            "source": "deezer",
        })
    return out


def build(artists: list[dict], albums: list[dict], dismissed: set[str], today: dt.date, get: Callable[..., dict] = _get) -> dict:
    new, missing = from_lidarr(artists, albums, today)
    have = {(norm(a.get("artistName")), norm(al.get("title")))
            for al in albums for a in artists
            if a["id"] == al.get("artistId") and ((al.get("statistics") or {}).get("trackFileCount") or 0) > 0}
    by_files = sorted(artists, key=lambda a: -((a.get("statistics") or {}).get("trackFileCount") or 0))
    seeds = [a.get("artistName", "") for a in by_files if ((a.get("statistics") or {}).get("trackFileCount") or 0) > 0]
    sim = similar(seeds, have, get=get)
    # „Für dich“: ähnliche Künstler und ältere Lücken abwechselnd
    for_you: list[dict] = []
    for i in range(max(len(sim), len(missing))):
        if i < len(sim):
            for_you.append(sim[i])
        if i < len(missing) and i < 6:
            for_you.append(missing[i])
    keep = lambda items: [i for i in items if i["key"] not in dismissed]  # noqa: E731
    return {"forYou": keep(for_you)[:16], "newReleases": keep(new)[:12], "date": today.isoformat()}
