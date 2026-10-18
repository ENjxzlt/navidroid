"""
Eigener Hörverlauf (Navidrome zählt Wiedergaben bei dieser Installation nicht).
NaviDroid meldet jeden fertig gehörten Titel; daraus entstehen Wiedergabezahlen für Mixe und Daylist
sowie „Zuletzt gespielt“ auf der Startseite.
"""
from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path

_lock = threading.Lock()


class History:
    def __init__(self, path: Path):
        self.path = path
        with self._db() as c:
            c.execute("""create table if not exists plays (
                user text not null, song_id text not null, title text, artist text, album text,
                album_id text, cover_art text, genre text, duration integer, ts integer not null)""")
            c.execute("create index if not exists plays_user_ts on plays(user, ts)")
            c.execute("create index if not exists plays_user_song on plays(user, song_id)")

    def _db(self) -> sqlite3.Connection:
        c = sqlite3.connect(self.path, timeout=10)
        c.row_factory = sqlite3.Row
        return c

    def record(self, user: str, song: dict, ts: int | None = None) -> None:
        ts = int(ts or time.time() * 1000)
        user = user.strip().lower()
        with _lock, self._db() as c:
            # Doppelte Meldung desselben Titels innerhalb von 30 s ignorieren
            last = c.execute("select ts from plays where user=? and song_id=? order by ts desc limit 1", (user, song["id"])).fetchone()
            if last and abs(ts - last["ts"]) < 30_000:
                return
            c.execute(
                "insert into plays values (?,?,?,?,?,?,?,?,?,?)",
                (user, song["id"], song.get("title"), song.get("artist"), song.get("album"), song.get("albumId"),
                 song.get("coverArt"), song.get("genre"), int(song.get("duration") or 0), ts),
            )

    def counts(self, user: str) -> dict[str, int]:
        with self._db() as c:
            return {r["song_id"]: r["n"] for r in c.execute(
                "select song_id, count(*) n from plays where user=? group by song_id", (user.strip().lower(),))}

    def summary(self, user: str, recent: int = 20) -> dict:
        u = user.strip().lower()
        with self._db() as c:
            albums = [dict(r) for r in c.execute(
                """select album_id as id, album as name, artist, cover_art as coverArt, max(ts) as lastPlayed, count(*) as plays
                   from plays where user=? and album_id is not null group by album_id order by lastPlayed desc limit ?""", (u, recent))]
            artists = [dict(r) for r in c.execute(
                "select artist as name, count(*) as plays from plays where user=? and artist is not null group by artist order by plays desc limit 20", (u,))]
            total = c.execute("select count(*) from plays where user=?", (u,)).fetchone()[0]
        return {"counts": self.counts(user), "recentAlbums": albums, "topArtists": artists, "total": total}
