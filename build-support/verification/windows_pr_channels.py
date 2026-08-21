#!/usr/bin/env python3
"""Select the Windows channels that a pull request must package."""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path
from typing import Iterable


NIGHTLY_SENSITIVE_PATTERNS = tuple(
    re.compile(pattern)
    for pattern in (
        r"(^|/)pom\.xml$",
        r"^packaging/desktop/",
        r"^modules/update/src/main/",
        r"^modules/desktop/src/main/",
        r"^modules/api/src/main/java/dev/frostguard/api/runtime/",
        r"^build-support/packaging/",
        r"^build-support/release/",
        r"^build-support/verification/",
        r"^tools/",
        r"^\.github/workflows/windows-native-package\.yml$",
        r"^\.github/workflows/signed-windows-channel-release\.yml$",
    )
)


def requires_nightly(paths: Iterable[str]) -> bool:
    """Return whether any changed path can affect the Nightly identity."""
    return any(
        pattern.search(path.replace("\\", "/"))
        for path in paths
        for pattern in NIGHTLY_SENSITIVE_PATTERNS
    )


def changed_paths(base_sha: str, head_sha: str) -> list[str]:
    if not base_sha or not head_sha:
        raise ValueError("pull-request channel selection requires base and head SHAs")
    result = subprocess.run(
        [
            "git", "diff", "--name-only", "--no-renames",
            "--diff-filter=ACDMRT", base_sha, head_sha,
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return [line for line in result.stdout.splitlines() if line]


def select_nightly(event_name: str, base_sha: str, head_sha: str) -> tuple[bool, list[str]]:
    if event_name != "pull_request":
        return True, []
    paths = changed_paths(base_sha, head_sha)
    return requires_nightly(paths), paths


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--base-sha", default="")
    parser.add_argument("--head-sha", default="")
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()

    nightly, paths = select_nightly(args.event_name, args.base_sha, args.head_sha)
    with args.github_output.open("a", encoding="utf-8") as output:
        output.write(f"nightly={'true' if nightly else 'false'}\n")

    if args.event_name == "pull_request":
        reason = "a channel-sensitive path changed" if nightly else "Stable covers these changes"
        print(f"Nightly required: {str(nightly).lower()} ({reason}).")
        for path in paths:
            print(f"- {path}")
    else:
        print("Nightly required: true (manual validation builds both channels).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
