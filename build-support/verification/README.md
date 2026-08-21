# Continuous Integration

Pull requests and `main` are built and tested by
[`ci.yml`](../../.github/workflows/ci.yml) with read-only repository access.
The authenticated
[`signed-windows-channel-release.yml`](../../.github/workflows/signed-windows-channel-release.yml)
publishes the native Nightly once per day. Temporary combined-PR test releases
are built by [`pr-test-build.yml`](../../.github/workflows/pr-test-build.yml)
without changing Stable, Nightly, or `main`.

## When it runs

| Workflow trigger | Purpose |
|---|---|
| CI: `pull_request` | Builds and tests the proposed tree without release permissions |
| CI: `push` to `main` | Rechecks the integrated tree |
| Native channel: `schedule` (daily) | Publishes an authenticated immutable MSI, advances `nightly`, and updates Discord |
| Native channel: `workflow_dispatch` | Publishes an explicit Stable or additional Nightly from `main` |
| PR test: `workflow_dispatch` | Combines selected open PRs and publishes an expiring test-only ZIP |

## What the PR-test ZIP verifier does

1. Checks out the repository **with Git LFS**, then asserts that every LFS asset
   was really materialised. The check fails if `git lfs ls-files` returns nothing
   at all, if one of the four critical assets is no longer tracked, if a file is
   still a pointer stub, or if it is implausibly small. Without those guards the
   step could pass vacuously and ship a bundle that fails only on a user's PC.
2. Sets up **Temurin JDK 21** with a Maven dependency cache.
3. Installs `libtesseract` / `libleptonica`, which tess4j binds at runtime for
   the OCR regression tests. OpenCV needs no system package — the
   `org.openpnp:opencv` artifact ships the Linux native image.
4. Runs the verifier's own unit tests (`build-support/verification/test_verify_bundle.py`), so a
   verification script that can no longer fail cannot silently green-light a
   broken artifact.
5. Runs the full JUnit 5 suite against Linux JavaFX under Xvfb, including the
   vision and OCR saved-frame tests, then **cross-builds the Windows desktop
   bundle from Linux** with `-Djavafx.platform=win` and tests skipped in that
   second Maven invocation.
6. **Structurally verifies** the ZIP with [`verify_bundle.py`](verify_bundle.py):
   Windows JavaFX runtime present and no other platform's runtime leaking, the
   launcher and watcher JARs, bundled `adb`/OCR assets, template sprites,
   `custom_tasks/`, a floor on staged runtime JARs, and that **every
   `Class-Path` entry of the launcher manifest really exists** in the archive.
7. **Launch-smoke-tests** the extracted bundle with
   [`smoke_test_bundle.sh`](smoke_test_bundle.sh): resolves the real entry points
   off the real bundle classpath, checks `java -jar` resolves the manifest
   `Class-Path`, and boots the shaded Telegram watcher for real.
8. Uploads the untrusted build output for a fresh trusted runner, which repeats
   the structural and launch checks before publication.
9. Publishes only a clearly labelled, expiring `pr-test-*` prerelease and sends
   the result back to the originating Discord request when applicable.

## Discord notifications

[`discord_notify.py`](../notifications/discord_notify.py) updates the single
maintained Nightly message after the native installer and signed channel feed
have been published. It uses the webhook in the
`DISCORD_NIGHTLY_WEBHOOK_URL` repository secret and the message ID in
`DISCORD_DAILY_MESSAGE_ID`.

### Permanent channel and immutable download

An Actions artifact URL requires a signed-in GitHub account. The maintained card
therefore links the current immutable MSI and this permanent channel page:

```
https://github.com/Shederator/wosbot/releases/tag/nightly
```

`nightly` carries only the project-signed manifest and a link to the current
immutable `vX.Y.Z-nightly.YYYYMMDD.N` release. The MSI remains versioned and is
never overwritten. GitHub's `releases/latest` remains reserved for Stable.

### Setting the secret

*Settings → Secrets and variables → Actions → New repository secret*

| Field | Value |
|---|---|
| Name | `DISCORD_NIGHTLY_WEBHOOK_URL` |
| Secret | the full `https://discord.com/api/webhooks/<id>/<token>` URL |

To rotate it, edit the same secret — nothing else has to change. If the secret is
absent the notify step logs a warning and the build still passes; a channel
notification is not worth failing a good artifact over.

### Behaviour worth knowing

- **Failures notify too.** The step is `if: always()`, so a broken nightly shows
  up as a red card instead of being silently absent — the failure mode a
  success-only notifier hides.
- **Pull requests never notify.** A PR from a fork gets a read-only token that
  cannot read secrets, and republishing `nightly` from unmerged code would hand
  testers an unreviewed build.
- **`continue-on-error: true`** keeps a Discord outage from turning a good build
  red. Delivery is retried on 429 (honouring `Retry-After`) and on 5xx.
- **No mass pings.** `allowed_mentions: {parse: []}` is set structurally, so an
  `@everyone` in a commit subject cannot ping the channel.
- **The commit message is passed through the environment**, never interpolated
  into the `run:` block, so `$(...)` in a commit subject cannot execute on the
  runner while the webhook secret is in scope.
- **The webhook is never printed.** Errors are redacted before logging, since
  Actions logs are public on a public repository.
- **A malformed download URL is dropped.** If the release step was skipped, the
  card falls back to the run link rather than advertising a broken download.

Test the payload without posting anything:

```sh
python3 build-support/notifications/test_discord_notify.py
python3 build-support/notifications/discord_notify.py --status success \
  --version 3.0.0-nightly.20260812.9 \
  --download-url https://example.com/Frostguard-Nightly.msi \
  --release-url https://example.com/releases/example \
  --channel-url https://example.com/releases/nightly --dry-run
```

## Why two verification layers

`verify_bundle.py` proves the right files are at the right paths. It cannot prove
they link together. A dependency dropped from a POM, a shaded JAR that lost a
transformer, or an incompatible library bump all produce a *structurally perfect*
bundle that dies with `NoClassDefFoundError` the first time a user runs it.
`smoke_test_bundle.sh` closes that gap by loading the classes for real.

Both layers were validated against a deliberately damaged bundle: removing
`lib/hibernate-core-*.jar` is caught by the manifest cross-check **and**
independently by the smoke test.

## Why `-Djavafx.platform=win`

JavaFX artifacts are platform-classified. Without this flag a Linux runner
resolves the `-linux` classifier and produces a bundle that cannot start on
Windows. The flag forces the `-win` classifier, and step 6 asserts the
substitution really took effect in both directions.

## Notes for maintainers

- Tests are **not** skipped. `OpenCvPatternLocator.loadNativeLibrary()` selects
  the native image per platform, so the vision suites run on Linux runners and on
  Windows developer machines alike.
- The bundle is ~220 MB, mostly the OpenCV and JavaFX runtimes. It is uploaded
  with `compression-level: 0` because a ZIP does not recompress usefully.
- `smoke_test_bundle.sh` unpacks over 400 MB. It extracts next to the ZIP rather
  than into `/tmp`, since `/tmp` is a small tmpfs on many machines. Override the
  location with `FROSTGUARD_SMOKE_TMPDIR`.
- Reproduce the whole pipeline locally on Linux or Windows with:

  ```sh
  ./mvnw clean install -Djavafx.platform=win
  python3 build-support/verification/test_verify_bundle.py
  python3 build-support/notifications/test_discord_notify.py
  python3 build-support/verification/verify_bundle.py packaging/desktop/target/frostguard-*-desktop-bundle.zip
  build-support/verification/smoke_test_bundle.sh packaging/desktop/target/frostguard-*-desktop-bundle.zip
  ```

## Stable release notifications

[`stable_release_notify.py`](../notifications/stable_release_notify.py) updates one maintained
Stable message without mentioning users. Its payload contains fixed release
facts, not contributor-controlled PR titles or commit messages. Stable releases
use the same `DISCORD_NIGHTLY_WEBHOOK_URL` credential and the message stored in
`DISCORD_STABLE_MESSAGE_ID`. `Refresh Stable Discord Message` can reconcile the
card with GitHub's current Latest release without publishing a new release.

Release policy and the `#downloads` channel templates live in
[`docs/releases.md`](../../docs/releases.md).

## Combined PR test builds (`/build-pr`)

Testers can request a temporary Windows bundle that combines one or more
**open** pull requests — including stacked PRs — without merging anything
(issue #68). Two entry points exist:

- **Actions tab** → *PR Test Build* → *Run workflow* with `prs: 47,48,49,65`.
- **Discord** `/build-pr 47 48 49 65` via the Cloudflare Worker in
  [`discord-bot/`](../../discord-bot/README.md), which validates the request,
  pins head SHAs, shows the plan and asks for confirmation before dispatching
  the same workflow.

The canonical pipeline is
[`pr-test-build.yml`](../../.github/workflows/pr-test-build.yml) with four jobs
that enforce a strict trust split:

| Job | Trust | What it does |
|---|---|---|
| `plan` | trusted | [`pr_build_plan.py plan`](../release/pr_build_plan.py): rejects closed/merged/non-numeric PRs with reasons, pins every head SHA, drops PRs already contained in another requested head or in `main` (stacked PRs), orders base-to-tip, trial-merges on a detached HEAD and reports conflicting files (binary conflicts flagged). Never executes PR code. |
| `build` | **untrusted** | `pr_build_plan.py merge` reproduces the planned merge and fails unless the tree is bit-identical to the planned one, then runs the full Maven build. Read-only token, **no secrets**. Its verification is advisory only. |
| `publish` | trusted | Fresh runner, pristine `main`: re-verifies the bundle with the trusted `verify_bundle.py` + `smoke_test_bundle.sh`, re-checks (`pr_build_plan.py recheck`) that every PR is still open and unchanged, then publishes the `pr-test-<digest>` prerelease. The digest covers base SHA + ordered pinned heads, so identical requests reuse the existing release. |
| `notify` | trusted | [`pr_test_notify.py`](../notifications/pr_test_notify.py) validates the Discord guild/channel context, replies to the original `/build-pr` status through the bot API and mentions only the requester. Manual dispatches without Discord context do not notify. |

No job ever pushes to `main` or a PR branch; the merged tree exists only
inside the runners. [`pr-test-cleanup.yml`](../../.github/workflows/pr-test-cleanup.yml)
deletes each test release after 7 days or once every included PR is closed,
and never touches `nightly` or real releases.

The Git LFS pointer-stub guard shared with the nightly lives in
[`check_lfs_assets.sh`](check_lfs_assets.sh).

Run the feature's tests locally:

```sh
python3 build-support/release/test_pr_build_plan.py       # planner, against real throwaway git repos
python3 build-support/notifications/test_pr_test_notify.py      # Discord result messages
python3 build-support/verification/check_workflow_python.py    # inline `python3 -c` snippets in the workflows
node discord-bot/test_worker.mjs       # worker helpers
```

### Inline workflow Python is compile-checked

The release notes are assembled by short `python3 -c '...'` snippets inside the
`publish` job. Those snippets sit in **shell single quotes**, so a backslash
escape such as `\"` is not consumed by the shell — it reaches Python verbatim
and raises `SyntaxError: unexpected character after line continuation
character`. That is a run-time failure: it surfaced only in `publish`, i.e.
*after* a full Maven build had already succeeded, and the requester saw nothing
but "Test build failed" in Discord.

[`check_workflow_python.py`](check_workflow_python.py) extracts every inline
snippet from `.github/workflows/*.yml` and `compile()`s it (it never executes
anything). The `plan` job runs it alongside the planner tests, so the same typo
now fails in seconds, before any runner time is spent.

There is no staged workflow copy. Changes are made directly under
`.github/workflows/`, and the compile checker validates that single source of truth.

Rules for these snippets, to keep them valid:

- no single quotes — they would close the shell quoting;
- no backslash escapes — use `"…{}".format(x["key"])` instead of an f-string
  with `\"` inside it;
- pass data as `sys.argv`, never by interpolating `${{ … }}` into the program.
