"""Selection logic and the SQLite record of what has already been used.

The rule the curator implements:

* One photo per day for the last 90 days.
* A photo is never used twice across runs, which is what the database is for.
* When a day has no photo left - because it had none, or because everything from it was already
  used - the curator walks further back until it finds one. If that still does not yield 90
  photos, the whole window is widened and the walk continues.

Kept free of HTTP so the selection can be unit tested.
"""

from __future__ import annotations

import logging
import random
import sqlite3
import threading
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Iterable, Optional

log = logging.getLogger("curator.core")


@dataclass
class Candidate:
    """A photo the curator may pick, with the day it was taken."""

    item_id: int
    filename: str
    taken_on: date


@dataclass
class Selection:
    """The outcome of one selection pass."""

    chosen: list[Candidate] = field(default_factory=list)
    window_days: int = 0
    days_available: int = 0
    days_skipped: int = 0


class Store:
    """SQLite record of used photos and past runs.

    The connection is shared across threads on purpose: a run executes in a worker thread while
    the web UI keeps serving status requests. SQLite forbids that by default, so the connection is
    opened with ``check_same_thread=False`` and every access is serialised through a lock.
    """

    def __init__(self, path: str) -> None:
        self._lock = threading.RLock()
        self._connection = sqlite3.connect(path, check_same_thread=False)
        self._connection.row_factory = sqlite3.Row
        # WAL lets a reader (the status page) proceed while a run is writing.
        self._connection.execute("PRAGMA journal_mode=WAL")
        self._create_schema()

    def _create_schema(self) -> None:
        with self._lock:
            self._connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS used_items (
                    item_id   INTEGER PRIMARY KEY,
                    filename  TEXT,
                    taken_at  INTEGER,
                    used_on   TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS used_items_used_on ON used_items (used_on);

                CREATE TABLE IF NOT EXISTS runs (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at  TEXT NOT NULL,
                    finished_at TEXT,
                    status      TEXT NOT NULL,
                    selected    INTEGER NOT NULL DEFAULT 0,
                    removed     INTEGER NOT NULL DEFAULT 0,
                    window_days INTEGER NOT NULL DEFAULT 0,
                    message     TEXT
                );
                """
            )
            self._connection.commit()

    # --- Used photos -----------------------------------------------------------------------

    def used_ids(self) -> set[int]:
        with self._lock:
            rows = self._connection.execute("SELECT item_id FROM used_items").fetchall()
        return {int(row["item_id"]) for row in rows}

    def mark_used(self, candidates: Iterable[Candidate], used_on: date) -> None:
        rows = [
            (c.item_id, c.filename,
             int(datetime.combine(c.taken_on, datetime.min.time()).timestamp()),
             used_on.isoformat())
            for c in candidates
        ]
        with self._lock:
            self._connection.executemany(
                "INSERT OR REPLACE INTO used_items (item_id, filename, taken_at, used_on) "
                "VALUES (?, ?, ?, ?)",
                rows,
            )
            self._connection.commit()

    def forget_all(self) -> int:
        """Clears the used-photo history, letting every photo be picked again."""
        with self._lock:
            removed = self._connection.execute(
                "SELECT COUNT(*) AS n FROM used_items"
            ).fetchone()["n"]
            self._connection.execute("DELETE FROM used_items")
            self._connection.commit()
        return int(removed)

    def used_count(self) -> int:
        with self._lock:
            row = self._connection.execute("SELECT COUNT(*) AS n FROM used_items").fetchone()
        return int(row["n"])

    # --- Runs ------------------------------------------------------------------------------

    def start_run(self) -> int:
        with self._lock:
            cursor = self._connection.execute(
                "INSERT INTO runs (started_at, status) VALUES (?, ?)",
                (datetime.now().astimezone().isoformat(timespec="seconds"), "running"),
            )
            self._connection.commit()
            return int(cursor.lastrowid)

    def finish_run(self, run_id: int, status: str, selected: int, removed: int,
                   window_days: int, message: str = "") -> None:
        with self._lock:
            self._connection.execute(
                "UPDATE runs SET finished_at = ?, status = ?, selected = ?, removed = ?, "
                "window_days = ?, message = ? WHERE id = ?",
                (datetime.now().astimezone().isoformat(timespec="seconds"), status, selected,
                 removed, window_days, message, run_id),
            )
            self._connection.commit()

    def last_runs(self, limit: int = 10) -> list[dict[str, object]]:
        with self._lock:
            rows = self._connection.execute(
                "SELECT * FROM runs ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
        return [dict(row) for row in rows]

    def last_success(self) -> Optional[dict[str, object]]:
        with self._lock:
            row = self._connection.execute(
                "SELECT * FROM runs WHERE status = 'ok' ORDER BY id DESC LIMIT 1"
            ).fetchone()
        return dict(row) if row else None

    def close(self) -> None:
        with self._lock:
            self._connection.close()


def select_daily(
    candidates: Iterable[Candidate],
    used: set[int],
    days: int,
    today: Optional[date] = None,
    rng: Optional[random.Random] = None,
) -> Selection:
    """Picks one unused photo per day, walking backwards past empty days.

    ``candidates`` may cover a wider window than ``days``; anything older is only reached when
    the nearer days run out.
    """
    reference = today or date.today()
    random_source = rng or random.Random()

    # Group the still-available candidates by the day they were taken.
    by_day: dict[date, list[Candidate]] = {}
    for candidate in candidates:
        if candidate.item_id in used:
            continue
        by_day.setdefault(candidate.taken_on, []).append(candidate)

    # Walk days newest first. Every calendar day gets a slot, empty ones are skipped.
    available_days = sorted((d for d, items in by_day.items() if items), reverse=True)
    result = Selection(window_days=days, days_available=len(available_days))

    day_cursor = 0
    for _ in range(days):
        while day_cursor < len(available_days) and not by_day[available_days[day_cursor]]:
            # This day has nothing left; the slot is filled from an older day instead.
            result.days_skipped += 1
            day_cursor += 1
        if day_cursor >= len(available_days):
            break
        day = available_days[day_cursor]
        bucket = by_day[day]
        result.chosen.append(bucket.pop(random_source.randrange(len(bucket))))
        day_cursor += 1

    return result


def day_span(taken_at: int) -> date:
    """The local calendar day a capture timestamp belongs to."""
    return datetime.fromtimestamp(taken_at).date()


def cutoff_for(days: int, today: Optional[date] = None) -> date:
    """The oldest day included in a window of ``days`` days."""
    reference = today or date.today()
    return reference - timedelta(days=days - 1)
