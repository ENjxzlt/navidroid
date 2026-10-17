import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import ma  # noqa: E402


class MaTest(unittest.TestCase):
    def test_ws_url(self):
        self.assertEqual(ma._ws_url("http://192.168.178.199:8095/"), "ws://192.168.178.199:8095/ws")
        self.assertEqual(ma._ws_url("https://ma.example"), "wss://ma.example/ws")

    def test_stream_url_contains_subsonic_auth(self):
        u = ma.stream_url({"url": "https://nav.example/", "user": "u", "token": "t", "salt": "s"}, "abc")
        self.assertTrue(u.startswith("https://nav.example/rest/stream?"))
        self.assertIn("id=abc", u)
        self.assertIn("t=t", u)

    def test_players_filters_unavailable_and_groups(self):
        raw = [
            {"player_id": "echo", "display_name": "Küche", "available": True, "type": "player", "volume_level": 30},
            {"player_id": "off", "display_name": "Weg", "available": False},
            {"player_id": "grp", "display_name": "Alle", "available": True, "type": "group"},
        ]
        with mock.patch.object(ma, "call", return_value=[raw]):
            ps = ma.players("http://x", "t")
        self.assertEqual([p["name"] for p in ps], ["Küche"])
        self.assertEqual(ps[0]["volume"], 30)

    def test_play_sends_urls_to_queue(self):
        with mock.patch.object(ma, "call") as c:
            ma.play("http://x", "t", "echo", {"url": "http://n", "user": "u", "token": "t", "salt": "s"}, ["1", "2"])
        cmd, args = c.call_args.args[2]
        self.assertEqual(cmd, "player_queues/play_media")
        self.assertEqual(args["queue_id"], "echo")
        self.assertEqual(len(args["media"]), 2)


if __name__ == "__main__":
    unittest.main()
