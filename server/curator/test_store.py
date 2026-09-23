"""Tests for the SQLite store.

Run with:  python3 -m unittest discover -s server/curator -p 'test_*.py'

The thread test is the important one: a run executes in a worker thread while the web UI keeps
serving status requests from the event loop thread, and SQLite refuses that by default. Without
the guard the first "Run now" fails with a bare Internal Server Error.
"""

from __future__ import annotations

import os
import tempfile
import threading
import unittest
from datetime import date

from curator import Candidate, Store


class StoreTest(unittest.TestCase):

    def setUp(self) -> None:
        handle, self.path = tempfile.mkstemp(suffix=".db")
        os.close(handle)
        self.store = Store(self.path)

    def tearDown(self) -> None:
        self.store.close()
        for suffix in ("", "-wal", "-shm"):
            try:
                os.unlink(self.path + suffix)
            except FileNotFoundError:
                pass

    def test_records_and_reads_back_used_photos(self) -> None:
        candidates = [
            Candidate(item_id=1, filename="a.jpg", taken_on=date(2026, 9, 1)),
            Candidate(item_id=2, filename="b.jpg", taken_on=date(2026, 9, 2)),
        ]

        self.store.mark_used(candidates, date(2026, 9, 23))

        self.assertEqual({1, 2}, self.store.used_ids())
        self.assertEqual(2, self.store.used_count())

    def test_is_usable_from_another_thread(self) -> None:
        # This is what "Run now" does: the endpoint hands the work to a worker thread while the
        # store was created on the event loop thread.
        errors: list[BaseException] = []

        def worker() -> None:
            try:
                run_id = self.store.start_run()
                self.store.mark_used(
                    [Candidate(item_id=7, filename="x.jpg", taken_on=date(2026, 9, 3))],
                    date(2026, 9, 23),
                )
                self.store.finish_run(run_id, "ok", 1, 0, 90)
            except BaseException as error:  # noqa: BLE001 - surfaced below
                errors.append(error)

        thread = threading.Thread(target=worker)
        thread.start()
        thread.join()

        self.assertEqual([], errors)
        self.assertEqual({7}, self.store.used_ids())
        self.assertEqual("ok", self.store.last_runs(1)[0]["status"])

    def test_forget_all_clears_the_exclusion_list(self) -> None:
        self.store.mark_used(
            [Candidate(item_id=1, filename="a.jpg", taken_on=date(2026, 9, 1))],
            date(2026, 9, 23),
        )

        removed = self.store.forget_all()

        self.assertEqual(1, removed)
        self.assertEqual(set(), self.store.used_ids())

    def test_run_history_is_newest_first(self) -> None:
        first = self.store.start_run()
        self.store.finish_run(first, "ok", 90, 80, 90)
        second = self.store.start_run()
        self.store.finish_run(second, "error", 0, 0, 0, "boom")

        runs = self.store.last_runs(10)

        self.assertEqual(second, runs[0]["id"])
        self.assertEqual("error", runs[0]["status"])
        self.assertEqual("boom", runs[0]["message"])
        self.assertEqual(first, runs[1]["id"])
        self.assertEqual("ok", runs[1]["status"])

    def test_last_success_skips_failed_runs(self) -> None:
        failed = self.store.start_run()
        self.store.finish_run(failed, "error", 0, 0, 0, "boom")
        good = self.store.start_run()
        self.store.finish_run(good, "ok", 90, 5, 90)

        last = self.store.last_success()

        self.assertIsNotNone(last)
        self.assertEqual(good, last["id"])

    def test_marking_the_same_photo_twice_keeps_one_row(self) -> None:
        candidate = Candidate(item_id=5, filename="a.jpg", taken_on=date(2026, 9, 1))

        self.store.mark_used([candidate], date(2026, 9, 23))
        self.store.mark_used([candidate], date(2026, 9, 24))

        self.assertEqual(1, self.store.used_count())


if __name__ == "__main__":
    unittest.main()
