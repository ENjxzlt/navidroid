import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import history  # noqa: E402


class HistoryTest(unittest.TestCase):
    def test_record_and_summary(self):
        with tempfile.TemporaryDirectory() as d:
            h = history.History(Path(d) / "h.db")
            song = {"id": "s1", "title": "Ivy", "artist": "Frank Ocean", "album": "Blonde", "albumId": "a1"}
            h.record("Admin", song, 1_000_000)
            h.record("admin", song, 1_010_000)  # doppelt innerhalb 30 s → ignoriert
            h.record("admin", song, 2_000_000)
            h.record("admin", {"id": "s2", "title": "Nikes", "artist": "Frank Ocean", "album": "Blonde", "albumId": "a1"}, 3_000_000)
            h.record("other", song, 4_000_000)
            s = h.summary("admin")
            self.assertEqual(s["counts"], {"s1": 2, "s2": 1})
            self.assertEqual(s["recentAlbums"][0]["id"], "a1")
            self.assertEqual(s["recentAlbums"][0]["plays"], 3)
            self.assertEqual(s["topArtists"][0], {"name": "Frank Ocean", "plays": 3})
            self.assertEqual(s["total"], 3)


if __name__ == "__main__":
    unittest.main()
