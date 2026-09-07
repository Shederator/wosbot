#!/usr/bin/env python3
"""Verify a native Frostguard jpackage application image."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import re
import zipfile
from pathlib import Path

MINIMUM_RUNTIME_JARS = 50
WINDOWS_STATIC_REQUIRED_FILES = (
    "runtime/bin/server/jvm.dll",
    "app/lib/adb/adb.exe",
    "app/lib/adb/AdbWinApi.dll",
    "app/lib/adb/AdbWinUsbApi.dll",
    "app/lib/tesseract/eng.traineddata",
    "app/lib/tesseract/osd.traineddata",
    "app/lib/tesseract/chi_sim.traineddata",
    "app/custom_tasks/README.md",
    "app/custom_tasks/dead_shot.json",
    "app/custom_tasks/dead_shot.txt",
    "app/custom_tasks/expert_idle_exploration.json",
    "app/custom_tasks/expert_idle_exploration.txt",
    "app/custom_tasks/shield.java",
    "app/custom_tasks/templates/deals/deadshot/event_tab.png",
)
MACOS_STATIC_REQUIRED_FILES = (
    "Contents/runtime/Contents/Home/lib/server/libjvm.dylib",
    "Contents/app/lib/adb/adb",
    "Contents/app/lib/tesseract/eng.traineddata",
    "Contents/app/lib/tesseract/osd.traineddata",
    "Contents/app/lib/tesseract/chi_sim.traineddata",
    "Contents/app/lib/tesseract/native/libtesseract.dylib",
)
# Back-compat for tests that still import the Windows constant name.
STATIC_REQUIRED_FILES = WINDOWS_STATIC_REQUIRED_FILES
FORBIDDEN_NAMES = {
    "frostguard-workspace.json",
    "frostguard.db",
    "telegram-watcher.properties",
}
BUILD_METADATA = "dev/frostguard/app/frostguard-build.properties"
UPDATE_KEY = "dev/frostguard/update/project-update-key.properties"
REPO_UPDATE_KEY = (
    Path(__file__).resolve().parents[2]
    / "modules/update/src/main/resources"
    / UPDATE_KEY
)


def _read_properties(content: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in content.splitlines():
        if not line or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            return {}
        values[key] = value
    return values


def inspect_image(
        image: Path, channel: str = "stable", product_name: str = "Frostguard",
        expected_desktop_launcher_sha256: str = "",
        expected_watcher_launcher_sha256: str = "",
        platform: str = "windows",
) -> list[str]:
    problems: list[str] = []
    if not image.is_dir():
        return [f"Application image does not exist: {image}"]
    if platform not in {"windows", "macos"}:
        return [f"Unsupported platform: {platform}"]

    files = {
        path.relative_to(image).as_posix(): path
        for path in image.rglob("*")
        if path.is_file()
    }
    watcher_name = "FrostguardNightlyWatcher" if channel == "nightly" else "FrostguardWatcher"
    if platform == "macos":
        static_required = MACOS_STATIC_REQUIRED_FILES
        desktop_launcher = f"Contents/MacOS/{product_name}"
        watcher_launcher = f"Contents/MacOS/{watcher_name}"
        desktop_cfg = f"Contents/app/{product_name}.cfg"
        watcher_cfg = f"Contents/app/{watcher_name}.cfg"
        jar_prefix = "Contents/app/"
        javafx_pattern = r"^Contents/app/lib/javafx-graphics-[^/]+-mac-aarch64\.jar$"
        watcher_launcher_option = (
            f"java-options=-Dfrostguard.watcher.launcher=$APPDIR/../MacOS/{watcher_name}"
        )
        desktop_launcher_option = (
            f"java-options=-Dfrostguard.launcher=$APPDIR/../MacOS/{product_name}"
        )
        jar_patterns = (
            (rf"^{jar_prefix}frostguard-desktop-[^/]+\.jar$", "desktop JAR"),
            (rf"^{jar_prefix}frostguard-watcher-[^/]+\.jar$", "watcher JAR"),
            (rf"^{jar_prefix}lib/frostguard-update-[^/]+\.jar$", "update module"),
            (rf"^{jar_prefix}lib/opencv-[^/]+\.jar$", "OpenCV runtime"),
            (rf"^{jar_prefix}lib/tess4j-[^/]+\.jar$", "Tess4J runtime"),
            (javafx_pattern, "macOS aarch64 JavaFX runtime"),
            (rf"^{jar_prefix}templates/.+\.png$", "template browser assets"),
            (rf"^{jar_prefix}custom_tasks/.+$", "custom task examples"),
        )
        runtime_jar_re = rf"^{jar_prefix}lib/[^/]+\.jar$"
    else:
        static_required = WINDOWS_STATIC_REQUIRED_FILES
        desktop_launcher = f"{product_name}.exe"
        watcher_launcher = f"{watcher_name}.exe"
        desktop_cfg = f"app/{product_name}.cfg"
        watcher_cfg = f"app/{watcher_name}.cfg"
        jar_prefix = "app/"
        javafx_pattern = r"^app/lib/javafx-graphics-[^/]+-win\.jar$"
        watcher_launcher_option = (
            f"java-options=-Dfrostguard.watcher.launcher=$APPDIR/../{watcher_name}.exe"
        )
        desktop_launcher_option = (
            f"java-options=-Dfrostguard.launcher=$APPDIR/../{product_name}.exe"
        )
        jar_patterns = (
            (r"^app/frostguard-desktop-[^/]+\.jar$", "desktop JAR"),
            (r"^app/frostguard-watcher-[^/]+\.jar$", "watcher JAR"),
            (r"^app/lib/frostguard-update-[^/]+\.jar$", "update module"),
            (r"^app/lib/opencv-[^/]+\.jar$", "OpenCV runtime"),
            (r"^app/lib/tess4j-[^/]+\.jar$", "Tess4J runtime"),
            (javafx_pattern, "Windows JavaFX runtime"),
            (r"^app/templates/.+\.png$", "template browser assets"),
            (r"^app/custom_tasks/.+$", "custom task examples"),
        )
        runtime_jar_re = r"^app/lib/[^/]+\.jar$"

    required_files = (
        *static_required,
        desktop_launcher,
        watcher_launcher,
        desktop_cfg,
        watcher_cfg,
    )
    for required in required_files:
        if required not in files:
            problems.append(f"Application image is missing {required}")

    for relative, expected_hash in (
        (desktop_launcher, expected_desktop_launcher_sha256),
        (watcher_launcher, expected_watcher_launcher_sha256),
    ):
        if not expected_hash or relative not in files:
            continue
        actual_hash = hashlib.sha256(files[relative].read_bytes()).hexdigest()
        if actual_hash != expected_hash:
            problems.append(
                f"{relative} SHA-256 changed: expected {expected_hash}, got {actual_hash}"
            )

    runtime_jars = [name for name in files if re.match(runtime_jar_re, name)]
    if len(runtime_jars) < MINIMUM_RUNTIME_JARS:
        problems.append(
            f"Only {len(runtime_jars)} runtime JARs found; expected at least "
            f"{MINIMUM_RUNTIME_JARS}"
        )
    for pattern, description in jar_patterns:
        if not any(re.match(pattern, name) for name in files):
            problems.append(f"Application image has no {description}")

    common_java_options = (
        f"java-options=-Dfrostguard.channel={channel}",
        f"java-options=-Dfrostguard.application.id=dev.frostguard.desktop{'.nightly' if channel == 'nightly' else ''}",
        "java-options=-Duser.dir=$APPDIR",
        watcher_launcher_option,
    )
    config_settings = {
        desktop_cfg: (
            "app.mainclass=dev.frostguard.app.bootstrap.Main",
            "java-options=-Dfrostguard.update.manifest.stable=",
            "java-options=-Dfrostguard.update.manifest.nightly=",
            *common_java_options,
        ),
        watcher_cfg: (
            "app.mainclass=dev.frostguard.watcher.TelegramWatcher",
            desktop_launcher_option,
            *common_java_options,
        ),
    }
    config_identities: dict[str, str] = {}
    for config_path, settings in config_settings.items():
        if config_path not in files:
            continue
        config = files[config_path].read_text(encoding="utf-8")
        for setting in settings:
            if setting not in config:
                problems.append(f"{Path(config_path).name} is missing: {setting}")
        identity = re.search(
            r"-Dfrostguard\.update\.pullRequestBuild=(true|false)", config
        )
        if identity is None:
            problems.append(
                f"{Path(config_path).name} is missing its PR-build update identity"
            )
        else:
            config_identities[config_path] = identity.group(1)

    desktop_jars = [
        path for name, path in files.items()
        if re.match(rf"^{jar_prefix}frostguard-desktop-[^/]+\.jar$", name)
    ]
    if len(desktop_jars) == 1:
        try:
            with zipfile.ZipFile(desktop_jars[0]) as desktop_jar:
                metadata = desktop_jar.read(BUILD_METADATA).decode("utf-8")
            metadata_values = {}
            for line in metadata.splitlines():
                key, separator, value = line.partition("=")
                if not separator or key in metadata_values:
                    metadata_values = {}
                    break
                metadata_values[key] = value
            if (set(metadata_values) != {"version", "pullRequestBuild", "authenticodePublisher"}
                    or metadata_values["pullRequestBuild"] not in {"true", "false"}):
                problems.append("Desktop JAR has invalid build metadata")
            else:
                jar_version = re.fullmatch(
                    r"frostguard-desktop-(.+)\.jar", desktop_jars[0].name
                ).group(1)
                if metadata_values["version"] != jar_version:
                    problems.append(
                        "Desktop JAR build metadata version does not match its filename"
                    )
                embedded_identity = metadata_values["pullRequestBuild"]
                for config_path, config_identity in config_identities.items():
                    if config_identity != embedded_identity:
                        problems.append(
                            f"{Path(config_path).name} PR-build identity does not match the desktop JAR"
                        )
        except (KeyError, UnicodeDecodeError, zipfile.BadZipFile):
            problems.append("Desktop JAR has no valid embedded PR-build update identity")

    update_jars = [
        path for name, path in files.items()
        if re.match(rf"^{jar_prefix}lib/frostguard-update-[^/]+\.jar$", name)
    ]
    if len(update_jars) == 1:
        try:
            with zipfile.ZipFile(update_jars[0]) as update_jar:
                embedded_key = update_jar.read(UPDATE_KEY).decode("utf-8")
            expected_key = REPO_UPDATE_KEY.read_text(encoding="utf-8")
            key_values = _read_properties(embedded_key)
            expected_values = _read_properties(expected_key)
            public_key = base64.b64decode(
                key_values.get("publicKey", ""), validate=True
            )
            if (set(key_values) != {"keyId", "publicKey"}
                    or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}",
                                        key_values.get("keyId", ""))
                    or len(public_key) != 44
                    or not public_key.startswith(bytes.fromhex("302a300506032b6570032100"))
                    or key_values != expected_values):
                problems.append("Update module has an invalid or unexpected project update key")
        except (KeyError, UnicodeDecodeError, binascii.Error, ValueError,
                OSError, zipfile.BadZipFile):
            problems.append("Update module has no valid embedded project update key")
    elif len(update_jars) > 1:
        problems.append("Application image contains multiple update modules")

    for relative in files:
        path = Path(relative)
        lower_name = path.name.lower()
        if lower_name in FORBIDDEN_NAMES or lower_name.endswith((".db-wal", ".db-shm", ".log")):
            problems.append(f"Runtime/user data leaked into the application image: {relative}")
        if any(part.lower() in {".frostguard", ".frostguard-dev", "logs"} for part in path.parts):
            problems.append(f"Runtime/user-data directory leaked into the application image: {relative}")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("--channel", choices=("stable", "nightly"), default="stable")
    parser.add_argument("--product-name", default="Frostguard")
    parser.add_argument("--platform", choices=("windows", "macos"), default="windows")
    parser.add_argument("--expected-desktop-launcher-sha256", default="")
    parser.add_argument("--expected-watcher-launcher-sha256", default="")
    args = parser.parse_args(argv)
    problems = inspect_image(
        args.image,
        args.channel,
        args.product_name,
        args.expected_desktop_launcher_sha256,
        args.expected_watcher_launcher_sha256,
        args.platform,
    )
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        return 1
    print(f"Native application image verification passed: {args.image}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
