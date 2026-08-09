#!/usr/bin/env python3
"""Summarise first-parent changes since the previous Frostguard Nightly."""

from __future__ import annotations

import argparse
import re
import subprocess

MERGE_PR = re.compile(r"^Merge pull request #(\d+)\b")
SQUASH_PR = re.compile(r"^(.*?)\s*\(#(\d+)\)$")


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], check=True, text=True, stdout=subprocess.PIPE
    ).stdout.strip()


def escape_link_text(value: str) -> str:
    value = " ".join((value or "").split())
    return value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")


def describe(repository: str, commit: str) -> str:
    subject = git("show", "-s", "--format=%s", commit)
    merge = MERGE_PR.match(subject)
    if merge:
        number = merge.group(1)
        try:
            title = git("show", "-s", "--format=%s", f"{commit}^2")
        except subprocess.CalledProcessError:
            title = subject
        return f"• [#{number}](https://github.com/{repository}/pull/{number}) {escape_link_text(title)}"

    squash = SQUASH_PR.match(subject)
    if squash:
        title, number = squash.groups()
        return f"• [#{number}](https://github.com/{repository}/pull/{number}) {escape_link_text(title)}"

    short = commit[:7]
    return (
        f"• [`{short}`](https://github.com/{repository}/commit/{commit}) "
        f"{escape_link_text(subject)}"
    )


def summary(repository: str, previous: str, current: str) -> str:
    if not previous or not current or previous == current:
        return "No new changes since the previous Nightly."
    try:
        commits = git(
            "rev-list", "--first-parent", "--reverse", f"{previous}..{current}"
        ).splitlines()
    except subprocess.CalledProcessError:
        return "Change history is unavailable for this Nightly."
    commits = [commit for commit in commits if commit]
    if not commits:
        return "No new changes since the previous Nightly."

    return "\n".join(describe(repository, commit) for commit in commits)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--previous", default="")
    parser.add_argument("--current", required=True)
    args = parser.parse_args()
    print(summary(args.repository, args.previous, args.current))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
