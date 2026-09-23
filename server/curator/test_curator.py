"""Tests for the daily selection.

Run with:  python3 -m unittest discover -s server/curator -p 'test_*.py'

The selection rules are the whole point of the service, and the awkward cases - a day with no
photos, a day whose photos were all used, a library too small to fill the album - are exactly
the ones that are hard to reproduce by hand against a real NAS.
"""

from __future__ import annotations

import random
import unittest
from datetime import date, timedelta

from curator import Candidate, cutoff_for, day_span, select_daily


def candidate(item_id: int, day: date) -> Candidate:
    return Candidate(item_id=item_id, filename=f"IMG_{item_id}.jpg", taken_on=day)


class SelectDailyTest(unittest.TestCase):

    def setUp(self) -> None:
        self.today = date(2026, 9, 23)
        self.rng = random.Random(1234)

    def days_back(self, count: int) -> list[date]:
        """The last ``count`` days, newest first."""
        return [self.today - timedelta(days=offset) for offset in range(count)]

    def test_picks_one_photo_per_day(self) -> None:
        # Two photos on each of 90 days; one must be chosen from every day.
        candidates = [
            candidate(day_index * 10 + n, day)
            for day_index, day in enumerate(self.days_back(90))
            for n in range(2)
        ]

        result = select_daily(candidates, used=set(), days=90, today=self.today, rng=self.rng)

        self.assertEqual(90, len(result.chosen))
        chosen_days = {c.taken_on for c in result.chosen}
        self.assertEqual(set(self.days_back(90)), chosen_days)
        # No photo may be picked twice.
        self.assertEqual(90, len({c.item_id for c in result.chosen}))

    def test_never_reuses_a_photo_from_an_earlier_run(self) -> None:
        candidates = [candidate(n, self.today) for n in range(1, 6)]
        used = {1, 2, 3}

        result = select_daily(candidates, used=used, days=1, today=self.today, rng=self.rng)

        self.assertEqual(1, len(result.chosen))
        self.assertIn(result.chosen[0].item_id, {4, 5})

    def test_walks_backwards_past_a_day_without_photos(self) -> None:
        # Today has nothing; the photo must come from an older day.
        candidates = [candidate(1, self.today - timedelta(days=3))]

        result = select_daily(candidates, used=set(), days=1, today=self.today, rng=self.rng)

        self.assertEqual(1, len(result.chosen))
        self.assertEqual(self.today - timedelta(days=3), result.chosen[0].taken_on)

    def test_walks_backwards_when_a_day_was_fully_used(self) -> None:
        # Every photo from the two newest days has been used before, so the third day is next.
        candidates = [
            candidate(1, self.today),
            candidate(2, self.today - timedelta(days=1)),
            candidate(3, self.today - timedelta(days=2)),
        ]

        result = select_daily(candidates, used={1, 2}, days=1, today=self.today, rng=self.rng)

        self.assertEqual([3], [c.item_id for c in result.chosen])

    def test_fills_the_album_when_some_days_are_empty(self) -> None:
        # Only 60 days hold photos, so 60 is the most that can be chosen from this window.
        candidates = [
            candidate(n, day)
            for n, day in enumerate(self.days_back(60))
        ]

        result = select_daily(candidates, used=set(), days=90, today=self.today, rng=self.rng)

        self.assertEqual(60, len(result.chosen))
        self.assertEqual(60, result.days_available)

    def test_reaches_beyond_the_window_when_asked(self) -> None:
        # Candidates older than the window are still reachable: the caller widens the fetch,
        # this only proves older days are not filtered out here.
        candidates = [
            candidate(1, self.today - timedelta(days=200)),
            candidate(2, self.today - timedelta(days=201)),
        ]

        result = select_daily(candidates, used=set(), days=2, today=self.today, rng=self.rng)

        self.assertEqual(2, len(result.chosen))

    def test_one_day_contributes_at_most_one_photo(self) -> None:
        # A single busy day cannot fill several slots: the rule is one photo per day, so the
        # curator walks further back for the rest rather than taking a second photo from it.
        candidates = [candidate(1, self.today), candidate(2, self.today)]

        result = select_daily(candidates, used=set(), days=2, today=self.today, rng=self.rng)

        self.assertEqual(1, len(result.chosen))
        self.assertIn(result.chosen[0].item_id, {1, 2})

    def test_returns_nothing_when_every_photo_was_used(self) -> None:
        candidates = [candidate(1, self.today), candidate(2, self.today)]

        result = select_daily(candidates, used={1, 2}, days=5, today=self.today, rng=self.rng)

        self.assertEqual([], result.chosen)

    def test_is_deterministic_for_a_given_seed(self) -> None:
        candidates = [
            candidate(day_index * 10 + n, day)
            for day_index, day in enumerate(self.days_back(30))
            for n in range(5)
        ]

        first = select_daily(candidates, set(), 30, self.today, random.Random(7))
        second = select_daily(candidates, set(), 30, self.today, random.Random(7))

        self.assertEqual([c.item_id for c in first.chosen], [c.item_id for c in second.chosen])


class WindowTest(unittest.TestCase):

    def test_cutoff_covers_exactly_the_requested_days(self) -> None:
        today = date(2026, 9, 23)

        # A 90 day window ends today and starts 89 days earlier, so today is included.
        self.assertEqual(date(2026, 6, 26), cutoff_for(90, today))
        self.assertEqual(today, cutoff_for(1, today))

    def test_day_span_uses_local_time(self) -> None:
        # 2026-09-23 12:00 UTC, which is the same calendar day in any timezone near Europe.
        timestamp = 1790164800

        self.assertEqual(date(2026, 9, 23), day_span(timestamp))


if __name__ == "__main__":
    unittest.main()
