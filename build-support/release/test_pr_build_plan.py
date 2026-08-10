#!/usr/bin/env python3
"""Unit tests for build-support/release/pr_build_plan.py.

The git-facing behaviour (containment, ordering, trial merges, conflict
reporting, plan reproduction) is tested against real throwaway repositories,
because a mocked `git` would just re-encode the assumptions this suite exists
to check. The GitHub API is never called: PR metadata is constructed directly.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pr_build_plan as plan_mod
from pr_build_plan import (
    MAX_PRS_PER_REQUEST,
    PlanError,
    Pull,
    compute_digest,
    conflict_report,
    is_ancestor,
    order_pulls,
    parse_pinned,
    parse_pr_numbers,
    release_tag,
    resolve_containment,
    trial_merge,
    validate_pulls,
    write_outputs,
)


def git(cwd: str, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", cwd, *args], capture_output=True, text=True, check=True,
    ).stdout.strip()


def make_pull(number: int, sha: str, **overrides) -> Pull:
    defaults = dict(
        number=number,
        title=f"PR {number}",
        state="open",
        merged=False,
        draft=False,
        head_sha=sha,
        head_ref=f"feature/{number}",
        head_owner="someone",
        base_ref="main",
        author="someone",
    )
    defaults.update(overrides)
    return Pull(**defaults)


class ParsePrNumbersTest(unittest.TestCase):
    def test_accepts_spaces_commas_and_hash_prefixes(self):
        numbers, errors = parse_pr_numbers("47, 48 #49;65")
        self.assertEqual(numbers, [47, 48, 49, 65])
        self.assertEqual(errors, [])

    def test_deduplicates_and_keeps_first_appearance_order(self):
        numbers, errors = parse_pr_numbers("49 47 49 47 48")
        self.assertEqual(numbers, [49, 47, 48])
        self.assertEqual(errors, [])

    def test_rejects_non_numeric_tokens_with_an_explanation(self):
        numbers, errors = parse_pr_numbers("47 main 48")
        self.assertEqual(numbers, [47, 48])
        self.assertEqual(len(errors), 1)
        self.assertIn("main", errors[0])

    def test_rejects_zero_and_negative_numbers(self):
        numbers, errors = parse_pr_numbers("0 -3 47")
        self.assertEqual(numbers, [47])
        self.assertEqual(len(errors), 2)

    def test_empty_input_is_an_error_not_a_silent_noop(self):
        numbers, errors = parse_pr_numbers("   ")
        self.assertEqual(numbers, [])
        self.assertTrue(errors)

    def test_enforces_the_per_request_ceiling(self):
        raw = " ".join(str(n) for n in range(1, MAX_PRS_PER_REQUEST + 2))
        _, errors = parse_pr_numbers(raw)
        self.assertTrue(any("limit" in e for e in errors))


class ParsePinnedTest(unittest.TestCase):
    def test_parses_colon_and_at_separators(self):
        pins, errors = parse_pinned("47:0123abcdef0,48@fedcba98765")
        self.assertEqual(pins, {47: "0123abcdef0", 48: "fedcba98765"})
        self.assertEqual(errors, [])

    def test_rejects_short_or_non_hex_pins(self):
        pins, errors = parse_pinned("47:xyz 48:012")
        self.assertEqual(pins, {})
        self.assertEqual(len(errors), 2)


class ValidatePullsTest(unittest.TestCase):
    SHA = "a" * 40
    OTHER = "b" * 40

    def test_merged_and_closed_prs_are_rejected_with_reasons(self):
        pulls = {
            1: make_pull(1, self.SHA, merged=True, state="closed"),
            2: make_pull(2, self.OTHER, state="closed"),
            3: None,
            4: make_pull(4, self.SHA),
        }
        usable, errors = validate_pulls([1, 2, 3, 4], pulls, {})
        self.assertEqual([p.number for p in usable], [4])
        self.assertEqual(len(errors), 3)
        self.assertIn("merged", errors[0])
        self.assertIn("closed", errors[1])
        self.assertIn("does not exist", errors[2])

    def test_a_moved_head_fails_the_pin_check(self):
        pulls = {5: make_pull(5, self.SHA)}
        usable, errors = validate_pulls([5], pulls, {5: "b" * 12})
        self.assertEqual(usable, [])
        self.assertIn("moved since it was planned", errors[0])

    def test_a_matching_pin_passes(self):
        pulls = {5: make_pull(5, self.SHA)}
        usable, errors = validate_pulls([5], pulls, {5: "a" * 12})
        self.assertEqual([p.number for p in usable], [5])
        self.assertEqual(errors, [])


class GitRepoTestCase(unittest.TestCase):
    """A tiny real repository with main plus branches for merge tests."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = self._tmp.name
        git(self.repo, "init", "-q", "-b", "main")
        git(self.repo, "config", "user.name", "Test")
        git(self.repo, "config", "user.email", "test@example.invalid")

    def tearDown(self):
        self._tmp.cleanup()

    def commit(self, filename: str, content: str, message: str) -> str:
        path = os.path.join(self.repo, filename)
        os.makedirs(os.path.dirname(path) or self.repo, exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(content)
        git(self.repo, "add", filename)
        git(self.repo, "commit", "-q", "-m", message)
        return git(self.repo, "rev-parse", "HEAD")

    def branch_from(self, name: str, start: str) -> None:
        git(self.repo, "checkout", "-q", "-b", name, start)


class ContainmentTest(GitRepoTestCase):
    def test_stacked_pr_is_dropped_in_favour_of_the_tip(self):
        base = self.commit("base.txt", "base", "base")
        self.branch_from("first", base)
        first = self.commit("a.txt", "a", "first")
        self.branch_from("second", first)
        second = self.commit("b.txt", "b", "second")

        result = resolve_containment(
            self.repo, base,
            [make_pull(47, first), make_pull(48, second)],
        )
        self.assertEqual([p.number for p in result.kept], [48])
        self.assertEqual(result.dropped, [{"number": 47, "contained_in": 48}])
        self.assertTrue(any("stacked" in n for n in result.notes))

    def test_pr_already_in_main_is_dropped_against_the_base(self):
        self.commit("base.txt", "base", "base")
        self.branch_from("merged-work", "main")
        merged = self.commit("done.txt", "done", "already merged")
        git(self.repo, "checkout", "-q", "main")
        git(self.repo, "merge", "-q", "--no-ff", "--no-edit", "merged-work")
        base = git(self.repo, "rev-parse", "main")

        result = resolve_containment(self.repo, base, [make_pull(41, merged)])
        self.assertEqual(result.kept, [])
        self.assertEqual(result.dropped, [{"number": 41, "contained_in": "base"}])

    def test_identical_heads_keep_the_lower_pr_number(self):
        base = self.commit("base.txt", "base", "base")
        self.branch_from("dup", base)
        head = self.commit("x.txt", "x", "work")

        result = resolve_containment(
            self.repo, base, [make_pull(50, head), make_pull(51, head)],
        )
        self.assertEqual([p.number for p in result.kept], [50])
        self.assertEqual(result.dropped, [{"number": 51, "contained_in": 50}])

    def test_independent_branches_are_all_kept(self):
        base = self.commit("base.txt", "base", "base")
        self.branch_from("one", base)
        one = self.commit("one.txt", "1", "one")
        self.branch_from("two", base)
        two = self.commit("two.txt", "2", "two")

        result = resolve_containment(
            self.repo, base, [make_pull(1, one), make_pull(2, two)],
        )
        self.assertEqual(sorted(p.number for p in result.kept), [1, 2])
        self.assertEqual(result.dropped, [])


class OrderTest(unittest.TestCase):
    A, B = "a" * 40, "b" * 40

    def test_defaults_to_ascending_pr_number(self):
        ordered = order_pulls([make_pull(49, self.A), make_pull(47, self.B)], None)
        self.assertEqual([p.number for p in ordered], [47, 49])

    def test_honours_an_explicit_permutation(self):
        ordered = order_pulls(
            [make_pull(47, self.A), make_pull(49, self.B)], [49, 47],
        )
        self.assertEqual([p.number for p in ordered], [49, 47])

    def test_rejects_an_order_that_is_not_a_permutation(self):
        with self.assertRaises(PlanError):
            order_pulls([make_pull(47, self.A)], [47, 99])


class DigestTest(unittest.TestCase):
    def test_digest_pins_base_heads_and_order(self):
        base = "c" * 40
        a, b = make_pull(1, "a" * 40), make_pull(2, "b" * 40)
        d1 = compute_digest(base, [a, b])
        self.assertNotEqual(d1, compute_digest(base, [b, a]))
        self.assertNotEqual(d1, compute_digest("d" * 40, [a, b]))
        self.assertEqual(d1, compute_digest(base, [a, b]))

    def test_release_tag_is_namespaced_away_from_nightly(self):
        tag = release_tag("abc123def456")
        self.assertTrue(tag.startswith("pr-test-"))
        self.assertNotIn("nightly", tag)


class TrialMergeTest(GitRepoTestCase):
    def test_clean_merge_reports_the_tree_and_no_conflicts(self):
        base = self.commit("base.txt", "base", "base")
        self.branch_from("one", base)
        one = self.commit("one.txt", "1", "one")
        self.branch_from("two", base)
        two = self.commit("two.txt", "2", "two")

        result = trial_merge(self.repo, base, [make_pull(1, one), make_pull(2, two)])
        self.assertTrue(result["ok"])
        self.assertTrue(result["tree_sha"])
        self.assertEqual(result["conflicts"], [])
        # Both files exist in the merged workspace.
        self.assertTrue(os.path.isfile(os.path.join(self.repo, "one.txt")))
        self.assertTrue(os.path.isfile(os.path.join(self.repo, "two.txt")))

    def test_merge_never_moves_main_or_the_pr_branches(self):
        base = self.commit("base.txt", "base", "base")
        self.branch_from("one", base)
        one = self.commit("one.txt", "1", "one")
        trial_merge(self.repo, base, [make_pull(1, one)])
        self.assertEqual(git(self.repo, "rev-parse", "main"), base)
        self.assertEqual(git(self.repo, "rev-parse", "one"), one)

    def test_conflict_names_the_pr_and_the_file_and_aborts(self):
        base = self.commit("shared.txt", "original\n", "base")
        self.branch_from("left", base)
        left = self.commit("shared.txt", "left version\n", "left")
        self.branch_from("right", base)
        right = self.commit("shared.txt", "right version\n", "right")

        result = trial_merge(
            self.repo, base, [make_pull(10, left), make_pull(11, right)],
        )
        self.assertFalse(result["ok"])
        self.assertEqual(len(result["conflicts"]), 1)
        self.assertEqual(result["conflicts"][0]["pr"], 11)
        files = result["conflicts"][0]["files"]
        self.assertEqual([f["path"] for f in files], ["shared.txt"])
        self.assertFalse(files[0]["binary"])
        # The merge was aborted: the workspace is clean again.
        self.assertEqual(git(self.repo, "status", "--porcelain"), "")

    def test_binary_conflicts_are_flagged_as_binary(self):
        path = os.path.join(self.repo, "blob.bin")
        with open(path, "wb") as handle:
            handle.write(b"\x00base\x01\x02")
        git(self.repo, "add", "blob.bin")
        git(self.repo, "commit", "-q", "-m", "base")
        base = git(self.repo, "rev-parse", "HEAD")

        self.branch_from("bin-left", base)
        with open(path, "wb") as handle:
            handle.write(b"\x00left\x01\x02")
        git(self.repo, "add", "blob.bin")
        git(self.repo, "commit", "-q", "-m", "left")
        left = git(self.repo, "rev-parse", "HEAD")

        self.branch_from("bin-right", base)
        with open(path, "wb") as handle:
            handle.write(b"\x00right\x01\x02")
        git(self.repo, "add", "blob.bin")
        git(self.repo, "commit", "-q", "-m", "right")
        right = git(self.repo, "rev-parse", "HEAD")

        result = trial_merge(
            self.repo, base, [make_pull(20, left), make_pull(21, right)],
        )
        self.assertFalse(result["ok"])
        files = result["conflicts"][0]["files"]
        self.assertEqual([f["path"] for f in files], ["blob.bin"])
        self.assertTrue(files[0]["binary"])

    def test_the_same_plan_reproduces_the_same_tree(self):
        base = self.commit("base.txt", "base", "base")
        self.branch_from("one", base)
        one = self.commit("one.txt", "1", "one")
        first = trial_merge(self.repo, base, [make_pull(1, one)])
        second = trial_merge(self.repo, base, [make_pull(1, one)])
        self.assertEqual(first["tree_sha"], second["tree_sha"])


class IsAncestorTest(GitRepoTestCase):
    def test_distinguishes_ancestry_from_unrelated_branches(self):
        base = self.commit("base.txt", "base", "base")
        self.branch_from("child", base)
        child = self.commit("c.txt", "c", "child")
        self.assertTrue(is_ancestor(self.repo, base, child))
        self.assertFalse(is_ancestor(self.repo, child, base))


class WriteOutputsTest(unittest.TestCase):
    def test_multiline_values_use_a_heredoc_delimiter(self):
        with tempfile.NamedTemporaryFile("r", suffix=".txt", delete=False) as handle:
            path = handle.name
        try:
            write_outputs(path, {"a": "one", "b": "line1\nline2"})
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            self.assertIn("a=one\n", text)
            self.assertIn("b<<PR_TEST_EOF\nline1\nline2\nPR_TEST_EOF\n", text)
        finally:
            os.unlink(path)

    def test_no_path_means_no_write(self):
        write_outputs("", {"a": "b"})  # must not raise


class MergeCommandTest(GitRepoTestCase):
    """`merge` must refuse anything but the exact planned tree."""

    def _plan_document(self, base: str, pulls: list[Pull], tree_sha: str,
                       ok: bool = True) -> str:
        plan = {
            "version": 1,
            "ok": ok,
            "repository": "example/repo",
            "base_ref": "main",
            "base_sha": base,
            "pulls": [
                {
                    "number": p.number, "title": p.title, "head_sha": p.head_sha,
                    "head_ref": p.head_ref, "head_owner": p.head_owner,
                    "author": p.author, "draft": p.draft,
                }
                for p in pulls
            ],
            "merge": {"ok": ok, "tree_sha": tree_sha, "conflicts": []},
        }
        handle = tempfile.NamedTemporaryFile(
            "w", suffix=".json", delete=False, encoding="utf-8",
        )
        with handle:
            json.dump(plan, handle)
        self.addCleanup(os.unlink, handle.name)
        return handle.name

    def _run_merge(self, plan_path: str) -> int:
        # fetch_pr_heads needs an origin serving refs/pull/N/head; the local
        # test repo has none, so point the fetch at pre-created local refs.
        original = plan_mod.fetch_pr_heads
        plan_mod.fetch_pr_heads = lambda ws, pulls: None
        try:
            return plan_mod.main(["merge", "--plan", plan_path,
                                  "--workspace", self.repo])
        finally:
            plan_mod.fetch_pr_heads = original

    def test_reproduces_a_clean_plan(self):
        base = self.commit("base.txt", "base", "base")
        self.branch_from("one", base)
        one = self.commit("one.txt", "1", "one")
        planned = trial_merge(self.repo, base, [make_pull(1, one)])
        plan_path = self._plan_document(base, [make_pull(1, one)],
                                        planned["tree_sha"])
        self.assertEqual(self._run_merge(plan_path), 0)

    def test_rejects_a_plan_whose_tree_no_longer_matches(self):
        base = self.commit("base.txt", "base", "base")
        self.branch_from("one", base)
        one = self.commit("one.txt", "1", "one")
        plan_path = self._plan_document(base, [make_pull(1, one)], "f" * 40)
        self.assertEqual(self._run_merge(plan_path), 1)

    def test_rejects_an_unbuildable_plan_outright(self):
        base = self.commit("base.txt", "base", "base")
        plan_path = self._plan_document(base, [], "", ok=False)
        self.assertEqual(self._run_merge(plan_path), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
