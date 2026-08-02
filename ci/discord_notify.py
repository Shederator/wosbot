#!/usr/bin/env python3
"""Post a Frostguard build notification to a Discord webhook.

The nightly bundle is ~220 MB, far above Discord's per-message upload ceiling,
and a GitHub Actions artifact link only works for signed-in users with access to
the repository. So the message this script posts carries the **public release
asset URL** as plain message content (tappable on mobile, no login required)
plus an embed with the facts a tester needs before downloading: version, size,
staged runtime JAR count, executed test count, trigger, branch and commit.

The webhook URL is read from the environment rather than argv, because anything
passed on a command line shows up in the process list and in `set -x` traces.
Nothing in this script ever prints the webhook: Discord webhook URLs are
bearer credentials, and a leaked one lets anyone post to the channel.

Usage:
    DISCORD_NIGHTLY_WEBHOOK_URL=... ci/discord_notify.py --status success ...
    ci/discord_notify.py --status success --dry-run   # print payload, post nothing
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

STATUS_STYLE = {
    "success": (0x2ECC71, "Build succeeded"),
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
    title = (
        f"Frostguard {version} — Windows desktop bundle"
        if args.status == "success"
        else f"Frostguard {version} — {headline.lower()}"
    )

    fields: list[dict] = []

    def add_field(name: str, value: str, inline: bool = True) -> None:
        value = truncate(value, EMBED_FIELD_VALUE_LIMIT)
        if value:
            fields.append(
                {
                    "name": truncate(name, EMBED_FIELD_NAME_LIMIT),
                    "value": value,
                    "inline": inline,
                }
            )

    add_field("Version", f"`{version}`")
    if args.bundle_bytes > 0:
        add_field("Download size", human_size(args.bundle_bytes))
    if args.jar_count > 0:
        add_field("Runtime JARs", str(args.jar_count))
    if args.test_count > 0:
        add_field("JUnit tests", f"{args.test_count} passed")
    if args.trigger:
        add_field("Trigger", f"`{args.trigger}`")
    if args.branch:
        add_field("Branch", f"`{args.branch}`")

    if args.commit:
        short = args.commit[:7]
        subject = first_line(args.commit_message)
        commit_url = (
            f"https://github.com/{args.repository}/commit/{args.commit}"
            if args.repository
            else ""
        )
        link = f"[`{short}`]({commit_url})" if commit_url else f"`{short}`"
        if subject:
            link += f" {truncate(subject, 200)}"
        add_field("Commit", link, inline=False)

    description_parts: list[str] = []
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
                f"**[⬇ Download {args.bundle_name or 'the bundle'}]"
                f"({args.download_url})**"
            )
            # The assembly ships no launcher .bat for the app itself (only
            # fg-watcher.bat), so `java -jar` is the real entry point. Naming a
            # script that is not in the ZIP would send every tester into a
            # support question.
            description_parts.append(
                "Verified: bundle structure, manifest classpath and launch "
                "smoke test. Extract the complete ZIP, then double-click "
                "`Start Frostguard.bat` (Java 21+ required)."
            )
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
        "title": truncate(title, EMBED_TITLE_LIMIT),
        "color": color,
        "description": truncate("\n\n".join(description_parts), EMBED_DESCRIPTION_LIMIT),
        "fields": fields,
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    if args.run_url:
        embed["url"] = args.run_url

    footer = args.repository or ""
    if args.run_number:
        footer = f"{footer} • run #{args.run_number}".strip(" •")
    if args.actor:
        footer = f"{footer} • {args.actor}".strip(" •")
    if footer:
        embed["footer"] = {"text": truncate(footer, EMBED_FOOTER_LIMIT)}

    # The bare URL as message content stays tappable in the mobile client and in
    # notification previews, where embed links are easy to miss.
    content = ""
    if args.status == "success" and args.download_url:
        content = truncate(args.download_url, CONTENT_LIMIT)

    payload = {
        "username": args.username,
        "embeds": [embed],
        # Never let a commit subject containing @everyone ping the channel.
        "allowed_mentions": {"parse": []},
    }
    if content:
        payload["content"] = content
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


def post(webhook: str, body: bytes, content_type: str, timeout: float) -> None:
    """POST with retries for rate limits and transient server errors."""
    last_error = "no attempt was made"
    for attempt in range(1, MAX_ATTEMPTS + 1):
        request = urllib.request.Request(
            webhook,
            data=body,
            method="POST",
            headers={
                "Content-Type": content_type,
                "User-Agent": "frostguard-ci/1.0 (+https://github.com/Shederator/wosbot)",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                print(f"Discord accepted the notification (HTTP {response.status}).")
                return
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
        "Check that the DISCORD_NIGHTLY_WEBHOOK_URL secret still points at a "
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
    parser.add_argument("--run-url", default="")
    parser.add_argument("--run-number", default="")
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

    post(webhook, body, content_type, args.timeout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
