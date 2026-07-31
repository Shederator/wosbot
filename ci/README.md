# Continuous Integration

Three workflows:

| Workflow | Purpose |
|---|---|
| `daily-windows-bundle.yml` | the nightly Windows bundle from `main`, published as the rolling `nightly` prerelease |
| `pr-test-build.yml` | temporary bundles built from **unmerged** pull requests, on request |
| `pr-test-build-cleanup.yml` | expiry of those temporary builds |

## Installing the workflows

All three currently live in [`workflows/`](workflows) instead of
`.github/workflows/`, because GitHub rejects a push that touches
`.github/workflows/` unless the pushing credential holds the `workflows`
permission — which the automation that opened this change does not have. Files in
`ci/workflows/` are inert: Actions never reads them.

A repository owner installs them once, from a normal clone:

```sh
bash ci/install_workflows.sh            # dry run, lists what would move
bash ci/install_workflows.sh --apply    # git mv into .github/workflows/
git commit -m "ci(actions): install pull request test build workflows"
git push
```

`daily-windows-bundle.yml` replaces the existing nightly workflow; the only
changes are the extracted LFS check and the new test step. The script is
idempotent — after the move `ci/workflows/` no longer exists and a second run
does nothing.

Once installed, the nightly needs no further activation; the first scheduled run
happens at the next 03:17 UTC. The PR test build is documented for testers in
[`docs/PR_TEST_BUILDS.md`](../docs/PR_TEST_BUILDS.md); the notes below cover the
implementation.

## When the nightly runs

| Trigger | Purpose |
|---|---|
| `schedule` (03:17 UTC daily) | Publishes a nightly Windows bundle for testers, updates the `nightly` release and posts it to Discord |
| `pull_request` | Guards `pom.xml`, `src/`, `tools/`, `custom_tasks/`, `ci/`, `fg-watcher.bat` and `.gitattributes` |
| `push` to `ci/**` | Lets CI changes be iterated on a branch |
| `workflow_dispatch` | On-demand build from the Actions tab |

## What the pipeline does

1. Checks out the repository **with Git LFS**, then asserts that every LFS asset
   was really materialised. The check fails if `git lfs ls-files` returns nothing
   at all, if one of the four critical assets is no longer tracked, if a file is
   still a pointer stub, or if it is implausibly small. Without those guards the
   step could pass vacuously and ship a bundle that fails only on a user's PC.
2. Sets up **Temurin JDK 21** with a Maven dependency cache.
3. Installs `libtesseract` / `libleptonica`, which tess4j binds at runtime for
   the OCR regression tests. OpenCV needs no system package — the
   `org.openpnp:opencv` artifact ships the Linux native image.
4. Runs the verifier's own unit tests (`ci/test_verify_bundle.py`), so a
   verification script that can no longer fail cannot silently green-light a
   broken artifact.
5. Runs `mvn clean install -Djavafx.platform=win`. This **cross-builds the
   Windows desktop bundle from Linux** while still executing the full JUnit 5
   suite, including the vision and OCR saved-frame tests.
6. **Structurally verifies** the ZIP with [`verify_bundle.py`](verify_bundle.py):
   Windows JavaFX runtime present and no other platform's runtime leaking, the
   launcher and watcher JARs, bundled `adb`/OCR assets, template sprites,
   `custom_tasks/`, a floor on staged runtime JARs, and that **every
   `Class-Path` entry of the launcher manifest really exists** in the archive.
7. **Launch-smoke-tests** the extracted bundle with
   [`smoke_test_bundle.sh`](smoke_test_bundle.sh): resolves the real entry points
   off the real bundle classpath, checks `java -jar` resolves the manifest
   `Class-Path`, and boots the shaded Telegram watcher for real.
8. Uploads the bundle (version-tagged, no re-compression) and the Surefire
   reports, and writes a job summary with size, JAR count and test count.
9. Republishes the rolling **`nightly` prerelease** with the bundle attached, so
   there is a public download URL that needs no GitHub login.
10. Posts a **Discord notification** with that download link.

## Discord notifications

[`discord_notify.py`](discord_notify.py) posts one message per non-PR run to the
webhook in the `DISCORD_NIGHTLY_WEBHOOK_URL` repository secret.

### Why a release and not the Actions artifact

An artifact download URL only resolves for a signed-in GitHub account with read
access to the repository, so it is useless as a "downloadable link" in a Discord
channel. The bundle is also ~220 MB, far above the 8 MiB a webhook may upload.
The workflow therefore republishes the rolling `nightly` tag on every `main`
build and links its asset at this permanent URL:

```
https://github.com/Shederator/wosbot/releases/download/nightly/frostguard-windows-desktop-bundle.zip
```

Three details keep that link from going stale or 404ing:

- **The asset name carries no version.** A download URL contains the asset
  filename, so uploading `frostguard-2.1.0-desktop-bundle.zip` would change the
  link at the next version bump and break every message already in the channel.
  The versioned ZIP is copied to a fixed name before upload; the version is
  still reported in the release title, notes and Discord card.
- **The release is deleted and recreated** (`--cleanup-tag`) rather than edited.
  `gh release edit --target` does not move an existing tag, so editing in place
  would leave `nightly` pinned to the first commit it was ever cut from while
  the notes advertised a newer SHA.
- **The URL is read back from the API** (`browser_download_url`) and then
  actually fetched, instead of being predicted from the filename. A predicted
  URL is precisely how a dead link reaches the channel. If the asset is missing
  or does not serve a 200/206, the step fails before anything is posted.

The tag is marked *prerelease*, so it never displaces a real tagged release as
"Latest".

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
python3 ci/test_discord_notify.py     # 21 self-tests, no network
python3 ci/discord_notify.py --status success --version 2.1.0 \
  --download-url https://example.com/bundle.zip --dry-run
```

## Unmerged pull request test builds

`/build-pr 47 48 49 65`, typed as a comment on any issue or pull request (or run
from the Actions tab), produces a temporary Windows bundle built from those pull
requests. Tester-facing documentation is in
[`docs/PR_TEST_BUILDS.md`](../docs/PR_TEST_BUILDS.md). The parts worth knowing
when changing the code:

| Script | Responsibility |
|---|---|
| [`pr_build_command.py`](pr_build_command.py) | parse the command, authorize the requester, enforce the cooldown |
| [`pr_build_plan.py`](pr_build_plan.py) | reject unbuildable pull requests, pin head commits, drop contained stack entries, derive the merge order, render the plan and the release notes |
| [`pr_build_combine.py`](pr_build_combine.py) | merge the pinned commits on a throwaway branch, report conflicts, optionally propose a both-sides resolution |
| [`pr_build_notify.py`](pr_build_notify.py) | the Discord message for plan, conflict, success and failure |
| [`pr_build_cleanup.py`](pr_build_cleanup.py) | retire `pr-test-*` prereleases by age or when every included pull request is closed |
| [`verify_lfs_assets.sh`](verify_lfs_assets.sh) | the Git LFS materialisation guard, shared by both build workflows |
| [`install_workflows.sh`](install_workflows.sh) | move the staged workflow files into `.github/workflows/`, once |

Design decisions that are easy to undo by accident:

- **The build job references no secret.** It compiles unreviewed code, including
  its `pom.xml` plugins. Adding `secrets.*` to that job — or widening its
  `permissions` beyond `contents: read` — would hand the webhook and a write
  token to whatever a pull request wants to execute. The publish job is where
  credentials belong, and it runs only default-branch code.
- **Commits are pinned, then re-verified.** `pr_build_plan.py verify` runs again
  after the ~30 minute build. A pull request that was closed, merged or
  force-pushed in the meantime stops the publication instead of producing a
  download whose notes are wrong.
- **Conflicts never pick a side.** `--ours`/`--theirs` would silently drop half a
  pull request. The only automatic resolution offered is `--union`, which keeps
  both sides, writes the diff out for review, and is refused outright for binary
  and delete/modify conflicts.
- **Nothing is pushed.** `pr_build_combine.py` raises if a `git push` ever
  appears in its arguments, and the combined commit only exists in the build
  job's workspace. The published tag points at the base commit — a new ref, not a
  moved branch.
- **The build key is the identity of a build.** It hashes the base commit and the
  ordered head commits, which is what makes reuse safe: a different order or a
  single new commit yields a different key, tag and asset name.
- **The release notes carry a hidden marker** (`<!-- frostguard-pr-test … -->`)
  listing the included pull requests. The cleanup reads it back, which is why no
  state has to be stored anywhere else — and why the notes must not be edited by
  hand.

Run the whole tool suite in about two seconds:

```sh
python3 ci/test_pr_build_plan.py       # 41 tests
python3 ci/test_pr_build_combine.py    # 13 tests, real throwaway repositories
python3 ci/test_pr_build_notify.py     # 18 tests
python3 ci/test_pr_build_command.py    # 23 tests
python3 ci/test_pr_build_cleanup.py    # 13 tests
node tools/discord-build-command/test-worker.mjs   # optional Discord endpoint
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
  mvn clean install -Djavafx.platform=win
  python3 ci/test_verify_bundle.py
  python3 ci/test_discord_notify.py
  python3 ci/verify_bundle.py fg-app/target/frostguard-*-desktop-bundle.zip
  ci/smoke_test_bundle.sh fg-app/target/frostguard-*-desktop-bundle.zip
  ```
