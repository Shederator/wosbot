# Frostguard Project Guidance

This is the shared contract for humans and coding agents working in this
repository. Keep it limited to rules that apply to every contributor.

Before planning work or running commands, check whether `AGENTS.local.md` exists
at the repository root and, if it does, read it completely. It contains
untracked personal workflow preferences and may refine local choices, but it
must not weaken the shared quality or verification rules here.

## Read Before Editing

- Before changing module boundaries, runtime ownership, scheduling internals,
  or task lifecycle behavior, read `docs/architecture.md`.
- Before changing automation routines, navigation, screen interaction, OCR,
  templates, colors, pixels, or timing assumptions, read
  `docs/design-guidelines.md` and any relevant note under `docs/task/`.
- For source setup, build, test, and local startup, use `docs/development.md`.
  For Windows-native packaging, runtime, or autostart behavior, also use
  `docs/windows.md`.
- When preparing a pull request, use `.github/pull_request_template.md` as a
  review guide and adapt it when another structure communicates the change more
  clearly.

## Build And Test

Choose the command based on the purpose of the build:

- `./mvnw package` builds and tests the current reactor state without deleting
  existing output first.
- `./mvnw -pl <module> -am test` runs focused module tests plus required upstream
  modules.
- `./mvnw javafx:run` compiles the required reactor modules and starts the
  desktop application from source.
- `./mvnw clean install` is appropriate for reproducible clean verification, CI,
  and release preparation when deleting generated output is intentional.
- `./mvnw clean install package` produces the fully clean packaged desktop
  distribution.

Use `mvnw.cmd` instead of `./mvnw` on Windows Command Prompt. Desktop packaging
is owned by `packaging/desktop`; normal module builds never install or update
Frostguard.

Generated `target/` output must not be committed. A local `AGENTS.local.md` may
select a preferred non-clean command for day-to-day work.

Tests use JUnit Jupiter. Name test classes `*Test` and behavior-focused methods
such as `rejectsMalformedPersistedReservationsConservatively`. Put saved image
or OCR fixtures in the affected module's `src/test/resources`. Run at least the
affected module tests; use a full reactor build for cross-module or packaging
changes.

## Shared Engineering Rules

- Use Java 21 conservatively, keep packages under `dev.frostguard`, use 4-space
  indentation and same-line braces, and match surrounding style.
- Keep game-specific automation in `modules/tasks`, reusable game interactions in
  `modules/automation`, and low-level image/OCR primitives in `modules/vision`.
- Put shared screen regions and OCR presets in `CommonGameAreas` and
  `CommonOCRSettings`; do not hide reusable detection logic inside one task.
- Prefer maintainable fixes over one-off patches. Do not leave dead code,
  commented-out experiments, or task-local copies of reusable helpers.
- Code comments and log messages must be English. Comments explain non-obvious
  rationale, not control flow, and must not contain author/date changelogs.
- Keep agent-facing documentation concise. Preserve constraints, decisions,
  evidence, fragile assumptions, fallbacks, and unsupported states; do not
  restate information that is clear from code and tests.

## Logging And Verification

Logs should make decisions explainable: include relevant profile context,
evidence, the chosen outcome, and retry or fallback reasons without flooding hot
loops. Runtime evidence belongs to the selected workspace, not generated
`target/` output. Source and IDE launches normally use `<worktree>/.frostguard-dev`;
installed Stable and Nightly releases default to
`~/.frostguard/workspaces/<channel>/<name>`. Each workspace keeps the global log
at `logs/frostguard.log`, account logs as `logs/account_<name>_<id>.log`, and
rotated archives under `logs/archive/`.

State the evidence level whenever reporting a behavioral fix:

- automated tests;
- saved real-frame verification;
- live account-log confirmation;
- plausible but still unverified.

Vision, OCR, and pattern changes should normally have saved-frame coverage and
live-log confirmation before merge readiness. Missing evidence must remain
explicit in the handoff or pull request.

## Project Workboard

Use the public GitHub Project as the source of truth for planned work,
ownership, priority, and status, and keep it current throughout the work.

Substantial work needs an existing or new issue and project item. This includes
work spanning files, modules, sessions, or contributors; user-visible or
architectural changes; persistence, scheduling, automation-safety, CI,
packaging, or release work; and anything needing explicit scope, dependencies,
prioritization, or live validation. Search first and do not duplicate items.
Typos, tiny mechanical or localized low-risk fixes, and corrections already
tracked by a PR or parent issue do not need standalone issues.

Issues must capture the outcome, scope, acceptance criteria, evidence,
dependencies, and important risks. Use existing category labels and the
board's documented priority and status workflow (`Backlog`, `Ready`, `In
progress`, `In review`, `Blocked`, `Done`); set relative `Size` (`XS`-`XL`) from
breadth, risk, and validation effort, and assign only the actual owner. Update
ownership, metadata, links, status, evidence, and blockers at each real
transition. Use `Blocked` only for a stated blocker and `Done` only for a
completed outcome. Keep durable detail in the issue or PR discussion. If
permissions prevent an update, report exactly what remains stale.

## Git And Pull Requests

Start feature and fix branches from `main` unless a stacked dependency is
intentional and documented. Keep commits reviewable and do not commit
credentials, profile databases, emulator-specific paths, private logs, runtime
artifacts, or generated output.

Sign off every commit under the Developer Certificate of Origin 1.1 with
`git commit -s`. The `Signed-off-by` identity must match the commit author.

Prefer a short native GitHub PR stack when substantial work has ordered,
dependent units that are independently reviewable and testable; do not stack
unrelated work or split mechanically. Use the official `gh stack` extension:
initialize and submit new stacks through it, or register existing PRs bottom to
top with `gh stack link`. Chained base branches alone are not a native stack.
Before reporting completion, verify that every PR has the same non-null stack
ID and the expected position and size; a "can be stacked" banner means this is
not yet complete.

Each PR must name its stack position and size, parent/child links, shared issue,
dependency, and merge order. Use one issue unless units need separate planning
or ownership, and never base stacks on local integration or deployment
branches. Merge bottom-up; after a parent merges, sync the stack, rerun affected
checks, and verify that the next PR's diff contains only its review unit.

Shape commits around coherent changes, not an arbitrary commit count. Keep
independently reviewable changes separate; fold fixups, naming cleanup, and
follow-up corrections into the commit they belong to before review when
rewriting the branch is safe. Do not squash distinct changes only to minimize
the number of commits.

Use concise English commit subjects and PR titles in the form
`type(scope): imperative summary`, ideally at most 72 characters. Choose the
smallest durable area as the scope, such as `research` or `guidance`; do not omit
the scope merely because a change spans multiple files. Treat type and scope
names as a consistency guide rather than a mechanical acceptance rule.

Use the PR template as a starting point. Adapt it for unusual changes when that
improves reviewability, but always explain what changed, why, actual validation,
and remaining risk. Never imply that an unperformed check passed.
