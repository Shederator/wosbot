# `/build-pr` — combined PR test builds from Discord

Lets approved Discord testers request a public Windows test build that
combines one or more **open** pull requests (including stacked PRs) without
merging anything, e.g.:

```
/build-pr prs: 47 48 49 65
```

This directory contains the Discord half; the build half lives in
[`setup/github-workflows/pr-test-build.yml`](../setup/github-workflows/pr-test-build.yml)
(run `bash setup/install-workflows.sh` once to copy the workflows into
`.github/workflows/` — required because workflow files cannot be pushed by
tokens without the `workflows` permission).
The Discord command is optional: the same feature works today from the
GitHub **Actions tab → PR Test Build → Run workflow** with `prs: 47,48,49,65`.

## How a request flows

```
/build-pr 47 48 49 65
   │
   ▼
Cloudflare Worker (this directory)
   • verifies the Discord Ed25519 signature
   • checks channel + tester role
   • validates numbers, rejects closed/merged PRs with reasons
   • pins the exact head SHA of every PR
   • shows the merge plan with Build / Cancel buttons
   │  (requester presses Build; cooldown + concurrency checked)
   ▼
GitHub Actions: pr-test-build.yml
   • plan    (trusted)   containment of stacked PRs, order, trial merge,
                         conflict report — never executes PR code
   • build   (UNTRUSTED) reproduces the exact planned merge, runs Maven;
                         read-only token, zero secrets
   • publish (trusted)   fresh runner re-verifies the bundle, re-checks every
                         PR is still open and unchanged, publishes the
                         temporary pr-test-<digest> prerelease
   • notify  (trusted)   posts the download link / conflict report / failure
                         to the existing Discord webhook
   ▼
pr-test-cleanup.yml deletes the release after 7 days
or when every included PR is closed.
```

Key properties:

- **No branch is ever modified.** All merging happens on a detached HEAD in
  the runner's disposable checkout.
- **SHAs are pinned twice** (at Discord confirmation and at planning) and
  re-checked before publishing, so a push mid-build withholds the release
  instead of shipping unadvertised code.
- **Conflicts stop the build** and the conflicting files are posted to
  Discord, binary conflicts flagged as needing a manual choice. Nothing is
  auto-resolved with `ours`/`theirs`.
- **Untrusted PR code never sees secrets.** The build job has a read-only
  token and no `secrets.*`; verification that gates publishing runs from
  pristine `main` on a fresh runner.
- **Identical requests reuse the existing build**: the release tag is a
  digest over the base SHA plus the ordered pinned heads.
- Every message and release is labelled an **UNMERGED TEST BUILD**.

## Setup (one-time, ~20 minutes)

You need: the Discord server's admin, a Cloudflare account (free tier is
fine), and repo admin on GitHub.

### 1. Create the Discord application

1. Open <https://discord.com/developers/applications> → **New Application**
   → name it e.g. `Frostguard Test Builds`.
2. On **General Information**, note the **Application ID** and the
   **Public Key**.
3. On **Bot**, click **Reset Token** and note the **Bot Token** (needed only
   for command registration, not by the worker).
4. On **Installation**, pick *Guild Install*; under OAuth2 scopes the bot
   needs only `applications.commands`. Install it to your server via the
   generated link.

> A webhook alone is not enough for slash commands: webhooks can only *post*
> messages. Slash commands need an application with an **interactions
> endpoint**, which is what this worker provides. Your existing
> `DISCORD_NIGHTLY_WEBHOOK_URL` webhook is still used — for posting results.

### 2. Create the fine-grained GitHub token for the worker

GitHub → Settings → Developer settings → Fine-grained tokens → Generate:

- Repository access: **only** `Shederator/wosbot`
- Permissions: **Actions: Read and write** (to dispatch the workflow),
  **Pull requests: Read**, **Contents: Read**
- Expiry: your choice; set a reminder to rotate it.

This token cannot push, merge or create releases even if leaked.

### 3. Deploy the worker

```bash
cd discord-bot
# Fill DISCORD_APPLICATION_ID (and optionally the channel/role IDs) in
# wrangler.toml, then:
npx wrangler deploy
npx wrangler secret put DISCORD_PUBLIC_KEY   # from step 1.2
npx wrangler secret put GITHUB_TOKEN         # from step 2
```

Wrangler prints the worker URL, e.g.
`https://frostguard-build-pr.<your-subdomain>.workers.dev`.

### 4. Point Discord at the worker

Developer Portal → your application → **General Information** →
**Interactions Endpoint URL** → paste the worker URL → Save. Discord sends a
signed PING; if the save succeeds, signature verification works.

### 5. Register the slash command

```bash
cd discord-bot
DISCORD_BOT_TOKEN=... DISCORD_APPLICATION_ID=... \
DISCORD_GUILD_ID=<your server id> node register-command.mjs
```

With `DISCORD_GUILD_ID` the command appears instantly; without it Discord
takes up to an hour to propagate it globally.

### 6. Restrict who can use it

Two independent layers; use either or both:

- **Worker config** (`wrangler.toml`): set `ALLOWED_CHANNEL_IDS` and/or
  `ALLOWED_ROLE_IDS` (enable Developer Mode in Discord, right-click a
  channel/role → Copy ID), then `npx wrangler deploy` again.
- **Discord UI**: Server Settings → Integrations → your app → `/build-pr` →
  limit to specific roles/channels.

### 7. Verify end-to-end

1. In the allowed channel run `/build-pr prs: <an open PR number>`.
2. Check the plan shows the pinned SHA, press **Build**.
3. Watch the run under Actions → *PR Test Build*.
4. The result message (or conflict report) arrives via the existing
   nightly webhook.

## Configuration reference

| Where | Name | What |
|---|---|---|
| `wrangler.toml` | `GITHUB_REPO` | repo whose PRs are built |
| `wrangler.toml` | `DISCORD_APPLICATION_ID` | application (client) ID |
| `wrangler.toml` | `ALLOWED_CHANNEL_IDS` | CSV channel allowlist (empty = all) |
| `wrangler.toml` | `ALLOWED_ROLE_IDS` | CSV role allowlist (empty = all) |
| `wrangler.toml` | `COOLDOWN_MINUTES` | flood-control gap between builds |
| worker secret | `DISCORD_PUBLIC_KEY` | interaction signature verification |
| worker secret | `GITHUB_TOKEN` | fine-grained dispatch-only token |
| repo secret | `DISCORD_NIGHTLY_WEBHOOK_URL` | already configured; reused for results |

## Tests

```bash
node discord-bot/test_worker.mjs      # worker helpers
python3 ci/test_pr_build_plan.py      # planner (real git repos)
python3 ci/test_pr_test_notify.py     # Discord result messages
```
