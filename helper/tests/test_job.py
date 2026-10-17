"""Spielt einen kompletten Import durch – YouTube und Lidarr sind dabei nachgebaut."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TMP = tempfile.mkdtemp()
os.environ.update({
    "HELPER_TOKEN": "t", "LIDARR_URL": "http://lidarr", "LIDARR_API_KEY": "k",
    "STAGING_DIR": f"{TMP}/staging", "LIDARR_STAGING_DIR": "/lidarr-sees/staging",
    "VIDEO_DIR": f"{TMP}/videos", "DATA_DIR": f"{TMP}/data",
})
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import app  # noqa: E402
from mutagen.id3 import ID3  # noqa: E402

ALBUM = {
    "id": 7, "title": "Test Album", "artistId": 3, "foreignAlbumId": "rg-1", "releaseDate": "2016-08-20T00:00:00Z",
    "artist": {"artistName": "Test Artist", "foreignArtistId": "ar-1", "path": "/music/Test Artist"},
    "releases": [{"id": 70, "monitored": True, "foreignReleaseId": "rel-1"}], "images": [],
}
TRACKS = [
    {"id": 101, "title": "First Song", "trackNumber": "1", "absoluteTrackNumber": 1, "mediumNumber": 1, "foreignTrackId": "t-1", "duration": 2000},
    {"id": 102, "title": "Second Song", "trackNumber": "2", "absoluteTrackNumber": 2, "mediumNumber": 1, "foreignTrackId": "t-2", "duration": 2000},
]


class FakeYDL:
    def __init__(self, opts):
        self.opts = opts

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def download(self, urls):
        out = Path(self.opts["outtmpl"].replace("%(ext)s", "mp3"))
        subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "sine=duration=1", "-y", str(out)], check=True)


class JobTest(unittest.TestCase):
    def test_full_import(self):
        posted = []

        def fake_lidarr(method, path, **kw):
            if path == "album/7":
                return ALBUM
            if path == "track":
                return [dict(t) for t in TRACKS]
            if path == "manualimport":
                folder = kw["params"]["folder"]
                self.assertEqual(folder, f"/lidarr-sees/staging/{job['id']}")
                local = app.STAGING / job["id"]
                return [{"path": f"{folder}/{p.name}", "quality": {"quality": {"id": 1}}} for p in sorted(local.iterdir())]
            if path == "command" and method == "POST":
                posted.append(kw["json"])
                return {"id": 55}
            if path == "command/55":
                return {"status": "completed"}
            raise AssertionError(f"unerwarteter Aufruf {method} {path}")

        job = {
            "id": "job1", "albumId": 7, "artist": "Test Artist", "videos": False,
            "mapping": [{"trackId": 101, "videoId": "aaa"}, {"trackId": 102, "videoId": "bbb"}],
            "steps": [{"key": k, "label": k, "done": 0, "total": 2, "state": "todo"} for k in ("download", "convert", "tag", "import")],
            "status": "running", "createdAt": 0,
        }
        app._jobs[job["id"]] = job
        captured = {}
        real_rmtree = app.shutil.rmtree

        def keep_files(path, **kw):
            # Vor dem Aufräumen die Tags prüfen
            for f in sorted(Path(path).iterdir()):
                captured[f.name] = ID3(f)
            real_rmtree(path, **kw)

        with mock.patch.object(app, "YoutubeDL", FakeYDL), mock.patch.object(app, "lidarr", fake_lidarr), \
                mock.patch.object(app.shutil, "rmtree", keep_files):
            app.run_job(job)

        self.assertEqual([s["state"] for s in job["steps"]], ["done"] * 4)
        self.assertEqual(sorted(captured), ["01 - First Song.mp3", "02 - Second Song.mp3"])
        tags = captured["01 - First Song.mp3"]
        self.assertEqual(str(tags["TIT2"]), "First Song")
        self.assertEqual(str(tags["TPE1"]), "Test Artist")
        self.assertEqual(str(tags["TALB"]), "Test Album")
        self.assertEqual(str(tags["TRCK"]), "1/2")
        self.assertEqual(str(tags["TDRC"]), "2016")
        self.assertEqual(str(tags["TXXX:MusicBrainz Album Id"]), "rel-1")
        self.assertEqual(str(tags["TXXX:MusicBrainz Release Track Id"]), "t-1")

        cmd = posted[0]
        self.assertEqual(cmd["name"], "ManualImport")
        self.assertEqual(cmd["importMode"], "move")
        files = sorted(cmd["files"], key=lambda f: f["path"])
        self.assertEqual([f["trackIds"] for f in files], [[101], [102]])
        self.assertTrue(all(f["albumId"] == 7 and f["artistId"] == 3 and f["albumReleaseId"] == 70 for f in files))
        self.assertFalse((app.STAGING / "job1").exists())


if __name__ == "__main__":
    unittest.main()
