#!/usr/bin/env python3
"""Combine pinned pull-request commits in a disposable test workspace.

What this script must never do is as important as what it does:

* It never touches ``main`` and never touches a PR branch. The combination
  happens on a throwaway local branch (``pr-test/<build key>``) that is only
  ever created inside the checkout of one CI job and is never pushed.
* It never resolves a conflict by picking ``--ours`` or ``--theirs``. Silently
  discarding one side of a conflict produces a build that behaves like neither
  pull request, and the tester has no way to notice.

Conflicts therefore stop the run by default. The requester gets the exact list
of conflicting files. Optionally (``--resolution union``) the script prepares a
*proposal* that keeps both sides of every text conflict, writes the resulting
diff out for review, and — because a union merge can produce code that compiles
but is nonsense — labels the outcome as a proposal in the report. Binary
conflicts are never resolved automatically: there is no meaningful union of two
PNGs or two DLLs.

Usage:
    pr_build_combine.py --plan plan.json --report combine-report.json \\
        [--resolution stop|union] [--proposal-diff proposal.diff] [--repo .]
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

# A conflicted file has up to three staged blobs: 1 = merge base, 2 = ours,
# 3 = theirs. A missing stage means the file was added or deleted on that side.
STAGE_BASE, STAGE_OURS, STAGE_THEIRS = 1, 2, 3


class GitError(RuntimeError):
    pass


class Git:
    """Thin wrapper that fails loudly and keeps the workspace read-auditable."""

    def __init__(self, repo: str = ".") -> None:
        self.repo = repo

    def run(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        if "push" in args:
            # Belt and braces: this script has no business writing to a remote,
            # and a future edit that adds a push should fail here immediately.
            raise GitError("pr_build_combine.py must never push")
        completed = subprocess.run(
            ["git", *args],
            cwd=self.repo,
            capture_output=True,
            text=True,
            check=False,
        )
        if check and completed.returncode != 0:
            raise GitError(
                f"git {' '.join(args)} failed with {completed.returncode}: "
                f"{completed.stderr.strip() or completed.stdout.strip()}"
            )
        return completed

    def out(self, *args: str) -> str:
        return self.run(*args).stdout.strip()

    def unmerged_paths(self) -> list[str]:
        listing = self.run("diff", "--name-only", "--diff-filter=U", "-z").stdout
        return [path for path in listing.split("\0") if path]

    def stage_blob(self, stage: int, path: str) -> bytes | None:
        completed = subprocess.run(
            ["git", "show", f":{stage}:{path}"],
            cwd=self.repo,
            capture_output=True,
            check=False,
        )
        return completed.stdout if completed.returncode == 0 else None

    def is_lfs_tracked(self, path: str) -> bool:
        value = self.run("check-attr", "filter", "--", path, check=False).stdout
        return value.strip().endswith(": lfs")


def looks_binary(blob: bytes | None) -> bool:
    """A NUL byte in the first 8 KiB is git's own heuristic for "binary"."""
    if blob is None:
        return False
    return b"\0" in blob[:8192]


def describe_conflict(git: Git, path: str) -> dict:
    """Collect enough about one conflicted file to explain it in a message."""
    base = git.stage_blob(STAGE_BASE, path)
    ours = git.stage_blob(STAGE_OURS, path)
    theirs = git.stage_blob(STAGE_THEIRS, path)

    if ours is None or theirs is None:
        # One side deleted or renamed the file while the other changed it. There
        # is no text merge for that; a human has to decide.
        kind = "delete/modify"
    elif looks_binary(base) or looks_binary(ours) or looks_binary(theirs):
        kind = "binary"
    elif git.is_lfs_tracked(path):
        kind = "binary"
    else:
        kind = "text"

    return {
        "path": path,
        "kind": kind,
        "resolvable_by_union": kind == "text",
    }


def union_resolve(git: Git, path: str) -> bool:
    """Rewrite one text conflict so that both sides survive.

    ``git merge-file --union`` keeps every line from both sides instead of
    choosing one, which is the only automatic resolution that cannot silently
    drop a pull request's change. It can still produce duplicated statements, so
    the caller must show the diff and ask before building it.
    """
    base = git.stage_blob(STAGE_BASE, path) or b""
    ours = git.stage_blob(STAGE_OURS, path)
    theirs = git.stage_blob(STAGE_THEIRS, path)
    if ours is None or theirs is None:
        return False

    root = git.out("rev-parse", "--show-toplevel")
    scratch = os.path.join(root, ".git", "pr-test-union")
    os.makedirs(scratch, exist_ok=True)
    names = {}
    for label, blob in (("base", base), ("ours", ours), ("theirs", theirs)):
        names[label] = os.path.join(scratch, f"{label}.blob")
        with open(names[label], "wb") as handle:
            handle.write(blob)

    merged = subprocess.run(
        [
            "git",
            "merge-file",
            "--union",
            "-p",
            "-L",
            "combined so far",
            "-L",
            "merge base",
            "-L",
            f"incoming ({path})",
            names["ours"],
            names["base"],
            names["theirs"],
        ],
        cwd=git.repo,
        capture_output=True,
        check=False,
    )
    # --union never leaves markers behind, so a non-zero exit means the inputs
    # could not be merged at all rather than "there were conflicts".
    if merged.returncode != 0 and not merged.stdout:
        return False

    target = os.path.join(root, path)
    os.makedirs(os.path.dirname(target) or root, exist_ok=True)
    with open(target, "wb") as handle:
        handle.write(merged.stdout)
    git.run("add", "--", path)
    return True


def combine(
    git: Git,
    plan: dict,
    resolution: str = "stop",
    proposal_diff: str = "",
) -> dict:
    """Merge every planned head onto a disposable branch. Returns the report."""
    order = plan.get("order") or []
    pulls = plan.get("pull_requests") or {}
    base_sha = plan.get("base_sha") or ""
    branch = f"pr-test/{plan.get('build_key', 'workspace')}"

    report: dict = {
        "status": "clean",
        "branch": branch,
        "base_sha": base_sha,
        "merges": [],
        "conflicts": [],
        "resolution": resolution,
        "resolved_paths": [],
        "head_sha": "",
    }

    if not order:
        report["status"] = "empty"
        return report

    # A detached, freshly created branch means the merge cannot possibly move
    # main or any PR branch, even if a later step misbehaves.
    git.run("checkout", "--force", "-B", branch, base_sha)

    for number in order:
        facts = pulls.get(str(number), {})
        sha = facts.get("head_sha") or ""
        title = facts.get("title") or ""
        if not sha:
            report["status"] = "error"
            report["error"] = f"#{number} has no pinned commit in the plan"
            return report

        message = f"test merge: PR #{number} {title}".strip()
        merged = git.run(
            "merge",
            "--no-ff",
            "--no-edit",
            "-m",
            message,
            sha,
            check=False,
        )
        if merged.returncode == 0:
            report["merges"].append(
                {"number": number, "sha": sha, "status": "merged"}
            )
            continue

        conflicts = [describe_conflict(git, path) for path in git.unmerged_paths()]
        if not conflicts:
            # Merge failed for a reason other than a content conflict, e.g. a
            # local modification or an unrelated-history refusal.
            git.run("merge", "--abort", check=False)
            report["status"] = "error"
            report["error"] = (
                f"merging #{number} ({sha[:7]}) failed without a content "
                f"conflict: {merged.stderr.strip()[:400]}"
            )
            return report

        report["conflicts"].append(
            {"number": number, "sha": sha, "files": conflicts}
        )

        unresolvable = [item for item in conflicts if not item["resolvable_by_union"]]
        if resolution != "union" or unresolvable:
            git.run("merge", "--abort", check=False)
            report["status"] = "conflict"
            return report

        for item in conflicts:
            if not union_resolve(git, item["path"]):
                git.run("merge", "--abort", check=False)
                report["status"] = "conflict"
                return report
            report["resolved_paths"].append(item["path"])

        ours_before = git.out("rev-parse", "HEAD")
        git.run(
            "commit",
            "--no-verify",
            "-m",
            f"{message} (union resolution proposal)",
        )
        report["merges"].append(
            {
                "number": number,
                "sha": sha,
                "status": "merged with union resolution",
                "conflicting_files": [item["path"] for item in conflicts],
            }
        )
        if proposal_diff:
            # Show what the proposal changed relative to the combination as it
            # stood before this merge. That is the diff a reviewer needs in
            # order to accept or reject the automatic resolution.
            diff = git.run(
                "diff",
                f"{ours_before}..HEAD",
                "--",
                *[item["path"] for item in conflicts],
                check=False,
            ).stdout
            with open(proposal_diff, "a", encoding="utf-8") as handle:
                handle.write(f"# union resolution proposal for PR #{number}\n")
                handle.write(diff)
                handle.write("\n")
        report["status"] = "resolved"

    report["head_sha"] = git.out("rev-parse", "HEAD")
    report["commits"] = len(
        git.run("rev-list", f"{base_sha}..HEAD").stdout.split()
    )
    return report


def render_conflict_report(report: dict, plan: dict) -> str:
    """Explain a stopped merge in a way a non-git-expert can act on."""
    lines = ["### The pull requests cannot be combined", ""]
    for entry in report.get("conflicts") or []:
        number = entry["number"]
        facts = (plan.get("pull_requests") or {}).get(str(number), {})
        lines.append(
            f"Merging [#{number}]({facts.get('url', '')}) "
            f"(`{entry['sha'][:7]}`) conflicts in:"
        )
        lines.append("")
        for item in entry["files"]:
            note = {
                "text": "text conflict",
                "binary": "binary file — needs a manual choice",
                "delete/modify": "changed on one side, removed on the other",
            }.get(item["kind"], item["kind"])
            lines.append(f"- `{item['path']}` — {note}")
        lines.append("")

    resolvable = all(
        item["resolvable_by_union"]
        for entry in report.get("conflicts") or []
        for item in entry["files"]
    )
    if resolvable and report.get("conflicts"):
        lines.append(
            "Every conflict is a text conflict, so a resolution that keeps "
            "**both** sides can be attempted. Re-request the build with "
            "conflict resolution set to `union`; the proposed diff is posted "
            "for review before anything is published."
        )
    else:
        lines.append(
            "At least one conflict cannot be resolved automatically, so the "
            "authors have to rebase or a maintainer has to combine the changes "
            "by hand. Nothing was changed in the repository."
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--report", default="combine-report.json")
    parser.add_argument("--markdown", default="", help="write the summary here")
    parser.add_argument("--proposal-diff", default="")
    parser.add_argument(
        "--resolution",
        default="stop",
        choices=("stop", "union"),
        help="stop on conflict, or propose a both-sides union resolution",
    )
    parser.add_argument("--repo", default=".")
    args = parser.parse_args(argv)

    with open(args.plan, encoding="utf-8") as handle:
        plan = json.load(handle)

    git = Git(args.repo)
    try:
        report = combine(git, plan, args.resolution, args.proposal_diff)
    except GitError as error:
        report = {"status": "error", "error": str(error), "conflicts": []}

    with open(args.report, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2, sort_keys=True)

    markdown = ""
    if report["status"] in {"conflict", "error"}:
        markdown = render_conflict_report(report, plan)
        if report.get("error"):
            markdown += f"\n`{report['error']}`\n"
    elif report["status"] == "resolved":
        markdown = (
            "### Conflicts were resolved with a union proposal\n\n"
            "The following files kept **both** sides of the conflict:\n\n"
            + "".join(f"- `{path}`\n" for path in report["resolved_paths"])
            + "\nReview the attached `proposal.diff` before trusting the "
            "resulting build.\n"
        )
    if args.markdown and markdown:
        with open(args.markdown, "w", encoding="utf-8") as handle:
            handle.write(markdown)
    if markdown:
        print(markdown)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary and markdown:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(markdown)

    if report["status"] in {"conflict", "error", "empty"}:
        print(f"::error::Combining the pull requests failed ({report['status']}).")
        return 1
    print(
        f"Combined {len(report['merges'])} pull request(s) onto "
        f"{report['base_sha'][:7]} as {report['head_sha'][:7]}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
