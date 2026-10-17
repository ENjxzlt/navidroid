import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from matching import clean_title, is_youtube_url, match, normalize, safe_name  # noqa: E402


class MatchingTest(unittest.TestCase):
    def test_normalize_strips_noise_and_artist(self):
        self.assertEqual(normalize("Frank Ocean - Ivy (Official Audio)", "Frank Ocean"), "ivy")
        self.assertEqual(normalize("Pink + White [HD]", "Frank Ocean"), "pink white")
        self.assertEqual(normalize("Solo (feat. Someone)"), "solo")

    def test_match_by_title_ignores_order_and_extras(self):
        tracks = [
            {"id": 1, "number": 1, "title": "Nikes", "seconds": 314},
            {"id": 2, "number": 2, "title": "Ivy", "seconds": 249},
            {"id": 3, "number": 3, "title": "Pink + White", "seconds": 184},
        ]
        videos = [
            {"id": "a", "index": 1, "title": "Album Trailer", "seconds": 60},
            {"id": "b", "index": 2, "title": "Frank Ocean - Pink + White (Audio)", "seconds": 185},
            {"id": "c", "index": 3, "title": "Frank Ocean - Nikes", "seconds": 315},
            {"id": "d", "index": 4, "title": "Ivy [Official Audio]", "seconds": 250},
        ]
        rows, unmatched = match(tracks, videos, "Frank Ocean")
        self.assertEqual([r["videoId"] for r in rows], ["c", "d", "b"])
        self.assertEqual([v["id"] for v in unmatched], ["a"])

    def test_positional_fallback_for_meaningless_titles(self):
        tracks = [{"id": i, "number": i, "title": t, "seconds": None} for i, t in enumerate(["Alpha", "Beta"], 1)]
        videos = [{"id": f"v{i}", "index": i, "title": f"Track {i}", "seconds": None} for i in (1, 2)]
        rows, unmatched = match(tracks, videos, "X")
        self.assertEqual([r["videoId"] for r in rows], ["v1", "v2"])
        self.assertEqual(unmatched, [])

    def test_unmatched_track_stays_empty(self):
        tracks = [{"id": 1, "number": 1, "title": "Nikes", "seconds": None}, {"id": 2, "number": 2, "title": "Seigfried", "seconds": None}]
        videos = [{"id": "a", "index": 1, "title": "Nikes", "seconds": None}]
        rows, _ = match(tracks, videos, "Frank Ocean")
        self.assertEqual(rows[0]["videoId"], "a")
        self.assertIsNone(rows[1]["videoId"])

    def test_url_whitelist_and_safe_names(self):
        self.assertTrue(is_youtube_url("https://www.youtube.com/playlist?list=PL1"))
        self.assertTrue(is_youtube_url("https://youtu.be/abc"))
        self.assertFalse(is_youtube_url("https://evil.example/youtube.com"))
        self.assertFalse(is_youtube_url("file:///etc/passwd"))
        self.assertEqual(safe_name('AC/DC: "Back"?'), "AC_DC_ _Back__")


class CleanTitleTest(unittest.TestCase):
    def test_clean_title_keeps_case_and_removes_noise(self):
        self.assertEqual(clean_title("Kraftklub - Songs für Liam (Official Video)", "Kraftklub"), "Songs für Liam")
        self.assertEqual(clean_title("Ivy [HD]", "Frank Ocean"), "Ivy")
        self.assertEqual(clean_title("Nikes | Frank Ocean Topic", "Frank Ocean"), "Nikes")
        self.assertEqual(clean_title("Solo (feat. X)", "Y"), "Solo (feat. X)")


if __name__ == "__main__":
    unittest.main()
