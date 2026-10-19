import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import videos  # noqa: E402

HITS = [
    {"id": "lyr", "title": "Wiener Schickeria (Lyrics)", "channel": "LyricsWorld", "duration": 200},
    {"id": "top", "title": "Wiener Schickeria", "channel": "Bibiza - Topic", "duration": 198},
    {"id": "off", "title": "BIBIZA - Wiener Schickeria (Official Video)", "channel": "BIBIZA", "channel_is_verified": True, "duration": 215},
    {"id": "liv", "title": "BIBIZA - Wiener Schickeria (Live at Donauinselfest)", "channel": "BIBIZA", "duration": 260},
    {"id": "oth", "title": "Ganz anderes Lied (Official Video)", "channel": "BIBIZA", "duration": 200},
]


class Rank(unittest.TestCase):
    def test_official_video_wins(self):
        r = videos.rank(HITS, "Bibiza", "Wiener Schickeria", 198)
        self.assertEqual(r[0]["videoId"], "off")
        self.assertTrue(r[0]["auto"])
        ids = [c["videoId"] for c in r]
        self.assertNotIn("top", ids)  # Topic-Kanal = nur Ton
        self.assertNotIn("oth", ids)  # anderer Titel

    def test_best_none_when_unsure(self):
        self.assertIsNone(videos.best([HITS[0], HITS[3]], "Bibiza", "Wiener Schickeria", 198))

    def test_live_ok_when_title_says_so(self):
        hit = {"id": "lf", "title": "Oasis - Live Forever (Official Video)", "channel": "Oasis", "duration": 280}
        self.assertEqual(videos.best([hit], "Oasis", "Live Forever", 276)["videoId"], "lf")

    def test_vevo_channel(self):
        hit = {"id": "v", "title": "Taylor Swift - Anti-Hero (Official Music Video)", "channel": "TaylorSwiftVEVO", "duration": 230}
        self.assertTrue(videos.best([hit], "Taylor Swift", "Anti-Hero", 200))

    def test_wrong_length(self):
        hit = {"id": "x", "title": "Song (Official Video)", "channel": "Band", "duration": 900}
        self.assertLess(videos.score(hit, "Band", "Song", 200), videos.AUTO_MIN)


if __name__ == "__main__":
    unittest.main()
