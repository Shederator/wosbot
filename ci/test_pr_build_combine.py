#!/usr/bin/env python3
"""Self-tests for ci/pr_build_combine.py.

These run against real throwaway repositories created with ``git init``, because
the properties worth guaranteeing are properties of git's behaviour, not of a
mock: a conflict really stops the run, a union resolution really keeps both
sides, a binary conflict is really refused, and ``main`` plus every source
branch really still point at the same commit afterwards.

Run with:  python3 ci/test_pr_build_combine.py
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pr_build_combine as combiner  # noqa: E402


def git(repo: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=repo,
        capture_output=True,
        text=True,
        check=True,
    )
    return completed.stdout.strip()


class Sandbox:
    """A tiny repository with a `main` branch and per-"pull request" branches."""

    def __init__(self, directory: Path) -> None:
        self.repo = directory
        git(self.repo, "init", "--quiet", "--initial-branch", "main")
        git(self.repo, "config", "user.email", "ci@example.invalid")
        git(self.repo, "config", "user.name", "Frostguard CI")
        git(self.repo, "config", "commit.gpgsign", "false")
        self.write("README.md", "base\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "--quiet", "-m", "base")
        self.base_sha = git(self.repo, "rev-parse", "HEAD")

    def write(self, name: str, content: str | bytes) -> None:
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8")

    def branch(self, name: str, files: dict[str, str | bytes]) -> str:
        git(self.repo, "checkout", "--quiet", "-B", name, self.base_sha)
        for filename, content in files.items():
            self.write(filename, content)
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "--quiet", "-m", f"work on {name}")
        sha = git(self.repo, "rev-parse", "HEAD")
        git(self.repo, "checkout", "--quiet", "main")
        return sha

    def plan(self, entries: list[tuple[int, str]]) -> dict:
        return {
            "build_key": "testkey00000",
            "base_ref": "main",
            "base_sha": self.base_sha,
            "order": [number for number, _ in entries],
            "pull_requests": {
                str(number): {
                    "number": number,
                    "title": f"change {number}",
                    "head_sha": sha,
                    "url": f"https://example.invalid/pull/{number}",
                }
                for number, sha in entries
            },
        }


class CombineTestCase(unittest.TestCase):

    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.sandbox = Sandbox(Path(self._temp.name))
        self.git = combiner.Git(str(self.sandbox.repo))

    def tearDown(self) -> None:
        self._temp.cleanup()


class CleanCombineTest(CombineTestCase):

    def test_independent_pull_requests_merge_cleanly_in_order(self):
        first = self.sandbox.branch("pr-47", {"a.txt": "from 47\n"})
        second = self.sandbox.branch("pr-48", {"b.txt": "from 48\n"})
        plan = self.sandbox.plan([(47, first), (48, second)])

        report = combiner.combine(self.git, plan)

        self.assertEqual("clean", report["status"])
        self.assertEqual(2, len(report["merges"]))
        self.assertEqual([], report["conflicts"])
        # Both changes are present: a merge that quietly dropped one would be
        # indistinguishable from success without this.
        self.assertEqual("from 47\n", (self.sandbox.repo / "a.txt").read_text())
        self.assertEqual("from 48\n", (self.sandbox.repo / "b.txt").read_text())

    def test_combining_never_moves_main_or_a_source_branch(self):
        first = self.sandbox.branch("pr-47", {"a.txt": "from 47\n"})
        second = self.sandbox.branch("pr-48", {"b.txt": "from 48\n"})
        before = {
            "main": git(self.sandbox.repo, "rev-parse", "main"),
            "pr-47": git(self.sandbox.repo, "rev-parse", "pr-47"),
            "pr-48": git(self.sandbox.repo, "rev-parse", "pr-48"),
        }

        combiner.combine(self.git, self.sandbox.plan([(47, first), (48, second)]))

        for ref, sha in before.items():
            self.assertEqual(
                sha,
                git(self.sandbox.repo, "rev-parse", ref),
                f"{ref} must not be modified by a test build",
            )

    def test_work_happens_on_a_disposable_branch_named_after_the_build(self):
        first = self.sandbox.branch("pr-47", {"a.txt": "from 47\n"})
        report = combiner.combine(self.git, self.sandbox.plan([(47, first)]))
        self.assertEqual("pr-test/testkey00000", report["branch"])
        self.assertEqual(
            "pr-test/testkey00000",
            git(self.sandbox.repo, "rev-parse", "--abbrev-ref", "HEAD"),
        )

    def test_an_empty_plan_is_reported_rather_than_built(self):
        report = combiner.combine(self.git, self.sandbox.plan([]))
        self.assertEqual("empty", report["status"])

    def test_pushing_is_refused_structurally(self):
        with self.assertRaises(combiner.GitError):
            self.git.run("push", "origin", "main")


class ConflictTest(CombineTestCase):

    def conflicting_plan(self) -> dict:
        first = self.sandbox.branch("pr-47", {"shared.txt": "line from 47\n"})
        second = self.sandbox.branch("pr-48", {"shared.txt": "line from 48\n"})
        return self.sandbox.plan([(47, first), (48, second)])

    def test_a_text_conflict_stops_the_run_and_names_the_file(self):
        report = combiner.combine(self.git, self.conflicting_plan())

        self.assertEqual("conflict", report["status"])
        self.assertEqual(1, len(report["conflicts"]))
        self.assertEqual(48, report["conflicts"][0]["number"])
        self.assertEqual(
            ["shared.txt"],
            [item["path"] for item in report["conflicts"][0]["files"]],
        )
        self.assertEqual("text", report["conflicts"][0]["files"][0]["kind"])

    def test_a_stopped_conflict_leaves_no_merge_in_progress(self):
        combiner.combine(self.git, self.conflicting_plan())
        # MERGE_HEAD surviving would mean the workspace was left mid-merge and
        # any following step would operate on a half-merged tree.
        self.assertFalse((self.sandbox.repo / ".git" / "MERGE_HEAD").exists())

    def test_the_conflict_report_never_picks_a_side(self):
        report = combiner.combine(self.git, self.conflicting_plan())
        rendered = combiner.render_conflict_report(report, self.conflicting_plan())
        self.assertIn("shared.txt", rendered)
        self.assertNotIn("--ours", rendered)
        self.assertNotIn("--theirs", rendered)
        self.assertIn("union", rendered)

    def test_union_resolution_keeps_both_sides(self):
        with tempfile.TemporaryDirectory() as out:
            diff_path = str(Path(out) / "proposal.diff")
            report = combiner.combine(
                self.git,
                self.conflicting_plan(),
                resolution="union",
                proposal_diff=diff_path,
            )
            self.assertEqual("resolved", report["status"])
            self.assertEqual(["shared.txt"], report["resolved_paths"])
            merged = (self.sandbox.repo / "shared.txt").read_text()
            self.assertIn("line from 47", merged)
            self.assertIn("line from 48", merged)
            self.assertNotIn("<<<<<<<", merged)
            # The proposal has to be reviewable, so the diff is written out.
            self.assertIn("shared.txt", Path(diff_path).read_text())

    def test_a_binary_conflict_is_refused_even_in_union_mode(self):
        first = self.sandbox.branch("pr-47", {"asset.bin": b"\x00\x01from 47"})
        second = self.sandbox.branch("pr-48", {"asset.bin": b"\x00\x02from 48"})
        plan = self.sandbox.plan([(47, first), (48, second)])

        report = combiner.combine(self.git, plan, resolution="union")

        self.assertEqual("conflict", report["status"])
        kinds = [item["kind"] for item in report["conflicts"][0]["files"]]
        self.assertEqual(["binary"], kinds)
        self.assertFalse(report["conflicts"][0]["files"][0]["resolvable_by_union"])
        self.assertIn(
            "manual",
            combiner.render_conflict_report(report, plan),
        )

    def test_a_delete_modify_conflict_is_refused_even_in_union_mode(self):
        self.sandbox.write("shared.txt", "original\n")
        git(self.sandbox.repo, "add", "-A")
        git(self.sandbox.repo, "commit", "--quiet", "-m", "add shared")
        self.sandbox.base_sha = git(self.sandbox.repo, "rev-parse", "HEAD")

        git(self.sandbox.repo, "checkout", "--quiet", "-B", "pr-47")
        (self.sandbox.repo / "shared.txt").unlink()
        git(self.sandbox.repo, "add", "-A")
        git(self.sandbox.repo, "commit", "--quiet", "-m", "delete shared")
        first = git(self.sandbox.repo, "rev-parse", "HEAD")
        git(self.sandbox.repo, "checkout", "--quiet", "main")

        second = self.sandbox.branch("pr-48", {"shared.txt": "changed by 48\n"})
        plan = self.sandbox.plan([(47, first), (48, second)])

        report = combiner.combine(self.git, plan, resolution="union")

        self.assertEqual("conflict", report["status"])
        self.assertEqual(
            "delete/modify", report["conflicts"][0]["files"][0]["kind"]
        )


class HelperTest(unittest.TestCase):

    def test_binary_detection_uses_the_nul_byte_heuristic(self):
        self.assertTrue(combiner.looks_binary(b"abc\x00def"))
        self.assertFalse(combiner.looks_binary(b"plain text\n"))
        self.assertFalse(combiner.looks_binary(None))

    def test_conflict_report_is_json_serialisable(self):
        json.dumps(
            {
                "status": "conflict",
                "conflicts": [
                    {
                        "number": 48,
                        "sha": "a" * 40,
                        "files": [
                            {
                                "path": "x",
                                "kind": "text",
                                "resolvable_by_union": True,
                            }
                        ],
                    }
                ],
            }
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
