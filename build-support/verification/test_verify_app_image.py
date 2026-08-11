#!/usr/bin/env python3

from __future__ import annotations

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_app_image  # noqa: E402


class VerifyAppImageTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.image = Path(self.temp.name) / "Frostguard"
        files = list(verify_app_image.STATIC_REQUIRED_FILES) + [
            "Frostguard.exe",
            "FrostguardWatcher.exe",
            "app/Frostguard.cfg",
            "app/FrostguardWatcher.cfg",
            "app/frostguard-desktop-3.0.0.jar",
            "app/frostguard-watcher-3.0.0.jar",
            "app/lib/frostguard-update-3.0.0.jar",
            "app/lib/opencv-4.9.0.jar",
            "app/lib/tess4j-5.14.0.jar",
            "app/lib/javafx-graphics-23.0.1-win.jar",
            "app/templates/home/world.png",
            "app/custom_tasks/shield.java",
        ] + [f"app/lib/runtime-{index}.jar" for index in range(60)]
        for relative in files:
            path = self.image / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"payload")
        with zipfile.ZipFile(self.image / "app/frostguard-desktop-3.0.0.jar", "w") as desktop_jar:
            desktop_jar.writestr(
                verify_app_image.BUILD_METADATA,
                "pullRequestBuild=false\nauthenticodePublisher=CN=Frostguard Project, O=Frostguard\n",
            )
        with zipfile.ZipFile(self.image / "app/lib/frostguard-update-3.0.0.jar", "w") as update_jar:
            update_jar.writestr(
                verify_app_image.UPDATE_KEY,
                verify_app_image.REPO_UPDATE_KEY.read_text(encoding="utf-8"),
            )
        common_options = "\n".join((
            "java-options=-Dfrostguard.channel=stable",
            "java-options=-Dfrostguard.application.id=dev.frostguard.desktop",
            "java-options=-Duser.dir=$APPDIR",
            "java-options=-Dfrostguard.watcher.launcher=$APPDIR/../FrostguardWatcher.exe",
        )) + (
            "\njava-options=-Dfrostguard.update.pullRequestBuild=false\n"
        )
        (self.image / "app/Frostguard.cfg").write_text(
            "app.mainclass=dev.frostguard.app.bootstrap.Main\n"
            "java-options=-Dfrostguard.update.manifest.stable=\n"
            "java-options=-Dfrostguard.update.manifest.nightly=\n" + common_options,
            encoding="utf-8")
        (self.image / "app/FrostguardWatcher.cfg").write_text(
            "app.mainclass=dev.frostguard.watcher.TelegramWatcher\n"
            "java-options=-Dfrostguard.launcher=$APPDIR/../Frostguard.exe\n" + common_options,
            encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_accepts_complete_image(self):
        self.assertEqual([], verify_app_image.inspect_image(self.image))

    def test_rejects_missing_bundled_runtime(self):
        (self.image / "runtime/bin/server/jvm.dll").unlink()
        self.assertTrue(any("jvm.dll" in item for item in verify_app_image.inspect_image(self.image)))

    def test_rejects_development_channel_launcher(self):
        config = self.image / "app/Frostguard.cfg"
        config.write_text(config.read_text().replace("channel=stable", "channel=development"))
        self.assertTrue(any("channel=stable" in item for item in verify_app_image.inspect_image(self.image)))

    def test_rejects_watcher_without_stable_channel(self):
        config = self.image / "app/FrostguardWatcher.cfg"
        config.write_text(config.read_text().replace("channel=stable", "channel=development"))
        self.assertTrue(any("FrostguardWatcher.cfg" in item
                            for item in verify_app_image.inspect_image(self.image)))

    def test_rejects_launcher_without_pr_build_identity(self):
        config = self.image / "app/Frostguard.cfg"
        config.write_text(config.read_text().replace(
            "java-options=-Dfrostguard.update.pullRequestBuild=false\n", ""))
        self.assertTrue(any("PR-build update identity" in item
                            for item in verify_app_image.inspect_image(self.image)))

    def test_rejects_launcher_identity_that_disagrees_with_desktop_jar(self):
        config = self.image / "app/Frostguard.cfg"
        config.write_text(config.read_text().replace(
            "frostguard.update.pullRequestBuild=false",
            "frostguard.update.pullRequestBuild=true"))
        self.assertTrue(any("does not match the desktop JAR" in item
                            for item in verify_app_image.inspect_image(self.image)))

    def test_rejects_desktop_jar_without_embedded_identity(self):
        (self.image / "app/frostguard-desktop-3.0.0.jar").write_bytes(b"not a jar")
        self.assertTrue(any("no valid embedded PR-build" in item
                            for item in verify_app_image.inspect_image(self.image)))

    def test_rejects_update_jar_without_project_key(self):
        with zipfile.ZipFile(
                self.image / "app/lib/frostguard-update-3.0.0.jar", "w"
        ) as update_jar:
            update_jar.writestr("placeholder", "missing key")
        self.assertTrue(any("project update key" in item
                            for item in verify_app_image.inspect_image(self.image)))

    def test_rejects_update_jar_with_unexpected_project_key(self):
        with zipfile.ZipFile(
                self.image / "app/lib/frostguard-update-3.0.0.jar", "w"
        ) as update_jar:
            update_jar.writestr(
                verify_app_image.UPDATE_KEY,
                "keyId=untrusted\npublicKey=MCowBQYDK2VwAyEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n",
            )
        self.assertTrue(any("unexpected project update key" in item
                            for item in verify_app_image.inspect_image(self.image)))

    def test_rejects_runtime_data(self):
        leaked = self.image / "app/logs/frostguard.log"
        leaked.parent.mkdir(parents=True)
        leaked.write_text("private runtime log")
        self.assertTrue(any("leaked" in item for item in verify_app_image.inspect_image(self.image)))

    def test_accepts_nightly_identity_and_rejects_stable_expectations(self):
        (self.image / "Frostguard.exe").rename(self.image / "Frostguard Nightly.exe")
        (self.image / "FrostguardWatcher.exe").rename(
            self.image / "FrostguardNightlyWatcher.exe")
        stable_config = self.image / "app/Frostguard.cfg"
        nightly_config = self.image / "app/Frostguard Nightly.cfg"
        stable_config.rename(nightly_config)
        stable_watcher_config = self.image / "app/FrostguardWatcher.cfg"
        nightly_watcher_config = self.image / "app/FrostguardNightlyWatcher.cfg"
        stable_watcher_config.rename(nightly_watcher_config)
        for config in (nightly_config, nightly_watcher_config):
            config.write_text(config.read_text().replace(
                "channel=stable", "channel=nightly").replace(
                "application.id=dev.frostguard.desktop", "application.id=dev.frostguard.desktop.nightly").replace(
                "../Frostguard.exe", "../Frostguard Nightly.exe").replace(
                "../FrostguardWatcher.exe", "../FrostguardNightlyWatcher.exe"), encoding="utf-8")

        self.assertEqual([], verify_app_image.inspect_image(
            self.image, "nightly", "Frostguard Nightly"))
        self.assertTrue(verify_app_image.inspect_image(self.image))


if __name__ == "__main__":
    unittest.main(verbosity=2)
