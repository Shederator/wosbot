# Frostguard Discord commands

This Worker provides two guild-scoped commands:

- `/build-pr` requests combined PR test builds.
- `/please-dont-make-me-say-it-again` gives a friendly public reminder to put
  bugs and suggestions in their dedicated channels and explain the underlying
  problem or goal. It is limited to members with **Manage Messages**.

Set `BUG_REPORT_CHANNEL_ID` and `SUGGESTIONS_CHANNEL_ID` in `wrangler.toml` to
make the reminder link the server's channels. Without them it displays the
plain-text fallbacks `#bug-reports` and `#suggestions`. Re-run the command-sync
workflow after adding or changing commands; channel changes only require a
Worker deployment.

## `/build-pr` — combined PR test builds from Discord

Lets Discord users request a public Windows test build that
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
   • checks the configured channel
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
   • notify  (trusted)   posts through the dedicated channel webhook, mentions
                         only the requester and links the original request
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
3. On **OAuth2**, note the **Client Secret**. The command-sync workflow exchanges
   it for a short-lived token scoped only to `applications.commands.update`.
4. On **Installation**, keep *Guild Install* with only the
   `applications.commands` scope. A bot user and the `bot` scope are not needed.

The Discord application owns the slash command, while its interactions endpoint
is the Cloudflare Worker below. A dedicated channel webhook sends final build
results, so no continuously running or server-installed bot is required.

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
# Fill DISCORD_APPLICATION_ID (and optionally the channel IDs) in
# wrangler.toml, then:
npx wrangler deploy
npx wrangler secret put DISCORD_PUBLIC_KEY   # from step 1.2
npx wrangler secret put GITHUB_TOKEN         # from step 2
```

Wrangler prints the worker URL, e.g.
`https://frostguard-build-pr.<your-subdomain>.workers.dev`.

For repeatable deployments, add `CLOUDFLARE_API_TOKEN` and
`CLOUDFLARE_ACCOUNT_ID` as GitHub repository secrets, then run **Deploy Discord
Build PR Worker** from the Actions tab. Existing Worker secrets remain stored
at Cloudflare and are not committed or printed by the workflow.

### 4. Point Discord at the worker

Developer Portal → your application → **General Information** →
**Interactions Endpoint URL** → paste the worker URL → Save. Discord sends a
signed PING; if the save succeeds, signature verification works.

### 5. Synchronise the slash command

```bash
cd discord-bot
DISCORD_CLIENT_SECRET=... DISCORD_APPLICATION_ID=... \
DISCORD_GUILD_ID=<your server id> node register-command.mjs
```

The script requires a guild ID, upserts all configured guild commands, and
removes global copies registered for the same application. This intentionally
leaves one guild copy of each command instead of duplicate global and guild
entries.

Alternatively, store the client secret as the GitHub repository secret
`DISCORD_CLIENT_SECRET` and run **Sync Discord Build PR Command** from the
Actions tab. The script requests a short-lived OAuth2 client-credentials token;
the secret and access token are never printed.

### 6. Configure result routing

Create a dedicated webhook under `#request-a-build` → **Edit Channel** →
**Integrations** → **Webhooks**. Configure the same channel in both systems so
the workflow validates the interaction context before using that webhook:

- **Worker config** (`wrangler.toml`): set `ALLOWED_CHANNEL_IDS`, then deploy.
- **GitHub secret**: `DISCORD_PR_BUILD_WEBHOOK_URL` (the dedicated webhook URL).
- **GitHub variable**: `DISCORD_PR_BUILD_GUILD_ID`.
- **GitHub variable**: `DISCORD_PR_BUILD_CHANNEL_IDS` (CSV).

Discord's documented incoming-webhook API does not support creating a native
reply to an arbitrary existing message. The result therefore pings only the
requester and includes an **Open the original request** link instead.

### 7. Verify end-to-end

1. In the allowed channel run `/build-pr prs: <an open PR number>`.
2. Check the plan shows the pinned SHA, press **Build**.
3. Watch the run under Actions → *PR Test Build*.
4. The result arrives in the same channel, mentions only the requester and
   links the original status message.

## Maintainer handoff

The committed configuration targets application `1532693190879215767`, server
`1475434539495981137`, and `#request-a-build`
(`1533460326111117322`). To make repository changes live, the application owner
only needs to:

1. Create a webhook in `#request-a-build`, then add
   `DISCORD_PR_BUILD_WEBHOOK_URL`, `DISCORD_CLIENT_SECRET`,
   `CLOUDFLARE_API_TOKEN`, and `CLOUDFLARE_ACCOUNT_ID` as GitHub repository
   secrets. A repository administrator can enter them; their values cannot be
   read back from GitHub afterward.
2. Confirm the existing Cloudflare Worker still has `DISCORD_PUBLIC_KEY` and
   its fine-grained `GITHUB_TOKEN` Worker secrets.
3. Run **Deploy Discord Build PR Worker**.
4. Run **Sync Discord Build PR Command**.
5. Test `/build-pr` in `#request-a-build` with a non-admin account.

## Configuration reference

| Where | Name | What |
|---|---|---|
| `wrangler.toml` | `GITHUB_REPO` | repo whose PRs are built |
| `wrangler.toml` | `DISCORD_APPLICATION_ID` | application (client) ID |
| `wrangler.toml` | `ALLOWED_CHANNEL_IDS` | CSV channel allowlist (empty = all) |
| `wrangler.toml` | `COOLDOWN_MINUTES` | flood-control gap between builds |
| `wrangler.toml` | `BUG_REPORT_CHANNEL_ID` | channel linked by the reporting reminder |
| `wrangler.toml` | `SUGGESTIONS_CHANNEL_ID` | channel linked by the reporting reminder |
| worker secret | `DISCORD_PUBLIC_KEY` | interaction signature verification |
| worker secret | `GITHUB_TOKEN` | fine-grained dispatch-only token |
| repo secret | `DISCORD_CLIENT_SECRET` | obtains a short-lived command-update token; no bot installation required |
| repo secret | `DISCORD_PR_BUILD_WEBHOOK_URL` | dedicated `#request-a-build` result webhook |
| repo variable | `DISCORD_PR_BUILD_GUILD_ID` | allowed server ID |
| repo variable | `DISCORD_PR_BUILD_CHANNEL_IDS` | allowed result channel IDs (CSV) |

## Tests

```bash
node discord-bot/test_worker.mjs      # worker helpers
python3 ci/test_pr_build_plan.py      # planner (real git repos)
python3 ci/test_pr_test_notify.py     # Discord result messages
```
