#!/usr/bin/env python3
"""Create or update the maintained Discord Stable download message."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from discord_notify import post, truncate  # noqa: E402


def build_payload(args: argparse.Namespace) -> dict:
    description = (
        "A tested, versioned build that changes only when a new Stable is published.\n\n"
        f"**[⬇️ Download Frostguard {args.version} for Windows]"
        f"({args.download_url})**\n\n"
        "Extract the complete archive and use the included Frostguard launcher. "
        "Java 21 or newer is "
        f"required.\n\n[📋 Release notes]({args.release_url})"
        f" • [🗂️ Previous stable releases]({args.archive_url})"
    )
    return {
        "content": "",
        "username": "Frostguard Releases",
        "embeds": [{
            "title": f"✅ Frostguard Stable {args.version}",
            "description": truncate(description, 4096),
            "color": 0x2ECC71,
        }],
        "allowed_mentions": {"parse": []},
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--download-url", required=True)
    parser.add_argument("--release-url", required=True)
    parser.add_argument("--archive-url", required=True)
    parser.add_argument(
        "--message-id",
        default="",
        help="edit this maintained webhook message instead of creating a new one",
    )
    parser.add_argument("--webhook-env", default="DISCORD_NIGHTLY_WEBHOOK_URL")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        print("::error::Stable version must use X.Y.Z format.")
        return 1
    for label, value in (("download", args.download_url),
                         ("release", args.release_url),
                         ("archive", args.archive_url)):
        if not value.startswith("https://github.com/"):
            print(f"::error::Stable {label} URL must be a GitHub HTTPS URL.")
            return 1
    if not args.message_id.isdigit():
        print("::error::Stable Discord message ID must be numeric.")
        return 1

    payload = build_payload(args)
    if args.dry_run:
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    webhook = os.environ.get(args.webhook_env, "").strip()
    if not webhook:
        print(f"::error::{args.webhook_env} is empty; stable announcement not sent.")
        return 1
    if not re.match(r"^https://(canary\.|ptb\.)?discord(app)?\.com/api/webhooks/", webhook):
        print(f"::error::{args.webhook_env} is not a Discord webhook URL.")
        return 1
    destination = webhook
    method = "POST"
    if args.message_id:
        destination = f"{webhook.rstrip('/')}/messages/{args.message_id}"
        method = "PATCH"
    post(
        destination,
        json.dumps(payload).encode(),
        "application/json",
        args.timeout,
        method=method,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
