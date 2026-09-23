"""Tests for the Synology payload parsing.

Run with:  python3 -m unittest discover -s server/curator -p 'test_*.py'

These cover shapes that differ between the personal and the shared space, which is easy to get
wrong and shows up only as a crash on a real NAS.
"""

from __future__ import annotations

import unittest

from syno import _is_video, _parse_items, describe_error


class ParseItemsTest(unittest.TestCase):

    def test_reads_the_shared_space_shape(self) -> None:
        # In the shared space `type` is a string, not a number.
        data = {
            "list": [
                {"id": 11, "type": "photo", "time": 1700000000,
                 "additional": {"thumbnail": {"cache_key": "k1", "original_name": "a.jpg"}}},
                {"id": 12, "type": "video", "time": 1700000001,
                 "additional": {"thumbnail": {"cache_key": "k2", "original_name": "b.mp4"}}},
            ]
        }

        items = _parse_items(data)

        self.assertEqual(2, len(items))
        self.assertEqual(11, items[0].id)
        self.assertEqual("a.jpg", items[0].filename)
        self.assertFalse(items[0].is_video)
        self.assertTrue(items[1].is_video)

    def test_reads_the_personal_space_shape(self) -> None:
        # In the personal space the same field is numeric.
        data = {"list": [{"id": 1, "type": 0, "time": 1}, {"id": 2, "type": 1, "time": 2}]}

        items = _parse_items(data)

        self.assertFalse(items[0].is_video)
        self.assertTrue(items[1].is_video)

    def test_skips_entries_without_an_id(self) -> None:
        data = {"list": [{"type": "photo"}, {"id": 3, "type": "photo", "time": 1}]}

        items = _parse_items(data)

        self.assertEqual([3], [item.id for item in items])

    def test_survives_missing_and_odd_fields(self) -> None:
        data = {"list": [{"id": 4}, {"id": 5, "type": None, "time": None},
                         {"id": 6, "type": "unknown"}]}

        items = _parse_items(data)

        self.assertEqual(3, len(items))
        self.assertEqual("item-4", items[0].filename)
        self.assertEqual(0, items[0].taken_at)
        self.assertFalse(items[1].is_video)
        self.assertFalse(items[2].is_video)

    def test_handles_an_empty_or_absent_list(self) -> None:
        self.assertEqual([], _parse_items({}))
        self.assertEqual([], _parse_items({"list": []}))


class IsVideoTest(unittest.TestCase):

    def test_accepts_both_representations(self) -> None:
        self.assertTrue(_is_video("video"))
        self.assertTrue(_is_video("VIDEO"))
        self.assertTrue(_is_video(1))
        self.assertTrue(_is_video("1"))

    def test_treats_photos_as_photos(self) -> None:
        self.assertFalse(_is_video("photo"))
        self.assertFalse(_is_video(0))
        self.assertFalse(_is_video(None))
        self.assertFalse(_is_video(""))
        # An unexpected value must not raise; the photo is simply treated as a photo.
        self.assertFalse(_is_video("something-else"))


class DescribeErrorTest(unittest.TestCase):

    def test_names_the_codes_a_user_can_act_on(self) -> None:
        self.assertIn("password", describe_error(400))
        self.assertIn("two-factor", describe_error(403))
        self.assertIn("Auto Block", describe_error(407))
        self.assertIn("expired", describe_error(119))

    def test_falls_back_for_unknown_codes(self) -> None:
        self.assertIn("12345", describe_error(12345))


if __name__ == "__main__":
    unittest.main()
