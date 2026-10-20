import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import audiobooks as ab  # noqa: E402
from matching import safe_name  # noqa: E402


def fake(url, opts):
    assert url.endswith("UCddf/releases"), url
    return {"entries": [
        {"id": "OLAK232", "title": "Folge 232: und der Live-Auftritt"},
        {"id": "OLAK231", "title": "Folge 231: und die Tour des Schreckens"},
        {"id": "OLAK230", "title": "Folge 230: und der Fluch des Drachen"},
        {"id": "OLAKX", "title": "Adventskalender 2023"},
    ]}


class EpisodeNumber(unittest.TestCase):
    def test_numbers(self):
        self.assertEqual(ab.episode_number("Folge 230: und der Fluch"), 230)
        self.assertEqual(ab.episode_number("012/und der Karpatenhund"), 12)
        self.assertEqual(ab.episode_number("Nr. 7 – Der Super-Papagei"), 7)
        self.assertIsNone(ab.episode_number("Adventskalender 2023"))
        self.assertIsNone(ab.episode_number("und der Super-Papagei"))
        # Ordnername nach safe_name („:“ → „_“)
        self.assertEqual(ab.episode_number("Folge 12_ und der Karpatenhund"), 12)


class Releases(unittest.TestCase):
    def test_no_music_filter(self):
        titles = [i["title"] for i in ab.releases("UCddf", fake)]
        # „Live“ und „Tour“ würden bei Musik herausgefiltert
        self.assertIn("Folge 232: und der Live-Auftritt", titles)
        self.assertIn("Folge 231: und die Tour des Schreckens", titles)


class Due(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_baseline_skips_back_catalog(self):
        items = ab.releases("UCddf", fake)
        s = ab.new_series("Die drei ???", {"id": "UCddf", "name": "Die drei ???"}, items, False, "s1", 0)
        self.assertEqual(ab.due(s, items, set(), set()), [])
        new = [{"key": "yt:OLAK233", "title": "Folge 233: und das neue Rätsel", "order": 0}] + items
        self.assertEqual([i["key"] for i in ab.due(s, new, set(), set())], ["yt:OLAK233"])

    def test_present_by_number_or_title_oldest_first(self):
        items = ab.releases("UCddf", fake)
        s = ab.new_series("Die drei ???", {"id": "UCddf"}, items, True, "s1", 0)
        series_dir = self.root / safe_name("Die drei ???")
        (series_dir / "230 - und der Fluch des Drachen (2024)").mkdir(parents=True)
        (series_dir / "Adventskalender 2023").mkdir()
        titles, numbers = ab.present(self.root, "Die drei ???", safe_name)
        self.assertEqual(numbers, {230})
        due = ab.due(s, items, titles, numbers)
        self.assertEqual([i["key"] for i in due], ["yt:OLAK231", "yt:OLAK232"])

    def test_imported_not_again(self):
        items = ab.releases("UCddf", fake)
        s = ab.new_series("Die drei ???", {"id": "UCddf"}, items, True, "s1", 0)
        s["imported"] = ["yt:OLAK231", "yt:OLAK232", "yt:OLAKX"]
        self.assertEqual([i["key"] for i in ab.due(s, items, set(), set())], ["yt:OLAK230"])

    def test_missing_folder(self):
        self.assertEqual(ab.present(self.root, "Gibt es nicht", safe_name), (set(), set()))


if __name__ == "__main__":
    unittest.main()
