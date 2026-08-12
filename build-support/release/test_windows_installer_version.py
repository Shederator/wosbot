#!/usr/bin/env python3

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from windows_installer_version import (  # noqa: E402
    require_newer_version,
    stable_candidate_windows_version,
    windows_installer_version,
)


class WindowsInstallerVersionTest(unittest.TestCase):
    def test_preserves_valid_stable_version(self):
        self.assertEqual("3.0.1", windows_installer_version("stable", "3.0.1"))

    def test_maps_nightly_date_and_sequence_to_monotonic_windows_fields(self):
        self.assertEqual("26.8.11001", windows_installer_version(
            "nightly", "3.1.0-nightly.20260811.1"))
        self.assertEqual("26.8.11002", windows_installer_version(
            "nightly", "3.1.0-nightly.20260811.2"))
        self.assertEqual("26.9.1001", windows_installer_version(
            "nightly", "3.1.0-nightly.20260901.1"))

    def test_rejects_ambiguous_or_invalid_release_versions(self):
        for channel, version in (
                ("nightly", "3.1.0-nightly.1"),
                ("nightly", "3.1.0-nightly.20260230.1"),
                ("nightly", "3.1.0-nightly.20260811.1000"),
                ("nightly", "3.1.0-nightly.19990811.1"),
                ("stable", "256.0.0"),
                ("stable", "3.0.0-nightly.20260811.1")):
            with self.subTest(channel=channel, version=version):
                with self.assertRaises(ValueError):
                    windows_installer_version(channel, version)

    def test_requires_each_channel_installer_version_to_increase(self):
        require_newer_version("stable", "3.0.2", "3.0.1")
        require_newer_version(
            "nightly", "3.1.0-nightly.20260812.1", "3.1.0-nightly.20260811.2")
        with self.assertRaises(ValueError):
            require_newer_version("stable", "3.0.1", "3.0.1")
        with self.assertRaises(ValueError):
            require_newer_version(
                "nightly", "3.1.0-nightly.20260811.1", "3.1.0-nightly.20260811.2")
        with self.assertRaises(ValueError):
            require_newer_version(
                "nightly", "3.0.0-nightly.20260812.1", "3.1.0-nightly.20260811.1")

    def test_accepts_numeric_stable_candidate_version_below_final_release(self):
        self.assertEqual("2.99.1", stable_candidate_windows_version(
            "3.0.0-rc.1", "2.99.1"))

    def test_rejects_invalid_or_non_upgradable_stable_candidate_versions(self):
        for version, windows_version in (
                ("3.0.0", "2.99.1"),
                ("3.0.0-01", "2.99.1"),
                ("3.0.0-rc.1", "3.0.0"),
                ("3.0.0-rc.1", "2.256.1"),
                ("3.0.0-rc.1", "2.99")):
            with self.subTest(version=version, windows_version=windows_version):
                with self.assertRaises(ValueError):
                    stable_candidate_windows_version(version, windows_version)


if __name__ == "__main__":
    unittest.main(verbosity=2)
