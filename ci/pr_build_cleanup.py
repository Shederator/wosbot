#!/usr/bin/env python3
"""Expire the temporary prereleases created for unmerged PR test builds.

A test build is only useful while the pull requests it contains are still open
and still at the commits that were built. Left alone, the ``pr-test-*`` tags
would accumulate forever and testers would keep finding stale downloads through
search engines, which is how an unreviewed build ends up being treated as a
release.

Two rules retire a build:

1. **Age.** Anything older than the TTL goes, because the base branch has moved
   on and the build no longer represents any reviewable state.
2. **All pull requests finished.** Once every included PR is closed or merged,
   the build has no audience left, whatever its age.

Releases whose tag does not start with ``pr-test-`` are never touched, so a real
tagged release or the rolling ``nightly`` tag cannot be deleted by a bug here.

Usage:
    pr_build_cleanup.py --repository owner/name [--ttl-days 7] [--dry-run]
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# The marker is written into the release notes by the planner, so both ends of
# that contract live in one module and cannot drift apart.
from pr_build_plan import (  # noqa: E402
    DEFAULT_API_BASE,
    DEFAULT_TTL_DAYS,
    MARKER_PATTERN,
    TAG_PREFIX,
    marker_block,
    parse_marker,
)


def parse_timestamp(value: str) -> datetime | None:
    try:
        return datetime.fromisoformat((value or "").replace("Z", "+00:00"))
    except ValueError:
        return None


def decide(
    releases: list[dict],
    pull_state,
    now: datetime,
    ttl_days: int = DEFAULT_TTL_DAYS,
) -> list[tuple[dict, str]]:
    """Pick the releases to delete. ``pull_state(number)`` returns open/closed.

    Kept free of I/O so the retirement rules can be tested exhaustively.
    """
    doomed: list[tuple[dict, str]] = []
    cutoff = now - timedelta(days=ttl_days)
    for release in releases:
        tag = release.get("tag_name") or ""
        if not tag.startswith(TAG_PREFIX):
            continue

        created = parse_timestamp(
            release.get("created_at") or release.get("published_at") or ""
        )
        if created is not None and created < cutoff:
            age = (now - created).days
            doomed.append((release, f"{age} days old (TTL is {ttl_days} days)"))
            continue

        prs = parse_marker(release.get("body") or "").get("prs") or []
        if not prs:
            # No marker means the notes were edited or the release predates the
            # marker. Age is then the only safe criterion, so leave it alone.
            continue
        states = {number: pull_state(int(number)) for number in prs}
        if all(state and state != "open" for state in states.values()):
            doomed.append(
                (
                    release,
                    "every included pull request is closed or merged ("
                    + ", ".join(f"#{n} {s}" for n, s in sorted(states.items()))
                    + ")",
                )
            )
    return doomed


class ReleaseApi:
    """The release and pull-request calls the cleanup needs, over plain REST."""

    def __init__(
        self,
        repository: str,
        token: str,
        api_base: str = DEFAULT_API_BASE,
        timeout: float = 30.0,
    ) -> None:
        self.repository = repository
        self.token = token
        self.api_base = api_base.rstrip("/")
        self.timeout = timeout

    def _request(self, method: str, path: str) -> tuple[int, object]:
        request = urllib.request.Request(
            f"{self.api_base}{path}",
            method=method,
            headers={
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "frostguard-ci/1.0 (+https://github.com/Shederator/wosbot)",
                **({"Authorization": f"Bearer {self.token}"} if self.token else {}),
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8") or "null"
                return response.status, json.loads(raw)
        except urllib.error.HTTPError as error:
            return error.code, None
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            print(f"::warning::{method} {path} failed: {error}")
            return 0, None

    def releases(self) -> list[dict]:
        found: list[dict] = []
        for page in range(1, 11):  # 1000 releases is far more than plausible
            status, body = self._request(
                "GET", f"/repos/{self.repository}/releases?per_page=100&page={page}"
            )
            if status != 200 or not isinstance(body, list) or not body:
                break
            found.extend(body)
            if len(body) < 100:
                break
        return found

    def pull_state(self, number: int) -> str:
        status, body = self._request(
            "GET", f"/repos/{self.repository}/pulls/{number}"
        )
        if status != 200 or not isinstance(body, dict):
            # An unreadable PR must not be treated as finished: that would let a
            # transient API error delete a build testers are still using.
            return "open"
        if body.get("merged"):
            return "merged"
        return body.get("state") or "open"

    def delete_release(self, release: dict) -> bool:
        status, _ = self._request(
            "DELETE", f"/repos/{self.repository}/releases/{release['id']}"
        )
        if status not in (204, 404):
            print(
                f"::warning::Could not delete release {release.get('tag_name')} "
                f"(HTTP {status})."
            )
            return False
        tag = release.get("tag_name") or ""
        tag_status, _ = self._request(
            "DELETE", f"/repos/{self.repository}/git/refs/tags/{tag}"
        )
        if tag_status not in (204, 404, 422):
            print(f"::warning::Release {tag} is gone but its tag remains.")
        return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--ttl-days", type=int, default=DEFAULT_TTL_DAYS)
    parser.add_argument("--api-base", default=DEFAULT_API_BASE)
    parser.add_argument("--token-env", default="GITHUB_TOKEN")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="list what would be deleted without deleting anything",
    )
    args = parser.parse_args(argv)

    token = os.environ.get(args.token_env, "")
    if not token:
        print(f"::error::{args.token_env} is empty; cannot talk to the API.")
        return 1

    api = ReleaseApi(args.repository, token, api_base=args.api_base)
    releases = api.releases()
    candidates = [
        release
        for release in releases
        if (release.get("tag_name") or "").startswith(TAG_PREFIX)
    ]
    doomed = decide(
        candidates, api.pull_state, datetime.now(timezone.utc), args.ttl_days
    )

    lines = [
        "### Unmerged test build cleanup",
        "",
        f"- temporary test releases found: {len(candidates)}",
        f"- retired in this run: {len(doomed)}"
        + (" (dry run)" if args.dry_run else ""),
        "",
    ]
    for release, reason in doomed:
        tag = release.get("tag_name")
        lines.append(f"- `{tag}` — {reason}")
        if args.dry_run:
            print(f"Would delete {tag}: {reason}")
            continue
        if api.delete_release(release):
            print(f"Deleted {tag}: {reason}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
    print(
        f"Checked {len(candidates)} temporary test release(s); "
        f"{len(doomed)} retired."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
