# Unmerged pull request test builds

Sometimes a fix exists in a pull request but has not been merged yet, and the
only way to find out whether it actually works is to run it. This is how a
tester gets a Windows bundle built from one or more **unmerged** pull requests,
including stacked ones, without anybody handing out repository access and without
touching `main` or the pull request branches.

> [!CAUTION]
> Every download produced this way contains code that has not been reviewed or
> merged. Point it at a test profile, not at your main account data.

> [!IMPORTANT]
> **One-time installation.** The workflow files arrive parked in
> [`ci/workflows/`](../ci/workflows) because the pull request that added them was
> not permitted to write into `.github/workflows/`. Until a repository owner runs
> the installer below, `/build-pr` does nothing. See
> [Installing the workflows](#installing-the-workflows).

## Requesting a build

Comment on any issue or pull request in this repository:

```
/build-pr 47 48 49 65
```

Nothing is built yet. The workflow replies in the same thread with the **merge
plan**: which pull requests it accepted, the exact commit it pinned for each one,
the order it will merge them in, and an explanation for anything it left out.
Read it, then confirm:

```
/build-pr 47 48 49 65 confirm
```

The build takes roughly 30 minutes. When it finishes, the download link is posted
in the thread and in the Discord channel.

| You type | What happens |
|---|---|
| `/build-pr 47 48` | plan only — nothing is built |
| `/build-pr 47 48 confirm` | build it and publish a temporary download |
| `/build-pr 47 48 union confirm` | on conflict, propose a resolution that keeps both sides |
| `/build-pr 47 48 order=48,47` | override the merge order |
| `/build-pr help` | print the usage into the thread |

Maintainers can do exactly the same from *Actions → PR Test Build → Run
workflow*, which is useful when there is no thread to comment in.

## What the plan step decides for you

- **Closed, merged or non-existent pull requests are rejected**, each with a
  reason. A typo does not silently produce a build of something else.
- **Duplicates are removed** and anything that is not a pull request number is
  reported back rather than guessed at.
- **Commits are pinned.** Once a plan exists, a later push to a pull request
  cannot change what gets built, and a push during the build stops the
  publication instead of shipping a download whose description is wrong.
- **Stacked pull requests are collapsed.** If #65's head commit already contains
  #47, #48 and #49 — which is exactly what a stack looks like — merging the
  ancestors again is redundant and can conflict for no reason. Only #65 is built,
  and the plan says so.
- **The merge order is derived**, base-to-tip: a pull request whose base branch is
  another requested pull request's head branch goes after it, otherwise older
  commits go first. Override it with `order=` if you know better.
- **An identical build is reused.** The same pull requests at the same commits
  produce the same build key, so you get the existing download instead of waiting
  another half hour.

## When the pull requests cannot be combined

Git conflicts stop the request. You get the list of conflicting files, each
labelled as a text conflict, a binary conflict, or a file one side deleted while
the other changed it. **No side is ever picked automatically** — a build that
silently dropped half of a pull request would behave like neither pull request,
and you would have no way to notice.

If every conflict is a text conflict, you can ask for a resolution that keeps
**both** sides:

```
/build-pr 47 48 union confirm
```

The proposed resolution is uploaded as `proposal.diff` on the workflow run and
listed in the job summary. Keeping both sides can produce code that compiles but
duplicates work, so treat such a build as a rough experiment, not as evidence
that the pull requests are compatible. Binary conflicts always need a human.

## What you get

A prerelease tagged `pr-test-<build key>`, marked as a prerelease so it never
displaces a real release, carrying one asset:

```
frostguard-unmerged-test-build-pr-<numbers>.zip
```

Unzip it anywhere and run `java -jar frostguard-*.jar` (Java 21+). The release
notes list every included pull request with its author and pinned commit.

The bundle went through the same verification as the nightly build: structure,
manifest classpath, launch smoke test, and the full JUnit suite.

**These downloads expire.** A cleanup job deletes them after 7 days, or as soon
as every included pull request is closed or merged. Real releases and the rolling
`nightly` prerelease are never touched.

## Guardrails

- Only people with **write access**, or names listed in the
  `PR_TEST_BUILD_ALLOWLIST` repository variable, can start a build. Everyone else
  gets an explanation instead of a build.
- **Cooldown:** at most 3 builds per person per hour. Planning is free.
- **One build at a time**, so a burst of comments cannot occupy every runner.
- At most **10 pull requests** in one request.
- A quoted `/build-pr` line in a reply does **not** start a second build.
- `main` and the pull request branches are never modified. The combination lives
  on a throwaway branch inside one CI job and is never pushed. Publishing creates
  a tag on the base commit — a new ref, not a moved branch.

## Why untrusted code cannot reach the secrets

Building a pull request means compiling code nobody has reviewed, including its
`pom.xml` and whatever build plugins it names. The workflow is therefore split
into three jobs with different privileges:

| Job | Runs | Token | Secrets |
|---|---|---|---|
| `plan` | only code from the default branch | read | none |
| `build` | **untrusted pull request code** | read-only | **none referenced** |
| `publish` | only code from the default branch | `contents: write` | Discord webhook |

The build job references no secret at all and holds a read-only token, so there
is nothing for a malicious `pom.xml` to steal — no webhook, no release
credentials, no write access. The publish job holds those, verifies the pull
requests are still open at the pinned commits, and treats the bundle purely as a
file to upload; it never executes anything that came out of a pull request.

## For maintainers

Configuration:

| Where | Name | Purpose |
|---|---|---|
| Secret | `DISCORD_PR_BUILD_WEBHOOK_URL` | optional; a separate channel for unreviewed builds. Falls back to `DISCORD_NIGHTLY_WEBHOOK_URL` |
| Variable | `PR_TEST_BUILD_ALLOWLIST` | optional; GitHub logins that may build without write access |

Workflows (paths after installation):

- `.github/workflows/pr-test-build.yml` — plan, combine, build, publish, report.
  Staged at [`ci/workflows/pr-test-build.yml`](../ci/workflows/pr-test-build.yml).
- `.github/workflows/pr-test-build-cleanup.yml` — daily expiry. A manual run
  defaults to a dry run. Staged at
  [`ci/workflows/pr-test-build-cleanup.yml`](../ci/workflows/pr-test-build-cleanup.yml).
- `.github/workflows/daily-windows-bundle.yml` — the existing nightly build, with
  the duplicated Git LFS check replaced by the shared
  [`ci/verify_lfs_assets.sh`](../ci/verify_lfs_assets.sh) and a step that runs the
  new self-tests. Staged at
  [`ci/workflows/daily-windows-bundle.yml`](../ci/workflows/daily-windows-bundle.yml).

### Installing the workflows

GitHub refuses pushes that add or change files under `.github/workflows/` unless
the pushing credential carries the `workflows` permission, which the automation
that opened the pull request does not have. The three files therefore sit in
`ci/workflows/`, where GitHub Actions ignores them, and a repository owner moves
them into place once — from a normal clone, with a normal personal push:

```sh
git checkout main
git pull
bash ci/install_workflows.sh            # shows what would move
bash ci/install_workflows.sh --apply    # moves the files and stages the change
git commit -m "ci(actions): install pull request test build workflows"
git push
```

The script is safe to re-run: once `ci/workflows/` is gone it reports that there
is nothing left to install. Afterwards the Actions tab lists **Unmerged pull
request test build** and **Unmerged pull request test build cleanup**, and the
first `/build-pr` comment will be answered.

Tooling and tests live in [`ci/`](../ci/README.md) and run in seconds:

```sh
python3 ci/test_pr_build_plan.py
python3 ci/test_pr_build_combine.py
python3 ci/test_pr_build_notify.py
python3 ci/test_pr_build_command.py
python3 ci/test_pr_build_cleanup.py
```

Preview a plan or a message locally without touching anything:

```sh
GITHUB_TOKEN=$(gh auth token) python3 ci/pr_build_plan.py plan \
  --repository Shederator/wosbot --request "47 48 49 65" \
  --output plan.json --markdown plan.md

python3 ci/pr_build_notify.py --status plan --plan plan.json --dry-run
```

Typing the command **inside Discord** needs a registered Discord application, not
just a webhook; a webhook can only send. That step is optional and documented in
[`tools/discord-build-command/`](../tools/discord-build-command/README.md).
