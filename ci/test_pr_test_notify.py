#!/usr/bin/env python3
"""Unit tests for ci/pr_test_notify.py."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pr_test_notify as notify
from discord_notify import (
    CONTENT_LIMIT,
    EMBED_DESCRIPTION_LIMIT,
    EMBED_FIELD_NAME_LIMIT,
    EMBED_FIELD_VALUE_LIMIT,
    EMBED_TITLE_LIMIT,
)


def sample_plan(**overrides) -> dict:
    plan = {
        "version": 1,
        "ok": True,
        "repository": "Shederator/wosbot",
        "base_ref": "main",
        "base_sha": "c0ffee" + "0" * 34,
        "requested": "47 48 49",
        "pulls": [
            {"number": 47, "title": "fix(scheduler): restore stamina waits",
             "head_sha": "a" * 40, "author": "codeltdave"},
            {"number": 49, "title": "fix(intel): typed march state",
             "head_sha": "b" * 40, "author": "codeltdave"},
        ],
        "dropped": [{"number": 48, "contained_in": 49}],
        "order": [47, 49],
        "digest": "abc123def456",
        "tag": "pr-test-abc123def456",
        "merge": {"ok": True, "tree_sha": "d" * 40, "conflicts": []},
        "errors": [],
        "notes": [],
        "reuse_url": "",
    }
    plan.update(overrides)
    return plan


def payload_for(kind: str, plan: dict | None, **extra) -> dict:
    argv = ["--kind", kind, "--dry-run"]
    for key, value in extra.items():
        flag = "--" + key.replace("_", "-")
        if value is True:
            argv.append(flag)
        else:
            argv.extend([flag, str(value)])
    if plan is not None:
        with tempfile.NamedTemporaryFile(
            "w", suffix=".json", delete=False, encoding="utf-8",
        ) as handle:
            json.dump(plan, handle)
            path = handle.name
        argv.extend(["--plan", path])
    args = notify.parse_args(argv)
    try:
        return notify.build_payload(args, notify.load_plan(args.plan))
    finally:
        if plan is not None:
            os.unlink(path)


def all_text(payload: dict) -> str:
    return json.dumps(payload)


class SuccessPayloadTest(unittest.TestCase):
    URL = "https://github.com/Shederator/wosbot/releases/download/pr-test-abc123def456/x.zip"

    def payload(self, **extra) -> dict:
        defaults = dict(download_url=self.URL, expires_utc="2026-08-07",
                        bundle_bytes=230000000)
        defaults.update(extra)
        return payload_for("success", sample_plan(), **defaults)

    def test_marks_the_download_as_an_unmerged_test_build(self):
        text = self.payload()["embeds"][0]["description"]
        self.assertIn("UNMERGED TEST BUILD", text)

    def test_content_carries_the_bare_download_url(self):
        self.assertEqual(self.payload()["content"], self.URL)

    def test_lists_every_pr_with_number_title_and_pinned_sha(self):
        text = all_text(self.payload())
        self.assertIn("#47", text)
        self.assertIn("#49", text)
        self.assertIn("a" * 12, text)
        self.assertIn("b" * 12, text)
        self.assertIn("restore stamina waits", text)

    def test_explains_the_dropped_stacked_pr(self):
        text = all_text(self.payload())
        self.assertIn("#48", text)
        self.assertIn("already contained in #49", text)

    def test_names_the_expiry(self):
        self.assertIn("2026-08-07", self.payload()["embeds"][0]["description"])

    def test_flags_a_reused_build(self):
        text = self.payload(reused=True)["embeds"][0]["description"]
        self.assertIn("reused", text)

    def test_is_green(self):
        self.assertEqual(self.payload()["embeds"][0]["color"], 0x2ECC71)

    def test_never_pings_the_channel(self):
        self.assertEqual(self.payload()["allowed_mentions"], {"parse": []})


class ConflictPayloadTest(unittest.TestCase):
    def plan(self) -> dict:
        return sample_plan(
            ok=False,
            merge={"ok": False, "tree_sha": "", "conflicts": [{
                "pr": 49,
                "files": [
                    {"path": "fg-engine/src/main/java/Foo.java", "binary": False},
                    {"path": "fg-vision/native/opencv.dll", "binary": True},
                ],
            }]},
            errors=["The PRs do not merge cleanly; see the conflict report."],
        )

    def test_names_the_conflicting_pr_and_files(self):
        text = all_text(payload_for("conflict", self.plan()))
        self.assertIn("#49", text)
        self.assertIn("Foo.java", text)
        self.assertIn("opencv.dll", text)

    def test_binary_conflicts_demand_a_manual_choice(self):
        text = all_text(payload_for("conflict", self.plan()))
        self.assertIn("binary", text)
        self.assertIn("manual choice", text)

    def test_says_nothing_was_auto_resolved(self):
        text = payload_for("conflict", self.plan())["embeds"][0]["description"]
        self.assertIn("Nothing was resolved", text)

    def test_carries_no_download_content(self):
        self.assertNotIn("content", payload_for("conflict", self.plan()))

    def test_truncates_an_enormous_conflict_wall(self):
        files = [{"path": f"module/File{i}.java", "binary": False}
                 for i in range(200)]
        plan = sample_plan(
            ok=False,
            merge={"ok": False, "tree_sha": "",
                   "conflicts": [{"pr": 49, "files": files}]},
        )
        payload = payload_for("conflict", plan)
        for field in payload["embeds"][0]["fields"]:
            self.assertLessEqual(len(field["value"]), EMBED_FIELD_VALUE_LIMIT)
        self.assertIn("more", all_text(payload))


class RejectedPayloadTest(unittest.TestCase):
    def test_repeats_the_problems_back_to_the_requester(self):
        plan = {
            "ok": False,
            "errors": ["PR #12 is already merged; its changes are in `main`.",
                       "`banana` is not a PR number."],
            "requested": "12 banana",
            "pulls": [], "dropped": [], "merge": {},
        }
        text = all_text(payload_for("rejected", plan))
        self.assertIn("already merged", text)
        self.assertIn("banana", text)

    def test_works_without_any_plan_file(self):
        payload = payload_for("rejected", None, reason="Rate limited",
                              requested="47")
        self.assertIn("Rate limited", all_text(payload))

    def test_is_red(self):
        payload = payload_for("rejected", None, reason="x")
        self.assertEqual(payload["embeds"][0]["color"], 0xE74C3C)


class StalePayloadTest(unittest.TestCase):
    def test_explains_why_publishing_was_withheld(self):
        payload = payload_for(
            "stale", sample_plan(),
            problems="PR #47 was pushed to after planning (`aaa` -> `bbb`).",
        )
        text = all_text(payload)
        self.assertIn("not** published", payload["embeds"][0]["description"])
        self.assertIn("pushed to after planning", text)
        self.assertNotIn("content", payload)


class FailurePayloadTest(unittest.TestCase):
    def test_links_the_workflow_log(self):
        payload = payload_for(
            "failure", sample_plan(),
            run_url="https://github.com/Shederator/wosbot/actions/runs/1",
        )
        self.assertIn("workflow log", payload["embeds"][0]["description"])
        self.assertNotIn("content", payload)


class LimitsTest(unittest.TestCase):
    def test_every_discord_length_limit_is_respected(self):
        plan = sample_plan(
            pulls=[{"number": n, "title": "T" * 500, "head_sha": "e" * 40,
                    "author": "x"} for n in range(1, 7)],
            digest="f" * 100,
        )
        payload = payload_for(
            "success", plan,
            download_url="https://example.com/" + "z" * 3000,
            requester="R" * 500,
        )
        embed = payload["embeds"][0]
        self.assertLessEqual(len(embed["title"]), EMBED_TITLE_LIMIT)
        self.assertLessEqual(len(embed["description"]), EMBED_DESCRIPTION_LIMIT)
        self.assertLessEqual(len(payload.get("content", "")), CONTENT_LIMIT)
        self.assertLessEqual(len(embed["fields"]), 10)
        for field in embed["fields"]:
            self.assertLessEqual(len(field["name"]), EMBED_FIELD_NAME_LIMIT)
            self.assertLessEqual(len(field["value"]), EMBED_FIELD_VALUE_LIMIT)

    def test_payload_is_json_serialisable(self):
        json.dumps(payload_for("success", sample_plan(),
                               download_url="https://example.com/a.zip"))


class MissingPlanTest(unittest.TestCase):
    def test_a_missing_plan_file_degrades_to_an_empty_plan(self):
        self.assertEqual(notify.load_plan("/nonexistent/plan.json"), {})

    def test_a_corrupt_plan_file_degrades_to_an_empty_plan(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json",
                                         delete=False) as handle:
            handle.write("{not json")
            path = handle.name
        try:
            self.assertEqual(notify.load_plan(path), {})
        finally:
            os.unlink(path)


class WebhookGuardTest(unittest.TestCase):
    def test_missing_webhook_warns_but_does_not_fail(self):
        env_var = "FG_PR_TEST_ABSENT"
        os.environ.pop(env_var, None)
        code = notify.main(["--kind", "rejected", "--reason", "x",
                            "--webhook-env", env_var])
        self.assertEqual(code, 0)

    def test_non_discord_webhook_is_rejected(self):
        env_var = "FG_PR_TEST_BAD_HOOK"
        os.environ[env_var] = "https://example.com/not-a-webhook"
        try:
            code = notify.main(["--kind", "rejected", "--reason", "x",
                                "--webhook-env", env_var])
        finally:
            del os.environ[env_var]
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
