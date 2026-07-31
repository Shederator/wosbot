#!/usr/bin/env python3
"""Self-tests for ci/pr_build_notify.py.

The notification is the only thing most testers will ever read about a test
build, so the payload has to carry the facts that decide whether installing it is
a good idea: which pull requests, at which commits, and that the download is
unmerged and unreviewed. These tests pin that contract, plus Discord's size
limits and the no-mass-ping rule, without touching the network.

Run with:  python3 ci/test_pr_build_notify.py
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pr_build_notify as notifier  # noqa: E402

PLAN = {
    "version": 1,
    "repository": "Shederator/wosbot",
    "base_ref": "main",
    "base_sha": "f" * 40,
    "build_key": "abc123def456",
    "tag": "pr-test-abc123def456",
    "asset_name": "frostguard-unmerged-test-build-pr-48-49.zip",
    "order": [48, 49],
    "requested": [47, 48, 49],
    "invalid_tokens": ["latest"],
    "duplicates_removed": [49],
    "rejected": [{"number": 44, "reason": "already merged into the base branch"}],
    "contained": [
        {"number": 47, "contained_in": 48, "reason": "1234567 is an ancestor"}
    ],
    "problems": [],
    "notes": [],
    "pull_requests": {
        "48": {
            "number": 48,
            "title": "fix(deployment): account final costs across march routines",
            "head_sha": "d0fb22a48f3469d4bfcf09f8fe20745bf3f8c373",
            "author": "CodeLtDave",
            "url": "https://github.com/Shederator/wosbot/pull/48",
        },
        "49": {
            "number": 49,
            "title": "fix(intel): derive availability from typed march state",
            "head_sha": "8162d39700d72e3afecae7eee1038d202b8cc367",
            "author": "CodeLtDave",
            "url": "https://github.com/Shederator/wosbot/pull/49",
        },
    },
}

REPORT = {
    "status": "conflict",
    "conflicts": [
        {
            "number": 49,
            "sha": "8162d39700d72e3afecae7eee1038d202b8cc367",
            "files": [
                {
                    "path": "fg-tasks/src/main/java/Intel.java",
                    "kind": "text",
                    "resolvable_by_union": True,
                },
                {
                    "path": "fg-vision/src/main/resources/templates/x.png",
                    "kind": "binary",
                    "resolvable_by_union": False,
                },
            ],
        }
    ],
}


class PayloadTestCase(unittest.TestCase):

    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        directory = Path(self._temp.name)
        self.plan_path = directory / "plan.json"
        self.plan_path.write_text(json.dumps(PLAN), encoding="utf-8")
        self.report_path = directory / "report.json"
        self.report_path.write_text(json.dumps(REPORT), encoding="utf-8")

    def tearDown(self) -> None:
        self._temp.cleanup()

    def payload(self, *extra: str) -> dict:
        args = notifier.parse_args(
            [
                "--plan",
                str(self.plan_path),
                "--repository",
                "Shederator/wosbot",
                "--run-url",
                "https://github.com/Shederator/wosbot/actions/runs/1",
                "--run-number",
                "7",
                *extra,
            ]
        )
        return notifier.build_payload(args)

    def flat(self, payload: dict) -> str:
        return json.dumps(payload)


class SuccessMessageTest(PayloadTestCase):

    def test_success_carries_the_download_link_as_tappable_content(self):
        payload = self.payload(
            "--status",
            "success",
            "--download-url",
            "https://github.com/Shederator/wosbot/releases/download/"
            "pr-test-abc123def456/frostguard-unmerged-test-build-pr-48-49.zip",
        )
        self.assertIn("releases/download/pr-test-", payload["content"])

    def test_success_labels_the_build_as_unmerged_and_unreviewed(self):
        payload = self.payload(
            "--status", "success", "--download-url", "https://example.invalid/x.zip"
        )
        description = payload["embeds"][0]["description"]
        self.assertIn("Unmerged test build", description)
        self.assertIn("not been reviewed", description)

    def test_every_included_pull_request_is_listed_with_its_pinned_commit(self):
        body = self.flat(self.payload("--status", "success"))
        self.assertIn("#48", body)
        self.assertIn("#49", body)
        self.assertIn("d0fb22a", body)
        self.assertIn("8162d39", body)

    def test_rejected_and_contained_pull_requests_are_explained(self):
        body = self.flat(self.payload("--status", "success"))
        self.assertIn("already merged", body)
        self.assertIn("#47 already contained in #48", body)
        self.assertIn("latest", body)

    def test_a_relative_download_url_is_dropped_instead_of_advertised(self):
        payload = self.payload(
            "--status", "success", "--download-url", "frostguard.zip"
        )
        self.assertNotIn("content", payload)

    def test_expiry_is_advertised_when_known(self):
        body = self.flat(
            self.payload("--status", "success", "--expires", "in 7 days")
        )
        self.assertIn("in 7 days", body)


class PlanMessageTest(PayloadTestCase):

    def test_a_plan_says_clearly_that_nothing_was_built(self):
        payload = self.payload("--status", "plan")
        self.assertIn("nothing has been built", payload["embeds"][0]["description"])
        self.assertNotIn("content", payload)

    def test_the_confirmation_hint_is_passed_through(self):
        payload = self.payload(
            "--status", "plan", "--confirm-hint", "Reply `/build-pr 48 49 confirm`"
        )
        self.assertIn("confirm", payload["embeds"][0]["description"])


class ConflictMessageTest(PayloadTestCase):

    def test_conflicting_files_are_named_with_their_kind(self):
        payload = self.payload(
            "--status", "conflict", "--report", str(self.report_path)
        )
        body = self.flat(payload)
        self.assertIn("Intel.java", body)
        self.assertIn("templates/x.png", body)
        self.assertIn("binary", body)

    def test_conflict_states_that_nothing_was_changed_or_published(self):
        payload = self.payload(
            "--status", "conflict", "--report", str(self.report_path)
        )
        description = payload["embeds"][0]["description"]
        self.assertIn("Nothing in the repository was changed", description)


class SafetyTest(PayloadTestCase):

    def test_mass_pings_are_disabled_structurally(self):
        payload = self.payload("--status", "success")
        self.assertEqual({"parse": []}, payload["allowed_mentions"])

    def test_a_pull_request_title_cannot_ping_the_channel(self):
        hostile = dict(PLAN)
        hostile["pull_requests"] = {
            "48": {
                "number": 48,
                "title": "@everyone install this now",
                "head_sha": "a" * 40,
                "author": "attacker",
                "url": "https://example.invalid/pull/48",
            }
        }
        hostile["order"] = [48]
        path = Path(self._temp.name) / "hostile.json"
        path.write_text(json.dumps(hostile), encoding="utf-8")
        args = notifier.parse_args(["--status", "success", "--plan", str(path)])
        payload = notifier.build_payload(args)
        self.assertEqual({"parse": []}, payload["allowed_mentions"])

    def test_every_string_stays_inside_discord_limits(self):
        long_plan = dict(PLAN)
        long_plan["pull_requests"] = {
            str(number): {
                "number": number,
                "title": "x" * 400,
                "head_sha": f"{number:040x}",
                "author": "y" * 100,
                "url": "https://example.invalid/pull/1",
            }
            for number in range(1, 31)
        }
        long_plan["order"] = list(range(1, 31))
        path = Path(self._temp.name) / "long.json"
        path.write_text(json.dumps(long_plan), encoding="utf-8")
        args = notifier.parse_args(["--status", "success", "--plan", str(path)])
        payload = notifier.build_payload(args)
        embed = payload["embeds"][0]
        self.assertLessEqual(len(embed["title"]), notifier.EMBED_TITLE_LIMIT)
        self.assertLessEqual(
            len(embed["description"]), notifier.EMBED_DESCRIPTION_LIMIT
        )
        for field in embed["fields"]:
            self.assertLessEqual(len(field["name"]), notifier.EMBED_FIELD_NAME_LIMIT)
            self.assertLessEqual(
                len(field["value"]), notifier.EMBED_FIELD_VALUE_LIMIT
            )

    def test_a_missing_plan_file_does_not_crash_the_notification(self):
        args = notifier.parse_args(
            ["--status", "failure", "--plan", "/nonexistent/plan.json"]
        )
        payload = notifier.build_payload(args)
        self.assertIn("failed", payload["embeds"][0]["title"].lower())


class WebhookResolutionTest(unittest.TestCase):

    def test_a_dedicated_webhook_wins_over_the_nightly_one(self):
        import os

        os.environ["FG_TEST_PRIMARY"] = "https://discord.com/api/webhooks/1/a"
        os.environ["FG_TEST_FALLBACK"] = "https://discord.com/api/webhooks/2/b"
        try:
            value, source = notifier.resolve_webhook(
                "FG_TEST_PRIMARY", "FG_TEST_FALLBACK"
            )
            self.assertEqual("https://discord.com/api/webhooks/1/a", value)
            self.assertEqual("FG_TEST_PRIMARY", source)
        finally:
            del os.environ["FG_TEST_PRIMARY"]
            del os.environ["FG_TEST_FALLBACK"]

    def test_the_nightly_webhook_is_used_when_no_dedicated_one_exists(self):
        import os

        os.environ.pop("FG_TEST_PRIMARY", None)
        os.environ["FG_TEST_FALLBACK"] = "https://discord.com/api/webhooks/2/b"
        try:
            value, source = notifier.resolve_webhook(
                "FG_TEST_PRIMARY", "FG_TEST_FALLBACK"
            )
            self.assertEqual("https://discord.com/api/webhooks/2/b", value)
            self.assertEqual("FG_TEST_FALLBACK", source)
        finally:
            del os.environ["FG_TEST_FALLBACK"]

    def test_a_non_discord_url_is_rejected_before_anything_is_posted(self):
        import os

        os.environ["FG_TEST_PRIMARY"] = "https://evil.example/api/webhooks/1/a"
        try:
            code = notifier.main(
                [
                    "--status",
                    "success",
                    "--webhook-env",
                    "FG_TEST_PRIMARY",
                    "--webhook-env-fallback",
                    "FG_TEST_ABSENT",
                ]
            )
            self.assertEqual(1, code)
        finally:
            del os.environ["FG_TEST_PRIMARY"]

    def test_no_webhook_at_all_is_a_warning_not_a_failure(self):
        code = notifier.main(
            [
                "--status",
                "success",
                "--webhook-env",
                "FG_TEST_ABSENT_A",
                "--webhook-env-fallback",
                "FG_TEST_ABSENT_B",
            ]
        )
        self.assertEqual(0, code)


if __name__ == "__main__":
    unittest.main(verbosity=2)
