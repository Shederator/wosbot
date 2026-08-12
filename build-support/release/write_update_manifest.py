#!/usr/bin/env python3
"""Create a schema-1 Frostguard update payload from an immutable installer."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

SEMANTIC_VERSION = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)


def write_manifest(
        *, channel: str, version: str, minimum_updater_version: str,
        published_at: str, release_notes_url: str, installer_url: str,
        installer: Path, output: Path, publisher: str = "") -> dict:
    if channel not in {"stable", "nightly"}:
        raise ValueError("channel must be stable or nightly")
    for value, label in ((version, "version"),
                         (minimum_updater_version, "minimum updater version")):
        if not SEMANTIC_VERSION.fullmatch(value):
            raise ValueError(f"{label} must use semantic versioning")
    if channel == "stable" and "-" in version:
        raise ValueError("Stable versions must not use a prerelease identifier")
    if channel == "nightly" and "nightly" not in version.lower():
        raise ValueError("Nightly versions must carry a nightly prerelease identifier")
    timestamp = datetime.fromisoformat(published_at.replace("Z", "+00:00"))
    if timestamp.tzinfo is None or timestamp.utcoffset() != timezone.utc.utcoffset(timestamp):
        raise ValueError("published-at must be an ISO-8601 UTC timestamp")
    for value, label in ((release_notes_url, "release notes URL"),
                         (installer_url, "installer URL")):
        parsed = urlparse(value)
        if parsed.scheme.lower() != "https" or not parsed.netloc:
            raise ValueError(f"{label} must use HTTPS")
    if not installer.is_file() or installer.stat().st_size <= 0:
        raise ValueError("installer must be a non-empty file")
    if installer.suffix.lower() != ".msi":
        raise ValueError("Windows installer must be an MSI package")
    if Path(urlparse(installer_url).path).name != installer.name:
        raise ValueError("installer URL must end with the exact installer file name")
    if version not in installer.name:
        raise ValueError("installer file name must contain the immutable release version")
    manifest = {
        "schemaVersion": 1,
        "channel": channel,
        "version": version,
        "publishedAt": published_at,
        "minimumUpdaterVersion": minimum_updater_version,
        "releaseNotesUrl": release_notes_url,
        "artifacts": {
            "windows-x64": {
                "operatingSystem": "windows",
                "architecture": "x64",
                "fileName": installer.name,
                "url": installer_url,
                "sha256": hashlib.sha256(installer.read_bytes()).hexdigest(),
                "size": installer.stat().st_size,
            }
        },
    }
    if publisher.strip():
        manifest["artifacts"]["windows-x64"]["signature"] = {
            "type": "authenticode",
            "publisher": publisher.strip(),
        }
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    temporary.replace(output)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", required=True, choices=("stable", "nightly"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--minimum-updater-version", required=True)
    parser.add_argument("--published-at", required=True)
    parser.add_argument("--release-notes-url", required=True)
    parser.add_argument("--installer-url", required=True)
    parser.add_argument("--installer", required=True, type=Path)
    parser.add_argument("--publisher", default="")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    write_manifest(
        channel=args.channel,
        version=args.version,
        minimum_updater_version=args.minimum_updater_version,
        published_at=args.published_at,
        release_notes_url=args.release_notes_url,
        installer_url=args.installer_url,
        installer=args.installer,
        publisher=args.publisher,
        output=args.output,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
