#!/usr/bin/env python3
from __future__ import annotations

import unittest
import os
from unittest import mock

import stable_release_notify as notify

WEBHOOK = "https://discord.com/api/webhooks/123456789/abcdefTOKEN-value_x"


class StablePayloadTest(unittest.TestCase):
    def args(self):
        return notify.parse_args([
            "--version", "2.1.0",
            "--download-url", "https://github.com/Shederator/wosbot/releases/latest/download/frostguard-windows-desktop-bundle.zip",
            "--release-url", "https://github.com/Shederator/wosbot/releases/tag/v2.1.0",
            "--archive-url", "https://github.com/Shederator/wosbot/releases",
            "--message-id", "1533506274472235099",
            "--dry-run",
        ])

    def test_does_not_ping_anyone(self):
        payload = notify.build_payload(self.args())
        self.assertEqual(payload["content"], "")
        self.assertEqual(payload["allowed_mentions"], {"parse": []})

    def test_contains_only_stable_release_facts(self):
        text = str(notify.build_payload(self.args()))
        self.assertIn("2.1.0", text)
        self.assertIn("included Frostguard launcher", text)
        self.assertIn("releases/latest/download", text)
        self.assertIn("Previous stable releases", text)
        self.assertNotIn("recommended", text.lower())
        self.assertNotIn("commit", text.lower())

    def test_names_the_maintained_stable_download(self):
        title = notify.build_payload(self.args())["embeds"][0]["title"]
        self.assertEqual("✅ Frostguard Stable 2.1.0", title)

    def test_existing_stable_message_uses_patch(self):
        os.environ["FG_STABLE_TEST_WEBHOOK"] = WEBHOOK
        try:
            with mock.patch.object(notify, "post") as sender:
                code = notify.main([
                    "--version", "2.1.0",
                    "--download-url", "https://github.com/a/releases/latest/download/a.zip",
                    "--release-url", "https://github.com/a/releases/tag/v2.1.0",
                    "--archive-url", "https://github.com/a/releases",
                    "--webhook-env", "FG_STABLE_TEST_WEBHOOK",
                    "--message-id", "1533506274472235099",
                ])
        finally:
            del os.environ["FG_STABLE_TEST_WEBHOOK"]
        self.assertEqual(0, code)
        self.assertEqual(
            f"{WEBHOOK}/messages/1533506274472235099",
            sender.call_args.args[0],
        )
        self.assertEqual("PATCH", sender.call_args.kwargs["method"])

    def test_rejects_non_semantic_version(self):
        argv = [
            "--version", "nightly", "--download-url", "https://github.com/a",
            "--release-url", "https://github.com/b", "--dry-run",
            "--archive-url", "https://github.com/a/releases",
            "--message-id", "1533506274472235099",
        ]
        self.assertEqual(1, notify.main(argv))


if __name__ == "__main__":
    unittest.main(verbosity=2)
