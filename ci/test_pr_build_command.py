#!/usr/bin/env python3
"""Self-tests for ci/pr_build_command.py.

The command parser and the two guards around it are the whole access-control
story for test builds: whoever gets past them can spend the repository's Actions
minutes and publish a public download. So the interesting cases here are the
hostile and the clumsy ones — a quoted command in a reply, a drive-by comment
from someone without write access, a typo repeated five times in a row.

Run with:  python3 ci/test_pr_build_command.py
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pr_build_command as command  # noqa: E402

NOW = datetime(2026, 7, 31, 12, 0, tzinfo=timezone.utc)


class ParsingTest(unittest.TestCase):

    def test_a_plain_request_is_a_plan_not_a_build(self):
        result = command.parse_command("/build-pr 47 48 49 65")
        self.assertTrue(result.is_command)
        self.assertEqual("47 48 49 65", result.prs)
        self.assertFalse(result.confirm)
        self.assertEqual("stop", result.resolution)

    def test_confirm_switches_the_request_into_a_real_build(self):
        result = command.parse_command("/build-pr 47 48 confirm")
        self.assertTrue(result.confirm)
        self.assertEqual("47 48", result.prs)

    def test_union_opts_into_a_both_sides_resolution(self):
        result = command.parse_command("/build-pr 47 48 union confirm")
        self.assertEqual("union", result.resolution)
        self.assertTrue(result.confirm)

    def test_order_override_is_extracted_and_not_treated_as_numbers(self):
        result = command.parse_command("/build-pr 47 48 49 order=49,47,48 confirm")
        self.assertEqual("49,47,48", result.order)
        self.assertEqual("47 48 49", result.prs)

    def test_hash_prefixed_numbers_and_commas_are_accepted(self):
        result = command.parse_command("/build-pr #47, #48")
        self.assertEqual("#47 #48", result.prs)

    def test_a_quoted_command_in_a_reply_does_not_trigger_a_build(self):
        # GitHub reply quoting prefixes lines with "> ". Reacting to those would
        # make every thread reply start another build.
        body = "> /build-pr 47 48 confirm\n\nLooks good to me."
        self.assertFalse(command.parse_command(body).is_command)

    def test_the_command_may_appear_after_a_line_of_prose(self):
        body = "Please build these:\n/build-pr 47 48 confirm"
        result = command.parse_command(body)
        self.assertTrue(result.is_command)
        self.assertTrue(result.confirm)

    def test_a_comment_without_the_command_is_ignored(self):
        self.assertFalse(command.parse_command("nice work, thanks!").is_command)
        self.assertFalse(command.parse_command("").is_command)

    def test_help_is_shown_for_an_empty_or_explicit_help_request(self):
        self.assertTrue(command.parse_command("/build-pr").help)
        self.assertTrue(command.parse_command("/build-pr help").help)

    def test_unknown_tokens_are_reported_instead_of_guessed(self):
        result = command.parse_command("/build-pr 47 latest nightly")
        self.assertEqual("47", result.prs)
        self.assertEqual(["latest", "nightly"], result.unknown)

    def test_usage_text_documents_every_accepted_keyword(self):
        for keyword in ("confirm", "union", "order=", "help"):
            self.assertIn(keyword, command.USAGE)


class AuthorizationTest(unittest.TestCase):

    def test_write_access_is_enough(self):
        for permission in ("admin", "maintain", "write"):
            allowed, _ = command.is_authorized(permission, actor="Shederator")
            self.assertTrue(allowed, permission)

    def test_read_access_is_not_enough(self):
        allowed, reason = command.is_authorized("read", actor="drive-by")
        self.assertFalse(allowed)
        self.assertIn("write access", reason)

    def test_an_explicit_allowlist_can_grant_a_trusted_tester(self):
        allowed, reason = command.is_authorized(
            "read", actor="TrustedTester", allowlist="someone, TRUSTEDTESTER"
        )
        self.assertTrue(allowed)
        self.assertIn("allowlist", reason)

    def test_the_author_association_is_accepted_when_the_api_says_nothing(self):
        allowed, _ = command.is_authorized("", association="OWNER", actor="x")
        self.assertTrue(allowed)
        allowed, _ = command.is_authorized(
            "", association="FIRST_TIME_CONTRIBUTOR", actor="x"
        )
        self.assertFalse(allowed)

    def test_an_empty_permission_and_association_is_refused(self):
        allowed, _ = command.is_authorized("", "", "nobody", "")
        self.assertFalse(allowed)


def run(actor: str, minutes_ago: int) -> dict:
    return {
        "actor": {"login": actor},
        "run_started_at": (NOW - timedelta(minutes=minutes_ago))
        .isoformat()
        .replace("+00:00", "Z"),
    }


class CooldownTest(unittest.TestCase):

    def test_a_first_request_is_never_blocked(self):
        blocked, _ = command.cooldown_exceeded([], "Shederator", NOW)
        self.assertFalse(blocked)

    def test_a_burst_of_requests_from_one_person_is_blocked(self):
        runs = [run("Shederator", 5), run("Shederator", 10), run("Shederator", 15)]
        blocked, reason = command.cooldown_exceeded(runs, "Shederator", NOW)
        self.assertTrue(blocked)
        self.assertIn("limit 3", reason)

    def test_old_requests_fall_out_of_the_window(self):
        runs = [run("Shederator", 90), run("Shederator", 120), run("Shederator", 200)]
        blocked, _ = command.cooldown_exceeded(runs, "Shederator", NOW)
        self.assertFalse(blocked)

    def test_other_people_do_not_consume_your_quota(self):
        runs = [run("CodeLtDave", 1), run("bizulk", 2), run("CodeLtDave", 3)]
        blocked, _ = command.cooldown_exceeded(runs, "Shederator", NOW)
        self.assertFalse(blocked)

    def test_a_malformed_timestamp_is_skipped_rather_than_crashing(self):
        runs = [{"actor": {"login": "Shederator"}, "run_started_at": "nonsense"}]
        blocked, _ = command.cooldown_exceeded(runs, "Shederator", NOW)
        self.assertFalse(blocked)

    def test_an_unreadable_history_never_blocks_a_legitimate_build(self):
        # A failing API call must not become a denial of service on the feature.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing.json"
            code = command.main(
                ["cooldown", "--runs", str(path), "--actor", "Shederator"]
            )
            self.assertEqual(0, code)

    def test_the_history_file_from_the_api_is_understood(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runs.json"
            path.write_text(
                json.dumps({"workflow_runs": [run("Shederator", 1)]}),
                encoding="utf-8",
            )
            code = command.main(
                ["cooldown", "--runs", str(path), "--actor", "Shederator"]
            )
            self.assertEqual(0, code)


if __name__ == "__main__":
    unittest.main(verbosity=2)
