#!/usr/bin/env python3
"""Resolve a release version to Windows Installer's three numeric fields."""

from __future__ import annotations

import argparse
import re
from datetime import datetime

STABLE_VERSION = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
NIGHTLY_VERSION = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"-nightly\.(\d{8})\.(0|[1-9]\d*)$"
)
STABLE_CANDIDATE_VERSION = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"-(?:0|[1-9]\d*|[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|[A-Za-z-][0-9A-Za-z-]*))*$"
)


def windows_installer_version(channel: str, version: str) -> str:
    return ".".join(str(value) for value in _windows_installer_fields(channel, version))


def require_newer_version(channel: str, version: str, previous_version: str) -> None:
    current = _windows_installer_fields(channel, version)
    previous = _windows_installer_fields(channel, previous_version)
    if _release_order(channel, version) <= _release_order(channel, previous_version):
        raise ValueError(f"Release version {version} must be newer than {previous_version}")
    if current <= previous:
        raise ValueError(
            f"Windows installer version {'.'.join(map(str, current))} must be newer than "
            f"{'.'.join(map(str, previous))}")


def stable_candidate_windows_version(version: str, windows_version: str) -> str:
    candidate = STABLE_CANDIDATE_VERSION.fullmatch(version)
    if candidate is None:
        raise ValueError("Stable candidate version must be a semantic prerelease")
    windows = STABLE_VERSION.fullmatch(windows_version)
    if windows is None:
        raise ValueError("Stable candidate Windows version must use X.Y.Z")
    candidate_fields = tuple(int(candidate.group(index)) for index in range(1, 4))
    windows_fields = tuple(int(value) for value in windows.groups())
    _validate_windows_fields(windows_fields)
    if windows_fields >= candidate_fields:
        raise ValueError("Stable candidate Windows version must remain below the final Stable version")
    return ".".join(str(value) for value in windows_fields)


def _release_order(channel: str, version: str) -> tuple[int, ...]:
    pattern = STABLE_VERSION if channel == "stable" else NIGHTLY_VERSION
    match = pattern.fullmatch(version)
    if match is None:
        raise ValueError(f"Invalid {channel} release version: {version}")
    return tuple(int(value) for value in match.groups())


def _windows_installer_fields(channel: str, version: str) -> tuple[int, int, int]:
    if channel == "stable":
        match = STABLE_VERSION.fullmatch(version)
        if match is None:
            raise ValueError("Stable version must use X.Y.Z")
        fields = tuple(int(value) for value in match.groups())
        _validate_windows_fields(fields)
        return fields

    if channel != "nightly":
        raise ValueError("channel must be stable or nightly")
    match = NIGHTLY_VERSION.fullmatch(version)
    if match is None:
        raise ValueError("Nightly version must use X.Y.Z-nightly.YYYYMMDD.N")
    release_date = datetime.strptime(match.group(4), "%Y%m%d").date()
    sequence = int(match.group(5))
    if sequence < 1 or sequence > 999:
        raise ValueError("Nightly sequence must be between 1 and 999")
    windows_version = (release_date.year - 2000, release_date.month,
                       release_date.day * 1000 + sequence)
    _validate_windows_fields(windows_version)
    return windows_version


def _validate_windows_fields(fields: tuple[int, int, int]) -> None:
    if any(value < 0 for value in fields) or fields[0] > 255 or fields[1] > 255 or fields[2] > 65_535:
        raise ValueError("Windows installer version exceeds 255.255.65535")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", required=True, choices=("stable", "nightly"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--previous-version")
    parser.add_argument("--candidate-windows-version")
    args = parser.parse_args()
    try:
        if args.candidate_windows_version:
            if args.channel != "stable":
                raise ValueError("Candidate Windows versions are available only for Stable")
            if args.previous_version:
                raise ValueError("Candidate and previous versions cannot be validated together")
            print(stable_candidate_windows_version(args.version, args.candidate_windows_version))
        elif args.previous_version:
            require_newer_version(args.channel, args.version, args.previous_version)
            print(windows_installer_version(args.channel, args.version))
        else:
            print(windows_installer_version(args.channel, args.version))
    except ValueError as failure:
        parser.error(str(failure))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
