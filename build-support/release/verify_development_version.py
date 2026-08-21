#!/usr/bin/env python3
"""Verify that the declared Maven baseline has not fallen behind Stable."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
VERSION_PATTERN = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
STABLE_TAG_PATTERN = re.compile(r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")


def version_tuple(value: str) -> tuple[int, int, int]:
    match = VERSION_PATTERN.fullmatch(value)
    if match is None:
        raise ValueError(f"Declared Maven revision must use X.Y.Z, found: {value}")
    return tuple(int(part) for part in match.groups())


def declared_revision(pom: Path) -> str:
    root = ET.parse(pom).getroot()
    revision = root.find("m:properties/m:revision", MAVEN_NAMESPACE)
    if revision is None or revision.text is None or not revision.text.strip():
        raise ValueError("pom.xml does not declare a non-empty revision")
    value = revision.text.strip()
    version_tuple(value)
    return value


def latest_stable_tag(tags: list[str]) -> str:
    stable = []
    for tag in tags:
        match = STABLE_TAG_PATTERN.fullmatch(tag.strip())
        if match is not None:
            stable.append((tuple(int(part) for part in match.groups()), tag.strip()))
    if not stable:
        raise ValueError("Git history contains no reachable Stable vX.Y.Z tag")
    return max(stable)[1]


def validate(declared: str, stable_tag: str, expected_stable: str | None = None) -> None:
    declared_value = version_tuple(declared)
    stable_value = version_tuple(stable_tag.removeprefix("v"))
    if declared_value < stable_value:
        raise ValueError(
            f"Declared Maven revision {declared} is older than latest Stable {stable_tag}"
        )
    if expected_stable is not None and declared != expected_stable:
        raise ValueError(
            f"Requested Stable {expected_stable} does not match declared Maven revision {declared}"
        )


def reachable_tags(repo: Path) -> list[str]:
    result = subprocess.run(
        ["git", "-C", str(repo), "tag", "--merged", "HEAD", "--list", "v*"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ValueError(f"Could not read reachable Git tags: {result.stderr.strip()}")
    return result.stdout.splitlines()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-stable", help="Stable version requested by the release workflow")
    args = parser.parse_args(argv)
    try:
        declared = declared_revision(REPO_ROOT / "pom.xml")
        stable_tag = latest_stable_tag(reachable_tags(REPO_ROOT))
        validate(declared, stable_tag, args.expected_stable)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    print(f"Declared Maven revision {declared} is valid against {stable_tag}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
