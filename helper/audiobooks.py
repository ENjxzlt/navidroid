"""
Hörspiel-Serien (z. B. Die drei ???): neue Folgen aus dem „Veröffentlichungen“-Reiter eines YouTube-Kanals
erkennen und automatisch in den Hörspiel-Ordner (eigene Navidrome-Bibliothek) laden.

Beim Hinzufügen einer Serie wird festgehalten, was dort schon steht („baseline“) – geladen wird nur, was
danach erscheint, nicht der ganze Katalog. Was schon im Ordner liegt (gleicher Titel oder gleiche
Folgennummer), wird nie erneut geladen.
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Callable

import youtube_releases as ytr

Extract = Callable[[str, dict], dict]

# „Folge 230: …“, „230/und der …“, „Nr. 12 – …“; Jahreszahlen (vierstellig) zählen nicht als Folge
_NUMBER = re.compile(r"^\D{0,20}?\b(\d{1,3})\b(?!\d)")
_YEAR_SUFFIX = re.compile(r"\s*\((19|20)\d{2}\)\s*$")


def episode_number(title: str | None) -> int | None:
    m = _NUMBER.search(title or "")
    return int(m.group(1)) if m else None


def releases(channel_id: str, extract: Extract = ytr._ydl_extract, limit: int = 40) -> list[dict]:
    """Wie youtube_releases.releases, aber ohne den Musik-Filter (Live, Tour, … sind bei Hörspielen normale Titel)."""
    res = extract(f"https://www.youtube.com/channel/{channel_id}/releases", {"playlistend": limit})
    out = []
    for i, e in enumerate(res.get("entries") or []):
        pid = e.get("id") or ""
        if not pid or not e.get("title"):
            continue
        thumbs = e.get("thumbnails") or []
        out.append({
            "key": ytr.key_for(pid),
            "playlistId": pid,
            "url": e.get("url") or f"https://www.youtube.com/playlist?list={pid}",
            "title": e.get("title"),
            "cover": thumbs[-1]["url"] if thumbs else None,
            "order": i,
        })
    return out


def present(root: Path, artist: str, safe_name: Callable[[str], str]) -> tuple[set[str], set[int]]:
    """Titel (normalisiert) und Folgennummern der Ordner unter <root>/<Serie>/."""
    folder = root / safe_name(artist)
    titles: set[str] = set()
    numbers: set[int] = set()
    if not folder.is_dir():
        return titles, numbers
    for d in folder.iterdir():
        if not d.is_dir():
            continue
        name = _YEAR_SUFFIX.sub("", d.name)
        titles.add(ytr.norm(name))
        n = episode_number(name)
        if n is not None:
            numbers.add(n)
    return titles, numbers


def due(series: dict, items: list[dict], titles: set[str], numbers: set[int]) -> list[dict]:
    """Was zu laden ist: neu seit dem Hinzufügen, noch nicht geladen und nicht schon im Ordner. Älteste zuerst."""
    skip = set(series.get("baseline") or []) | set(series.get("imported") or [])
    out = []
    for i in items:
        if i["key"] in skip or ytr.norm(i["title"]) in titles:
            continue
        n = episode_number(i["title"])
        if n is not None and n in numbers:
            continue
        out.append(i)
    return sorted(out, key=lambda i: -i.get("order", 0))


def new_series(name: str, channel: dict, items: list[dict], import_existing: bool, sid: str, now_ms: int) -> dict:
    return {
        "id": sid,
        "name": name,
        "channel": channel,
        "baseline": [] if import_existing else [i["key"] for i in items],
        "imported": [],
        "addedAt": now_ms,
        "lastCheck": None,
        "lastError": None,
    }


def public(series: dict) -> dict:
    return {k: series.get(k) for k in ("id", "name", "channel", "addedAt", "lastCheck", "lastError")} | {
        "imported": len(series.get("imported") or []),
    }
