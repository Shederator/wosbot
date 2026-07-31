# Optional: the `/build-pr` slash command in Discord

Everything about unmerged PR test builds already works **without** this
directory. A maintainer or an allowlisted tester types

```
/build-pr 47 48 49 65
```

as a comment on any issue or pull request, and the results — plan, conflicts or
download link — are posted to the Discord channel through the webhook that is
already configured. See [`docs/PR_TEST_BUILDS.md`](../../docs/PR_TEST_BUILDS.md).

This directory exists for the one thing a webhook fundamentally cannot do.

## Why a webhook is not enough

A Discord webhook is **outbound only**. It is a URL you POST messages to; it
never receives anything, has no presence in the server and cannot be typed at.
Slash commands and buttons are *inbound interactions*, and Discord only delivers
those to a registered **application**, either over a gateway connection (a
process that must stay online) or to an **Interactions Endpoint URL** — an HTTPS
endpoint that must verify a signature and answer within three seconds.

So: keep the webhook for the notifications, and add an application only if you
want the command itself inside Discord. The application here needs no gateway
process and no always-on server; it is one Cloudflare Worker on the free plan.

## What it does

```
/build-pr in Discord
      │  signature-verified, channel and role checked
      ▼
Cloudflare Worker ──► workflow_dispatch on pr-test-build.yml
      │
      ▼
"Planning…" plus a Build it / Cancel button pair
      │
GitHub Actions plans, builds and publishes, then the existing webhook posts
the plan, the conflict report or the download link into the channel
```

The Worker deliberately does very little: it sanitises the PR list down to
digits, checks the channel and the caller's roles, and starts exactly one
workflow. Every real guard — who may build, the cooldown, whether the pull
requests are open, whether they can be combined — stays in the workflow, where it
is unit-tested and where a mistake in this file cannot bypass it.

## Setup

**1. Create the application** at <https://discord.com/developers/applications>.
Copy the **Application ID** and the **Public Key**. Under *Bot*, copy the token
(needed only once, in step 4).

**2. Create a GitHub token.** A fine-grained personal access token on
`Shederator/wosbot` with **Actions: Read and write** and nothing else. It can
start the test-build workflow and do nothing more.

**3. Deploy the Worker:**

```sh
cd tools/discord-build-command
npx wrangler secret put DISCORD_PUBLIC_KEY   # paste the public key
npx wrangler secret put GITHUB_TOKEN         # paste the fine-grained token
npx wrangler deploy
```

Restrict who may use it (recommended — right-click a channel or role in Discord
with developer mode on to copy its ID):

```sh
npx wrangler secret put ALLOWED_CHANNEL_IDS  # e.g. 123456789012345678
npx wrangler secret put ALLOWED_ROLE_IDS     # e.g. the "Tester" role ID
```

**4. Point Discord at the Worker.** In the application's *General Information*
page, set **Interactions Endpoint URL** to the deployed
`https://frostguard-build-command.<your-subdomain>.workers.dev` and save.
Discord immediately sends a signed PING; saving only succeeds if the Worker
verified it, which is a useful end-to-end check.

**5. Register the command** (once, and after any option change):

```sh
DISCORD_APPLICATION_ID=... DISCORD_BOT_TOKEN=... DISCORD_GUILD_ID=... \
  node register-command.mjs
```

**6. Invite the application** to the server with the `applications.commands`
scope. It needs no message permissions and no privileged intents.

## Tests

```sh
node tools/discord-build-command/test-worker.mjs
```

14 tests, no network: input sanitising (including shell metacharacters and a cap
on how many pull requests one command may queue), the channel and role
allowlists, the dispatch payload shape, and that no message can ping the channel.

## Rotating or removing it

Rotate the GitHub token or the public key with `wrangler secret put` — nothing
else changes. To remove the command entirely, delete the Worker and clear the
Interactions Endpoint URL; the GitHub comment command keeps working, because it
never depended on any of this.
