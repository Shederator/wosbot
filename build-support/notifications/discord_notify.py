#!/usr/bin/env python3
"""Create or update the maintained Frostguard Nightly Discord message.

The maintained message links the current immutable native installer and the
permanent rolling Nightly channel. CI-only metrics stay in Actions.

The webhook URL is read from the environment rather than argv, because anything
passed on a command line shows up in the process list and in `set -x` traces.
Nothing in this script ever prints the webhook: Discord webhook URLs are
bearer credentials, and a leaked one lets anyone post to the channel.

Usage:
    DISCORD_NIGHTLY_WEBHOOK_URL=... build-support/notifications/discord_notify.py --status success ...
    build-support/notifications/discord_notify.py --status success --dry-run
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

# Discord's documented limits. Exceeding any of them makes the whole request
# fail with a 400, so every user-controlled string is truncated to fit.
CONTENT_LIMIT = 2000
EMBED_TITLE_LIMIT = 256
EMBED_DESCRIPTION_LIMIT = 4096
EMBED_FIELD_NAME_LIMIT = 256
EMBED_FIELD_VALUE_LIMIT = 1024
EMBED_FOOTER_LIMIT = 2048

# Webhooks on a non-boosted guild may upload at most 8 MiB. Attaching is opt-in
# and falls back to a link when the file is larger, because a failed upload
# would otherwise swallow the notification entirely.
ATTACHMENT_LIMIT_BYTES = 8 * 1024 * 1024
MAX_CHANGE_FIELDS = 4

STATUS_STYLE = {
    # Amber distinguishes an intentionally unstable Nightly from the green
    # Stable announcement without making a successful build look like a fault.
    "success": (0xF1C40F, "Build succeeded"),
    "failure": (0xE74C3C, "Build failed"),
    "cancelled": (0x95A5A6, "Build cancelled"),
    "skipped": (0x95A5A6, "Build skipped"),
}

MAX_ATTEMPTS = 5


def lenient_int(value: str) -> int:
    """Parse an integer that may arrive empty from an unset step output."""
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return 0


def human_size(num_bytes: int) -> str:
    """Format a byte count the way a release page would."""
    if num_bytes <= 0:
        return "unknown"
    size = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} GB"


def truncate(text: str, limit: int) -> str:
    """Cut `text` to `limit` characters, marking that it was cut."""
    text = (text or "").strip()
    if len(text) <= limit:
        return text
    return text[: max(0, limit - 1)].rstrip() + "…"


def first_line(text: str) -> str:
    """A commit subject is the first line; the body is noise in a notification."""
    return (text or "").strip().splitlines()[0].strip() if (text or "").strip() else ""


def change_fields(changes: str, repository: str) -> list[dict]:
    """Split Nightly changes across readable fields within Discord's limits."""
    lines = [truncate(line, EMBED_FIELD_VALUE_LIMIT) for line in changes.splitlines()
             if line.strip()]
    chunks: list[list[str]] = []
    for line in lines:
        if not chunks or len("\n".join([*chunks[-1], line])) > EMBED_FIELD_VALUE_LIMIT:
            chunks.append([line])
        else:
            chunks[-1].append(line)

    if len(chunks) > MAX_CHANGE_FIELDS:
        chunks = chunks[-MAX_CHANGE_FIELDS:]
        release_url = f"https://github.com/{repository}/releases/tag/nightly"
        link = f"• Earlier changes: [view the complete Nightly changelog]({release_url})"
        while chunks[0] and len("\n".join([link, *chunks[0]])) > EMBED_FIELD_VALUE_LIMIT:
            chunks[0].pop(0)
        chunks[0].insert(0, link)

    return [{
        "name": "Changes since the previous Nightly"
        if index == 0 else "Changes since the previous Nightly (continued)",
        "value": "\n".join(chunk),
        "inline": False,
    } for index, chunk in enumerate(chunks)]


def redact(text: str) -> str:
    """Strip anything that looks like a webhook token out of diagnostics."""
    return re.sub(
        r"(https?://[^\s]*?/webhooks/)\d+/[\w-]+",
        r"\1<redacted>",
        text or "",
    )


def build_payload(args: argparse.Namespace) -> dict:
    color, headline = STATUS_STYLE.get(args.status, STATUS_STYLE["failure"])
    version = args.version or "unknown"
    description_parts: list[str] = []
    fields: list[dict] = []

    if args.status == "success":
        if args.download_url and not args.download_url.startswith("http"):
            # A half-populated URL means the release step was skipped or failed.
            # Advertising it would post a dead link, which is worse than no link.
            print(
                "::warning::Ignoring a download URL that is not absolute: "
                f"{args.download_url!r}"
            )
            args.download_url = ""
        if args.download_url:
            description_parts.append(
                "The newest automated development build. It may contain "
                "unfinished or unstable changes."
            )
            description_parts.append(
                f"**[⬇️ Download Frostguard {version} for Windows]"
                f"({args.download_url})**"
            )
            description_parts.append(
                "Run the self-contained per-user MSI installer; a separate Java "
                "installation is not required. Windows may currently show an "
                "Unknown publisher warning."
            )
            links = []
            if args.release_url:
                links.append(f"[📋 Release notes]({args.release_url})")
            if args.channel_url:
                links.append(f"[🌙 Latest Nightly]({args.channel_url})")
            if links:
                description_parts.append(" • ".join(links))
            if args.changes:
                fields.extend(change_fields(args.changes, args.repository))
        elif args.run_url:
            description_parts.append(
                f"Build passed. The artifact is attached to "
                f"[the workflow run]({args.run_url}) "
                "(a GitHub login with repository access is required)."
            )
    else:
        description_parts.append(
            f"{headline}. See [the workflow log]({args.run_url}) for the failing "
            "step." if args.run_url else f"{headline}."
        )

    embed = {
        "title": truncate(
            f"🌙 Frostguard Nightly {version}"
            if args.status == "success"
            else f"Frostguard {version} — {headline.lower()}",
            EMBED_TITLE_LIMIT,
        ),
        "color": color,
        "description": truncate("\n\n".join(description_parts), EMBED_DESCRIPTION_LIMIT),
        "fields": fields,
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    if args.status == "success" and args.download_url:
        embed["url"] = args.download_url
        embed["footer"] = {"text": "Nightly channel • updated automatically"}
    elif args.run_url:
        embed["url"] = args.run_url

    payload = {
        # PATCH preserves omitted fields. Explicitly clear content so the raw
        # URL from the original version of the maintained message disappears.
        "content": "",
        "username": args.username,
        "embeds": [embed],
        # Never let a commit subject containing @everyone ping the channel.
        "allowed_mentions": {"parse": []},
    }
    return payload


def encode_multipart(payload: dict, file_path: str) -> tuple[bytes, str]:
    """Build a multipart/form-data body carrying `payload_json` plus one file."""
    boundary = f"----frostguard{uuid.uuid4().hex}"
    name = os.path.basename(file_path)
    with open(file_path, "rb") as handle:
        blob = handle.read()

    parts: list[bytes] = []
    parts.append(f"--{boundary}\r\n".encode())
    parts.append(b'Content-Disposition: form-data; name="payload_json"\r\n')
    parts.append(b"Content-Type: application/json\r\n\r\n")
    parts.append(json.dumps(payload).encode("utf-8") + b"\r\n")
    parts.append(f"--{boundary}\r\n".encode())
    parts.append(
        f'Content-Disposition: form-data; name="files[0]"; filename="{name}"\r\n'
        .encode()
    )
    parts.append(b"Content-Type: application/octet-stream\r\n\r\n")
    parts.append(blob + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def retry_after_seconds(error: urllib.error.HTTPError, body: str) -> float:
    """Honour Discord's rate-limit hint instead of guessing."""
    header = error.headers.get("Retry-After") if error.headers else None
    if header:
        try:
            return min(60.0, max(1.0, float(header)))
        except ValueError:
            pass
    try:
        return min(60.0, max(1.0, float(json.loads(body).get("retry_after", 5))))
    except (ValueError, AttributeError, TypeError):
        return 5.0


def post(webhook: str, body: bytes, content_type: str, timeout: float,
         method: str = "POST",
         credential_name: str = "DISCORD_NIGHTLY_WEBHOOK_URL") -> bytes:
    """Send a webhook request with retries for rate limits and transient errors."""
    last_error = "no attempt was made"
    for attempt in range(1, MAX_ATTEMPTS + 1):
        request = urllib.request.Request(
            webhook,
            data=body,
            method=method,
            headers={
                "Content-Type": content_type,
                "User-Agent": "frostguard-ci/1.0 (+https://github.com/Shederator/wosbot)",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                response_body = response.read()
                print(f"Discord accepted the notification (HTTP {response.status}).")
                return response_body
        except urllib.error.HTTPError as error:
            detail = ""
            try:
                detail = error.read().decode("utf-8", "replace")
            except OSError:
                pass
            last_error = f"HTTP {error.code}: {redact(detail)[:500]}"
            if error.code == 429:
                delay = retry_after_seconds(error, detail)
                print(f"Rate limited by Discord; retrying in {delay:.1f}s.")
                time.sleep(delay)
                continue
            if 500 <= error.code < 600:
                delay = min(30.0, 2.0**attempt)
                print(f"Discord returned {error.code}; retrying in {delay:.0f}s.")
                time.sleep(delay)
                continue
            # 400/401/404 are permanent: a malformed payload, or a webhook that
            # was rotated or deleted. Retrying cannot help.
            break
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = f"network error: {redact(str(error))}"
            delay = min(30.0, 2.0**attempt)
            print(f"{last_error}; retrying in {delay:.0f}s.")
            time.sleep(delay)

    raise SystemExit(
        f"::error::Could not deliver the Discord notification ({last_error}). "
        f"Check that the {credential_name} secret still points at a "
        "live webhook."
    )


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--status",
        required=True,
        choices=sorted(STATUS_STYLE),
        help="outcome of the build job",
    )
    parser.add_argument("--version", default="")
    parser.add_argument("--bundle-name", default="")
    # A skipped or failed upstream step yields an empty string from
    # `steps.*.outputs.*`, which int() would reject and take the whole
    # notification down with it. Treat unparseable counts as "not measured".
    parser.add_argument("--bundle-bytes", type=lenient_int, default=0)
    parser.add_argument("--jar-count", type=lenient_int, default=0)
    parser.add_argument("--test-count", type=lenient_int, default=0)
    parser.add_argument("--download-url", default="")
    parser.add_argument("--release-url", default="")
    parser.add_argument("--channel-url", default="")
    parser.add_argument("--run-url", default="")
    parser.add_argument("--repository", default="")
    parser.add_argument("--branch", default="")
    parser.add_argument(
        "--trigger",
        default="",
        help="the event that started the run (schedule, workflow_dispatch, ...)",
    )
    parser.add_argument("--commit", default="")
    parser.add_argument("--commit-message", default="")
    parser.add_argument("--actor", default="")
    parser.add_argument("--changes", default="")
    parser.add_argument("--username", default="Frostguard Builds")
    parser.add_argument(
        "--attach",
        default="",
        help="upload this file with the message when it is small enough",
    )
    parser.add_argument(
        "--webhook-env",
        default="DISCORD_NIGHTLY_WEBHOOK_URL",
        help="environment variable holding the webhook URL",
    )
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument(
        "--message-id",
        default="",
        help="edit this existing webhook message instead of creating a new one",
    )
    parser.add_argument(
        "--message-id-output",
        default="",
        help="write the ID of a newly created message to this GitHub output file",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the payload instead of posting it",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    payload = build_payload(args)

    if args.dry_run:
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    webhook = os.environ.get(args.webhook_env, "").strip()
    if not webhook:
        # A missing secret is a configuration gap, not a build failure, but it
        # must be loud: a silent skip looks exactly like a working pipeline.
        print(
            f"::warning::{args.webhook_env} is empty; no Discord notification "
            "was sent."
        )
        return 0
    if not re.match(r"^https://(canary\.|ptb\.)?discord(app)?\.com/api/webhooks/", webhook):
        print(
            f"::error::{args.webhook_env} does not look like a Discord webhook "
            "URL (expected https://discord.com/api/webhooks/<id>/<token>)."
        )
        return 1

    body: bytes
    content_type: str
    if args.attach and os.path.isfile(args.attach):
        size = os.path.getsize(args.attach)
        if size <= ATTACHMENT_LIMIT_BYTES:
            body, content_type = encode_multipart(payload, args.attach)
        else:
            print(
                f"::notice::{os.path.basename(args.attach)} is "
                f"{human_size(size)}, over Discord's {human_size(ATTACHMENT_LIMIT_BYTES)} "
                "webhook upload limit; posting the download link instead."
            )
            body = json.dumps(payload).encode("utf-8")
            content_type = "application/json"
    else:
        body = json.dumps(payload).encode("utf-8")
        content_type = "application/json"

    destination = webhook
    method = "POST"
    if args.message_id:
        if not args.message_id.isdigit():
            print("::error::Discord message ID must be numeric.")
            return 1
        if args.attach:
            print("::error::Attachments are not supported when editing a message.")
            return 1
        destination = f"{webhook.rstrip('/')}/messages/{args.message_id}"
        method = "PATCH"
    elif args.message_id_output:
        # Discord only returns the created message when wait=true is requested.
        separator = "&" if "?" in webhook else "?"
        destination = f"{webhook}{separator}wait=true"

    response_body = post(destination, body, content_type, args.timeout, method=method)
    if args.message_id_output:
        try:
            message_id = str(json.loads(response_body).get("id", ""))
        except (json.JSONDecodeError, AttributeError, TypeError):
            message_id = ""
        if not message_id.isdigit():
            print("::error::Discord did not return a valid created message ID.")
            return 1
        with open(args.message_id_output, "a", encoding="utf-8") as output:
            output.write(f"message_id={message_id}\n")
        print(f"Created maintained Discord message {message_id}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
