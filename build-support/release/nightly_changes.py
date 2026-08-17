#!/usr/bin/env python3
"""Summarise first-parent changes since the previous Frostguard Nightly."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

MERGE_PR = re.compile(r"^Merge pull request #(\d+)\b")
SQUASH_PR = re.compile(r"^(.*?)\s*\(#(\d+)\)$")
NIGHTLY_TAG = re.compile(r"^v\d+\.\d+\.\d+-nightly\.\d{8}\.\d+$")


@dataclass(frozen=True)
class ChangeRange:
    previous: str
    current: str
    unchanged: bool
    updated_at: str


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
        f"• [`{short}`](https://github.com/{repository}/commit/{short}) "
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


def resolve_range(releases: list[dict], current_tag: str) -> ChangeRange:
    """Resolve the last code-changing Nightly interval from release history."""
    if releases and isinstance(releases[0], list):
        releases = [release for page in releases for release in page]

    def value(release: dict, camel: str, snake: str):
        return release.get(camel, release.get(snake))

    normalized = [{
        "tagName": value(release, "tagName", "tag_name"),
        "targetCommitish": value(release, "targetCommitish", "target_commitish"),
        "publishedAt": value(release, "publishedAt", "published_at"),
        "isDraft": value(release, "isDraft", "draft"),
    } for release in releases]
    nightlies = sorted(
        (
            release for release in normalized
            if NIGHTLY_TAG.match(str(release.get("tagName", "")))
            and not release.get("isDraft", False)
            and release.get("targetCommitish")
            and release.get("publishedAt")
        ),
        key=lambda release: release["publishedAt"],
        reverse=True,
    )
    current_index = next(
        (index for index, release in enumerate(nightlies)
         if release["tagName"] == current_tag),
        None,
    )
    if current_index is None:
        raise ValueError(f"Current Nightly release {current_tag!r} is missing")

    current = nightlies[current_index]
    older = nightlies[current_index + 1:]
    if not older:
        return ChangeRange("", current["targetCommitish"], False,
                           current["publishedAt"])

    current_sha = current["targetCommitish"]
    previous = older[0]
    if previous["targetCommitish"] != current_sha:
        return ChangeRange(previous["targetCommitish"], current_sha, False,
                           current["publishedAt"])

    # Several Nightlies may intentionally point at the same commit. Keep the
    # changelog from the first one in that run, and compare it to the preceding
    # distinct build instead of replacing useful entries with "no changes".
    oldest_same = current
    for release in older:
        if release["targetCommitish"] != current_sha:
            return ChangeRange(release["targetCommitish"], current_sha, True,
                               oldest_same["publishedAt"])
        oldest_same = release

    return ChangeRange("", current_sha, True, oldest_same["publishedAt"])


def write_github_output(path: str, changes: str, change_range: ChangeRange) -> None:
    with open(path, "a", encoding="utf-8") as output:
        output.write("changes<<FROSTGUARD_CHANGES\n")
        output.write(changes + "\n")
        output.write("FROSTGUARD_CHANGES\n")
        output.write(f"unchanged={str(change_range.unchanged).lower()}\n")
        output.write(f"updated_at={change_range.updated_at}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--previous", default="")
    parser.add_argument("--current", default="")
    parser.add_argument("--release-history", default="")
    parser.add_argument("--current-tag", default="")
    parser.add_argument("--github-output", default="")
    args = parser.parse_args()
    if args.release_history:
        releases = json.loads(
            Path(args.release_history).read_text(encoding="utf-8-sig")
        )
        change_range = resolve_range(releases, args.current_tag)
        result = summary(args.repository, change_range.previous, change_range.current)
        if args.github_output:
            write_github_output(args.github_output, result, change_range)
        print(result)
        return 0
    if not args.current:
        parser.error("--current is required without --release-history")
    print(summary(args.repository, args.previous, args.current))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
