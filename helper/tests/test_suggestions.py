import datetime as dt
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import suggestions  # noqa: E402

ARTISTS = [
    {"id": 1, "artistName": "Clairo", "statistics": {"trackFileCount": 20}},
    {"id": 2, "artistName": "Tame Impala", "statistics": {"trackFileCount": 30}},
    {"id": 3, "artistName": "Nur angefragt", "statistics": {"trackFileCount": 0}},
]
ALBUMS = [
    {"artistId": 1, "title": "Charm", "albumType": "Album", "secondaryTypes": [], "releaseDate": "2024-07-12T00:00:00Z", "foreignAlbumId": "c", "statistics": {"trackFileCount": 0}},
    {"artistId": 1, "title": "Immunity", "albumType": "Album", "secondaryTypes": [], "releaseDate": "2019-08-02T00:00:00Z", "foreignAlbumId": "i", "statistics": {"trackFileCount": 11}},
    {"artistId": 2, "title": "Lonerism", "albumType": "Album", "secondaryTypes": [], "releaseDate": "2012-10-05T00:00:00Z", "foreignAlbumId": "l", "statistics": {"trackFileCount": 0}},
    {"artistId": 2, "title": "Live Versions", "albumType": "Album", "secondaryTypes": ["Live"], "releaseDate": "2014-01-01", "foreignAlbumId": "lv", "statistics": {}},
    {"artistId": 2, "title": "Currents (Deluxe Edition)", "albumType": "Album", "secondaryTypes": [], "releaseDate": "2016-01-01", "foreignAlbumId": "cd", "statistics": {}},
    {"artistId": 2, "title": "Zukunft", "albumType": "Album", "secondaryTypes": [], "releaseDate": "2030-01-01", "foreignAlbumId": "z", "statistics": {}},
    {"artistId": 3, "title": "Irgendwas", "albumType": "Album", "secondaryTypes": [], "releaseDate": "2020-01-01", "foreignAlbumId": "x", "statistics": {}},
    {"artistId": 2, "title": "Single", "albumType": "Single", "secondaryTypes": [], "releaseDate": "2020-01-01", "foreignAlbumId": "s", "statistics": {}},
]


def fake_deezer(path, **params):
    if path == "search/artist":
        return {"data": [{"id": 10 if params["q"] == "Clairo" else 20, "name": params["q"]}]}
    if path == "artist/10/related":
        return {"data": [{"id": 100, "name": "Lorde", "nb_fan": 5}, {"id": 200, "name": "Tame Impala"}]}
    if path == "artist/20/related":
        return {"data": [{"id": 100, "name": "Lorde", "nb_fan": 5}, {"id": 300, "name": "MGMT", "nb_fan": 9}]}
    if path == "artist/100/albums":
        return {"data": [{"id": 1, "title": "Melodrama", "record_type": "album", "fans": 3}, {"id": 2, "title": "Pure Heroine", "record_type": "album", "fans": 9}, {"id": 9, "title": "Royals", "record_type": "single"}]}
    if path == "artist/300/albums":
        return {"data": [{"id": 3, "title": "Oracular Spectacular (Remastered)", "record_type": "album"}, {"id": 4, "title": "Congratulations", "record_type": "album"}, {"id": 5, "title": "Best of", "record_type": "compile"}]}
    if path == "artist/100/top":
        return {"data": [{"album": {"id": 1, "title": "Melodrama", "cover_xl": "m.jpg"}}, {"album": {"id": 1, "title": "Melodrama"}}, {"album": {"id": 2, "title": "Pure Heroine"}}]}
    if path == "artist/300/top":
        return {"data": [{"album": {"id": 3, "title": "Oracular Spectacular (Remastered)"}}, {"album": {"id": 4, "title": "Congratulations"}}]}
    raise RuntimeError(path)


class SuggestionsTest(unittest.TestCase):
    def test_build(self):
        res = suggestions.build(ARTISTS, ALBUMS, {"dz:4"}, dt.date(2025, 6, 1), get=fake_deezer)
        self.assertEqual([i["title"] for i in res["newReleases"]], ["Charm"])
        titles = [i["title"] for i in res["forYou"]]
        # Lorde von zwei Künstlern empfohlen → zuerst; MGMT-Album ausgeblendet; Tame Impala ist schon da
        self.assertEqual(titles[0], "Melodrama")
        self.assertEqual(res["forYou"][0]["reason"], "Weil du Tame Impala hörst")
        self.assertIn("Lonerism", titles)
        self.assertNotIn("Congratulations", titles)
        for skip in ("Live Versions", "Currents (Deluxe Edition)", "Zukunft", "Irgendwas", "Single", "Immunity"):
            self.assertNotIn(skip, titles + [i["title"] for i in res["newReleases"]])


if __name__ == "__main__":
    unittest.main()
