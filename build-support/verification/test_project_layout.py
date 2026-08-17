#!/usr/bin/env python3
"""Repository-boundary checks for the Frostguard 3.0 project layout."""

from __future__ import annotations

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}

MODULES = {
    "modules/api": "frostguard-api",
    "modules/persistence": "frostguard-persistence",
    "modules/vision": "frostguard-vision",
    "modules/automation": "frostguard-automation",
    "modules/tasks": "frostguard-tasks",
    "modules/watcher": "frostguard-watcher",
    "modules/update": "frostguard-update",
    "modules/desktop": "frostguard-desktop",
    "packaging/desktop": "frostguard-desktop-package",
}


class ProjectLayoutTest(unittest.TestCase):

    @staticmethod
    def plugin(root, group_id, artifact_id):
        for plugin in root.findall("m:build/m:plugins/m:plugin", MAVEN_NAMESPACE):
            group = plugin.find("m:groupId", MAVEN_NAMESPACE)
            artifact = plugin.find("m:artifactId", MAVEN_NAMESPACE)
            if group is not None and artifact is not None:
                if group.text == group_id and artifact.text == artifact_id:
                    return plugin
        return None

    def test_reactor_uses_canonical_module_paths(self):
        root = ET.parse(REPO_ROOT / "pom.xml").getroot()
        actual = [node.text for node in root.findall("m:modules/m:module", MAVEN_NAMESPACE)]

        self.assertEqual(list(MODULES), actual)

    def test_modules_use_canonical_artifact_ids(self):
        for module, expected_artifact in MODULES.items():
            with self.subTest(module=module):
                pom = REPO_ROOT / module / "pom.xml"
                self.assertTrue(pom.is_file(), f"Missing module POM: {pom}")
                root = ET.parse(pom).getroot()
                artifact = root.find("m:artifactId", MAVEN_NAMESPACE)
                self.assertIsNotNone(artifact)
                self.assertEqual(expected_artifact, artifact.text)

    def test_support_directories_have_single_canonical_locations(self):
        for path in (
            "build-support/verification",
            "build-support/release",
            "build-support/notifications",
            "examples/custom-tasks",
            ".github/workflows",
        ):
            self.assertTrue((REPO_ROOT / path).is_dir(), f"Missing directory: {path}")

        for path in ("ci", "setup/github-workflows", "custom_tasks"):
            self.assertFalse((REPO_ROOT / path).exists(), f"Legacy directory remains: {path}")

    def test_root_has_no_product_launcher_or_build_scripts(self):
        for name in ("Start Frostguard.bat", "fg-build.bat", "fg-watcher.bat"):
            self.assertFalse((REPO_ROOT / name).exists(), f"Legacy root script remains: {name}")

    def test_wrapper_pins_maven_distribution_with_checksum(self):
        properties = {}
        wrapper = REPO_ROOT / ".mvn/wrapper/maven-wrapper.properties"
        for line in wrapper.read_text(encoding="utf-8").splitlines():
            key, value = line.split("=", 1)
            properties[key] = value

        self.assertIn("apache-maven-3.9.16-bin.zip", properties["distributionUrl"])
        self.assertRegex(properties["distributionSha256Sum"], r"^[0-9a-f]{64}$")

    def test_root_javafx_goal_runs_only_desktop_module(self):
        root = ET.parse(REPO_ROOT / "pom.xml").getroot()
        root_plugin = self.plugin(root, "org.openjfx", "javafx-maven-plugin")
        self.assertIsNotNone(root_plugin)
        self.assertEqual(
            "true",
            root_plugin.find("m:configuration/m:skip", MAVEN_NAMESPACE).text,
        )
        self.assertEqual(
            "dev.frostguard.app.bootstrap.Main",
            root_plugin.find("m:configuration/m:mainClass", MAVEN_NAMESPACE).text,
        )

        desktop = ET.parse(REPO_ROOT / "modules/desktop/pom.xml").getroot()
        desktop_plugin = self.plugin(desktop, "org.openjfx", "javafx-maven-plugin")
        self.assertIsNotNone(desktop_plugin)
        self.assertEqual(
            "false",
            desktop_plugin.find("m:configuration/m:skip", MAVEN_NAMESPACE).text,
        )
        self.assertEqual(
            "dev.frostguard.app.bootstrap.Main",
            desktop_plugin.find("m:configuration/m:mainClass", MAVEN_NAMESPACE).text,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
