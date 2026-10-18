import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import youtube_releases as ytr  # noqa: E402


def fake(url, opts):
    if url.startswith("ytsearch"):
        q = url.split(":", 1)[1]
        if q == "Bibiza":
            return {"entries": [
                {"channel": "Fanseite Bibiza", "channel_id": "UCfan", "channel_is_verified": False},
                {"channel": "BIBIZA", "channel_id": "UCbib", "channel_is_verified": True},
            ]}
        if q == "PA69":
            return {"entries": [{"channel": "PA69 - Topic", "channel_id": "UCpa", "channel_is_verified": False}]}
        return {"entries": [{"channel": "Irgendwer", "channel_id": "UCx"}]}
    if url.endswith("UCbib/releases"):
        return {"entries": [
            {"id": "OLAK1", "title": "Rocknrollst★r", "thumbnails": [{"url": "a.jpg"}]},
            {"id": "OLAK2", "title": "Wiener Schickeria"},
            {"id": "OLAK3", "title": "Morgen hör ich auf! (Club Mixtape)"},
            {"id": "OLAK4", "title": "Galopp! (feat. Ikkimel)"},
            {"id": "OLAK6", "title": "Wiener Schickeria (Live)"},
            {"id": "OLAK7", "title": "Bis Dato (Remixes)"},
        ]}
    if "playlist?list=" in url:
        return {"entries": [{"id": "vid" + url.rsplit("=", 1)[1]}]}
    if url.endswith("UCpa/releases"):
        return {"entries": [{"id": "OLAK9", "title": "Bushalte-Boogie"}, {"id": "OLAK5", "title": "Rocknrollst★r"},
                            {"id": "OLAK10", "title": "Bushalte-Boogie (Clean)"}]}
    raise RuntimeError(url)


LIB = [
    {"album": "Wiener Schickeria", "albumartist": "Bibiza", "artist": "Bibiza", "title": "Wiener Schickeria"},
    {"album": "Bis Dato", "albumartist": "Bibiza", "artist": "Bibiza", "title": "Galopp!"},
    {"album": "XXL", "albumartist": "PA69", "artist": "PA69", "title": "XXL"},
]


class YouTubeReleasesTest(unittest.TestCase):
    def test_channel_prefers_verified_name_match(self):
        self.assertEqual(ytr.find_channel("Bibiza", fake)["id"], "UCbib")
        self.assertEqual(ytr.find_channel("PA69", fake)["id"], "UCpa")  # Topic-Kanal als Ersatz
        self.assertIsNone(ytr.find_channel("Unbekannt", fake))

    def test_scan_skips_what_is_in_library(self):
        found, channels = ytr.scan(["Bibiza", "PA69", "Unbekannt"], LIB, {}, fake)
        self.assertEqual([i["title"] for i in found["Bibiza"]], ["Rocknrollst★r", "Morgen hör ich auf! (Club Mixtape)"])
        self.assertEqual(found["Bibiza"][0]["key"], "yt:OLAK1")
        self.assertEqual(found["Bibiza"][0]["cover"], "a.jpg")
        self.assertEqual(found["Bibiza"][1]["cover"], "https://i.ytimg.com/vi/vidOLAK3/maxresdefault.jpg")
        self.assertEqual([i["title"] for i in found["PA69"]], ["Bushalte-Boogie", "Rocknrollst★r"])
        self.assertEqual(found["PA69"][0]["alsoKeys"], ["yt:OLAK10"])  # Clean-Version zusammengefasst
        self.assertIs(channels["unbekannt"], False)  # gemerkt: kein Kanal, nicht jedes Mal neu suchen
        merged = ytr.interleave(found)
        self.assertEqual([i["title"] for i in merged], ["Rocknrollst★r", "Bushalte-Boogie", "Morgen hör ich auf! (Club Mixtape)"])
        self.assertEqual(merged[0]["artistLine"], "Bibiza & PA69")  # auf beiden Kanälen → eine Karte


if __name__ == "__main__":
    unittest.main()
