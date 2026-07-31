#!/usr/bin/env python3
"""Self-tests for ci/pr_build_cleanup.py.

This script deletes releases, so the tests care mostly about what it must *not*
delete: a real tagged release, the rolling ``nightly`` prerelease, a fresh test
build, or a build whose pull requests are still open. A cleanup bug that deletes
too much is far more expensive than one that deletes too little.

Run with:  python3 ci/test_pr_build_cleanup.py
"""

from __future__ import annotations

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pr_build_cleanup as cleanup  # noqa: E402

NOW = datetime(2026, 7, 31, 12, 0, tzinfo=timezone.utc)


def release(
    tag: str,
    days_old: float = 0,
    prs: list[int] | None = None,
    identifier: int = 1,
) -> dict:
    created = (NOW - timedelta(days=days_old)).isoformat().replace("+00:00", "Z")
    body = "Unmerged test build.\n"
    if prs is not None:
        body += cleanup.marker_block(prs, "abc123", "f" * 40)
    return {
        "id": identifier,
        "tag_name": tag,
        "created_at": created,
        "body": body,
    }


def states(mapping: dict[int, str]):
    return lambda number: mapping.get(number, "open")


class MarkerTest(unittest.TestCase):

    def test_the_marker_round_trips(self):
        body = "notes\n" + cleanup.marker_block([48, 49], "key", "base")
        parsed = cleanup.parse_marker(body)
        self.assertEqual([48, 49], parsed["prs"])
        self.assertEqual("key", parsed["key"])

    def test_a_missing_or_broken_marker_yields_nothing(self):
        self.assertEqual({}, cleanup.parse_marker("just notes"))
        self.assertEqual(
            {}, cleanup.parse_marker("<!-- frostguard-pr-test {not json} -->")
        )

    def test_the_marker_is_invisible_in_rendered_markdown(self):
        # It is an HTML comment on purpose: testers should not see bookkeeping.
        self.assertTrue(cleanup.marker_block([1], "k", "b").startswith("<!--"))


class RetirementRuleTest(unittest.TestCase):

    def test_a_fresh_test_build_with_open_pull_requests_is_kept(self):
        doomed = cleanup.decide(
            [release("pr-test-abc", days_old=1, prs=[48, 49])],
            states({}),
            NOW,
        )
        self.assertEqual([], doomed)

    def test_an_expired_test_build_is_retired(self):
        doomed = cleanup.decide(
            [release("pr-test-abc", days_old=9, prs=[48])], states({}), NOW
        )
        self.assertEqual(1, len(doomed))
        self.assertIn("TTL", doomed[0][1])

    def test_a_build_whose_pull_requests_all_finished_is_retired(self):
        doomed = cleanup.decide(
            [release("pr-test-abc", days_old=1, prs=[48, 49])],
            states({48: "merged", 49: "closed"}),
            NOW,
        )
        self.assertEqual(1, len(doomed))
        self.assertIn("closed or merged", doomed[0][1])

    def test_one_still_open_pull_request_keeps_the_build_alive(self):
        doomed = cleanup.decide(
            [release("pr-test-abc", days_old=1, prs=[48, 49])],
            states({48: "merged"}),
            NOW,
        )
        self.assertEqual([], doomed)

    def test_a_real_release_is_never_touched(self):
        doomed = cleanup.decide(
            [
                {"id": 9, "tag_name": "v2.1.0", "created_at": "2020-01-01T00:00:00Z"},
                {"id": 8, "tag_name": "nightly", "created_at": "2020-01-01T00:00:00Z"},
            ],
            states({}),
            NOW,
        )
        self.assertEqual([], doomed)

    def test_a_test_build_without_a_marker_is_only_retired_by_age(self):
        fresh = release("pr-test-abc", days_old=1)
        fresh["body"] = "notes without a marker"
        self.assertEqual([], cleanup.decide([fresh], states({}), NOW))

        old = release("pr-test-def", days_old=30)
        old["body"] = "notes without a marker"
        self.assertEqual(1, len(cleanup.decide([old], states({}), NOW)))

    def test_the_ttl_is_configurable(self):
        candidate = [release("pr-test-abc", days_old=3, prs=[48])]
        self.assertEqual([], cleanup.decide(candidate, states({}), NOW, ttl_days=7))
        self.assertEqual(
            1, len(cleanup.decide(candidate, states({}), NOW, ttl_days=2))
        )

    def test_an_unreadable_timestamp_does_not_delete_the_release(self):
        broken = release("pr-test-abc", prs=[48])
        broken["created_at"] = "not a date"
        self.assertEqual([], cleanup.decide([broken], states({}), NOW))


class ApiSafetyTest(unittest.TestCase):

    def test_an_unreadable_pull_request_counts_as_open(self):
        # Otherwise a transient API failure would delete builds testers are
        # still downloading.
        api = cleanup.ReleaseApi("owner/name", token="x", api_base="http://127.0.0.1:1")
        self.assertEqual("open", api.pull_state(48))

    def test_only_the_test_prefix_is_considered(self):
        self.assertTrue("pr-test-abc".startswith(cleanup.TAG_PREFIX))
        self.assertFalse("nightly".startswith(cleanup.TAG_PREFIX))


if __name__ == "__main__":
    unittest.main(verbosity=2)
