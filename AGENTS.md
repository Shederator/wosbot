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
loops. Runtime evidence is normally under `modules/desktop/target`: account logs in
`logs/`, the global log in `log/frostguard.log`, archives in `log/archive/`, and
debug screenshots in `temp/`.

State the evidence level whenever reporting a behavioral fix:

- automated tests;
- saved real-frame verification;
- live account-log confirmation;
- plausible but still unverified.

Vision, OCR, and pattern changes should normally have saved-frame coverage and
live-log confirmation before merge readiness. Missing evidence must remain
explicit in the handoff or pull request.

## Git And Pull Requests

Use the GitHub Project workboard as the source of truth for planned work and
work status. Before starting implementation, check for an existing work item
and keep its status aligned with actual progress. Link related issues and pull
requests instead of duplicating their details in the board.

Start feature and fix branches from `main` unless a stacked dependency is
intentional and documented. Keep commits reviewable and do not commit
credentials, profile databases, emulator-specific paths, private logs, runtime
artifacts, or generated output.

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
