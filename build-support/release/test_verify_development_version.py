#!/usr/bin/env python3

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from verify_development_version import declared_revision, latest_stable_tag, validate


class VerifyDevelopmentVersionTest(unittest.TestCase):
    def test_reads_declared_maven_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / "pom.xml"
            pom.write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                "<properties><revision>3.0.0</revision></properties></project>",
                encoding="utf-8",
            )
            self.assertEqual("3.0.0", declared_revision(pom))

    def test_selects_only_highest_stable_semantic_tag(self):
        self.assertEqual(
            "v3.1.0",
            latest_stable_tag([
                "v2.1.0",
                "v3.0.0-nightly.20260817.5",
                "v3.1.0",
                "updates-nightly",
            ]),
        )

    def test_rejects_revision_older_than_latest_stable(self):
        with self.assertRaisesRegex(ValueError, "older than latest Stable"):
            validate("2.1.0", "v3.0.0")

    def test_rejects_requested_stable_that_differs_from_revision(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            validate("3.0.0", "v3.0.0", "3.1.0")

    def test_accepts_current_or_future_baseline(self):
        validate("3.0.0", "v3.0.0", "3.0.0")
        validate("3.1.0", "v3.0.0")


if __name__ == "__main__":
    unittest.main(verbosity=2)
