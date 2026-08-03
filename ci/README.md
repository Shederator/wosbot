# Continuous Integration

Frostguard builds on GitHub Actions from
[`.github/workflows/daily-windows-bundle.yml`](../.github/workflows/daily-windows-bundle.yml).
No manual activation step is needed — the workflow is live as soon as it lands on
`main`, and the first scheduled run happens at the next 03:17 UTC.

## When it runs

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

## Stable Windows releases

[`stable-windows-release.yml`](../.github/workflows/stable-windows-release.yml)
promotes a successful Daily Windows Bundle run from `main` instead of rebuilding
a potentially different tree. A maintainer supplies the `X.Y.Z` version and
daily run ID. The workflow validates the source run and Maven version, pins its
commit, downloads and re-verifies its bundle, creates an immutable `vX.Y.Z`
release and checks the public URL before announcing it.

[`stable_release_notify.py`](stable_release_notify.py) updates one maintained
Stable message without mentioning users. Its payload contains fixed release
facts, not contributor-controlled PR titles or commit messages. Stable releases
use the same `DISCORD_NIGHTLY_WEBHOOK_URL` credential and the message stored in
`DISCORD_STABLE_MESSAGE_ID`. `Refresh Stable Discord Message` can reconcile the
card with GitHub's current Latest release without publishing a new release.

Release policy and the `#downloads` channel templates live in
[`docs/releases.md`](../docs/releases.md).

## Combined PR test builds (`/build-pr`)

Testers can request a temporary Windows bundle that combines one or more
**open** pull requests — including stacked PRs — without merging anything
(issue #68). Two entry points exist:

- **Actions tab** → *PR Test Build* → *Run workflow* with `prs: 47,48,49,65`.
- **Discord** `/build-pr 47 48 49 65` via the Cloudflare Worker in
  [`discord-bot/`](../discord-bot/README.md), which validates the request,
  pins head SHAs, shows the plan and asks for confirmation before dispatching
  the same workflow.

The pipeline is [`pr-test-build.yml`](../setup/github-workflows/pr-test-build.yml)
(staged under `setup/github-workflows/` until `setup/install-workflows.sh` copies
it into `.github/workflows/`) with four jobs that enforce a strict trust split:

| Job | Trust | What it does |
|---|---|---|
| `plan` | trusted | [`pr_build_plan.py plan`](pr_build_plan.py): rejects closed/merged/non-numeric PRs with reasons, pins every head SHA, drops PRs already contained in another requested head or in `main` (stacked PRs), orders base-to-tip, trial-merges on a detached HEAD and reports conflicting files (binary conflicts flagged). Never executes PR code. |
| `build` | **untrusted** | `pr_build_plan.py merge` reproduces the planned merge and fails unless the tree is bit-identical to the planned one, then runs the full Maven build. Read-only token, **no secrets**. Its verification is advisory only. |
| `publish` | trusted | Fresh runner, pristine `main`: re-verifies the bundle with the trusted `verify_bundle.py` + `smoke_test_bundle.sh`, re-checks (`pr_build_plan.py recheck`) that every PR is still open and unchanged, then publishes the `pr-test-<digest>` prerelease. The digest covers base SHA + ordered pinned heads, so identical requests reuse the existing release. |
| `notify` | trusted | [`pr_test_notify.py`](pr_test_notify.py) validates the Discord guild/channel context, replies to the original `/build-pr` status through the bot API and mentions only the requester. Manual dispatches without Discord context do not notify. |

No job ever pushes to `main` or a PR branch; the merged tree exists only
inside the runners. [`pr-test-cleanup.yml`](../setup/github-workflows/pr-test-cleanup.yml)
deletes each test release after 7 days or once every included PR is closed,
and never touches `nightly` or real releases.

The Git LFS pointer-stub guard shared with the nightly lives in
[`check_lfs_assets.sh`](check_lfs_assets.sh).

Run the feature's tests locally:

```sh
python3 ci/test_pr_build_plan.py       # planner, against real throwaway git repos
python3 ci/test_pr_test_notify.py      # Discord result messages
python3 ci/check_workflow_python.py    # inline `python3 -c` snippets in the workflows
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

Because `.github/workflows/` can only be written by a credential holding the
`workflows` permission, the fix lands in the staged copy under
[`setup/github-workflows/`](../setup/github-workflows/) and reaches the live
workflow when a maintainer runs:

```sh
bash setup/install-workflows.sh    # also compile-checks what it installed
git add .github/workflows
git commit -m "ci: install the PR test build fix"
git push
```

Until that is done, `python3 ci/check_workflow_python.py` reports the installed
copy as broken — that failure *is* the reminder that the staged fix has not been
applied yet.

Rules for these snippets, to keep them valid:

- no single quotes — they would close the shell quoting;
- no backslash escapes — use `"…{}".format(x["key"])` instead of an f-string
  with `\"` inside it;
- pass data as `sys.argv`, never by interpolating `${{ … }}` into the program.
