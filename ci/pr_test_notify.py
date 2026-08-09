#!/usr/bin/env python3
"""Post a combined-PR test build result through a dedicated Discord webhook.

This is the reporting half of the `/build-pr` feature (issue #68). It reads
the plan.json written by ci/pr_build_plan.py and renders one of four message
kinds:

- rejected   The request never became a plan (bad numbers, closed/merged PRs).
- conflict   Git could not combine the PRs; the conflicting files are listed
             per PR, with binary conflicts flagged. Nothing was auto-resolved.
- stale      The publisher's re-check found a PR that closed or moved after
             planning; the build was withheld.
- success    A verified bundle was published; the message carries the public
             download link plus every PR number, title and pinned SHA.
- failure    The build itself failed; links the workflow log.

Every download is explicitly marked as an UNMERGED TEST BUILD so nobody
mistakes it for a nightly. Discord IDs are validated against the configured
guild/channel allowlist before the webhook is used. Manual runs with no
Discord context remain valid and intentionally send no message.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from discord_notify import (  # noqa: E402
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

KIND_STYLE = {
    "success": (0x2ECC71, "Test build ready"),
    "conflict": (0xE67E22, "PRs do not merge cleanly"),
    "rejected": (0xE74C3C, "Request rejected"),
    "stale": (0xE67E22, "Build withheld — a PR changed"),
    "failure": (0xE74C3C, "Test build failed"),
}

TEST_BUILD_WARNING = (
    "⚠️ **UNMERGED TEST BUILD** — this bundle contains pull-request code that "
    "has not been reviewed or merged into `main`. Use it only for testing."
)

# A conflict wall with hundreds of files must not blow Discord's limits;
# past this many paths the rest is summarised.
MAX_CONFLICT_FILES_SHOWN = 15
MAX_LIST_ITEMS = 10


def load_plan(path: str) -> dict:
    if not path or not os.path.isfile(path):
        return {}
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def pr_lines(plan: dict) -> list[str]:
    """One line per included PR: number, title, pinned SHA, author."""
    repo = plan.get("repository") or ""
    lines = []
    for entry in plan.get("pulls") or []:
        number = entry.get("number")
        sha = str(entry.get("head_sha") or "")[:12]
        title = truncate(str(entry.get("title") or ""), 80)
        link = (
            f"[#{number}](https://github.com/{repo}/pull/{number})"
            if repo else f"#{number}"
        )
        lines.append(f"{link} `{sha}` — {title}")
    return lines


def dropped_lines(plan: dict) -> list[str]:
    lines = []
    for entry in plan.get("dropped") or []:
        number = entry.get("number")
        container = entry.get("contained_in")
        where = "`main`" if container == "base" else f"#{container}"
        lines.append(f"#{number} — already contained in {where}, skipped")
    return lines


def conflict_lines(plan: dict) -> list[str]:
    lines = []
    for conflict in (plan.get("merge") or {}).get("conflicts") or []:
        pr = conflict.get("pr")
        files = conflict.get("files") or []
        lines.append(f"Merging **#{pr}** conflicted in {len(files)} file(s):")
        for entry in files[:MAX_CONFLICT_FILES_SHOWN]:
            marker = " **(binary — needs a manual choice)**" if entry.get("binary") else ""
            lines.append(f"• `{entry.get('path')}`{marker}")
        hidden = len(files) - MAX_CONFLICT_FILES_SHOWN
        if hidden > 0:
            lines.append(f"• …and {hidden} more")
    return lines


def clamp_list(items: list[str], limit: int = MAX_LIST_ITEMS) -> list[str]:
    if len(items) <= limit:
        return items
    return items[:limit] + [f"…and {len(items) - limit} more"]


def build_payload(args: argparse.Namespace, plan: dict) -> dict:
    color, headline = KIND_STYLE.get(args.kind, KIND_STYLE["failure"])
    digest = plan.get("digest") or ""
    requested = str(plan.get("requested") or args.requested or "").strip()

    title = f"/build-pr — {headline}"
    if digest:
        title += f" ({digest})"

    fields: list[dict] = []

    def add_field(name: str, value: str, inline: bool = False) -> None:
        value = truncate(value, EMBED_FIELD_VALUE_LIMIT)
        if value:
            fields.append({
                "name": truncate(name, EMBED_FIELD_NAME_LIMIT),
                "value": value,
                "inline": inline,
            })

    description_parts: list[str] = []

    included = pr_lines(plan)
    dropped = dropped_lines(plan)

    if args.kind == "success":
        description_parts.append(TEST_BUILD_WARNING)
        if args.download_url and args.download_url.startswith("http"):
            reused = " (reused an earlier identical build)" if args.reused else ""
            description_parts.append(
                f"**[⬇ Download the test bundle]({args.download_url})**{reused}"
            )
            description_parts.append(
                "Verified: bundle structure, manifest classpath and launch "
                "smoke test. Unzip anywhere, then run "
                "`java -jar frostguard-*.jar` (Java 21+ required)."
            )
        if args.expires_utc:
            description_parts.append(
                f"This test release expires on **{args.expires_utc}** or when "
                "every included PR is closed."
            )
        if included:
            add_field("Included PRs (merge order, pinned)", "\n".join(included))
        base_sha = str(plan.get("base_sha") or "")[:12]
        if base_sha:
            add_field("Base", f"`main @ {base_sha}`", inline=True)
    elif args.kind == "conflict":
        description_parts.append(
            "The requested PRs could not be combined automatically. Nothing "
            "was resolved with `ours`/`theirs` and no branch was modified."
        )
        lines = conflict_lines(plan)
        if lines:
            add_field("Conflicts", "\n".join(lines))
        if included:
            add_field("Attempted merge order", "\n".join(included))
        description_parts.append(
            "Rebase the later PR onto the earlier one (or resolve the "
            "conflict in the PR itself), then request the build again."
        )
    elif args.kind == "rejected":
        errors = clamp_list([str(e) for e in plan.get("errors") or []])
        if not errors and args.reason:
            errors = [args.reason]
        description_parts.append(
            "The request was not valid, so no build was started."
        )
        if errors:
            add_field("Problems", "\n".join(f"• {e}" for e in errors))
    elif args.kind == "stale":
        problems = clamp_list(
            [line for line in (args.problems or "").splitlines() if line.strip()]
        )
        description_parts.append(
            "Between planning and publishing, at least one PR was closed, "
            "merged or force-pushed. The finished build was **not** published "
            "because its pinned SHAs no longer describe the PRs. Request the "
            "build again to plan against the current heads."
        )
        if problems:
            add_field("What changed", "\n".join(f"• {p}" for p in problems))
    else:  # failure
        description_parts.append(
            f"The build failed. See [the workflow log]({args.run_url}) for "
            "the failing step." if args.run_url else "The build failed."
        )
        if included:
            add_field("Requested PRs", "\n".join(included))

    if dropped and args.kind in ("success", "conflict"):
        add_field("Dropped (stacked/contained)", "\n".join(clamp_list(dropped)))
    if requested and args.kind == "rejected":
        add_field("You asked for", f"`{truncate(requested, 200)}`", inline=True)
    if args.requester:
        add_field("Requested by", truncate(args.requester, 100), inline=True)
    if args.bundle_bytes > 0 and args.kind == "success":
        add_field("Download size", human_size(args.bundle_bytes), inline=True)
    if args.message_id and args.guild_id and args.channel_id:
        add_field(
            "Request",
            f"[Open the original request](https://discord.com/channels/"
            f"{args.guild_id}/{args.channel_id}/{args.message_id})",
            inline=True,
        )

    embed = {
        "title": truncate(title, EMBED_TITLE_LIMIT),
        "color": color,
        "description": truncate("\n\n".join(description_parts), EMBED_DESCRIPTION_LIMIT),
        "fields": fields[:10],
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    if args.run_url:
        embed["url"] = args.run_url
    footer = "Frostguard PR test builds"
    if digest:
        footer += f" • {digest}"
    embed["footer"] = {"text": truncate(footer, EMBED_FOOTER_LIMIT)}

    payload = {
        "embeds": [embed],
        # A PR title is contributor-controlled text; never let it ping.
        "allowed_mentions": {"parse": []},
    }
    if args.requester_id:
        payload["content"] = f"<@{args.requester_id}>"
        payload["allowed_mentions"] = {
            "parse": [],
            "users": [args.requester_id],
        }
    return payload


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", required=True, choices=sorted(KIND_STYLE))
    parser.add_argument("--plan", default="", help="path to plan.json")
    parser.add_argument("--download-url", default="")
    parser.add_argument("--expires-utc", default="")
    parser.add_argument("--reused", action="store_true",
                        help="an existing identical build was reused")
    parser.add_argument("--bundle-bytes", type=lenient_int, default=0)
    parser.add_argument("--run-url", default="")
    parser.add_argument("--requester", default="",
                        help="Discord user who asked for the build")
    parser.add_argument("--requested", default="",
                        help="raw PR list as typed, for rejection messages")
    parser.add_argument("--reason", default="",
                        help="single-line rejection reason when no plan exists")
    parser.add_argument("--problems", default="",
                        help="newline-separated staleness findings")
    parser.add_argument("--guild-id", default="")
    parser.add_argument("--channel-id", default="")
    parser.add_argument("--requester-id", default="")
    parser.add_argument("--message-id", default="")
    parser.add_argument("--request-id", default="")
    parser.add_argument("--allowed-guild-id", default="")
    parser.add_argument("--allowed-channel-ids", default="")
    parser.add_argument("--webhook-env", default="DISCORD_PR_BUILD_WEBHOOK_URL")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(argv)


def valid_snowflake(value: str) -> bool:
    return value.isdigit() and 5 <= len(value) <= 20


def validate_discord_context(args: argparse.Namespace) -> str:
    values = [args.guild_id, args.channel_id, args.requester_id, args.message_id]
    if not any(values):
        return "missing"
    if not all(valid_snowflake(value) for value in values):
        return "Discord context contains a missing or invalid ID"
    if args.request_id and not valid_snowflake(args.request_id):
        return "Discord request ID is invalid"
    if not valid_snowflake(args.allowed_guild_id):
        return "configured Discord guild allowlist is missing or invalid"
    if args.guild_id != args.allowed_guild_id:
        return "Discord guild is not allowlisted"
    channels = {
        value.strip() for value in args.allowed_channel_ids.split(",")
        if value.strip()
    }
    if not channels or not all(valid_snowflake(value) for value in channels):
        return "configured Discord channel allowlist is missing or invalid"
    if args.channel_id not in channels:
        return "Discord channel is not allowlisted"
    return ""


def valid_webhook_url(value: str) -> bool:
    return bool(re.match(
        r"^https://(canary\.|ptb\.)?discord(app)?\.com/api/webhooks/\d+/[\w-]+$",
        value,
    ))


def post_webhook_message(webhook: str, payload: dict, timeout: float) -> None:
    post(
        webhook,
        json.dumps(payload).encode("utf-8"),
        "application/json",
        timeout,
        credential_name="DISCORD_PR_BUILD_WEBHOOK_URL",
    )


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    plan = load_plan(args.plan)
    payload = build_payload(args, plan)

    if args.dry_run:
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    context_error = validate_discord_context(args)
    if context_error == "missing":
        print("Manual run has no Discord context; no Discord message sent.")
        return 0
    if context_error:
        print(f"::warning::{context_error}; no Discord message sent.")
        return 0

    webhook = os.environ.get(args.webhook_env, "").strip()
    if not webhook:
        print(f"::warning::{args.webhook_env} is empty; no Discord message sent.")
        return 0
    if not valid_webhook_url(webhook):
        print(f"::error::{args.webhook_env} is not a valid Discord webhook URL.")
        return 1

    post_webhook_message(webhook, payload, args.timeout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
