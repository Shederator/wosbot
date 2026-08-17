#!/usr/bin/env python3

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from nightly_retention import obsolete_nightlies  # noqa: E402


def release(tag: str, published_at: str, *, draft: bool = False) -> dict:
    return {
        "tag_name": tag,
        "published_at": published_at,
        "draft": draft,
    }


class NightlyRetentionTest(unittest.TestCase):
    CURRENT = "v3.0.0-nightly.20260817.1"

    def test_keeps_the_two_newest_immutable_nightlies(self):
        releases = [
            release(self.CURRENT, "2026-08-17T03:20:00Z"),
            release("v3.0.0-nightly.20260816.1", "2026-08-16T03:20:00Z"),
            release("v3.0.0-nightly.20260815.1", "2026-08-15T03:20:00Z"),
            release("v3.0.0-nightly.20260813.3", "2026-08-13T05:00:00Z"),
        ]

        self.assertEqual(
            ["v3.0.0-nightly.20260815.1",
             "v3.0.0-nightly.20260813.3"],
            obsolete_nightlies(releases, self.CURRENT),
        )

    def test_accepts_paginated_camel_case_release_history(self):
        releases = [[{
            "tagName": self.CURRENT,
            "publishedAt": "2026-08-17T03:20:00Z",
            "isDraft": False,
        }], [{
            "tagName": "v3.0.0-nightly.20260816.1",
            "publishedAt": "2026-08-16T03:20:00Z",
            "isDraft": False,
        }, {
            "tagName": "v3.0.0-nightly.20260815.1",
            "publishedAt": "2026-08-15T03:20:00Z",
            "isDraft": False,
        }]]

        self.assertEqual(
            ["v3.0.0-nightly.20260815.1"],
            obsolete_nightlies(releases, self.CURRENT),
        )

    def test_ignores_rolling_stable_pr_test_and_draft_releases(self):
        releases = [
            release(self.CURRENT, "2026-08-17T03:20:00Z"),
            release("v3.0.0-nightly.20260816.1", "2026-08-16T03:20:00Z"),
            release("v3.0.0", "2026-08-12T15:00:00Z"),
            release("nightly", "2026-08-17T03:21:00Z"),
            release("pr-test-example", "2026-08-17T03:22:00Z"),
            release(
                "v3.0.0-nightly.20260818.1",
                "2026-08-18T03:20:00Z",
                draft=True,
            ),
        ]

        self.assertEqual([], obsolete_nightlies(releases, self.CURRENT))

    def test_refuses_cleanup_when_current_release_is_missing_or_not_newest(self):
        with self.assertRaisesRegex(ValueError, "is missing"):
            obsolete_nightlies([], self.CURRENT)

        releases = [
            release("v3.0.0-nightly.20260818.2", "2026-08-18T04:20:00Z"),
            release("v3.0.0-nightly.20260818.1", "2026-08-18T03:20:00Z"),
            release(self.CURRENT, "2026-08-17T03:20:00Z"),
        ]
        with self.assertRaisesRegex(ValueError, "is not among the newest 2"):
            obsolete_nightlies(releases, self.CURRENT)

    def test_rejects_unsafe_inputs(self):
        with self.assertRaisesRegex(ValueError, "At least one"):
            obsolete_nightlies([], self.CURRENT, keep=0)
        with self.assertRaisesRegex(ValueError, "is invalid"):
            obsolete_nightlies([], "nightly")
        duplicates = [
            release(self.CURRENT, "2026-08-17T03:20:00Z"),
            release(self.CURRENT, "2026-08-17T03:21:00Z"),
        ]
        with self.assertRaisesRegex(ValueError, "occurs more than once"):
            obsolete_nightlies(duplicates, self.CURRENT)


if __name__ == "__main__":
    unittest.main(verbosity=2)
