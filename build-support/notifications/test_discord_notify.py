#!/usr/bin/env python3
"""Self-tests for build-support/notifications/discord_notify.py.

The notifier runs once per nightly build, in a step that is deliberately
non-blocking. That means a bug in it produces either no message at all or a
message Discord rejects with a 400 — both of which look like "the pipeline is
fine" from the Actions tab. These tests pin the payload contract (Discord's
documented limits, no mass pings, no leaked webhook token) without touching the
network.

Run with:  python3 build-support/notifications/test_discord_notify.py
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
import urllib.error
from unittest import mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import discord_notify  # noqa: E402

WEBHOOK = "https://discord.com/api/webhooks/123456789/abcdefTOKEN-value_x"

BASE_ARGS = [
    "--status", "success",
    "--version", "3.0.0-nightly.20260812.9",
    "--download-url",
    "https://github.com/Shederator/wosbot/releases/download/"
    "v3.0.0-nightly.20260812.9/"
    "Frostguard-Nightly-3.0.0-nightly.20260812.9-windows-x64.msi",
    "--release-url",
    "https://github.com/Shederator/wosbot/releases/tag/"
    "v3.0.0-nightly.20260812.9",
    "--channel-url",
    "https://github.com/Shederator/wosbot/releases/tag/nightly",
    "--run-url", "https://github.com/Shederator/wosbot/actions/runs/1",
    "--repository", "Shederator/wosbot",
    "--branch", "main",
    "--commit", "b962083c0ffee1234567890abcdefabcdefabcde",
    "--commit-message", "fix(intel): claim final rewards\n\nbody line ignored",
    "--actor", "Shederator",
    "--changes", "• [#79](https://github.com/Shederator/wosbot/pull/79) Cleaner downloads",
]


def payload(extra: list[str] | None = None) -> dict:
    args = discord_notify.parse_args(BASE_ARGS + (extra or []))
    return discord_notify.build_payload(args)


class PayloadTest(unittest.TestCase):

    def test_success_payload_has_no_bare_url_content(self):
        result = payload()
        self.assertEqual("", result["content"])
        description = result["embeds"][0]["description"]
        self.assertIn("windows-x64.msi", description)
        self.assertIn("releases/tag/nightly", description)

    def test_success_embed_is_amber_and_names_the_version(self):
        embed = payload()["embeds"][0]
        self.assertEqual(0xF1C40F, embed["color"])
        self.assertIn("3.0.0-nightly.20260812.9", embed["title"])
        self.assertIn("Frostguard Nightly", embed["title"])
        self.assertEqual("Nightly channel • updated automatically",
                         embed["footer"]["text"])

    def test_failure_payload_is_red_and_links_the_log_not_a_download(self):
        result = payload(["--status", "failure"])
        embed = result["embeds"][0]
        self.assertEqual(0xE74C3C, embed["color"])
        self.assertEqual("", result["content"])
        self.assertIn("workflow log", embed["description"])

    def test_never_pings_the_channel(self):
        # A commit subject is attacker-influencable via a PR branch, so mentions
        # must be disabled structurally rather than by sanitising the text.
        result = payload(["--commit-message", "@everyone please test this"])
        self.assertEqual({"parse": []}, result["allowed_mentions"])

    def test_lists_changes_since_the_previous_nightly(self):
        fields = payload()["embeds"][0]["fields"]
        changes = next(
            field for field in fields
            if field["name"] == "Changes since the previous Nightly"
        )
        self.assertIn("#79", changes["value"])

    def test_splits_many_changes_without_omitting_any(self):
        changes = "\n".join(f"• change {index} " + "x" * 180 for index in range(20))
        fields = payload(["--changes", changes])["embeds"][0]["fields"]
        text = "\n".join(field["value"] for field in fields)
        self.assertGreater(len(fields), 1)
        for index in range(20):
            self.assertIn(f"change {index} ", text)

    def test_long_entries_are_shortened_but_every_change_remains(self):
        changes = "\n".join(f"• change {index} " + "x" * 900 for index in range(8))
        fields = payload(["--changes", changes])["embeds"][0]["fields"]
        text = "\n".join(field["value"] for field in fields)
        for index in range(8):
            self.assertIn(f"change {index} ", text)

    def test_impossible_change_count_fails_instead_of_hiding_entries(self):
        changes = "\n".join(f"• change {index}" for index in range(100))
        with self.assertRaisesRegex(ValueError, "too many entries"):
            payload(["--changes", changes])

    def test_unchanged_build_retains_changes_and_dates_their_last_update(self):
        embed = payload([
            "--changes-unchanged",
            "--changes-updated-at", "2026-08-13T04:00:00Z",
        ])["embeds"][0]
        self.assertIn("No code changes were added", embed["description"])
        self.assertIn("<t:1786593600:f>", embed["description"])
        self.assertIn("#79", embed["fields"][0]["value"])

    def test_omits_internal_ci_metrics(self):
        result = payload()
        names = {f["name"] for f in result["embeds"][0]["fields"]}
        self.assertNotIn("JUnit tests", names)
        self.assertNotIn("Runtime JARs", names)
        self.assertNotIn("Trigger", names)
        self.assertNotIn("Branch", names)
        self.assertNotIn("Commit", names)

    def test_native_installer_guidance_does_not_mention_zip_or_external_java(self):
        description = payload()["embeds"][0]["description"]
        self.assertIn("self-contained per-user MSI", description)
        self.assertNotIn("Extract", description)
        self.assertNotIn("Java 21", description)

    def test_respects_every_discord_length_limit(self):
        long = "x" * 6000
        result = payload([
            "--commit-message", long,
            "--version", long,
            "--repository", long,
        ])
        embed = result["embeds"][0]
        self.assertLessEqual(len(result.get("content", "")), discord_notify.CONTENT_LIMIT)
        self.assertLessEqual(len(embed["title"]), discord_notify.EMBED_TITLE_LIMIT)
        self.assertLessEqual(
            len(embed["description"]), discord_notify.EMBED_DESCRIPTION_LIMIT
        )
        self.assertLessEqual(
            len(embed["footer"]["text"]), discord_notify.EMBED_FOOTER_LIMIT
        )
        for field in embed["fields"]:
            self.assertLessEqual(len(field["name"]), discord_notify.EMBED_FIELD_NAME_LIMIT)
            self.assertLessEqual(
                len(field["value"]), discord_notify.EMBED_FIELD_VALUE_LIMIT
            )
        total = (
            len(embed["title"]) + len(embed["description"])
            + len(embed["footer"]["text"])
            + sum(len(field["name"]) + len(field["value"])
                  for field in embed["fields"])
        )
        self.assertLessEqual(total, discord_notify.EMBED_TOTAL_LIMIT)

    def test_embed_stays_within_the_ten_field_ceiling_of_a_readable_card(self):
        self.assertLessEqual(len(payload()["embeds"][0]["fields"]), 10)

    def test_payload_is_json_serialisable(self):
        json.dumps(payload())

    def test_survives_empty_step_outputs(self):
        # When the build fails early, every `steps.*.outputs.*` interpolates to
        # an empty string. int("") would crash and lose the failure notice.
        result = payload([
            "--status", "failure",
            "--bundle-bytes", "",
            "--jar-count", "",
            "--test-count", "",
            "--version", "",
            "--download-url", "",
        ])
        self.assertEqual(0xE74C3C, result["embeds"][0]["color"])
        self.assertIn("unknown", result["embeds"][0]["title"])

    def test_ignores_a_malformed_download_url(self):
        # A dead link posted to the channel is worse than no link: testers click
        # it, get a 404 and report the release as broken.
        result = payload(["--download-url", "frostguard.zip"])
        self.assertEqual("", result["content"])
        self.assertIn("workflow run", result["embeds"][0]["description"])

    def test_success_without_a_release_falls_back_to_the_run_link(self):
        # Branch builds skip the release publish, so there is no public URL.
        result = payload(["--download-url", ""])
        self.assertEqual("", result["content"])
        self.assertIn("workflow run", result["embeds"][0]["description"])


class WebhookHandlingTest(unittest.TestCase):

    def test_existing_daily_message_uses_patch(self):
        env_var = "FG_TEST_DAILY_WEBHOOK"
        os.environ[env_var] = WEBHOOK
        try:
            with mock.patch.object(discord_notify, "post") as sender:
                code = discord_notify.main(
                    BASE_ARGS + ["--webhook-env", env_var, "--message-id",
                                 "1490710978805895298"]
                )
        finally:
            del os.environ[env_var]
        self.assertEqual(0, code)
        self.assertEqual(
            f"{WEBHOOK}/messages/1490710978805895298",
            sender.call_args.args[0],
        )
        self.assertEqual("PATCH", sender.call_args.kwargs["method"])

    def test_new_daily_message_writes_its_id(self):
        env_var = "FG_TEST_DAILY_WEBHOOK"
        os.environ[env_var] = WEBHOOK
        with tempfile.NamedTemporaryFile(mode="r+", delete=False) as output:
            output_path = output.name
        try:
            with mock.patch.object(
                discord_notify,
                "post",
                return_value=b'{"id":"1533475915571527701"}',
            ) as sender:
                code = discord_notify.main(
                    BASE_ARGS + ["--webhook-env", env_var,
                                 "--message-id-output", output_path]
                )
            with open(output_path, encoding="utf-8") as output:
                written = output.read()
        finally:
            del os.environ[env_var]
            os.unlink(output_path)
        self.assertEqual(0, code)
        self.assertEqual("message_id=1533475915571527701\n", written)
        self.assertEqual(f"{WEBHOOK}?wait=true", sender.call_args.args[0])
        self.assertEqual("POST", sender.call_args.kwargs["method"])

    def test_rejects_missing_id_in_create_response(self):
        env_var = "FG_TEST_DAILY_WEBHOOK"
        os.environ[env_var] = WEBHOOK
        with tempfile.NamedTemporaryFile(delete=False) as output:
            output_path = output.name
        try:
            with mock.patch.object(discord_notify, "post", return_value=b'{}'):
                code = discord_notify.main(
                    BASE_ARGS + ["--webhook-env", env_var,
                                 "--message-id-output", output_path]
                )
        finally:
            del os.environ[env_var]
            os.unlink(output_path)
        self.assertEqual(1, code)

    def test_rejects_non_numeric_daily_message_id(self):
        env_var = "FG_TEST_DAILY_WEBHOOK"
        os.environ[env_var] = WEBHOOK
        try:
            code = discord_notify.main(
                BASE_ARGS + ["--webhook-env", env_var, "--message-id", "bad"]
            )
        finally:
            del os.environ[env_var]
        self.assertEqual(1, code)

    def test_missing_secret_warns_but_does_not_fail_the_build(self):
        # A build that produced a good artifact must not be marked failed just
        # because the channel notification could not be addressed.
        code = discord_notify.main(BASE_ARGS + ["--webhook-env", "FG_ABSENT_VAR"])
        self.assertEqual(0, code)

    def test_rejects_a_secret_that_is_not_a_discord_webhook(self):
        os.environ["FG_TEST_WEBHOOK"] = "https://example.com/not-a-webhook"
        try:
            code = discord_notify.main(
                BASE_ARGS + ["--webhook-env", "FG_TEST_WEBHOOK"]
            )
        finally:
            del os.environ["FG_TEST_WEBHOOK"]
        self.assertEqual(1, code)

    def test_redacts_the_webhook_token_from_diagnostics(self):
        # Actions logs are public on a public repository; a leaked webhook lets
        # anyone post to the Discord channel.
        message = discord_notify.redact(f"POST {WEBHOOK} failed")
        self.assertNotIn("abcdefTOKEN-value_x", message)
        self.assertIn("<redacted>", message)

    def test_honours_the_rate_limit_hint_from_the_response_body(self):
        error = urllib.error.HTTPError(WEBHOOK, 429, "Too Many Requests", {}, None)
        delay = discord_notify.retry_after_seconds(error, '{"retry_after": 3.5}')
        self.assertEqual(3.5, delay)

    def test_clamps_an_absurd_rate_limit_hint(self):
        error = urllib.error.HTTPError(WEBHOOK, 429, "Too Many Requests", {}, None)
        self.assertEqual(
            60.0, discord_notify.retry_after_seconds(error, '{"retry_after": 99999}')
        )


class AttachmentTest(unittest.TestCase):

    def test_small_file_is_uploaded_as_multipart(self):
        with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as handle:
            handle.write(b"small payload")
        body, content_type = discord_notify.encode_multipart(payload(), handle.name)
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))
        self.assertIn(b'name="payload_json"', body)
        self.assertIn(b'name="files[0]"', body)
        self.assertIn(b"small payload", body)

    def test_the_upload_ceiling_is_below_discords_webhook_limit(self):
        # A ~220 MB bundle must never be attempted as an attachment: the failed
        # upload would swallow the notification entirely.
        self.assertLessEqual(discord_notify.ATTACHMENT_LIMIT_BYTES, 8 * 1024 * 1024)


class LenientIntTest(unittest.TestCase):

    def test_parses_numbers_and_absorbs_junk(self):
        self.assertEqual(76, discord_notify.lenient_int("76"))
        self.assertEqual(76, discord_notify.lenient_int(" 76 "))
        self.assertEqual(0, discord_notify.lenient_int(""))
        self.assertEqual(0, discord_notify.lenient_int("not-a-number"))
        self.assertEqual(0, discord_notify.lenient_int(None))


class HumanSizeTest(unittest.TestCase):

    def test_formats_common_magnitudes(self):
        self.assertEqual("unknown", discord_notify.human_size(0))
        self.assertEqual("512 B", discord_notify.human_size(512))
        self.assertEqual("1.0 KB", discord_notify.human_size(1024))
        self.assertEqual("1.0 MB", discord_notify.human_size(1024 * 1024))
        self.assertEqual("2.0 GB", discord_notify.human_size(2 * 1024**3))


if __name__ == "__main__":
    unittest.main(verbosity=2)
