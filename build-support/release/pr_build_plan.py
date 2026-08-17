#!/usr/bin/env python3
"""Plan, merge and re-check combined pull-request test builds.

This is the trusted planning half of the `/build-pr` feature (issue #68). It
never runs code from the requested pull requests: everything here is plain git
plumbing plus the GitHub REST API, executed from the version of this script
that lives on the trusted workflow ref.

Sub-commands
------------
plan     Validate the requested PR numbers, pin every head SHA, drop PRs whose
         head is already contained in another requested head (stacked PRs),
         pick a deterministic base-to-tip order, and trial-merge everything in
         a disposable detached worktree. Writes a machine-readable plan.json
         and (optionally) GitHub Actions step outputs. Never touches `main`
         or any PR branch.
merge    Reproduce the exact merge described by an existing plan.json in the
         current workspace and fail loudly unless the resulting tree is
         bit-identical to the one the plan promised. Used by the untrusted
         build job so the planner and the builder cannot disagree.
recheck  Verify that every PR in a plan is still open and still points at the
         pinned head SHA. Used by the publisher immediately before a release
         is created, so a force-push between "plan" and "publish" cannot ship
         code nobody reviewed under the advertised SHAs.

Design rules carried over from the issue:
- Only numeric PR numbers are accepted; duplicates are deduplicated.
- Closed and merged PRs are rejected with a per-PR explanation.
- Conflicts are reported with the conflicting file list. The script never
  resolves a conflict with `ours`/`theirs`; a conflicted plan is unbuildable.
- The digest over (base SHA + ordered head SHAs) names the release tag, so an
  identical request can reuse an existing build instead of rebuilding.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone

PLAN_VERSION = 1
TAG_PREFIX = "pr-test-"
DIGEST_LENGTH = 12

# More PRs than this in one request is almost certainly a typo, and each extra
# head multiplies the chance of a conflict wall that helps nobody.
MAX_PRS_PER_REQUEST = 6

FULL_SHA_RE = re.compile(r"^[0-9a-f]{40}$")
SHA_PREFIX_RE = re.compile(r"^[0-9a-f]{7,40}$")


class PlanError(Exception):
    """A problem the requester can fix (bad input, closed PR, stale SHA)."""


# ---------------------------------------------------------------------------
# Input parsing
# ---------------------------------------------------------------------------

def parse_pr_numbers(raw: str) -> tuple[list[int], list[str]]:
    """Parse '47, 48 49' into deduplicated PR numbers plus per-token errors.

    Order of first appearance is preserved so the requester's intent survives
    into tie-breaking. Anything that is not a positive integer becomes an
    error message instead of being silently dropped.
    """
    numbers: list[int] = []
    errors: list[str] = []
    seen: set[int] = set()
    for token in re.split(r"[\s,;]+", (raw or "").strip()):
        if not token:
            continue
        # `#47` is how humans write PR numbers; accept it.
        cleaned = token.lstrip("#")
        if not cleaned.isdigit():
            errors.append(f"`{token}` is not a PR number.")
            continue
        number = int(cleaned)
        if number <= 0:
            errors.append(f"`{token}` is not a valid PR number.")
            continue
        if number in seen:
            continue  # Deduplicate silently; repeating a number is harmless.
        seen.add(number)
        numbers.append(number)
    if not numbers and not errors:
        errors.append("No PR numbers were given.")
    if len(numbers) > MAX_PRS_PER_REQUEST:
        errors.append(
            f"{len(numbers)} PRs requested; the limit is {MAX_PRS_PER_REQUEST} "
            "per test build."
        )
    return numbers, errors


def parse_pinned(raw: str) -> tuple[dict[int, str], list[str]]:
    """Parse '47:0123abc,48:4567def' into {number: sha_prefix}."""
    pins: dict[int, str] = {}
    errors: list[str] = []
    for token in re.split(r"[\s,;]+", (raw or "").strip()):
        if not token:
            continue
        number_part, sep, sha_part = token.partition(":")
        if sep != ":" or not sha_part:
            number_part, sep, sha_part = token.partition("@")
        sha_part = sha_part.lower()
        if not number_part.lstrip("#").isdigit() or not SHA_PREFIX_RE.match(sha_part):
            errors.append(f"`{token}` is not a `<pr>:<sha>` pin.")
            continue
        pins[int(number_part.lstrip("#"))] = sha_part
    return pins, errors


# ---------------------------------------------------------------------------
# GitHub REST API (stdlib only, same policy as the notification helpers)
# ---------------------------------------------------------------------------

def github_token() -> str:
    return (os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or "").strip()


def github_api(path: str, token: str) -> dict | list | None:
    """GET one API path. Returns None on 404, raises PlanError otherwise."""
    request = urllib.request.Request(
        f"https://api.github.com{path}",
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "frostguard-pr-test/1.0 (+https://github.com/Shederator/wosbot)",
            "X-GitHub-Api-Version": "2022-11-28",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise PlanError(f"GitHub API {path} failed with HTTP {error.code}.") from error
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise PlanError(f"GitHub API {path} was unreachable: {error}.") from error


@dataclass
class Pull:
    number: int
    title: str
    state: str            # "open" / "closed"
    merged: bool
    draft: bool
    head_sha: str
    head_ref: str
    head_owner: str
    base_ref: str
    author: str

    @property
    def is_open(self) -> bool:
        return self.state == "open"


def fetch_pull(repo: str, number: int, token: str) -> Pull | None:
    data = github_api(f"/repos/{repo}/pulls/{number}", token)
    if data is None:
        return None
    return Pull(
        number=number,
        title=str(data.get("title") or ""),
        state=str(data.get("state") or ""),
        merged=bool(data.get("merged")),
        draft=bool(data.get("draft")),
        head_sha=str((data.get("head") or {}).get("sha") or ""),
        head_ref=str((data.get("head") or {}).get("ref") or ""),
        head_owner=str(
            (((data.get("head") or {}).get("repo") or {}).get("owner") or {}).get("login")
            or ""
        ),
        base_ref=str((data.get("base") or {}).get("ref") or ""),
        author=str((data.get("user") or {}).get("login") or ""),
    )


def validate_pulls(
    numbers: list[int],
    pulls: dict[int, Pull | None],
    pins: dict[int, str],
) -> tuple[list[Pull], list[str]]:
    """Keep only usable PRs; explain every rejection in requester language."""
    usable: list[Pull] = []
    errors: list[str] = []
    for number in numbers:
        pull = pulls.get(number)
        if pull is None:
            errors.append(f"PR #{number} does not exist in this repository.")
            continue
        if pull.merged:
            errors.append(
                f"PR #{number} is already merged; its changes are in `main`."
            )
            continue
        if not pull.is_open:
            errors.append(f"PR #{number} is closed and cannot be test-built.")
            continue
        if not FULL_SHA_RE.match(pull.head_sha):
            errors.append(f"PR #{number} has no resolvable head commit.")
            continue
        pin = pins.get(number)
        if pin and not pull.head_sha.startswith(pin):
            errors.append(
                f"PR #{number} moved since it was planned: pinned `{pin}` but the "
                f"branch now points at `{pull.head_sha[:12]}`. Re-run the plan."
            )
            continue
        usable.append(pull)
    return usable, errors


# ---------------------------------------------------------------------------
# Git plumbing (all read-only against the remote; writes stay in the workspace)
# ---------------------------------------------------------------------------

def run_git(workspace: str, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", workspace, *args],
        capture_output=True,
        text=True,
        check=check,
    )


def fetch_pr_heads(workspace: str, pulls: list[Pull]) -> None:
    """Fetch every pinned head commit into the local object store.

    `pull/N/head` is served by GitHub for every PR regardless of the fork it
    came from, so this works for fork PRs without any credentials beyond the
    ones the checkout already has.
    """
    refspecs = [f"+refs/pull/{p.number}/head:refs/pr-test/{p.number}" for p in pulls]
    result = run_git(workspace, "fetch", "--no-tags", "origin", *refspecs, check=False)
    if result.returncode != 0:
        raise PlanError(
            "Could not fetch the PR head commits from origin: "
            + (result.stderr or result.stdout).strip()[:500]
        )
    for pull in pulls:
        # The pinned SHA must be the commit we actually fetched. If the branch
        # moved between the API call and the fetch, resolve the pinned SHA
        # directly; it stays fetchable for a while even after a force-push.
        have = run_git(workspace, "rev-parse", f"refs/pr-test/{pull.number}").stdout.strip()
        if have != pull.head_sha:
            probe = run_git(workspace, "cat-file", "-e", f"{pull.head_sha}^{{commit}}", check=False)
            if probe.returncode != 0:
                raise PlanError(
                    f"PR #{pull.number} was pushed to while planning "
                    f"(expected `{pull.head_sha[:12]}`, fetched `{have[:12]}`). "
                    "Please retry."
                )


def is_ancestor(workspace: str, ancestor_sha: str, descendant_sha: str) -> bool:
    result = run_git(
        workspace, "merge-base", "--is-ancestor", ancestor_sha, descendant_sha,
        check=False,
    )
    if result.returncode not in (0, 1):
        raise PlanError(
            f"git merge-base failed for {ancestor_sha[:12]}..{descendant_sha[:12]}: "
            + (result.stderr or "").strip()[:300]
        )
    return result.returncode == 0


@dataclass
class Containment:
    kept: list[Pull]
    dropped: list[dict] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


def resolve_containment(workspace: str, base_sha: str, pulls: list[Pull]) -> Containment:
    """Drop PRs whose head is already reachable from the base or another head.

    For a stack 47 <- 48 <- 49, requesting all three keeps only 49: merging 49
    brings the other two along, and merging them again would be a no-op that
    only muddies the report.
    """
    result = Containment(kept=[])
    dropped_numbers: set[int] = set()

    for pull in pulls:
        if is_ancestor(workspace, pull.head_sha, base_sha):
            result.dropped.append({"number": pull.number, "contained_in": "base"})
            result.notes.append(
                f"PR #{pull.number} is already contained in `main` "
                f"(head `{pull.head_sha[:12]}`); it was dropped from the plan."
            )
            dropped_numbers.add(pull.number)

    candidates = [p for p in pulls if p.number not in dropped_numbers]
    for pull in candidates:
        for other in candidates:
            if other.number == pull.number or other.number in dropped_numbers:
                continue
            if pull.head_sha == other.head_sha:
                # Identical heads: keep the lower PR number deterministically.
                if pull.number > other.number:
                    result.dropped.append(
                        {"number": pull.number, "contained_in": other.number}
                    )
                    result.notes.append(
                        f"PR #{pull.number} points at the same commit as "
                        f"#{other.number}; it was dropped as a duplicate."
                    )
                    dropped_numbers.add(pull.number)
                    break
                continue
            if is_ancestor(workspace, pull.head_sha, other.head_sha):
                result.dropped.append(
                    {"number": pull.number, "contained_in": other.number}
                )
                result.notes.append(
                    f"PR #{pull.number} is fully contained in PR #{other.number} "
                    "(stacked); merging the tip covers it."
                )
                dropped_numbers.add(pull.number)
                break

    result.kept = [p for p in pulls if p.number not in dropped_numbers]
    return result


def order_pulls(kept: list[Pull], explicit_order: list[int] | None) -> list[Pull]:
    """Deterministic merge order: requester's explicit order, else ascending.

    After containment removal no kept head is an ancestor of another, so any
    order is *correct*; ascending PR number (oldest work first) is the closest
    thing to base-to-tip that exists for independent branches and is what a
    human would predict.
    """
    if explicit_order:
        wanted = {p.number for p in kept}
        if sorted(explicit_order) != sorted(wanted):
            raise PlanError(
                "The explicit order must be a permutation of the kept PRs "
                f"({', '.join(f'#{n}' for n in sorted(wanted))})."
            )
        by_number = {p.number: p for p in kept}
        return [by_number[n] for n in explicit_order]
    return sorted(kept, key=lambda p: p.number)


def compute_digest(base_sha: str, ordered: list[Pull]) -> str:
    material = base_sha + "|" + ",".join(f"{p.number}@{p.head_sha}" for p in ordered)
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:DIGEST_LENGTH]


# ---------------------------------------------------------------------------
# Trial merge in a disposable detached worktree
# ---------------------------------------------------------------------------

def blob_is_binary(workspace: str, blob_sha: str) -> bool:
    """Git's own heuristic: a NUL byte in the first 8000 bytes means binary."""
    result = subprocess.run(
        ["git", "-C", workspace, "cat-file", "blob", blob_sha],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        return False
    return b"\x00" in result.stdout[:8000]


def conflict_report(workspace: str) -> list[dict]:
    """Describe every unmerged path, flagging binary conflicts explicitly.

    Binary detection reads the actual conflicting index stages instead of
    trusting `diff --numstat`, which reports `0 0` (not `- -`) for unmerged
    binary paths and would silently misclassify every one of them as text.
    """
    stages: dict[str, list[str]] = {}
    for line in run_git(workspace, "ls-files", "-u").stdout.splitlines():
        # "<mode> <sha> <stage>\t<path>"
        meta, _, path = line.partition("\t")
        parts = meta.split()
        if len(parts) == 3 and path:
            stages.setdefault(path, []).append(parts[1])
    return [
        {
            "path": path,
            "binary": any(blob_is_binary(workspace, sha) for sha in shas),
        }
        for path, shas in sorted(stages.items())
    ]


def trial_merge(workspace: str, base_sha: str, ordered: list[Pull]) -> dict:
    """Merge every head onto a detached HEAD at base_sha, base-to-tip.

    Returns {"ok", "tree_sha", "conflicts": [...]}. On conflict the merge is
    aborted and the report names the PR that introduced it plus every
    conflicting file. Nothing here can move a branch: HEAD is detached and no
    push ever happens.
    """
    run_git(workspace, "checkout", "--detach", base_sha)
    # Merge commits need an identity; use an obviously-synthetic one so the
    # throwaway commits can never be mistaken for authored work.
    identity = [
        "-c", "user.name=Frostguard PR test build",
        "-c", "user.email=pr-test-build@users.noreply.github.com",
    ]
    for pull in ordered:
        result = subprocess.run(
            [
                "git", "-C", workspace, *identity, "merge", "--no-ff", "--no-edit",
                "-m", f"test-merge: PR #{pull.number} @ {pull.head_sha[:12]}",
                pull.head_sha,
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            conflicts = conflict_report(workspace)
            run_git(workspace, "merge", "--abort", check=False)
            if not conflicts:
                raise PlanError(
                    f"Merging PR #{pull.number} failed without conflict markers: "
                    + (result.stderr or result.stdout).strip()[:500]
                )
            return {
                "ok": False,
                "tree_sha": "",
                "conflicts": [{"pr": pull.number, "files": conflicts}],
            }
    tree_sha = run_git(workspace, "rev-parse", "HEAD^{tree}").stdout.strip()
    return {"ok": True, "tree_sha": tree_sha, "conflicts": []}


# ---------------------------------------------------------------------------
# Plan document + step outputs
# ---------------------------------------------------------------------------

def release_tag(digest: str) -> str:
    return f"{TAG_PREFIX}{digest}"


def find_reusable_release(repo: str, tag: str, token: str) -> str:
    """Return the bundle download URL when this exact plan was already built."""
    release = github_api(f"/repos/{repo}/releases/tags/{tag}", token)
    if not isinstance(release, dict):
        return ""
    for asset in release.get("assets") or []:
        name = str(asset.get("name") or "")
        if name.endswith(".zip"):
            return str(asset.get("browser_download_url") or "")
    return ""


def write_outputs(path: str, values: dict[str, str]) -> None:
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        for key, value in values.items():
            text = str(value)
            if "\n" in text:
                handle.write(f"{key}<<PR_TEST_EOF\n{text}\nPR_TEST_EOF\n")
            else:
                handle.write(f"{key}={text}\n")


def failure_plan(repo: str, base_ref: str, requested_raw: str, errors: list[str]) -> dict:
    return {
        "version": PLAN_VERSION,
        "ok": False,
        "repository": repo,
        "base_ref": base_ref,
        "base_sha": "",
        "requested": requested_raw,
        "pulls": [],
        "dropped": [],
        "order": [],
        "digest": "",
        "tag": "",
        "merge": {"ok": False, "tree_sha": "", "conflicts": []},
        "errors": errors,
        "notes": [],
        "reuse_url": "",
        "created_utc": datetime.now(timezone.utc).isoformat(),
    }


def build_plan(args: argparse.Namespace, token: str) -> dict:
    """Compute the full plan document. Raises PlanError only for infrastructure
    failures; requester-fixable problems become a failure plan with errors."""
    numbers, parse_errors = parse_pr_numbers(args.prs)
    pins, pin_errors = parse_pinned(args.pinned)
    errors = parse_errors + pin_errors
    if errors or not numbers:
        return failure_plan(args.repo, args.base_ref, args.prs, errors)

    pulls_by_number = {n: fetch_pull(args.repo, n, token) for n in numbers}
    usable, errors = validate_pulls(numbers, pulls_by_number, pins)
    if errors or not usable:
        if not errors:
            errors = ["No usable PRs remain after validation."]
        return failure_plan(args.repo, args.base_ref, args.prs, errors)

    base_sha = run_git(
        args.workspace, "rev-parse", f"refs/remotes/origin/{args.base_ref}",
    ).stdout.strip()
    if not FULL_SHA_RE.match(base_sha):
        return failure_plan(
            args.repo, args.base_ref, args.prs,
            [f"Could not resolve origin/{args.base_ref} in the workspace."],
        )

    fetch_pr_heads(args.workspace, usable)
    containment = resolve_containment(args.workspace, base_sha, usable)
    if not containment.kept:
        plan = failure_plan(
            args.repo, args.base_ref, args.prs,
            ["Every requested PR is already contained in `main`; there is "
             "nothing to build."],
        )
        plan["notes"] = containment.notes
        plan["dropped"] = containment.dropped
        return plan

    explicit = None
    if args.order:
        explicit, order_errors = parse_pr_numbers(args.order)
        if order_errors:
            return failure_plan(args.repo, args.base_ref, args.prs, order_errors)
    ordered = order_pulls(containment.kept, explicit)

    digest = compute_digest(base_sha, ordered)
    tag = release_tag(digest)
    merge = trial_merge(args.workspace, base_sha, ordered)
    reuse_url = ""
    if merge["ok"]:
        reuse_url = find_reusable_release(args.repo, tag, token)

    return {
        "version": PLAN_VERSION,
        "ok": merge["ok"],
        "repository": args.repo,
        "base_ref": args.base_ref,
        "base_sha": base_sha,
        "requested": args.prs,
        "pulls": [
            {
                "number": p.number,
                "title": p.title,
                "head_sha": p.head_sha,
                "head_ref": p.head_ref,
                "head_owner": p.head_owner,
                "author": p.author,
                "draft": p.draft,
            }
            for p in ordered
        ],
        "dropped": containment.dropped,
        "order": [p.number for p in ordered],
        "digest": digest,
        "tag": tag,
        "merge": merge,
        "errors": [] if merge["ok"] else [
            "The PRs do not merge cleanly; see the conflict report."
        ],
        "notes": containment.notes,
        "reuse_url": reuse_url,
        "created_utc": datetime.now(timezone.utc).isoformat(),
    }


def cmd_plan(args: argparse.Namespace) -> int:
    token = github_token()
    try:
        plan = build_plan(args, token)
    except PlanError as error:
        plan = failure_plan(args.repo, args.base_ref, args.prs, [str(error)])

    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(plan, handle, indent=2, sort_keys=True)
        handle.write("\n")

    write_outputs(args.github_output, {
        "ok": "true" if plan["ok"] else "false",
        "digest": plan["digest"],
        "tag": plan["tag"],
        "base_sha": plan["base_sha"],
        "reuse_url": plan.get("reuse_url", ""),
        "order": ",".join(str(n) for n in plan["order"]),
        "pinned": ",".join(
            f"{p['number']}:{p['head_sha'][:12]}" for p in plan["pulls"]
        ),
    })

    for note in plan.get("notes", []):
        print(f"::notice::{note}")
    for error in plan.get("errors", []):
        print(f"::error::{error}")
    if plan["ok"]:
        heads = ", ".join(
            f"#{p['number']}@{p['head_sha'][:12]}" for p in plan["pulls"]
        )
        print(f"Plan {plan['digest']}: merge {heads} onto {plan['base_sha'][:12]}")
    return 0 if plan["ok"] else 1


def cmd_merge(args: argparse.Namespace) -> int:
    with open(args.plan, encoding="utf-8") as handle:
        plan = json.load(handle)
    if not plan.get("ok"):
        print("::error::The plan is not buildable; refusing to merge.")
        return 1

    pulls = [
        Pull(
            number=int(p["number"]),
            title=str(p.get("title") or ""),
            state="open",
            merged=False,
            draft=bool(p.get("draft")),
            head_sha=str(p["head_sha"]),
            head_ref=str(p.get("head_ref") or ""),
            head_owner=str(p.get("head_owner") or ""),
            base_ref=str(plan.get("base_ref") or "main"),
            author=str(p.get("author") or ""),
        )
        for p in plan["pulls"]
    ]
    try:
        fetch_pr_heads(args.workspace, pulls)
        probe = run_git(
            args.workspace, "cat-file", "-e", f"{plan['base_sha']}^{{commit}}",
            check=False,
        )
        if probe.returncode != 0:
            raise PlanError(
                f"Base commit {plan['base_sha'][:12]} is not in the workspace; "
                "fetch main history first."
            )
        merge = trial_merge(args.workspace, plan["base_sha"], pulls)
    except PlanError as error:
        print(f"::error::{error}")
        return 1
    if not merge["ok"]:
        print("::error::The merge conflicted although the plan said it would not. "
              "A branch probably moved; re-run the plan.")
        return 1
    if merge["tree_sha"] != plan["merge"]["tree_sha"]:
        print(
            "::error::The merged tree does not match the planned tree "
            f"({merge['tree_sha'][:12]} != {plan['merge']['tree_sha'][:12]}). "
            "Refusing to build an unplanned tree."
        )
        return 1
    print(f"Workspace now holds the planned merge (tree {merge['tree_sha'][:12]}).")
    return 0


def cmd_recheck(args: argparse.Namespace) -> int:
    with open(args.plan, encoding="utf-8") as handle:
        plan = json.load(handle)
    token = github_token()
    problems: list[str] = []
    for entry in plan.get("pulls", []):
        number = int(entry["number"])
        try:
            pull = fetch_pull(plan["repository"], number, token)
        except PlanError as error:
            problems.append(str(error))
            continue
        if pull is None:
            problems.append(f"PR #{number} disappeared.")
        elif pull.merged:
            problems.append(f"PR #{number} was merged after planning.")
        elif not pull.is_open:
            problems.append(f"PR #{number} was closed after planning.")
        elif pull.head_sha != entry["head_sha"]:
            problems.append(
                f"PR #{number} was pushed to after planning "
                f"(`{entry['head_sha'][:12]}` -> `{pull.head_sha[:12]}`)."
            )
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        write_outputs(args.github_output, {"fresh": "false",
                                           "problems": "\n".join(problems)})
        return 1
    write_outputs(args.github_output, {"fresh": "true", "problems": ""})
    print("Every planned PR is still open and unchanged.")
    return 0


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    plan = sub.add_parser("plan", help="validate, pin, order and trial-merge")
    plan.add_argument("--prs", required=True, help="e.g. '47,48,49' or '47 48'")
    plan.add_argument("--pinned", default="", help="e.g. '47:0123abcdef01,48:...'")
    plan.add_argument("--order", default="", help="explicit merge order override")
    plan.add_argument("--repo", required=True, help="owner/name")
    plan.add_argument("--base-ref", default="main")
    plan.add_argument("--workspace", default=".")
    plan.add_argument("--output", default="plan.json")
    plan.add_argument("--github-output", default=os.environ.get("GITHUB_OUTPUT", ""))
    plan.set_defaults(func=cmd_plan)

    merge = sub.add_parser("merge", help="reproduce a planned merge exactly")
    merge.add_argument("--plan", required=True)
    merge.add_argument("--workspace", default=".")
    merge.set_defaults(func=cmd_merge)

    recheck = sub.add_parser("recheck", help="verify PRs are still open/unchanged")
    recheck.add_argument("--plan", required=True)
    recheck.add_argument("--github-output", default=os.environ.get("GITHUB_OUTPUT", ""))
    recheck.set_defaults(func=cmd_recheck)

    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        return args.func(args)
    except PlanError as error:
        print(f"::error::{error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
