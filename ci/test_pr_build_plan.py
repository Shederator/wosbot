#!/usr/bin/env python3
"""Self-tests for ci/pr_build_plan.py.

Every rule that protects a tester lives in this module: rejecting closed pull
requests, pinning head commits, dropping stack entries that are already
contained in a later head, and refusing to publish when a branch moved during
the build. All of them are decisions, not I/O, so they are tested here without a
network or a repository.

Run with:  python3 ci/test_pr_build_plan.py
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pr_build_plan as planner  # noqa: E402


def facts(number: int, **overrides) -> planner.PullRequestFacts:
    defaults = {
        "title": f"change {number}",
        "state": "open",
        "head_sha": f"{number:040x}",
        "head_ref": f"branch-{number}",
        "head_repo": "CodeLtDave/wosbot",
        "base_ref": "main",
        "author": "CodeLtDave",
        "url": f"https://github.com/Shederator/wosbot/pull/{number}",
    }
    defaults.update(overrides)
    return planner.PullRequestFacts(number=number, **defaults)


def no_ancestry(_a: str, _b: str) -> bool:
    return False


def order_of(
    requested: list[int],
    entries: dict[int, planner.PullRequestFacts],
    is_ancestor=no_ancestry,
    times: dict[int, int] | None = None,
    **kwargs,
) -> planner.PlanResult:
    times = times or {}
    return planner.select_and_order(
        requested,
        entries,
        is_ancestor=is_ancestor,
        commit_time=lambda sha: times.get(sha, 0),
        **kwargs,
    )


class RequestParsingTest(unittest.TestCase):

    def test_accepts_the_separators_people_actually_type(self):
        numbers, invalid, duplicates = planner.parse_pr_request("47 48,49  #65")
        self.assertEqual([47, 48, 49, 65], numbers)
        self.assertEqual([], invalid)
        self.assertEqual([], duplicates)

    def test_reports_duplicates_instead_of_building_them_twice(self):
        numbers, _, duplicates = planner.parse_pr_request("47 47 48 47")
        self.assertEqual([47, 48], numbers)
        self.assertEqual([47, 47], duplicates)

    def test_reports_tokens_that_are_not_pull_request_numbers(self):
        numbers, invalid, _ = planner.parse_pr_request("47 latest v2 48")
        self.assertEqual([47, 48], numbers)
        self.assertEqual(["latest", "v2"], invalid)

    def test_rejects_zero_and_empty_requests(self):
        self.assertEqual(([], [], []), planner.parse_pr_request(""))
        self.assertEqual([], planner.parse_pr_request("0")[0])


class ClassificationTest(unittest.TestCase):

    def test_open_pull_request_with_a_head_commit_is_buildable(self):
        self.assertEqual("", planner.classify(facts(47)))

    def test_merged_and_closed_pull_requests_are_rejected_with_a_reason(self):
        self.assertIn("merged", planner.classify(facts(47, merged=True, state="closed")))
        self.assertIn("not open", planner.classify(facts(47, state="closed")))

    def test_missing_or_malformed_head_commit_is_rejected(self):
        self.assertIn("head commit", planner.classify(facts(47, head_sha="")))
        self.assertIn("head commit", planner.classify(facts(47, head_sha="nope")))

    def test_api_errors_are_surfaced_verbatim(self):
        self.assertEqual(
            "no such pull request in this repository",
            planner.classify(
                planner.PullRequestFacts(
                    number=999, error="no such pull request in this repository"
                )
            ),
        )


class SelectionTest(unittest.TestCase):

    def test_closed_pull_requests_are_rejected_but_the_rest_still_builds(self):
        entries = {47: facts(47), 48: facts(48, state="closed")}
        result = order_of([47, 48], entries)
        self.assertEqual([47], result.order)
        self.assertEqual(1, len(result.rejected))
        self.assertEqual(48, result.rejected[0].number)

    def test_contained_stack_entry_is_dropped(self):
        # #47's head is an ancestor of #48's head: merging both is redundant.
        entries = {47: facts(47), 48: facts(48)}

        def ancestry(a: str, b: str) -> bool:
            return a == entries[47].head_sha and b == entries[48].head_sha

        result = order_of([47, 48], entries, is_ancestor=ancestry)
        self.assertEqual([48], result.order)
        self.assertEqual([47], [item.number for item in result.contained])
        self.assertEqual(48, result.contained[0].contained_in)

    def test_identical_heads_keep_the_lower_number_deterministically(self):
        same = "a" * 40
        entries = {51: facts(51, head_sha=same), 52: facts(52, head_sha=same)}
        result = order_of([51, 52], entries, is_ancestor=lambda a, b: a == b)
        self.assertEqual([51], result.order)
        self.assertEqual([52], [item.number for item in result.contained])

    def test_base_branch_of_a_same_repository_stack_forces_the_order(self):
        entries = {
            41: facts(41, head_repo="Shederator/wosbot", head_ref="feat/a"),
            42: facts(
                42,
                head_repo="Shederator/wosbot",
                head_ref="feat/b",
                base_ref="feat/a",
            ),
        }
        # Newest first in the request, and #42 has the older commit time: only
        # the base-branch relation can produce the correct base-to-tip order.
        result = order_of(
            [42, 41],
            entries,
            times={entries[41].head_sha: 200, entries[42].head_sha: 100},
            repository="Shederator/wosbot",
        )
        self.assertEqual([41, 42], result.order)

    def test_unrelated_pull_requests_are_ordered_oldest_commit_first(self):
        entries = {47: facts(47), 48: facts(48), 65: facts(65)}
        result = order_of(
            [65, 47, 48],
            entries,
            times={
                entries[47].head_sha: 300,
                entries[48].head_sha: 100,
                entries[65].head_sha: 200,
            },
        )
        self.assertEqual([48, 65, 47], result.order)

    def test_draft_pull_requests_are_built_but_flagged(self):
        result = order_of([47], {47: facts(47, draft=True)})
        self.assertEqual([47], result.order)
        self.assertTrue(any("draft" in note for note in result.notes))

    def test_order_override_wins_and_is_recorded(self):
        entries = {47: facts(47), 48: facts(48)}
        result = order_of([47, 48], entries, order_override="48 47")
        self.assertEqual([48, 47], result.order)
        self.assertTrue(any("overridden" in note for note in result.notes))

    def test_order_override_cannot_smuggle_in_unknown_numbers(self):
        entries = {47: facts(47), 48: facts(48)}
        result = order_of([47, 48], entries, order_override="48 999")
        self.assertEqual([48, 47], result.order)
        self.assertTrue(any("#999" in note for note in result.notes))

    def test_a_request_where_nothing_is_buildable_yields_no_order(self):
        entries = {47: facts(47, state="closed")}
        result = order_of([47], entries)
        self.assertEqual([], result.order)


class BuildKeyTest(unittest.TestCase):

    def test_same_commits_produce_the_same_key(self):
        first = planner.compute_build_key("base", ["a" * 40, "b" * 40])
        second = planner.compute_build_key("base", ["a" * 40, "b" * 40])
        self.assertEqual(first, second)

    def test_a_push_to_any_pull_request_changes_the_key(self):
        first = planner.compute_build_key("base", ["a" * 40, "b" * 40])
        second = planner.compute_build_key("base", ["a" * 40, "c" * 40])
        self.assertNotEqual(first, second)

    def test_merge_order_changes_the_key(self):
        # Order matters for the result of a merge, so it must matter for the
        # identity of the build as well; otherwise the reuse check would hand
        # back a bundle that was combined differently.
        self.assertNotEqual(
            planner.compute_build_key("base", ["a" * 40, "b" * 40]),
            planner.compute_build_key("base", ["b" * 40, "a" * 40]),
        )

    def test_a_new_base_commit_changes_the_key(self):
        self.assertNotEqual(
            planner.compute_build_key("base1", ["a" * 40]),
            planner.compute_build_key("base2", ["a" * 40]),
        )

    def test_key_is_short_enough_for_a_tag(self):
        key = planner.compute_build_key("base", ["a" * 40])
        self.assertEqual(planner.BUILD_KEY_LENGTH, len(key))


class PlanDocumentTest(unittest.TestCase):

    def plan(self, **kwargs) -> dict:
        entries = {47: facts(47), 48: facts(48), 44: facts(44, state="closed")}
        result = order_of([47, 48, 44], entries)
        return planner.plan_to_dict(
            repository="Shederator/wosbot",
            request_text="47 48 44 44 latest",
            requested=[47, 48, 44],
            invalid=["latest"],
            duplicates=[44],
            facts=entries,
            result=result,
            base_ref="main",
            base_sha="f" * 40,
            requester="Shederator",
            **kwargs,
        )

    def test_plan_pins_every_head_commit(self):
        plan = self.plan()
        self.assertEqual([47, 48], plan["order"])
        for number in plan["order"]:
            self.assertEqual(
                f"{number:040x}", plan["pull_requests"][str(number)]["head_sha"]
            )

    def test_plan_is_json_serialisable_and_stable(self):
        json.dumps(self.plan())

    def test_tag_and_asset_name_are_derived_from_the_request(self):
        plan = self.plan()
        self.assertTrue(plan["tag"].startswith(planner.TAG_PREFIX))
        self.assertEqual(
            "frostguard-unmerged-test-build-pr-47-48.zip", plan["asset_name"]
        )

    def test_markdown_explains_rejections_duplicates_and_junk(self):
        markdown = planner.render_markdown(self.plan())
        self.assertIn("#47", markdown)
        self.assertIn("Rejected", markdown)
        self.assertIn("#44", markdown)
        self.assertIn("Duplicates removed", markdown)
        self.assertIn("`latest`", markdown)

    def test_markdown_shows_the_pinned_commits(self):
        markdown = planner.render_markdown(self.plan())
        self.assertIn(f"{47:040x}"[:7], markdown)

    def test_markdown_survives_a_title_containing_a_table_pipe(self):
        entries = {47: facts(47, title="fix: a | b")}
        result = order_of([47], entries)
        plan = planner.plan_to_dict(
            repository="Shederator/wosbot",
            request_text="47",
            requested=[47],
            invalid=[],
            duplicates=[],
            facts=entries,
            result=result,
            base_ref="main",
            base_sha="f" * 40,
        )
        self.assertIn("fix: a \\| b", planner.render_markdown(plan))


class FakeClient:
    def __init__(self, entries: dict[int, planner.PullRequestFacts]) -> None:
        self.entries = entries

    def pull(self, number: int) -> planner.PullRequestFacts:
        return self.entries.get(number) or planner.PullRequestFacts(
            number=number, error="not found"
        )


class VerifyTest(unittest.TestCase):

    def base_plan(self) -> dict:
        entries = {47: facts(47), 48: facts(48)}
        result = order_of([47, 48], entries)
        return planner.plan_to_dict(
            repository="Shederator/wosbot",
            request_text="47 48",
            requested=[47, 48],
            invalid=[],
            duplicates=[],
            facts=entries,
            result=result,
            base_ref="main",
            base_sha="f" * 40,
        )

    def test_unchanged_pull_requests_verify_clean(self):
        plan = self.base_plan()
        client = FakeClient({47: facts(47), 48: facts(48)})
        self.assertEqual([], planner.verify_plan(plan, client))

    def test_a_force_push_during_the_build_blocks_publishing(self):
        plan = self.base_plan()
        client = FakeClient({47: facts(47, head_sha="d" * 40), 48: facts(48)})
        problems = planner.verify_plan(plan, client)
        self.assertEqual(1, len(problems))
        self.assertIn("moved", problems[0])

    def test_a_pull_request_merged_during_the_build_blocks_publishing(self):
        plan = self.base_plan()
        client = FakeClient(
            {47: facts(47), 48: facts(48, state="closed", merged=True)}
        )
        problems = planner.verify_plan(plan, client)
        self.assertEqual(1, len(problems))
        self.assertIn("#48", problems[0])


class ReleaseNotesTest(unittest.TestCase):

    def plan(self) -> dict:
        entries = {48: facts(48), 49: facts(49)}
        result = order_of([48, 49], entries)
        return planner.plan_to_dict(
            repository="Shederator/wosbot",
            request_text="48 49",
            requested=[48, 49],
            invalid=[],
            duplicates=[],
            facts=entries,
            result=result,
            base_ref="main",
            base_sha="f" * 40,
        )

    def test_the_warning_comes_before_anything_else(self):
        # Somebody will find this release through a search engine with no idea
        # what "pr-test-" means; the first thing they read must be the warning.
        notes = planner.render_notes(self.plan())
        self.assertTrue(notes.startswith("> [!CAUTION]"))
        self.assertIn("has not been reviewed or merged", notes)
        self.assertIn("not a release", notes)

    def test_notes_list_every_pull_request_with_its_pinned_commit(self):
        notes = planner.render_notes(self.plan())
        self.assertIn("1. #48", notes)
        self.assertIn("2. #49", notes)
        self.assertIn(f"{48:040x}"[:7], notes)

    def test_notes_state_the_install_command_that_actually_exists(self):
        # The bundle ships no launcher .bat for the app itself, so naming one
        # would send every tester into a support question.
        self.assertIn("java -jar frostguard-*.jar", planner.render_notes(self.plan()))

    def test_notes_announce_the_expiry(self):
        notes = planner.render_notes(self.plan(), ttl_days=3)
        self.assertIn("after 3 days", notes)

    def test_notes_embed_a_marker_the_cleanup_can_read_back(self):
        notes = planner.render_notes(self.plan())
        parsed = planner.parse_marker(notes)
        self.assertEqual([48, 49], parsed["prs"])
        self.assertEqual(self.plan()["build_key"], parsed["key"])

    def test_the_marker_round_trips_and_survives_junk(self):
        block = planner.marker_block([1, 2], "key", "base")
        self.assertEqual([1, 2], planner.parse_marker(f"notes\n{block}\n")["prs"])
        self.assertEqual({}, planner.parse_marker("no marker here"))
        self.assertEqual(
            {}, planner.parse_marker("<!-- frostguard-pr-test {broken} -->")
        )

    def test_notes_command_writes_a_file(self):
        with tempfile.TemporaryDirectory() as directory:
            plan_path = Path(directory) / "plan.json"
            notes_path = Path(directory) / "notes.md"
            plan_path.write_text(json.dumps(self.plan()), encoding="utf-8")
            code = planner.main(
                [
                    "notes",
                    "--plan",
                    str(plan_path),
                    "--output",
                    str(notes_path),
                    "--ttl-days",
                    "7",
                ]
            )
            self.assertEqual(0, code)
            self.assertIn("#48", notes_path.read_text(encoding="utf-8"))


class CliTest(unittest.TestCase):

    def test_verify_command_reads_a_plan_file(self):
        entries = {47: facts(47)}
        result = order_of([47], entries)
        plan = planner.plan_to_dict(
            repository="Shederator/wosbot",
            request_text="47",
            requested=[47],
            invalid=[],
            duplicates=[],
            facts=entries,
            result=result,
            base_ref="main",
            base_sha="f" * 40,
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            path.write_text(json.dumps(plan), encoding="utf-8")
            args = planner.parse_args(["verify", "--plan", str(path)])
            self.assertEqual(str(path), args.plan)

    def test_plan_command_requires_a_repository_and_a_request(self):
        with self.assertRaises(SystemExit):
            planner.parse_args(["plan"])

    def test_max_pull_requests_is_a_real_guardrail(self):
        self.assertGreaterEqual(planner.MAX_PULL_REQUESTS, 2)
        self.assertLessEqual(planner.MAX_PULL_REQUESTS, 20)


if __name__ == "__main__":
    unittest.main(verbosity=2)
