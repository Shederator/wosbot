#!/usr/bin/env python3

from __future__ import annotations

import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from next_nightly_version import next_nightly_version  # noqa: E402


class NextNightlyVersionTest(unittest.TestCase):
    def test_increments_the_sequence_on_the_same_utc_date(self):
        self.assertEqual(
            "3.0.0-nightly.20260812.9",
            next_nightly_version(
                date(2026, 8, 12), "3.0.0-nightly.20260812.8"),
        )

    def test_resets_the_sequence_on_the_next_utc_date(self):
        self.assertEqual(
            "3.0.0-nightly.20260813.1",
            next_nightly_version(
                date(2026, 8, 13), "3.0.0-nightly.20260812.8"),
        )

    def test_starts_from_the_latest_stable_core_without_a_feed(self):
        self.assertEqual(
            "3.1.0-nightly.20260813.1",
            next_nightly_version(date(2026, 8, 13), base_version="3.1.0"),
        )

    def test_rejects_invalid_or_backdated_inputs(self):
        invalid = (
            (date(2026, 8, 11), "3.0.0-nightly.20260812.8", None),
            (date(2026, 8, 12), "nightly.8", None),
            (date(2026, 8, 12), None, "3.0"),
        )
        for release_date, previous, base in invalid:
            with self.subTest(previous=previous, base=base):
                with self.assertRaises(ValueError):
                    next_nightly_version(release_date, previous, base)

    def test_rejects_more_than_999_builds_per_day(self):
        with self.assertRaises(ValueError):
            next_nightly_version(
                date(2026, 8, 12), "3.0.0-nightly.20260812.999")


if __name__ == "__main__":
    unittest.main(verbosity=2)
