#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import unittest
from unittest import mock

import nightly_changes as changes


class NightlyChangesTest(unittest.TestCase):
    def test_merge_commit_uses_pr_number_and_second_parent_title(self):
        with mock.patch.object(changes, "git", side_effect=[
            "Merge pull request #80 from example/fix",
            "fix(releases): retry CDN propagation delays",
        ]):
            line = changes.describe("Shederator/wosbot", "abcdef123")
        self.assertIn("[#80]", line)
        self.assertIn("retry CDN propagation delays", line)
        self.assertNotIn("example/fix", line)

    def test_squash_commit_links_the_pr(self):
        with mock.patch.object(
            changes, "git", return_value="feat(ui): cleaner downloads (#79)"
        ):
            line = changes.describe("Shederator/wosbot", "123456789")
        self.assertIn("[#79]", line)
        self.assertIn("cleaner downloads", line)

    def test_direct_commit_links_the_commit(self):
        with mock.patch.object(changes, "git", return_value="docs: explain Nightly"):
            line = changes.describe("Shederator/wosbot", "123456789")
        self.assertIn("[`1234567`]", line)
        self.assertIn("/commit/1234567", line)

    def test_summary_keeps_every_change(self):
        commits = "\n".join(f"commit{i}" for i in range(7))
        with mock.patch.object(changes, "git", return_value=commits), \
                mock.patch.object(changes, "describe", side_effect=lambda _, c: c):
            result = changes.summary("Shederator/wosbot", "old", "new")
        self.assertIn("commit0", result)
        self.assertIn("commit6", result)

    def test_missing_history_is_a_readable_fallback(self):
        error = subprocess.CalledProcessError(128, ["git"])
        with mock.patch.object(changes, "git", side_effect=error):
            result = changes.summary("Shederator/wosbot", "old", "new")
        self.assertIn("unavailable", result)

    def test_markdown_in_titles_is_escaped(self):
        self.assertEqual(r"fix \[text\]", changes.escape_link_text(r"fix [text]"))

    def test_release_range_compares_adjacent_distinct_builds(self):
        result = changes.resolve_range([
            release("v3.0.0-nightly.20260813.2", "new", "2026-08-13T04:00:00Z"),
            release("v3.0.0-nightly.20260813.1", "old", "2026-08-13T03:00:00Z"),
        ], "v3.0.0-nightly.20260813.2")
        self.assertEqual(("old", "new"), (result.previous, result.current))
        self.assertFalse(result.unchanged)
        self.assertEqual("2026-08-13T04:00:00Z", result.updated_at)

    def test_unchanged_build_retains_last_non_empty_range_and_update_time(self):
        result = changes.resolve_range([
            release("v3.0.0-nightly.20260813.3", "same", "2026-08-13T05:00:00Z"),
            release("v3.0.0-nightly.20260813.2", "same", "2026-08-13T04:00:00Z"),
            release("v3.0.0-nightly.20260813.1", "old", "2026-08-13T03:00:00Z"),
        ], "v3.0.0-nightly.20260813.3")
        self.assertEqual(("old", "same"), (result.previous, result.current))
        self.assertTrue(result.unchanged)
        self.assertEqual("2026-08-13T04:00:00Z", result.updated_at)

    def test_ignores_rolling_and_draft_releases(self):
        result = changes.resolve_range([
            release("nightly", "rolling", "2026-08-13T06:00:00Z"),
            release("v3.0.0-nightly.20260813.2", "new", "2026-08-13T05:00:00Z"),
            release("v3.0.0-nightly.20260813.1", "draft", "2026-08-13T04:00:00Z", True),
            release("v3.0.0-nightly.20260812.9", "old", "2026-08-12T03:00:00Z"),
        ], "v3.0.0-nightly.20260813.2")
        self.assertEqual("old", result.previous)

    def test_accepts_paginated_github_rest_release_data(self):
        result = changes.resolve_range([[
            {
                "tag_name": "v3.0.0-nightly.20260813.2",
                "target_commitish": "new",
                "published_at": "2026-08-13T05:00:00Z",
                "draft": False,
            },
            {
                "tag_name": "v3.0.0-nightly.20260813.1",
                "target_commitish": "old",
                "published_at": "2026-08-13T04:00:00Z",
                "draft": False,
            },
        ]], "v3.0.0-nightly.20260813.2")
        self.assertEqual(("old", "new"), (result.previous, result.current))


def release(tag: str, sha: str, published_at: str, draft: bool = False) -> dict:
    return {
        "tagName": tag,
        "targetCommitish": sha,
        "publishedAt": published_at,
        "isDraft": draft,
    }


if __name__ == "__main__":
    unittest.main(verbosity=2)
