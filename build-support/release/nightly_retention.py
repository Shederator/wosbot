#!/usr/bin/env python3
"""Select obsolete immutable Frostguard Nightly releases for deletion."""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime
from pathlib import Path

NIGHTLY_TAG = re.compile(r"^v\d+\.\d+\.\d+-nightly\.\d{8}\.\d+$")


def _value(release: dict, camel: str, snake: str):
    return release.get(camel, release.get(snake))


def _published_at(release: dict) -> datetime:
    value = release["publishedAt"]
    if not isinstance(value, str):
        raise ValueError(
            f"Nightly release {release['tagName']!r} has no publication time")
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(
            f"Nightly release {release['tagName']!r} has an invalid publication time"
        ) from error


def obsolete_nightlies(
        releases: list[dict], current_tag: str, keep: int = 2) -> list[str]:
    """Return public immutable Nightly tags older than the retained releases."""
    if keep < 1:
        raise ValueError("At least one immutable Nightly must be retained")
    if not NIGHTLY_TAG.fullmatch(current_tag):
        raise ValueError(f"Current Nightly tag {current_tag!r} is invalid")
    if releases and isinstance(releases[0], list):
        releases = [release for page in releases for release in page]

    nightlies = []
    seen_tags = set()
    for release in releases:
        tag = _value(release, "tagName", "tag_name")
        if not isinstance(tag, str) or not NIGHTLY_TAG.fullmatch(tag):
            continue
        if _value(release, "isDraft", "draft"):
            continue
        if tag in seen_tags:
            raise ValueError(f"Nightly release {tag!r} occurs more than once")
        seen_tags.add(tag)
        normalized = {
            "tagName": tag,
            "publishedAt": _value(release, "publishedAt", "published_at"),
        }
        nightlies.append((_published_at(normalized), tag))

    nightlies.sort(reverse=True)
    tags = [tag for _, tag in nightlies]
    if current_tag not in tags:
        raise ValueError(f"Current Nightly release {current_tag!r} is missing")
    retained = tags[:keep]
    if current_tag not in retained:
        raise ValueError(
            f"Current Nightly release {current_tag!r} is not among the newest {keep}")
    return tags[keep:]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-history", required=True)
    parser.add_argument("--current-tag", required=True)
    parser.add_argument("--keep", type=int, default=2)
    args = parser.parse_args()
    releases = json.loads(
        Path(args.release_history).read_text(encoding="utf-8-sig"))
    for tag in obsolete_nightlies(releases, args.current_tag, args.keep):
        print(tag)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
