#!/usr/bin/env python3
"""Parse and authorize a ``/build-pr`` request written as a GitHub comment.

A Discord webhook can only *send* messages, so the command itself has to arrive
somewhere GitHub can already see it. A comment on any issue or pull request is
that place: it needs no extra hosting, no bot token and no third-party service,
and the workflow file that reacts to it is always the trusted copy from the
default branch.

Accepted syntax (everything after the command name is free-form):

    /build-pr 47 48 49 65          plan only, nothing is built yet
    /build-pr 47 48 49 65 confirm  actually build and publish
    /build-pr 47 48 union confirm  allow a both-sides union resolution
    /build-pr 47 48 order=48,47    override the derived merge order
    /build-pr help                 print the usage back into the thread

Three separate guards keep this from becoming a build-farm faucet:

* the commenter must have write access, or be listed in an explicit allowlist;
* the plan step never builds unless ``confirm`` is present;
* a cooldown limits how many builds one person can start per hour.

Usage:
    pr_build_command.py parse --body-file comment.txt
    pr_build_command.py authorize --permission write --actor octocat
    pr_build_command.py cooldown --runs runs.json --actor octocat
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

COMMAND = "/build-pr"

# Permission levels GitHub reports for a collaborator. "write" and above may
# start builds; "read" and "none" may not, because a build consumes the
# repository's Actions minutes and publishes a public download.
WRITE_PERMISSIONS = {"admin", "maintain", "write"}

# Author associations that are accepted without an extra API call.
TRUSTED_ASSOCIATIONS = {"OWNER", "MEMBER", "COLLABORATOR"}

DEFAULT_COOLDOWN_MINUTES = 60
DEFAULT_MAX_RUNS_PER_WINDOW = 3

USAGE = f"""**Unmerged PR test builds**

```
{COMMAND} 47 48 49 65          show the merge plan (nothing is built)
{COMMAND} 47 48 49 65 confirm  build it and publish a temporary download
{COMMAND} 47 48 union confirm  allow a resolution that keeps both sides
{COMMAND} 47 48 order=48,47    override the merge order
{COMMAND} help                 this message
```

The plan is always shown first: it lists the pinned commits, drops pull requests
that are already contained in a later one, and explains anything it rejected.
Add `confirm` once the plan looks right. Downloads are temporary and labelled as
unmerged test builds.
"""


@dataclass
class Command:
    is_command: bool = False
    help: bool = False
    prs: str = ""
    confirm: bool = False
    resolution: str = "stop"
    order: str = ""
    unknown: list[str] = field(default_factory=list)


def parse_command(body: str) -> Command:
    """Read the first ``/build-pr`` line of a comment.

    Only a line that *starts* with the command counts, so quoting somebody
    else's command in a reply cannot trigger a second build.
    """
    for raw_line in (body or "").splitlines():
        line = raw_line.strip()
        if not line.lower().startswith(COMMAND):
            continue
        remainder = line[len(COMMAND):].strip()
        command = Command(is_command=True)

        order_match = re.search(r"order\s*=\s*([0-9,\s#]+)", remainder, re.I)
        if order_match:
            command.order = order_match.group(1).strip()
            remainder = remainder.replace(order_match.group(0), " ")

        words: list[str] = []
        for token in re.split(r"[\s,]+", remainder):
            if not token:
                continue
            lowered = token.lower()
            if lowered in {"help", "-h", "--help", "?"}:
                command.help = True
            elif lowered in {"confirm", "--confirm", "yes", "build"}:
                command.confirm = True
            elif lowered in {"union", "--union", "resolve"}:
                command.resolution = "union"
            elif lowered in {"stop", "--stop"}:
                command.resolution = "stop"
            elif re.fullmatch(r"#?\d+", token):
                words.append(token)
            else:
                command.unknown.append(token)
        command.prs = " ".join(words)
        if not command.prs and not command.help:
            command.help = True
        return command
    return Command()


def is_authorized(
    permission: str,
    association: str = "",
    actor: str = "",
    allowlist: str = "",
) -> tuple[bool, str]:
    """Decide whether ``actor`` may spend runner time on a test build."""
    allowed = {
        name.strip().lower()
        for name in re.split(r"[\s,]+", allowlist or "")
        if name.strip()
    }
    if actor and actor.lower() in allowed:
        return True, f"@{actor} is on the test-build allowlist"
    if (permission or "").lower() in WRITE_PERMISSIONS:
        return True, f"@{actor or 'requester'} has {permission} access"
    if (association or "").upper() in TRUSTED_ASSOCIATIONS:
        return True, f"@{actor or 'requester'} is a repository {association.lower()}"
    return False, (
        "Only people with write access to this repository (or names on the "
        "`PR_TEST_BUILD_ALLOWLIST` variable) can start a test build. Ask a "
        "maintainer to run it for you."
    )


def cooldown_exceeded(
    runs: list[dict],
    actor: str,
    now: datetime,
    minutes: int = DEFAULT_COOLDOWN_MINUTES,
    max_runs: int = DEFAULT_MAX_RUNS_PER_WINDOW,
) -> tuple[bool, str]:
    """Count this actor's recent builds, so a typo cannot flood the queue.

    ``runs`` is the ``workflow_runs`` array from the REST API. Only runs that
    actually built something count: a plan-only run costs a minute and should
    not use up somebody's quota.
    """
    window_start = now - timedelta(minutes=minutes)
    recent = 0
    for run in runs:
        if (run.get("actor") or {}).get("login", "").lower() != (actor or "").lower():
            continue
        started = run.get("run_started_at") or run.get("created_at") or ""
        try:
            when = datetime.fromisoformat(started.replace("Z", "+00:00"))
        except ValueError:
            continue
        if when >= window_start:
            recent += 1
    if recent >= max_runs:
        return True, (
            f"@{actor} already started {recent} test builds in the last "
            f"{minutes} minutes (limit {max_runs}). Wait for those to finish "
            "before requesting another one."
        )
    return False, ""


def write_output(pairs: dict[str, str]) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        for key, value in pairs.items():
            print(f"{key}={value}")
        return
    with open(path, "a", encoding="utf-8") as handle:
        for key, value in pairs.items():
            if "\n" in value:
                handle.write(f"{key}<<__FG_EOF__\n{value}\n__FG_EOF__\n")
            else:
                handle.write(f"{key}={value}\n")


def command_parse(args: argparse.Namespace) -> int:
    if args.body_file:
        with open(args.body_file, encoding="utf-8") as handle:
            body = handle.read()
    else:
        body = args.body
    command = parse_command(body)
    write_output(
        {
            "is_command": "true" if command.is_command else "false",
            "help": "true" if command.help else "false",
            "prs": command.prs,
            "confirm": "true" if command.confirm else "false",
            "resolution": command.resolution,
            "order": command.order,
            "unknown": " ".join(command.unknown),
            "usage": USAGE,
        }
    )
    if command.unknown:
        print(
            "::warning::Ignored tokens that are not pull request numbers: "
            + ", ".join(command.unknown)
        )
    return 0


def command_authorize(args: argparse.Namespace) -> int:
    allowed, reason = is_authorized(
        args.permission, args.association, args.actor, args.allowlist
    )
    write_output(
        {"authorized": "true" if allowed else "false", "reason": reason}
    )
    print(reason)
    return 0


def command_cooldown(args: argparse.Namespace) -> int:
    try:
        with open(args.runs, encoding="utf-8") as handle:
            payload = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        # Not being able to read the history must not block a legitimate build.
        print(f"::warning::Could not read the run history ({error}); skipping "
              "the cooldown check.")
        write_output({"blocked": "false", "reason": ""})
        return 0
    runs = payload.get("workflow_runs") if isinstance(payload, dict) else payload
    blocked, reason = cooldown_exceeded(
        runs or [],
        args.actor,
        datetime.now(timezone.utc),
        args.minutes,
        args.max_runs,
    )
    write_output({"blocked": "true" if blocked else "false", "reason": reason})
    if blocked:
        print(f"::warning::{reason}")
    return 0


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    parse_cmd = subparsers.add_parser("parse")
    parse_cmd.add_argument("--body", default="")
    parse_cmd.add_argument("--body-file", default="")
    parse_cmd.set_defaults(func=command_parse)

    auth = subparsers.add_parser("authorize")
    auth.add_argument("--permission", default="")
    auth.add_argument("--association", default="")
    auth.add_argument("--actor", default="")
    auth.add_argument("--allowlist", default="")
    auth.set_defaults(func=command_authorize)

    cooldown = subparsers.add_parser("cooldown")
    cooldown.add_argument("--runs", required=True)
    cooldown.add_argument("--actor", default="")
    cooldown.add_argument("--minutes", type=int, default=DEFAULT_COOLDOWN_MINUTES)
    cooldown.add_argument(
        "--max-runs", type=int, default=DEFAULT_MAX_RUNS_PER_WINDOW
    )
    cooldown.set_defaults(func=command_cooldown)

    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
