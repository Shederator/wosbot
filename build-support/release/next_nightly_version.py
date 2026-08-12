#!/usr/bin/env python3
"""Derive the next date-sequenced Frostguard Nightly version."""

from __future__ import annotations

import argparse
import re
from datetime import date, datetime, timezone

STABLE_VERSION = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
NIGHTLY_VERSION = re.compile(
    r"^((?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))"
    r"-nightly\.(\d{8})\.(0|[1-9]\d*)$"
)


def next_nightly_version(
        release_date: date,
        previous_version: str | None = None,
        base_version: str | None = None) -> str:
    """Return ``X.Y.Z-nightly.YYYYMMDD.N`` after the supplied predecessor."""
    stamp = release_date.strftime("%Y%m%d")
    if previous_version:
        match = NIGHTLY_VERSION.fullmatch(previous_version)
        if match is None:
            raise ValueError("previous Nightly must use X.Y.Z-nightly.YYYYMMDD.N")
        previous_date = datetime.strptime(match.group(2), "%Y%m%d").date()
        if release_date < previous_date:
            raise ValueError("release date cannot precede the previous Nightly")
        base = match.group(1)
        sequence = int(match.group(3)) + 1 if release_date == previous_date else 1
    else:
        if not base_version or STABLE_VERSION.fullmatch(base_version) is None:
            raise ValueError("base version must use X.Y.Z when no previous Nightly exists")
        base = base_version
        sequence = 1
    if sequence > 999:
        raise ValueError("Nightly sequence cannot exceed 999 in one day")
    return f"{base}-nightly.{stamp}.{sequence}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous-version")
    parser.add_argument("--base-version")
    parser.add_argument(
        "--date",
        default=datetime.now(timezone.utc).strftime("%Y%m%d"),
        help="UTC release date as YYYYMMDD (defaults to today)",
    )
    args = parser.parse_args()
    try:
        release_date = datetime.strptime(args.date, "%Y%m%d").date()
        print(next_nightly_version(
            release_date,
            previous_version=args.previous_version,
            base_version=args.base_version,
        ))
    except ValueError as failure:
        parser.error(str(failure))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
