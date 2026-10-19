"""
Musikvideos nachladen: zu einem Titel der Bibliothek das offizielle Musikvideo auf YouTube finden.

Bewertet die Treffer einer YouTube-Suche: Der Titel muss vorkommen; „Official Video“, der Kanal des
Künstlers (oder VEVO) und eine passende Länge sprechen dafür, Lyric-/Audio-Videos, Live-Mitschnitte,
Cover u. Ä. dagegen. Automatisch geladen wird nur, was sicher genug ist ([AUTO_MIN]).
"""
from __future__ import annotations

import re

from matching import _ascii, normalize

AUTO_MIN = 4

_OFFICIAL = re.compile(r"\b(official (music )?video|music video|offizielles (musik)?video|musikvideo|video oficial|clip officiel|official mv|\bmv\b)", re.I)
_BAD = re.compile(
    r"\b(lyrics?|lyric video|official audio|audio only|visuali[sz]er|live|cover|reaction|karaoke|instrumental|remix|"
    r"sped up|slowed|nightcore|8d|teaser|trailer|snippet|behind the scenes|making of|fan ?made|tutorial|tiktok)\b",
    re.I,
)


def _channel_ok(channel: str, artist: str) -> bool:
    c = normalize(channel)
    a = normalize(artist)
    return bool(a) and (c == a or c == a.replace(" ", "") + "vevo" or c.replace(" ", "") == a.replace(" ", "") + "vevo" or c == f"{a} official")


def score(entry: dict, artist: str, title: str, seconds: float | None = None) -> int:
    """Punkte für einen Suchtreffer; negativ = passt nicht."""
    vt = entry.get("title") or ""
    channel = entry.get("channel") or entry.get("uploader") or ""
    t = normalize(title, artist)
    v = normalize(vt, artist)
    if not t or f" {t} " not in f" {v} ":
        return -10  # Titel kommt nicht vor
    if normalize(channel).endswith(" topic"):
        return -10  # „Künstler - Topic“: nur Tonspur mit Standbild
    s = 0
    if _OFFICIAL.search(vt):
        s += 3
    if _channel_ok(channel, artist):
        s += 2
    elif normalize(artist) and f" {normalize(artist)} " not in f" {_plain(vt)} ":
        s -= 2  # weder Kanal noch Videotitel nennen den Künstler
    if entry.get("channel_is_verified"):
        s += 1
    # Abweichende Fassungen – außer der Titel selbst heißt so (z. B. „Live Forever“)
    for m in _BAD.finditer(vt):
        if m.group(0).lower() not in _ascii(title):
            s -= 4
            break
    dur = entry.get("duration")
    if seconds and dur:
        diff = dur - seconds
        if -15 <= diff <= 90:  # Videos haben oft Intro/Outro
            s += 1
        elif abs(diff) > 180:
            s -= 3
    return s


def _plain(text: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", _ascii(text))).strip()


def rank(entries: list[dict], artist: str, title: str, seconds: float | None = None, limit: int = 6) -> list[dict]:
    """Beste Kandidaten zuerst (nur solche, deren Titel passt)."""
    scored = []
    for e in entries:
        if not e or not e.get("id"):
            continue
        s = score(e, artist, title, seconds)
        if s <= -10:
            continue
        thumbs = e.get("thumbnails") or []
        scored.append({
            "videoId": e["id"],
            "title": e.get("title") or "",
            "channel": e.get("channel") or e.get("uploader") or "",
            "seconds": e.get("duration"),
            "thumbnail": f"https://i.ytimg.com/vi/{e['id']}/mqdefault.jpg" if e.get("id") else (thumbs[-1]["url"] if thumbs else None),
            "score": s,
            "auto": s >= AUTO_MIN,
        })
    scored.sort(key=lambda c: -c["score"])
    return scored[:limit]


def best(entries: list[dict], artist: str, title: str, seconds: float | None = None) -> dict | None:
    """Kandidat für den automatischen Download oder None, wenn keiner sicher genug ist."""
    top = rank(entries, artist, title, seconds, limit=1)
    return top[0] if top and top[0]["auto"] else None
