#!/usr/bin/env python3
"""Post the status of an unmerged PR test build to a Discord webhook.

The nightly notifier in ``discord_notify.py`` answers "is main healthy". This
one answers a different question: "what exactly is in the build I am about to
install, and is it safe to trust it?" Every message therefore carries the pull
request numbers, titles and the **pinned** head commits, and every download is
labelled as an unmerged test build so nobody mistakes it for a release.

Delivery, retrying, redaction and the "never let @everyone in a title ping the
channel" rule are shared with the nightly notifier instead of being reimplemented
here.

Usage:
    DISCORD_PR_BUILD_WEBHOOK_URL=... ci/pr_build_notify.py --status success \\
        --plan plan.json --download-url https://... --run-url https://...
    ci/pr_build_notify.py --status plan --plan plan.json --dry-run
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from discord_notify import (  # noqa: E402  (deliberate: shared helpers)
    CONTENT_LIMIT,
    EMBED_DESCRIPTION_LIMIT,
    EMBED_FIELD_NAME_LIMIT,
    EMBED_FIELD_VALUE_LIMIT,
    EMBED_FOOTER_LIMIT,
    EMBED_TITLE_LIMIT,
    human_size,
    lenient_int,
    post,
    truncate,
)
from datetime import datetime, timezone  # noqa: E402

STATUS_STYLE = {
    # A plan is not a result: it is a request for confirmation before ~30
    # minutes of runner time is spent, so it gets its own neutral colour.
    "plan": (0x3498DB, "Test build planned"),
    "success": (0x2ECC71, "Unmerged test build ready"),
    "conflict": (0xE67E22, "Pull requests could not be combined"),
    "failure": (0xE74C3C, "Test build failed"),
    "rejected": (0x95A5A6, "Request rejected"),
    "reused": (0x2ECC71, "Existing test build reused"),
}

WEBHOOK_ENV_DEFAULT = "DISCORD_PR_BUILD_WEBHOOK_URL"
WEBHOOK_ENV_FALLBACK = "DISCORD_NIGHTLY_WEBHOOK_URL"

WARNING = (
    "⚠️ **Unmerged test build.** It contains code that has not been reviewed "
    "or merged. Use a test profile, not your main account data."
)


def load_plan(path: str) -> dict:
    if not path:
        return {}
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        print(f"::warning::Could not read the plan {path!r}: {error}")
        return {}


def pr_lines(plan: dict, limit: int = 10) -> list[str]:
    """One line per included pull request: number, title, author, pinned SHA."""
    lines: list[str] = []
    order = plan.get("order") or []
    pulls = plan.get("pull_requests") or {}
    for number in order[:limit]:
        facts = pulls.get(str(number), {})
        title = truncate(facts.get("title") or "", 110)
        sha = (facts.get("head_sha") or "")[:7]
        url = facts.get("url") or ""
        head = f"[#{number}]({url})" if url else f"#{number}"
        author = facts.get("author") or "unknown"
        lines.append(f"{head} `{sha}` {title} — @{author}")
    if len(order) > limit:
        lines.append(f"…and {len(order) - limit} more")
    return lines


def rejection_lines(plan: dict) -> list[str]:
    lines = [
        f"#{item['number']}: {item['reason']}"
        for item in plan.get("rejected") or []
    ]
    lines += [
        f"#{item['number']} already contained in #{item['contained_in']}"
        for item in plan.get("contained") or []
    ]
    if plan.get("invalid_tokens"):
        lines.append(
            "Not pull request numbers: "
            + ", ".join(f"`{token}`" for token in plan["invalid_tokens"])
        )
    if plan.get("duplicates_removed"):
        lines.append(
            "Duplicates removed: "
            + ", ".join(f"#{number}" for number in plan["duplicates_removed"])
        )
    return lines


def conflict_lines(report: dict, limit: int = 12) -> list[str]:
    lines: list[str] = []
    shown = 0
    for entry in report.get("conflicts") or []:
        lines.append(f"**#{entry['number']}** (`{entry['sha'][:7]}`)")
        for item in entry.get("files") or []:
            if shown >= limit:
                lines.append("…more files omitted")
                return lines
            suffix = "" if item.get("kind") == "text" else f" ({item['kind']})"
            lines.append(f"`{item['path']}`{suffix}")
            shown += 1
    return lines


def build_payload(args: argparse.Namespace) -> dict:
    plan = load_plan(args.plan)
    report = load_plan(args.report)
    color, headline = STATUS_STYLE.get(args.status, STATUS_STYLE["failure"])

    numbers = plan.get("order") or []
    label = ", ".join(f"#{number}" for number in numbers) or "no pull request"
    title = f"{headline} — {label}"

    fields: list[dict] = []

    def add_field(name: str, value: str, inline: bool = False) -> None:
        value = truncate(value, EMBED_FIELD_VALUE_LIMIT)
        if value:
            fields.append(
                {
                    "name": truncate(name, EMBED_FIELD_NAME_LIMIT),
                    "value": value,
                    "inline": inline,
                }
            )

    if numbers:
        add_field("Included pull requests", "\n".join(pr_lines(plan)))
    rejected = rejection_lines(plan)
    if rejected:
        add_field("Not included", "\n".join(rejected))
    if args.status == "conflict":
        add_field("Conflicting files", "\n".join(conflict_lines(report)))

    base_sha = (plan.get("base_sha") or "")[:7]
    if base_sha:
        add_field("Base", f"`{plan.get('base_ref', 'main')}` at `{base_sha}`", True)
    if plan.get("build_key"):
        add_field("Build key", f"`{plan['build_key']}`", True)
    if args.bundle_bytes > 0:
        add_field("Download size", human_size(args.bundle_bytes), True)
    if args.test_count > 0:
        add_field("JUnit tests", f"{args.test_count} passed", True)
    if args.requester:
        add_field("Requested by", args.requester, True)
    if args.expires:
        add_field("Expires", args.expires, True)

    description: list[str] = []
    if args.status in {"success", "reused"}:
        if args.download_url and not args.download_url.startswith("http"):
            print(
                "::warning::Ignoring a download URL that is not absolute: "
                f"{args.download_url!r}"
            )
            args.download_url = ""
        if args.download_url:
            description.append(
                f"**[⬇ Download the test build]({args.download_url})**"
            )
            description.append(
                "Unzip anywhere, then run `java -jar frostguard-*.jar` "
                "(Java 21+ required)."
            )
        description.append(WARNING)
    elif args.status == "plan":
        description.append(
            "This is the merge plan only — **nothing has been built yet.**"
        )
        if args.confirm_hint:
            description.append(args.confirm_hint)
    elif args.status == "conflict":
        description.append(
            "Git cannot combine these pull requests. Nothing in the "
            "repository was changed and no build was published."
        )
    elif args.status == "rejected":
        description.append(args.reason or "The request was not accepted.")
    else:
        description.append(
            f"{headline}. The pull request branches were not modified."
        )

    if args.run_url and args.status != "plan":
        description.append(f"[Workflow log]({args.run_url})")
    elif args.run_url:
        description.append(f"[Workflow run]({args.run_url})")

    embed = {
        "title": truncate(title, EMBED_TITLE_LIMIT),
        "color": color,
        "description": truncate("\n\n".join(description), EMBED_DESCRIPTION_LIMIT),
        "fields": fields,
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    if args.run_url:
        embed["url"] = args.run_url

    footer_parts = [part for part in (args.repository, args.thread_url) if part]
    if args.run_number:
        footer_parts.append(f"run #{args.run_number}")
    footer = " • ".join(footer_parts)
    if footer:
        embed["footer"] = {"text": truncate(footer, EMBED_FOOTER_LIMIT)}

    payload = {
        "username": args.username,
        "embeds": [embed],
        # Structural, not cosmetic: a PR title containing @everyone must not be
        # able to ping the channel.
        "allowed_mentions": {"parse": []},
    }
    # The bare link stays tappable in the mobile client and in the notification
    # preview, where an embed link is easy to miss.
    if args.status in {"success", "reused"} and args.download_url:
        payload["content"] = truncate(args.download_url, CONTENT_LIMIT)
    return payload


def resolve_webhook(primary_env: str, fallback_env: str) -> tuple[str, str]:
    """Prefer a dedicated test-build webhook, fall back to the nightly one.

    A separate channel for unreviewed builds is the safer setup, but requiring
    it would mean the feature does nothing on a server that already has the
    nightly webhook configured.
    """
    value = os.environ.get(primary_env, "").strip()
    if value:
        return value, primary_env
    return os.environ.get(fallback_env, "").strip(), fallback_env


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--status", required=True, choices=sorted(STATUS_STYLE))
    parser.add_argument("--plan", default="", help="plan JSON from pr_build_plan.py")
    parser.add_argument("--report", default="", help="combine report JSON")
    parser.add_argument("--download-url", default="")
    parser.add_argument("--run-url", default="")
    parser.add_argument("--run-number", default="")
    parser.add_argument("--repository", default="")
    parser.add_argument("--requester", default="")
    parser.add_argument("--thread-url", default="")
    parser.add_argument("--reason", default="")
    parser.add_argument("--expires", default="")
    parser.add_argument("--confirm-hint", default="")
    parser.add_argument("--bundle-bytes", type=lenient_int, default=0)
    parser.add_argument("--test-count", type=lenient_int, default=0)
    parser.add_argument("--username", default="Frostguard Test Builds")
    parser.add_argument("--webhook-env", default=WEBHOOK_ENV_DEFAULT)
    parser.add_argument("--webhook-env-fallback", default=WEBHOOK_ENV_FALLBACK)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    payload = build_payload(args)

    if args.dry_run:
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    webhook, source = resolve_webhook(args.webhook_env, args.webhook_env_fallback)
    if not webhook:
        print(
            f"::warning::Neither {args.webhook_env} nor "
            f"{args.webhook_env_fallback} is set; no Discord message was sent."
        )
        return 0
    if not re.match(
        r"^https://(canary\.|ptb\.)?discord(app)?\.com/api/webhooks/", webhook
    ):
        print(
            f"::error::{source} does not look like a Discord webhook URL "
            "(expected https://discord.com/api/webhooks/<id>/<token>)."
        )
        return 1

    post(
        webhook,
        json.dumps(payload).encode("utf-8"),
        "application/json",
        args.timeout,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
