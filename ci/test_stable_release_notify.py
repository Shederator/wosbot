#!/usr/bin/env python3
from __future__ import annotations

import unittest

import stable_release_notify as notify


class StablePayloadTest(unittest.TestCase):
    def args(self):
        return notify.parse_args([
            "--version", "2.1.0",
            "--download-url", "https://github.com/Shederator/wosbot/releases/latest/download/frostguard-windows-desktop-bundle.zip",
            "--release-url", "https://github.com/Shederator/wosbot/releases/tag/v2.1.0",
            "--dry-run",
        ])

    def test_mentions_everyone_explicitly(self):
        payload = notify.build_payload(self.args())
        self.assertEqual(payload["content"], "@everyone")
        self.assertEqual(payload["allowed_mentions"], {"parse": ["everyone"]})

    def test_contains_only_stable_release_facts(self):
        text = str(notify.build_payload(self.args()))
        self.assertIn("2.1.0", text)
        self.assertIn("included Frostguard launcher", text)
        self.assertIn("releases/latest/download", text)
        self.assertNotIn("commit", text.lower())

    def test_rejects_non_semantic_version(self):
        argv = [
            "--version", "nightly", "--download-url", "https://github.com/a",
            "--release-url", "https://github.com/b", "--dry-run",
        ]
        self.assertEqual(1, notify.main(argv))


if __name__ == "__main__":
    unittest.main(verbosity=2)
