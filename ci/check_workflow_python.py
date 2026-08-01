#!/usr/bin/env python3
"""Compile every inline Python snippet embedded in the GitHub workflows.

A `python3 -c '...'` block inside a workflow only fails when the job that
contains it actually runs, which for the PR test build means the failure lands
in the publish job after a 30-minute Maven build. That is exactly what happened
with a snippet that used `\\"` escapes inside a shell single-quoted f-string:

    print(f"- #{p[\\"number\\"]} ...")   # SyntaxError at run time

This checker extracts those snippets and compiles them, so the mistake fails in
seconds inside the planner test step instead of an hour later.

It deliberately does not execute anything: `compile()` only parses.

Usage:
    python3 ci/check_workflow_python.py [workflow_or_directory ...]

With no arguments it checks both .github/workflows and the staged copies under
setup/github-workflows, because setup/install-workflows.sh copies the latter
over the former — a fix applied to only one of the two comes straight back.
"""

from __future__ import annotations

import sys
import textwrap
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TARGETS = (
    REPO_ROOT / ".github" / "workflows",
    REPO_ROOT / "setup" / "github-workflows",
)

# Markers that open an inline Python program inside a workflow `run:` block.
INLINE_MARKERS = ("python3 -c '", "python -c '")
HEREDOC_MARKERS = ("python3 - <<'", "python3 <<'", "python - <<'", "python <<'")


class Snippet:
    """One inline Python program together with where it came from."""

    def __init__(self, path: Path, line: int, source: str) -> None:
        self.path = path
        self.line = line
        self.source = source

    @property
    def where(self) -> str:
        rel = self.path
        try:
            rel = self.path.relative_to(REPO_ROOT)
        except ValueError:
            pass
        return f"{rel}:{self.line}"


def _extract_single_quoted(lines: list[str], start: int) -> tuple[list[str], int]:
    """Collect the body of a `python3 -c '` block that opens at ``start``.

    The block ends on the first line whose stripped form begins with the
    closing single quote, which is how these snippets are written in the
    workflows. Returns the body lines and the index of that closing line.
    """
    body: list[str] = []
    index = start + 1
    while index < len(lines):
        stripped = lines[index].strip()
        if stripped.startswith("'"):
            return body, index
        body.append(lines[index])
        index += 1
    return body, index


def _extract_heredoc(lines: list[str], start: int, terminator: str) -> tuple[list[str], int]:
    """Collect a `python3 - <<'TAG'` heredoc body opened at ``start``."""
    body: list[str] = []
    index = start + 1
    while index < len(lines):
        if lines[index].strip() == terminator:
            return body, index
        body.append(lines[index])
        index += 1
    return body, index


def find_snippets(path: Path) -> list[Snippet]:
    """Return every inline Python program found in ``path``."""
    lines = path.read_text(encoding="utf-8").splitlines()
    snippets: list[Snippet] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        marker = next((m for m in INLINE_MARKERS if m in line), None)
        if marker is not None:
            after = line.split(marker, 1)[1]
            if after.strip():
                # Whole program on one line: `python3 -c 'print(1)'`.
                program = after.rsplit("'", 1)[0]
                snippets.append(Snippet(path, index + 1, program))
                index += 1
                continue
            body, end = _extract_single_quoted(lines, index)
            snippets.append(Snippet(path, index + 2, textwrap.dedent("\n".join(body))))
            index = end + 1
            continue

        heredoc = next((m for m in HEREDOC_MARKERS if m in line), None)
        if heredoc is not None and "'" in line.split(heredoc, 1)[1] + "'":
            tag = line.split(heredoc, 1)[1].split("'", 1)[0]
            if tag:
                body, end = _extract_heredoc(lines, index, tag)
                snippets.append(Snippet(path, index + 2, textwrap.dedent("\n".join(body))))
                index = end + 1
                continue

        index += 1
    return snippets


def workflow_files(targets: list[str]) -> list[Path]:
    """Expand command line targets into a sorted list of workflow files."""
    paths: list[Path] = []
    for target in targets:
        path = Path(target)
        if path.is_dir():
            paths.extend(sorted(path.glob("*.yml")) + sorted(path.glob("*.yaml")))
        else:
            paths.append(path)
    return paths


def main(argv: list[str]) -> int:
    targets = argv[1:] or [str(p) for p in DEFAULT_TARGETS if p.is_dir()]
    paths = workflow_files(targets)
    if not paths:
        print(f"No workflow files found in: {', '.join(targets)}", file=sys.stderr)
        return 1

    failures = 0
    checked = 0
    for path in paths:
        if not path.is_file():
            print(f"Missing workflow file: {path}", file=sys.stderr)
            failures += 1
            continue
        for snippet in find_snippets(path):
            checked += 1
            try:
                compile(snippet.source, snippet.where, "exec")
            except SyntaxError as error:
                failures += 1
                print(f"{snippet.where}: inline Python does not compile", file=sys.stderr)
                print(f"  {type(error).__name__}: {error.msg} (line {error.lineno})", file=sys.stderr)
                if error.text:
                    print(f"  {error.text.rstrip()}", file=sys.stderr)
                if "\\" in snippet.source:
                    print(
                        "  Hint: these snippets sit inside shell single quotes, "
                        "so backslash escapes such as \\\" reach Python verbatim. "
                        "Use str.format() or a different quote style instead.",
                        file=sys.stderr,
                    )

    if failures:
        print(f"\n{failures} inline Python snippet(s) failed to compile.", file=sys.stderr)
        return 1
    print(f"All {checked} inline workflow Python snippet(s) compile.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
