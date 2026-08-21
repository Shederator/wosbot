#!/usr/bin/env python3
"""Verify the contents of a Frostguard desktop-bundle ZIP.

The packaged application starts through the manifest ``Class-Path`` of
``frostguard-desktop-<version>.jar``, so a runtime JAR that is referenced but missing
from the distribution ZIP only fails once a user double-clicks the launcher.
The same applies to the bundled ``adb`` binaries, the Tesseract OCR models and
the template sprites: none of them are touched during the build, so a staging
regression stays invisible until somebody runs the bot.

This script reads the ZIP central directory directly instead of parsing
``unzip -l`` output. Entry names are therefore compared exactly, which avoids
the substring false-pass where ``lib/foo.jar`` would appear to be satisfied by
an unrelated ``lib/foo.jar.disabled``.

Usage:
    verify_bundle.py <bundle.zip> [--platform win]
"""

from __future__ import annotations

import argparse
import posixpath
import re
import sys
import zipfile

# A clean build stages every runtime dependency into lib/. The real number is 76
# at the time of writing; the floor only has to be high enough to catch a ZIP
# that was assembled before the dependencies were copied (which yielded zero).
MINIMUM_RUNTIME_JARS = 50

# Exact entries that must be present at these exact paths.
REQUIRED_FILES = [
    "Start Frostguard.bat",
    "fg-watcher.bat",
    "lib/adb/adb.exe",
    "lib/adb/AdbWinApi.dll",
    "lib/adb/AdbWinUsbApi.dll",
    "lib/tesseract/eng.traineddata",
    "lib/tesseract/osd.traineddata",
    "lib/tesseract/chi_sim.traineddata",
    "docs/README.md",
    "docs/PRIVACY.md",
    "custom_tasks/README.md",
    "custom_tasks/dead_shot.json",
    "custom_tasks/dead_shot.txt",
    "custom_tasks/expert_idle_exploration.json",
    "custom_tasks/expert_idle_exploration.txt",
    "custom_tasks/shield.java",
    "custom_tasks/templates/deals/deadshot/event_tab.png",
]

# Regular expressions that must each match at least one entry name.
REQUIRED_PATTERNS = [
    (r"^frostguard-desktop-[^/]+\.jar$", "packaged desktop application JAR"),
    (r"^frostguard-watcher-[^/]+\.jar$", "shaded Telegram watcher JAR"),
    (r"^lib/opencv-[^/]+\.jar$", "OpenCV Java bindings"),
    (r"^lib/tess4j-[^/]+\.jar$", "tess4j OCR bindings"),
    (r"^lib/frostguard-vision-[^/]+\.jar$", "Frostguard vision module"),
    (r"^templates/.+\.png$", "template sprites for the Image Recognition tool"),
    (r"^custom_tasks/.+$", "user-editable custom task scripts"),
]

# JavaFX is platform-classified. The bundle has to carry the runtime for the
# platform it is built for, and must not leak any other platform's runtime.
JAVAFX_MODULES = ("base", "controls", "fxml", "graphics")
JAVAFX_PLATFORMS = ("win", "linux", "mac", "mac-aarch64")


def fail(message: str) -> None:
    """Emit a GitHub Actions error annotation."""
    print(f"::error::{message}")


def unfold_manifest(text: str) -> list[str]:
    """Join JAR-manifest continuation lines (those starting with a single space)."""
    lines: list[str] = []
    for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if line.startswith(" ") and lines:
            lines[-1] += line[1:]
        else:
            lines.append(line)
    return lines


def class_path_entries(manifest_text: str) -> list[str]:
    for line in unfold_manifest(manifest_text):
        if line.startswith("Class-Path:"):
            return line[len("Class-Path:"):].split()
    return []


def check_required_files(names: set[str]) -> list[str]:
    return [
        f"Bundle is missing a required file: {path}"
        for path in REQUIRED_FILES
        if path not in names
    ]


def check_required_patterns(names: set[str]) -> list[str]:
    problems = []
    for pattern, description in REQUIRED_PATTERNS:
        matcher = re.compile(pattern)
        if not any(matcher.match(name) for name in names):
            problems.append(
                f"Bundle has no entry matching {pattern} ({description})."
            )
    return problems


def check_javafx_platform(names: set[str], platform: str) -> list[str]:
    """Assert the wanted JavaFX classifier is present and no other one leaked."""
    problems = []
    for module in JAVAFX_MODULES:
        wanted = re.compile(rf"^lib/javafx-{module}-[^/]+-{re.escape(platform)}\.jar$")
        if not any(wanted.match(name) for name in names):
            problems.append(
                f"Bundle is missing the {platform} JavaFX runtime for "
                f"javafx-{module}; the app cannot start on {platform}."
            )

    for foreign in JAVAFX_PLATFORMS:
        if foreign == platform:
            continue
        leaked = re.compile(rf"^lib/javafx-[^/]+-{re.escape(foreign)}\.jar$")
        offenders = sorted(name for name in names if leaked.match(name))
        if offenders:
            problems.append(
                f"Bundle contains {foreign} JavaFX runtime JARs in a "
                f"{platform} bundle: {', '.join(offenders)}"
            )
    return problems


def check_runtime_jar_floor(names: set[str]) -> tuple[list[str], int]:
    """Guard the regression where the ZIP was built before lib/ was staged."""
    jars = [
        name for name in names
        if name.startswith("lib/")
        and name.endswith(".jar")
        and "/" not in name[len("lib/"):]
    ]
    if len(jars) < MINIMUM_RUNTIME_JARS:
        return (
            [
                f"Only {len(jars)} runtime JARs found under lib/ "
                f"(expected at least {MINIMUM_RUNTIME_JARS}); the distribution "
                "ZIP looks like it was assembled before the dependencies were "
                "staged, so the launcher would fail with NoClassDefFoundError."
            ],
            len(jars),
        )
    return [], len(jars)


def check_manifest_class_path(bundle: zipfile.ZipFile, names: set[str]) -> tuple[list[str], int]:
    """Every Class-Path entry of the launcher JAR must exist inside the ZIP."""
    launchers = sorted(
        name for name in names if re.match(r"^frostguard-desktop-[^/]+\.jar$", name)
    )
    if not launchers:
        return ["No packaged application JAR found at the root of the bundle."], 0

    launcher = launchers[-1]
    try:
        with bundle.open(launcher) as jar_stream, zipfile.ZipFile(jar_stream) as jar:
            manifest_text = jar.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    except (KeyError, zipfile.BadZipFile) as error:
        return [f"Could not read the manifest of {launcher}: {error}"], 0

    entries = class_path_entries(manifest_text)
    if not entries:
        return [f"{launcher} has no Class-Path manifest entry."], 0

    # Class-Path entries are relative to the JAR, which sits at the bundle root.
    missing = [
        entry for entry in entries
        if posixpath.normpath(entry) not in names
    ]
    return (
        [
            f"{launcher} references a JAR that is absent from the bundle: {entry}"
            for entry in missing
        ],
        len(entries),
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", help="path to the desktop-bundle ZIP")
    parser.add_argument(
        "--platform",
        default="win",
        choices=JAVAFX_PLATFORMS,
        help="JavaFX platform classifier the bundle targets (default: win)",
    )
    args = parser.parse_args(argv)

    try:
        bundle = zipfile.ZipFile(args.bundle)
    except (OSError, zipfile.BadZipFile) as error:
        fail(f"Cannot open bundle {args.bundle}: {error}")
        return 1

    with bundle:
        if bundle.testzip() is not None:
            fail(f"Bundle {args.bundle} contains a corrupt entry.")
            return 1
        names = {name for name in bundle.namelist() if not name.endswith("/")}

        problems: list[str] = []
        problems += check_required_files(names)
        problems += check_required_patterns(names)
        problems += check_javafx_platform(names, args.platform)

        floor_problems, jar_count = check_runtime_jar_floor(names)
        problems += floor_problems

        manifest_problems, class_path_count = check_manifest_class_path(bundle, names)
        problems += manifest_problems

    if problems:
        for problem in problems:
            fail(problem)
        print(f"\nBundle verification FAILED with {len(problems)} problem(s).")
        return 1

    template_count = sum(
        1 for name in names if name.startswith("templates/") and name.endswith(".png")
    )
    print(f"Bundle verification passed for {args.bundle}:")
    print(f"  entries                       : {len(names)}")
    print(f"  runtime JARs under lib/       : {jar_count}")
    print(f"  manifest Class-Path entries   : {class_path_count} (all present)")
    print(f"  template sprites              : {template_count}")
    print(f"  JavaFX platform               : {args.platform}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
