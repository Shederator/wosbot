#!/usr/bin/env python3
"""Add or replace one platform artifact in a schema-1 Frostguard update payload."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlparse

SEMANTIC_VERSION = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)

PLATFORM_SUFFIX = {
    "windows-x64": ".msi",
    "macos-arm64": ".pkg",
}


def add_artifact(
        *,
        payload: Path,
        platform: str,
        installer: Path,
        installer_url: str,
        output: Path,
        publisher: str = "",
) -> dict:
    if platform not in PLATFORM_SUFFIX:
        raise ValueError(f"unsupported platform {platform}")
    expected_suffix = PLATFORM_SUFFIX[platform]
    if not installer.is_file() or installer.stat().st_size <= 0:
        raise ValueError("installer must be a non-empty file")
    if installer.suffix.lower() != expected_suffix:
        raise ValueError(f"{platform} installer must end with {expected_suffix}")
    parsed = urlparse(installer_url)
    if parsed.scheme.lower() != "https" or not parsed.netloc:
        raise ValueError("installer URL must use HTTPS")
    if Path(parsed.path).name != installer.name:
        raise ValueError("installer URL must end with the exact installer file name")

    manifest = json.loads(payload.read_text(encoding="utf-8"))
    version = manifest.get("version")
    if not isinstance(version, str) or not SEMANTIC_VERSION.fullmatch(version):
        raise ValueError("payload version must use semantic versioning")
    if version not in installer.name:
        raise ValueError("installer file name must contain the immutable release version")

    operating_system, architecture = platform.split("-", 1)
    artifact = {
        "operatingSystem": operating_system,
        "architecture": architecture,
        "fileName": installer.name,
        "url": installer_url,
        "sha256": hashlib.sha256(installer.read_bytes()).hexdigest(),
        "size": installer.stat().st_size,
    }
    if publisher.strip():
        if platform != "windows-x64":
            raise ValueError("Authenticode publisher is only valid for windows-x64")
        artifact["signature"] = {
            "type": "authenticode",
            "publisher": publisher.strip(),
        }

    artifacts = dict(manifest.get("artifacts") or {})
    artifacts[platform] = artifact
    manifest["artifacts"] = artifacts

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    temporary.replace(output)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload", required=True, type=Path)
    parser.add_argument("--platform", required=True, choices=sorted(PLATFORM_SUFFIX))
    parser.add_argument("--installer", required=True, type=Path)
    parser.add_argument("--installer-url", required=True)
    parser.add_argument("--publisher", default="")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    add_artifact(
        payload=args.payload,
        platform=args.platform,
        installer=args.installer,
        installer_url=args.installer_url,
        publisher=args.publisher,
        output=args.output,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
