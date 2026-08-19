#!/usr/bin/env python3

from __future__ import annotations

import unittest
from unittest.mock import patch

import windows_pr_channels


class WindowsPrChannelsTest(unittest.TestCase):
    def test_ordinary_task_change_needs_only_stable(self):
        self.assertFalse(windows_pr_channels.requires_nightly([
            "modules/tasks/src/main/java/dev/frostguard/tasks/city/ResearchRoutine.java",
            "modules/tasks/src/test/java/dev/frostguard/tasks/city/ResearchRoutineTest.java",
        ]))

    def test_custom_task_example_needs_only_stable(self):
        self.assertFalse(windows_pr_channels.requires_nightly([
            "examples/custom-tasks/bg_telemetry.java",
        ]))

    def test_channel_and_packaging_paths_need_nightly(self):
        sensitive_paths = (
            "pom.xml",
            "modules/desktop/pom.xml",
            "packaging/desktop/pom.xml",
            "modules/update/src/main/java/dev/frostguard/update/UpdateSelector.java",
            "modules/desktop/src/main/java/dev/frostguard/app/RuntimeVersion.java",
            "modules/api/src/main/java/dev/frostguard/api/runtime/WorkspacePaths.java",
            "build-support/release/windows_installer_version.py",
            "build-support/verification/verify_app_image.py",
            "tools/windows/Frostguard.exe",
            ".github/workflows/windows-native-package.yml",
            ".github/workflows/signed-windows-channel-release.yml",
        )
        for path in sensitive_paths:
            with self.subTest(path=path):
                self.assertTrue(windows_pr_channels.requires_nightly([path]))

    @patch("windows_pr_channels.changed_paths")
    def test_pull_request_uses_changed_paths(self, changed_paths):
        changed_paths.return_value = ["packaging/desktop/pom.xml"]

        nightly, paths = windows_pr_channels.select_nightly(
            "pull_request", "base", "head")

        self.assertTrue(nightly)
        self.assertEqual(["packaging/desktop/pom.xml"], paths)
        changed_paths.assert_called_once_with("base", "head")

    @patch("windows_pr_channels.changed_paths")
    def test_manual_run_always_builds_both_channels(self, changed_paths):
        nightly, paths = windows_pr_channels.select_nightly(
            "workflow_dispatch", "", "")

        self.assertTrue(nightly)
        self.assertEqual([], paths)
        changed_paths.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
