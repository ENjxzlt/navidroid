import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import library  # noqa: E402


def make_audio(path: Path, tags: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    meta = []
    for k, v in tags.items():
        meta += ["-metadata", f"{k}={v}"]
    subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono", "-t", "0.5", *meta, str(path)], check=True)


class LibraryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.music = Path(self.tmp.name) / "music"
        self.data = Path(self.tmp.name) / "data"
        self.data.mkdir()
        # Flacher Künstlerordner mit zwei Alben + ein eigener Albumordner
        flat = self.music / "Fleetwood Mac"
        make_audio(flat / "01 - Dreams.mp3", {"title": "Dreams", "album": "Rumours", "album_artist": "Fleetwood Mac", "artist": "Fleetwood Mac", "track": "1"})
        make_audio(flat / "02 - Go Your Own Way.mp3", {"title": "Go Your Own Way", "album": "Rumours", "album_artist": "Fleetwood Mac", "artist": "Fleetwood Mac", "track": "2"})
        make_audio(flat / "01 - Big Love.flac", {"title": "Big Love", "album": "Tango in the Night", "album_artist": "Fleetwood Mac", "artist": "Fleetwood Mac", "track": "1"})
        own = self.music / "Frank Ocen" / "Blonde (2016)"
        make_audio(own / "01 - Nikes.flac", {"title": "Nikes", "album": "Blonde", "artist": "Frank Ocen", "track": "1"})
        make_audio(own / "02 - Ivy.flac", {"title": "Ivy", "album": "Blonde", "artist": "Frank Ocen", "track": "2"})
        (own / "cover.jpg").write_bytes(b"old")
        self.index = library.Index(self.music)

    def tearDown(self):
        self.tmp.cleanup()

    def test_find_album_in_flat_folder(self):
        files = library.album_files(self.index, "Fleetwood Mac", "Rumours")
        self.assertEqual([f["title"] for f in files], ["Dreams", "Go Your Own Way"])
        songs = [{"title": "Go Your Own Way", "track": 2, "disc": 1}, {"title": "Dreams", "track": 1, "disc": 1}]
        m = library.assign(files, songs)
        self.assertEqual(m[0]["title"], "Go Your Own Way")
        self.assertEqual(m[1]["title"], "Dreams")

    def test_edit_tags(self):
        files = library.album_files(self.index, "Frank Ocen", "Blonde")
        for f in files:
            library.write_tags(f["path"], {"albumartist": "Frank Ocean", "artist": "Frank Ocean", "date": "2016"})
        library.write_tags(files[1]["path"], {"title": "Ivy (Edit)", "tracknumber": "3"})
        self.index.forget([f["path"] for f in files])
        files = library.album_files(self.index, "Frank Ocean", "Blonde")
        self.assertEqual(len(files), 2)
        self.assertEqual(files[1]["title"], "Ivy (Edit)")
        self.assertEqual(files[1]["track"], 3)

    def test_cover_only_into_exclusive_folder(self):
        rumours = library.album_files(self.index, "Fleetwood Mac", "Rumours")
        library.set_cover(self.index, rumours, b"\xff\xd8newjpeg", "image/jpeg")
        self.assertFalse((self.music / "Fleetwood Mac" / "cover.jpg").exists())  # Ordner hat noch ein anderes Album
        blonde = library.album_files(self.index, "Frank Ocen", "Blonde")
        library.set_cover(self.index, blonde, b"\xff\xd8newjpeg", "image/jpeg")
        self.assertEqual((self.music / "Frank Ocen" / "Blonde (2016)" / "cover.jpg").read_bytes(), b"\xff\xd8newjpeg")

    def test_trash_delete_and_restore(self):
        trash = library.Trash(self.music, self.data, self.index)
        rumours = library.album_files(self.index, "Fleetwood Mac", "Rumours")
        res = trash.delete("Fleetwood Mac", "Rumours", rumours)
        self.assertEqual(res["tracks"], 2)
        self.assertTrue((self.music / "Fleetwood Mac" / "01 - Big Love.flac").exists())  # anderes Album bleibt
        self.assertEqual(library.album_files(self.index, "Fleetwood Mac", "Rumours"), [])
        blonde = library.album_files(self.index, "Frank Ocen", "Blonde")
        trash.delete("Frank Ocen", "Blonde", blonde)
        self.assertFalse((self.music / "Frank Ocen").exists())  # leerer Ordner samt Cover weg
        self.assertEqual(len(trash.list()), 2)
        self.assertEqual(trash.list()[0]["daysLeft"], 30)
        trash.restore(res["id"])
        self.assertEqual(len(library.album_files(self.index, "Fleetwood Mac", "Rumours")), 2)
        blonde_id = trash.list()[0]["id"]
        trash.restore(blonde_id)
        self.assertTrue((self.music / "Frank Ocen" / "Blonde (2016)" / "cover.jpg").exists())
        self.assertEqual(trash.list(), [])

    def test_purge_old_entries(self):
        trash = library.Trash(self.music, self.data, self.index)
        trash.delete("Fleetwood Mac", "Rumours", library.album_files(self.index, "Fleetwood Mac", "Rumours"))
        self.assertEqual(trash.purge(older_than_days=30), 0)
        self.assertEqual(trash.purge(older_than_days=-1), 1)
        self.assertEqual(trash.list(), [])

    def test_upload_target_and_duplicates(self):
        t = library.upload_target(self.music, "Die Nachbarn", "Demos", 2024, 3, 1, "Kellerprobe", ".MP3")
        self.assertEqual(t, self.music / "Die Nachbarn" / "Demos (2024)" / "03 - Kellerprobe.mp3")
        self.assertEqual(library.upload_target(self.music, "A", "B", None, 4, 2, "T", ".flac", discs=2).name, "204 - T.flac")
        self.assertTrue(library.already_there(self.index, self.music / "x.mp3", "Fleetwood Mac", "Rumours", "Dreams"))
        self.assertFalse(library.already_there(self.index, self.music / "x.mp3", "Fleetwood Mac", "Rumours", "Songbird"))


if __name__ == "__main__":
    unittest.main()
