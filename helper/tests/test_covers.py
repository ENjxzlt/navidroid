import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import covers  # noqa: E402


def fake(url, params):
    if "deezer" in url:
        return {"data": [{"title": "Blonde", "artist": {"name": "Frank Ocean"}, "cover_xl": "https://e-cdns-images.dzcdn.net/b.jpg"},
                         {"title": "Blond Remix", "artist": {"name": "Someone"}, "cover_xl": "https://e-cdns-images.dzcdn.net/r.jpg"}]}
    if "itunes" in url:
        return {"results": [{"collectionName": "Blonde", "artistName": "Frank Ocean", "artworkUrl100": "https://is1.mzstatic.com/x/100x100bb.jpg"}]}
    if "musicbrainz" in url:
        return {"release-groups": [{"id": "rg1", "title": "Blonde", "score": 100, "artist-credit": [{"name": "Frank Ocean"}]},
                                   {"id": "rg2", "title": "Other", "score": 40}]}
    raise RuntimeError(url)


class CoversTest(unittest.TestCase):
    def test_search(self):
        res = covers.search("Frank Ocean", "Blonde", get=fake)
        urls = [r["url"] for r in res]
        self.assertEqual(len(res), 3)
        self.assertIn("https://is1.mzstatic.com/x/1400x1400bb.jpg", urls)
        self.assertIn("https://coverartarchive.org/release-group/rg1/front-1200", urls)
        self.assertNotIn("Blond Remix", [r["title"] for r in res])  # anderes Album fällt raus

    def test_download_only_known_hosts(self):
        for bad in ("http://192.168.178.1/x.jpg", "file:///etc/passwd", "https://evil.example/x.jpg"):
            with self.assertRaises(ValueError):
                covers.download(bad)


if __name__ == "__main__":
    unittest.main()


class ExactDeezerTest(unittest.TestCase):
    def test_only_exact(self):
        self.assertEqual(covers.exact_deezer("Frank Ocean", "Blonde", get=fake)["title"], "Blonde")
        self.assertIsNone(covers.exact_deezer("Frank Ocean", "Endless", get=fake))
