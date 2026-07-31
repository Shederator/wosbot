#!/usr/bin/env python3
"""Plan a combined Windows test build for one or more open pull requests.

A tester asks for a build with a list of PR numbers, for example
``/build-pr 47 48 49 65``. Turning that wish into something safe to build takes
more than a ``git merge``:

* A closed or merged PR must be rejected with a reason, not silently skipped.
* Every head commit is **pinned** here. Once a build is planned, a later push to
  a PR branch must not be able to change what gets built and published.
* Stacked PRs overlap. If #48's head already contains #47's head, merging both
  is redundant and merging them in the wrong order can conflict for no reason,
  so contained entries are dropped and the rest is ordered base-to-tip.
* Nothing in here writes to the repository. The planner only reads the API and
  the local object database.

The module is deliberately split into pure functions plus two thin adapters
(:class:`GitHubClient`, :class:`GitCommands`) so the whole decision logic can be
unit-tested without a network or a real repository — see
``ci/test_pr_build_plan.py``.

Usage:
    pr_build_plan.py plan  --repository owner/name --request "47 48,49" \\
        --base-ref main --output plan.json --markdown plan.md
    pr_build_plan.py verify --repository owner/name --plan plan.json
"""

from __future__ import annotations

import argparse
import hashlib
import heapq
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone

DEFAULT_API_BASE = "https://api.github.com"

# A single request may combine at most this many pull requests. The ceiling is a
# flood guard, not a technical limit: every extra PR adds a full ~30 minute
# Windows build to the queue, and a merge plan nobody can review is not useful.
MAX_PULL_REQUESTS = 10

# Length of the hex digest that identifies a build. Long enough that two
# different PR sets cannot collide in practice, short enough for a release tag.
BUILD_KEY_LENGTH = 12

TAG_PREFIX = "pr-test-"

# The publisher embeds this marker in the release notes, and the cleanup reads it
# back. Keeping the bookkeeping inside the release itself means no extra state
# has to be stored anywhere, and an HTML comment is invisible to testers.
MARKER_PATTERN = re.compile(r"<!--\s*frostguard-pr-test\s*(\{.*?\})\s*-->", re.S)

DEFAULT_TTL_DAYS = 7


@dataclass
class PullRequestFacts:
    """The pinned facts about one pull request, as read once from the API."""

    number: int
    title: str = ""
    state: str = ""
    merged: bool = False
    draft: bool = False
    head_sha: str = ""
    head_ref: str = ""
    head_repo: str = ""
    base_ref: str = ""
    author: str = ""
    url: str = ""
    updated_at: str = ""
    error: str = ""

    @property
    def short_sha(self) -> str:
        return self.head_sha[:7]


@dataclass
class Rejection:
    number: int
    reason: str


@dataclass
class Containment:
    number: int
    contained_in: int
    reason: str


@dataclass
class PlanResult:
    order: list[int] = field(default_factory=list)
    rejected: list[Rejection] = field(default_factory=list)
    contained: list[Containment] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


def parse_pr_request(text: str) -> tuple[list[int], list[str], list[int]]:
    """Split a free-form request into PR numbers, junk tokens and duplicates.

    Testers type ``47 48,49``, ``#47, #48`` or ``47/48``. Only decimal numbers
    are accepted; everything else is reported back so the requester learns what
    was ignored instead of silently getting a different build.

    Returns ``(numbers, invalid_tokens, duplicates_removed)`` with the first
    occurrence order preserved.
    """
    tokens = [token for token in re.split(r"[^0-9A-Za-z#_-]+", text or "") if token]

    numbers: list[int] = []
    invalid: list[str] = []
    duplicates: list[int] = []
    for token in tokens:
        # "#47" and "047" are both unambiguous ways of writing 47; anything
        # else (a word, a URL fragment, a range like "47-49") is not a PR
        # number and is reported instead of guessed at.
        candidate = token.lstrip("#")
        if not candidate.isdigit():
            invalid.append(token)
            continue
        value = int(candidate)
        if value <= 0:
            invalid.append(token)
            continue
        if value in numbers:
            duplicates.append(value)
            continue
        numbers.append(value)
    return numbers, invalid, duplicates


def parse_order_override(text: str, allowed: list[int]) -> tuple[list[int], list[str]]:
    """Parse an explicit base-to-tip order, keeping only known PR numbers."""
    numbers, invalid, _ = parse_pr_request(text)
    problems: list[str] = []
    if invalid:
        problems.append(
            "Ignored unparseable tokens in the order override: "
            + ", ".join(sorted(set(invalid)))
        )
    unknown = [number for number in numbers if number not in allowed]
    if unknown:
        problems.append(
            "The order override lists pull requests that are not part of the "
            "plan: " + ", ".join(f"#{number}" for number in unknown)
        )
    kept = [number for number in numbers if number in allowed]
    missing = [number for number in allowed if number not in kept]
    return kept + missing, problems


def classify(facts: PullRequestFacts) -> str:
    """Return an empty string when the PR is buildable, else the reason why not."""
    if facts.error:
        return facts.error
    if facts.merged:
        return "already merged into the base branch"
    if facts.state != "open":
        return f"not open (state: {facts.state or 'unknown'})"
    if not re.fullmatch(r"[0-9a-f]{40}", facts.head_sha or ""):
        return "the API returned no usable head commit"
    return ""


def select_and_order(
    requested: list[int],
    facts: dict[int, PullRequestFacts],
    is_ancestor,
    commit_time,
    repository: str = "",
    order_override: str = "",
) -> PlanResult:
    """Reject unbuildable PRs, drop contained ones and order the rest.

    ``is_ancestor(a, b)`` must report whether commit ``a`` is an ancestor of
    commit ``b``; ``commit_time(sha)`` returns a sortable committer timestamp.
    Both are injected so this function stays free of subprocess calls.
    """
    result = PlanResult()

    kept: list[int] = []
    for number in requested:
        entry = facts.get(number) or PullRequestFacts(number=number, error="not found")
        reason = classify(entry)
        if reason:
            result.rejected.append(Rejection(number=number, reason=reason))
            continue
        if entry.draft:
            result.notes.append(
                f"#{number} is a draft pull request; its code is included anyway."
            )
        kept.append(number)

    # --- containment -------------------------------------------------------
    # A stacked PR's head commit already contains its ancestors' commits, so
    # merging the ancestor as well is pure risk: it either no-ops or, after a
    # rebase, resurrects an older version of the same change.
    dropped: set[int] = set()
    for outer in kept:
        for inner in kept:
            if outer == inner or outer in dropped or inner in dropped:
                continue
            outer_sha = facts[outer].head_sha
            inner_sha = facts[inner].head_sha
            if outer_sha == inner_sha:
                # Two PRs pointing at the same commit: keep the lower number so
                # the choice is stable across re-planning.
                loser, winner = max(outer, inner), min(outer, inner)
                if loser not in dropped:
                    dropped.add(loser)
                    result.contained.append(
                        Containment(
                            number=loser,
                            contained_in=winner,
                            reason="identical head commit",
                        )
                    )
                continue
            if is_ancestor(inner_sha, outer_sha):
                dropped.add(inner)
                result.contained.append(
                    Containment(
                        number=inner,
                        contained_in=outer,
                        reason=(
                            f"{inner_sha[:7]} is an ancestor of "
                            f"{outer_sha[:7]}"
                        ),
                    )
                )

    remaining = [number for number in kept if number not in dropped]
    if not remaining:
        return result

    # --- ordering ----------------------------------------------------------
    # Two independent signals put a stack in base-to-tip order:
    #   1. A PR whose base branch is another requested PR's head branch must be
    #      merged after it (only possible for same-repository PRs, since a fork
    #      cannot target another fork's branch).
    #   2. Otherwise older head commits go first, which keeps the merge sequence
    #      close to the order the work was actually written in.
    dependencies: dict[int, set[int]] = {number: set() for number in remaining}
    for earlier in remaining:
        for later in remaining:
            if earlier == later:
                continue
            same_repo = (
                not repository
                or facts[earlier].head_repo.lower() == repository.lower()
            )
            if (
                same_repo
                and facts[earlier].head_ref
                and facts[later].base_ref == facts[earlier].head_ref
            ):
                dependencies[later].add(earlier)

    times: dict[int, int] = {}
    for number in remaining:
        try:
            times[number] = int(commit_time(facts[number].head_sha))
        except (TypeError, ValueError):
            times[number] = 0

    ready = [(times[n], n) for n in remaining if not dependencies[n]]
    heapq.heapify(ready)
    ordered: list[int] = []
    pending = {number: set(deps) for number, deps in dependencies.items()}
    while ready:
        _, number = heapq.heappop(ready)
        ordered.append(number)
        for other, deps in pending.items():
            if number in deps:
                deps.discard(number)
                if not deps and other not in ordered:
                    heapq.heappush(ready, (times[other], other))

    if len(ordered) != len(remaining):
        # A cycle can only come from contradictory base branches. Falling back
        # to the requested order keeps the build possible; the note explains it.
        leftovers = [number for number in remaining if number not in ordered]
        result.problems.append(
            "Could not derive a stack order for "
            + ", ".join(f"#{number}" for number in leftovers)
            + " (their base branches form a cycle); using the requested order."
        )
        ordered = list(remaining)

    if order_override:
        override, problems = parse_order_override(order_override, ordered)
        result.notes.extend(problems)
        result.notes.append(
            "Merge order was overridden by the requester: "
            + " → ".join(f"#{number}" for number in override)
        )
        ordered = override

    result.order = ordered
    return result


def compute_build_key(base_sha: str, ordered_shas: list[str]) -> str:
    """Derive a stable identifier from the exact commits that will be merged.

    The same PR set at the same commits, on the same base, yields the same key,
    which is what makes "reuse the existing build instead of burning another 30
    minutes of runner time" possible. Any push to any included PR changes it.
    """
    digest = hashlib.sha256()
    digest.update(f"{base_sha}\n".encode())
    for sha in ordered_shas:
        digest.update(f"{sha}\n".encode())
    return digest.hexdigest()[:BUILD_KEY_LENGTH]


class GitHubClient:
    """The few REST calls the planner needs, with a readable failure mode."""

    def __init__(
        self,
        repository: str,
        token: str = "",
        api_base: str = DEFAULT_API_BASE,
        timeout: float = 30.0,
    ) -> None:
        self.repository = repository
        self.token = token
        self.api_base = api_base.rstrip("/")
        self.timeout = timeout

    def _get(self, path: str) -> tuple[int, dict]:
        request = urllib.request.Request(
            f"{self.api_base}{path}",
            headers={
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "frostguard-ci/1.0 (+https://github.com/Shederator/wosbot)",
                **({"Authorization": f"Bearer {self.token}"} if self.token else {}),
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            body = ""
            try:
                body = error.read().decode("utf-8", "replace")[:200]
            except OSError:
                pass
            return error.code, {"message": body}
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            return 0, {"message": str(error)}

    def pull(self, number: int) -> PullRequestFacts:
        status, body = self._get(f"/repos/{self.repository}/pulls/{number}")
        if status == 404:
            return PullRequestFacts(
                number=number,
                error="no such pull request in this repository",
            )
        if status != 200:
            return PullRequestFacts(
                number=number,
                error=f"the GitHub API returned HTTP {status or 'no response'}",
            )
        head = body.get("head") or {}
        head_repo = (head.get("repo") or {}).get("full_name") or ""
        return PullRequestFacts(
            number=number,
            title=body.get("title") or "",
            state=body.get("state") or "",
            merged=bool(body.get("merged")),
            draft=bool(body.get("draft")),
            head_sha=head.get("sha") or "",
            head_ref=head.get("ref") or "",
            head_repo=head_repo,
            base_ref=(body.get("base") or {}).get("ref") or "",
            author=(body.get("user") or {}).get("login") or "",
            url=body.get("html_url") or "",
            updated_at=body.get("updated_at") or "",
        )


class GitCommands:
    """Local read-only git queries. Nothing here writes a ref or pushes."""

    def __init__(self, cwd: str = ".") -> None:
        self.cwd = cwd

    def _run(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["git", *args],
            cwd=self.cwd,
            capture_output=True,
            text=True,
            check=False,
        )

    def fetch_pull_head(self, number: int, remote: str = "origin") -> str:
        """Fetch ``refs/pull/<n>/head`` and return the SHA it resolved to.

        Fetching the base repository's PR ref works for forks too, and it is the
        only way to pin a fork's commit without trusting the fork's remote.
        """
        completed = self._run(
            "fetch",
            "--no-tags",
            "--quiet",
            remote,
            f"+refs/pull/{number}/head:refs/pr-test/{number}",
        )
        if completed.returncode != 0:
            return ""
        return self.rev_parse(f"refs/pr-test/{number}")

    def rev_parse(self, ref: str) -> str:
        completed = self._run("rev-parse", "--verify", f"{ref}^{{commit}}")
        return completed.stdout.strip() if completed.returncode == 0 else ""

    def is_ancestor(self, maybe_ancestor: str, descendant: str) -> bool:
        if not maybe_ancestor or not descendant:
            return False
        return self._run(
            "merge-base", "--is-ancestor", maybe_ancestor, descendant
        ).returncode == 0

    def commit_time(self, sha: str) -> int:
        completed = self._run("show", "-s", "--format=%ct", sha)
        try:
            return int(completed.stdout.strip())
        except ValueError:
            return 0


def plan_to_dict(
    repository: str,
    request_text: str,
    requested: list[int],
    invalid: list[str],
    duplicates: list[int],
    facts: dict[int, PullRequestFacts],
    result: PlanResult,
    base_ref: str,
    base_sha: str,
    requester: str = "",
    conflict_resolution: str = "stop",
) -> dict:
    ordered_shas = [facts[number].head_sha for number in result.order]
    key = compute_build_key(base_sha, ordered_shas)
    return {
        "version": 1,
        "repository": repository,
        "request": request_text,
        "requester": requester,
        "created_at": datetime.now(timezone.utc)
        .isoformat()
        .replace("+00:00", "Z"),
        "base_ref": base_ref,
        "base_sha": base_sha,
        "conflict_resolution": conflict_resolution,
        "requested": requested,
        "invalid_tokens": sorted(set(invalid)),
        "duplicates_removed": sorted(set(duplicates)),
        "rejected": [vars(item) for item in result.rejected],
        "contained": [vars(item) for item in result.contained],
        "problems": result.problems,
        "notes": result.notes,
        "order": result.order,
        "pull_requests": {
            str(number): vars(facts[number])
            for number in result.order
        },
        "build_key": key,
        "tag": f"{TAG_PREFIX}{key}",
        "asset_name": asset_name(result.order),
    }


def asset_name(order: list[int]) -> str:
    """A download filename that says what it is before anyone opens it.

    The name is derived only from the PR numbers, so re-requesting the same set
    produces the same URL and an already-posted link keeps working.
    """
    numbers = "-".join(str(number) for number in order) or "none"
    return f"frostguard-unmerged-test-build-pr-{numbers}.zip"


def render_markdown(plan: dict) -> str:
    """Render the merge plan a human has to approve before resources are spent."""
    lines: list[str] = []
    order = plan.get("order") or []
    lines.append("### Unmerged PR test build — merge plan")
    lines.append("")
    if order:
        lines.append(
            f"Base: `{plan.get('base_ref', 'main')}` at "
            f"`{(plan.get('base_sha') or '')[:7]}` — "
            f"merged in this order:"
        )
        lines.append("")
        lines.append("| # | Pull request | Author | Pinned commit |")
        lines.append("|---|---|---|---|")
        for position, number in enumerate(order, start=1):
            facts = (plan.get("pull_requests") or {}).get(str(number), {})
            title = (facts.get("title") or "").replace("|", "\\|")
            lines.append(
                f"| {position} | [#{number}]({facts.get('url', '')}) {title} "
                f"| @{facts.get('author', 'unknown')} "
                f"| `{(facts.get('head_sha') or '')[:7]}` |"
            )
        lines.append("")
        lines.append(f"Build key: `{plan.get('build_key', '')}`")
    else:
        lines.append("Nothing buildable was requested.")
    lines.append("")

    if plan.get("rejected"):
        lines.append("**Rejected**")
        for item in plan["rejected"]:
            lines.append(f"- #{item['number']}: {item['reason']}")
        lines.append("")
    if plan.get("contained"):
        lines.append("**Already contained in a later head (dropped)**")
        for item in plan["contained"]:
            lines.append(
                f"- #{item['number']} is contained in #{item['contained_in']} "
                f"({item['reason']})"
            )
        lines.append("")
    if plan.get("duplicates_removed"):
        lines.append(
            "**Duplicates removed:** "
            + ", ".join(f"#{number}" for number in plan["duplicates_removed"])
        )
        lines.append("")
    if plan.get("invalid_tokens"):
        lines.append(
            "**Ignored, not a PR number:** "
            + ", ".join(f"`{token}`" for token in plan["invalid_tokens"])
        )
        lines.append("")
    for problem in plan.get("problems") or []:
        lines.append(f"> [!WARNING]\n> {problem}")
        lines.append("")
    for note in plan.get("notes") or []:
        lines.append(f"- {note}")
    if plan.get("notes"):
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def marker_block(prs: list[int], build_key: str, base_sha: str) -> str:
    """Render the machine-readable marker embedded in the release notes."""
    payload = json.dumps(
        {"prs": list(prs), "key": build_key, "base": base_sha},
        sort_keys=True,
    )
    return f"<!-- frostguard-pr-test {payload} -->"


def parse_marker(body: str) -> dict:
    match = MARKER_PATTERN.search(body or "")
    if not match:
        return {}
    try:
        return json.loads(match.group(1))
    except json.JSONDecodeError:
        return {}


def render_notes(plan: dict, ttl_days: int = DEFAULT_TTL_DAYS) -> str:
    """Write the release body for a temporary, unmerged test build.

    The warning comes first and is unmissable. Somebody will find this release
    through a search engine months from now with no idea what ``pr-test-`` means,
    and the page has to tell them not to run it on their real account data.
    """
    base_sha = plan.get("base_sha") or ""
    lines = [
        "> [!CAUTION]",
        "> **Unmerged test build.** This bundle contains pull request code that",
        "> has not been reviewed or merged, and it is not a release. Use a test",
        "> profile, not your main account data.",
        "",
        f"Built by combining these pull requests onto `{plan.get('base_ref', 'main')}` "
        f"at `{base_sha[:7]}`, in this order:",
        "",
    ]
    for position, number in enumerate(plan.get("order") or [], start=1):
        facts = (plan.get("pull_requests") or {}).get(str(number), {})
        lines.append(
            f"{position}. #{number} {facts.get('title', '')} "
            f"(`{(facts.get('head_sha') or '')[:7]}`, @{facts.get('author', 'unknown')})"
        )
    lines += [
        "",
        "**Install:** unzip anywhere, then run `java -jar frostguard-*.jar` "
        "(Java 21+ required).",
        "",
        "Verified: bundle structure, manifest classpath and launch smoke test.",
        "",
        f"This prerelease is deleted automatically after {ttl_days} days, or as "
        "soon as every included pull request is closed or merged.",
        "",
        marker_block(
            plan.get("order") or [], plan.get("build_key", ""), base_sha
        ),
        "",
    ]
    return "\n".join(lines)


def verify_plan(plan: dict, client: GitHubClient) -> list[str]:
    """Re-check, right before publishing, that the plan still describes reality.

    Between planning and publishing there is a full Windows build. A PR can be
    closed, merged or force-pushed in that window, and publishing a download
    that claims to contain a commit it does not is exactly the confusion this
    check exists to prevent.
    """
    problems: list[str] = []
    for number in plan.get("order") or []:
        pinned = (plan.get("pull_requests") or {}).get(str(number), {})
        current = client.pull(int(number))
        reason = classify(current)
        if reason:
            problems.append(f"#{number} is no longer buildable: {reason}")
            continue
        if current.head_sha != pinned.get("head_sha"):
            problems.append(
                f"#{number} moved from {str(pinned.get('head_sha'))[:7]} to "
                f"{current.short_sha} while the build was running."
            )
    return problems


def write_github_output(pairs: dict[str, str]) -> None:
    """Publish step outputs, tolerating a local run with no Actions context."""
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


def command_plan(args: argparse.Namespace) -> int:
    requested, invalid, duplicates = parse_pr_request(args.request)
    if not requested:
        print(
            "::error::No pull request number was given. Expected something "
            'like "47 48 49".'
        )
        return 1
    if len(requested) > MAX_PULL_REQUESTS:
        print(
            f"::error::{len(requested)} pull requests were requested; at most "
            f"{MAX_PULL_REQUESTS} may be combined in one test build."
        )
        return 1

    client = GitHubClient(
        args.repository,
        token=os.environ.get(args.token_env, ""),
        api_base=args.api_base,
    )
    git = GitCommands()

    facts: dict[int, PullRequestFacts] = {}
    for number in requested:
        entry = client.pull(number)
        if not classify(entry):
            # Pin the commit locally as well. If the ref cannot be fetched the
            # build could never reproduce this plan, so treat it as a rejection
            # here rather than as a mysterious failure 30 minutes later.
            fetched = git.fetch_pull_head(number, remote=args.remote)
            if not fetched:
                entry.error = (
                    "its head commit could not be fetched from "
                    f"refs/pull/{number}/head"
                )
            elif fetched != entry.head_sha:
                entry.error = (
                    f"its head moved while planning ({entry.short_sha} → "
                    f"{fetched[:7]}); request the build again"
                )
        facts[number] = entry

    base_sha = git.rev_parse(args.base_sha or f"{args.remote}/{args.base_ref}")
    if not base_sha:
        base_sha = git.rev_parse("HEAD")
    if not base_sha:
        print("::error::Could not resolve the base commit to build on top of.")
        return 1

    result = select_and_order(
        requested,
        facts,
        is_ancestor=git.is_ancestor,
        commit_time=git.commit_time,
        repository=args.repository,
        order_override=args.order,
    )
    plan = plan_to_dict(
        repository=args.repository,
        request_text=args.request,
        requested=requested,
        invalid=invalid,
        duplicates=duplicates,
        facts=facts,
        result=result,
        base_ref=args.base_ref,
        base_sha=base_sha,
        requester=args.requester,
        conflict_resolution=args.conflict_resolution,
    )

    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            json.dump(plan, handle, indent=2, sort_keys=True)
    markdown = render_markdown(plan)
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as handle:
            handle.write(markdown)
    print(markdown)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(markdown)

    write_github_output(
        {
            "buildable": "true" if plan["order"] else "false",
            "build_key": plan["build_key"],
            "tag": plan["tag"],
            "asset_name": plan["asset_name"],
            "pr_list": ",".join(str(number) for number in plan["order"]),
        }
    )

    if not plan["order"]:
        print(
            "::error::None of the requested pull requests can be built. "
            "See the plan above for the reason per pull request."
        )
        return 1
    return 0


def command_verify(args: argparse.Namespace) -> int:
    with open(args.plan, encoding="utf-8") as handle:
        plan = json.load(handle)
    client = GitHubClient(
        plan.get("repository") or args.repository,
        token=os.environ.get(args.token_env, ""),
        api_base=args.api_base,
    )
    problems = verify_plan(plan, client)
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        print(
            "\nThe plan no longer matches the repository, so nothing was "
            "published. Request the build again to pick up the new commits."
        )
        return 1
    print(
        "All "
        f"{len(plan.get('order') or [])} pull requests are still open at the "
        "pinned commits."
    )
    return 0


def command_notes(args: argparse.Namespace) -> int:
    with open(args.plan, encoding="utf-8") as handle:
        plan = json.load(handle)
    notes = render_notes(plan, args.ttl_days)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(notes)
    else:
        print(notes)
    # The publisher needs these three facts for `gh release create`, and reading
    # them out of the same file that produced the notes keeps them consistent.
    write_github_output(
        {
            "base_sha": plan.get("base_sha", ""),
            "tag": plan.get("tag", ""),
            "asset_name": plan.get("asset_name", ""),
            "pr_list": ",".join(str(number) for number in plan.get("order") or []),
        }
    )
    return 0


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-base", default=DEFAULT_API_BASE)
    parser.add_argument(
        "--token-env",
        default="GITHUB_TOKEN",
        help="environment variable holding the API token",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    planner = subparsers.add_parser("plan", help="resolve and order a request")
    planner.add_argument("--repository", required=True)
    planner.add_argument("--request", required=True)
    planner.add_argument("--requester", default="")
    planner.add_argument("--base-ref", default="main")
    planner.add_argument("--base-sha", default="")
    planner.add_argument("--remote", default="origin")
    planner.add_argument("--order", default="", help="explicit base-to-tip order")
    planner.add_argument(
        "--conflict-resolution",
        default="stop",
        choices=("stop", "union"),
        help="what to do when git cannot combine the branches",
    )
    planner.add_argument("--output", default="", help="write the plan JSON here")
    planner.add_argument("--markdown", default="", help="write the summary here")
    planner.set_defaults(func=command_plan)

    verifier = subparsers.add_parser(
        "verify", help="re-check a plan against the live repository"
    )
    verifier.add_argument("--plan", required=True)
    verifier.add_argument("--repository", default="")
    verifier.set_defaults(func=command_verify)

    notes = subparsers.add_parser(
        "notes", help="render the release body for a planned test build"
    )
    notes.add_argument("--plan", required=True)
    notes.add_argument("--output", default="")
    notes.add_argument("--ttl-days", type=int, default=DEFAULT_TTL_DAYS)
    notes.set_defaults(func=command_notes)

    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
