#!/usr/bin/env python3
"""Self-tests for build-support/verification/verify_bundle.py.

A verification script that cannot fail is worse than no verification at all: it
turns a broken release artifact into a green check mark. These tests build
synthetic bundles in memory and assert that each guard really rejects the
regression it is meant to catch, and that a well-formed bundle passes.

Run with:  python3 build-support/verification/test_verify_bundle.py
"""

from __future__ import annotations

import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_bundle  # noqa: E402

VERSION = "9.9.9"
JAVAFX_MODULES = ("base", "controls", "fxml", "graphics")

# A realistic set of runtime JARs, padded past the staging floor.
RUNTIME_JARS = (
    [f"lib/javafx-{m}-23.0.1-win.jar" for m in JAVAFX_MODULES]
    + [
        "lib/opencv-4.9.0-0.jar",
        "lib/tess4j-5.14.0.jar",
        "lib/frostguard-vision-9.9.9.jar",
        "lib/frostguard-automation-9.9.9.jar",
    ]
    + [f"lib/filler-{i}.jar" for i in range(60)]
)

OTHER_FILES = [
    "Start Frostguard.bat",
    "fg-watcher.bat",
    f"frostguard-watcher-{VERSION}.jar",
    "lib/adb/adb.exe",
    "lib/adb/AdbWinApi.dll",
    "lib/adb/AdbWinUsbApi.dll",
    "lib/tesseract/eng.traineddata",
    "lib/tesseract/osd.traineddata",
    "lib/tesseract/chi_sim.traineddata",
    "docs/README.md",
    "docs/PRIVACY.md",
    "templates/city/cityIcon.png",
    "custom_tasks/shield.java",
]


def build_launcher_jar(class_path: list[str]) -> bytes:
    """Build an app JAR whose manifest Class-Path is folded like Maven does."""
    header = "Class-Path: " + " ".join(class_path)
    folded: list[str] = []
    remaining = header
    # A JAR manifest line is limited to 72 bytes; continuations start with a space.
    while len(remaining) > 70:
        folded.append(remaining[:70])
        remaining = " " + remaining[70:]
    folded.append(remaining)

    manifest = "Manifest-Version: 1.0\r\n"
    manifest += "Main-Class: dev.frostguard.app.bootstrap.Main\r\n"
    manifest += "\r\n".join(folded) + "\r\n\r\n"

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF", manifest)
        jar.writestr("dev/frostguard/app/bootstrap/Main.class", b"\xca\xfe\xba\xbe")
    return buffer.getvalue()


def build_bundle(
    *,
    runtime_jars: list[str] | None = None,
    other_files: list[str] | None = None,
    class_path: list[str] | None = None,
    include_launcher: bool = True,
) -> str:
    """Write a synthetic bundle ZIP to a temp file and return its path."""
    runtime_jars = RUNTIME_JARS if runtime_jars is None else runtime_jars
    other_files = OTHER_FILES if other_files is None else other_files
    class_path = runtime_jars if class_path is None else class_path

    handle = tempfile.NamedTemporaryFile(suffix=".zip", delete=False)
    with zipfile.ZipFile(handle, "w") as bundle:
        # Directory entries, which is what the old grep-based checks matched on.
        for directory in ("lib/", "lib/adb/", "templates/", "custom_tasks/", "docs/"):
            bundle.writestr(directory, b"")
        for name in runtime_jars + other_files:
            bundle.writestr(name, b"payload")
        if include_launcher:
            bundle.writestr(
                f"frostguard-desktop-{VERSION}.jar", build_launcher_jar(class_path)
            )
    handle.close()
    return handle.name


def run(bundle_path: str, platform: str = "win") -> int:
    return verify_bundle.main([bundle_path, "--platform", platform])


class VerifyBundleTest(unittest.TestCase):

    def test_accepts_a_well_formed_windows_bundle(self):
        self.assertEqual(0, run(build_bundle()))

    def test_rejects_bundle_assembled_before_dependencies_were_staged(self):
        # The exact regression that shipped a 17 MB ZIP with an empty lib/.
        self.assertEqual(1, run(build_bundle(runtime_jars=[], class_path=[])))

    def test_rejects_manifest_reference_to_a_jar_missing_from_the_bundle(self):
        bundle = build_bundle(class_path=RUNTIME_JARS + ["lib/ghost-1.0.jar"])
        self.assertEqual(1, run(bundle))

    def test_does_not_accept_a_similarly_named_jar_as_a_substitute(self):
        # Guards the substring false-pass of the previous grep-based verifier:
        # lib/ghost-1.0.jar must NOT be satisfied by lib/ghost-1.0.jar.disabled.
        jars = RUNTIME_JARS + ["lib/ghost-1.0.jar.disabled"]
        bundle = build_bundle(
            runtime_jars=jars, class_path=RUNTIME_JARS + ["lib/ghost-1.0.jar"]
        )
        self.assertEqual(1, run(bundle))

    def test_rejects_bundle_missing_the_windows_javafx_runtime(self):
        jars = [j for j in RUNTIME_JARS if "javafx-graphics" not in j]
        self.assertEqual(1, run(build_bundle(runtime_jars=jars, class_path=jars)))

    def test_rejects_linux_javafx_runtime_leaking_into_a_windows_bundle(self):
        jars = RUNTIME_JARS + ["lib/javafx-graphics-23.0.1-linux.jar"]
        self.assertEqual(1, run(build_bundle(runtime_jars=jars, class_path=jars)))

    def test_rejects_bundle_without_the_bundled_adb_binaries(self):
        files = [f for f in OTHER_FILES if not f.startswith("lib/adb/")]
        self.assertEqual(1, run(build_bundle(other_files=files)))

    def test_rejects_bundle_without_the_ocr_models(self):
        files = [f for f in OTHER_FILES if not f.endswith(".traineddata")]
        self.assertEqual(1, run(build_bundle(other_files=files)))

    def test_rejects_bundle_without_the_watcher_launcher(self):
        files = [f for f in OTHER_FILES if f != "fg-watcher.bat"]
        self.assertEqual(1, run(build_bundle(other_files=files)))

    def test_rejects_bundle_without_the_app_launcher(self):
        files = [f for f in OTHER_FILES if f != "Start Frostguard.bat"]
        self.assertEqual(1, run(build_bundle(other_files=files)))

    def test_rejects_empty_templates_directory(self):
        # A bare `templates/` directory entry used to satisfy the old check even
        # when every sprite was missing.
        files = [f for f in OTHER_FILES if not f.startswith("templates/")]
        self.assertEqual(1, run(build_bundle(other_files=files)))

    def test_rejects_empty_custom_tasks_directory(self):
        files = [f for f in OTHER_FILES if not f.startswith("custom_tasks/")]
        self.assertEqual(1, run(build_bundle(other_files=files)))

    def test_rejects_bundle_without_a_launcher_jar(self):
        self.assertEqual(1, run(build_bundle(include_launcher=False)))

    def test_rejects_a_file_that_is_not_a_zip(self):
        with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as handle:
            handle.write(b"this is not a zip archive")
        self.assertEqual(1, run(handle.name))

    def test_unfolds_manifest_continuation_lines(self):
        entries = verify_bundle.class_path_entries(
            "Manifest-Version: 1.0\r\nClass-Path: lib/a.jar lib/bb\r\n b.jar\r\n\r\n"
        )
        self.assertEqual(["lib/a.jar", "lib/bbb.jar"], entries)


if __name__ == "__main__":
    unittest.main(verbosity=2)
