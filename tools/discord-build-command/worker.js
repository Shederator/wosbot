// Discord interactions endpoint for the `/build-pr` command.
//
// This is OPTIONAL. The test-build feature works without it: `/build-pr 47 48`
// typed as a GitHub comment does the same thing, and a Discord *webhook* already
// delivers the results to the channel. What a webhook cannot do is receive
// anything — it is outbound only. A slash command with buttons is an inbound
// interaction, and Discord only delivers those to a registered application, at
// an HTTPS endpoint that answers within three seconds. This file is that
// endpoint, small enough to run on a free Cloudflare Worker.
//
// It holds a GitHub token, so it never trusts its input:
//   * every request is Ed25519-verified against the application public key, so
//     only Discord can invoke it;
//   * the channel and the caller's roles are checked against an allowlist;
//   * only digits survive from the PR list, so nothing can be smuggled into the
//     workflow inputs;
//   * it can only start ONE workflow (`pr-test-build.yml`) — the token needs no
//     other permission, and the workflow re-runs its own authorization and
//     cooldown checks anyway.
//
// See README.md in this directory for the deployment steps.

const INTERACTION_PING = 1;
const INTERACTION_COMMAND = 2;
const INTERACTION_COMPONENT = 3;

const REPLY = 4;
const REPLY_EPHEMERAL_FLAG = 64;

/** Extract at most 10 pull request numbers, discarding everything else. */
export function parsePrsOption(raw) {
  const numbers = [];
  for (const token of String(raw ?? "").split(/[^0-9]+/)) {
    if (!token) continue;
    const value = Number.parseInt(token, 10);
    if (!Number.isSafeInteger(value) || value <= 0) continue;
    if (!numbers.includes(value)) numbers.push(value);
  }
  return numbers.slice(0, 10);
}

/** Parse a space or comma separated allowlist from an environment variable. */
export function parseIdList(raw) {
  return String(raw ?? "")
    .split(/[\s,]+/)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

/**
 * Decide whether an interaction may start a build.
 *
 * An empty channel list means "any channel"; an empty role list means "anyone
 * who can see the channel". Both are explicit opt-outs, so a misconfigured
 * deployment fails closed only where the operator asked it to.
 */
export function isAllowed(interaction, env) {
  const channels = parseIdList(env.ALLOWED_CHANNEL_IDS);
  if (channels.length > 0 && !channels.includes(interaction.channel_id)) {
    return { allowed: false, reason: "This command only works in the test-build channel." };
  }
  const roles = parseIdList(env.ALLOWED_ROLE_IDS);
  if (roles.length === 0) return { allowed: true, reason: "" };

  const held = interaction.member?.roles ?? [];
  if (held.some((role) => roles.includes(role))) return { allowed: true, reason: "" };
  return {
    allowed: false,
    reason: "You need the tester role to request a build. Ask a moderator for it.",
  };
}

/** Read the options of a slash command into a plain object. */
export function commandOptions(interaction) {
  const options = interaction.data?.options ?? [];
  const value = (name) => options.find((option) => option.name === name)?.value;
  return {
    prs: parsePrsOption(value("prs")),
    confirm: Boolean(value("confirm")),
    union: Boolean(value("union")),
  };
}

/** Build the `workflow_dispatch` body. Inputs must all be strings. */
export function dispatchPayload({ prs, confirm, union }, ref) {
  return {
    ref: ref || "main",
    inputs: {
      prs: prs.join(" "),
      confirm: confirm ? "true" : "false",
      conflict_resolution: union ? "union" : "stop",
      order: "",
    },
  };
}

/** The message posted back into the channel while the workflow starts up. */
export function acknowledgement({ prs, confirm }, runsUrl) {
  const list = prs.map((number) => `#${number}`).join(", ");
  const lines = confirm
    ? [
        `Building an **unmerged test build** of ${list}.`,
        "The merge plan, the pinned commits and the download link are posted here when it finishes (roughly 30 minutes).",
      ]
    : [
        `Planning a test build of ${list}. **Nothing is built yet.**`,
        "The plan lists the pinned commits and drops pull requests that are already contained in a later one. Press **Build it** once it looks right.",
      ];
  if (runsUrl) lines.push(`[Workflow runs](${runsUrl})`);

  const message = {
    content: lines.join("\n"),
    allowed_mentions: { parse: [] },
  };
  if (!confirm && prs.length > 0) {
    message.components = [
      {
        type: 1,
        components: [
          {
            type: 2,
            style: 3,
            label: "Build it",
            custom_id: `build:${prs.join("-")}`,
          },
          {
            type: 2,
            style: 2,
            label: "Cancel",
            custom_id: "cancel",
          },
        ],
      },
    ];
  }
  return message;
}

function reply(message, ephemeral = false) {
  const data = { ...message };
  if (ephemeral) data.flags = REPLY_EPHEMERAL_FLAG;
  return Response.json({ type: REPLY, data });
}

function hexToBytes(hex) {
  const clean = String(hex ?? "").trim();
  const bytes = new Uint8Array(clean.length / 2);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = Number.parseInt(clean.slice(index * 2, index * 2 + 2), 16);
  }
  return bytes;
}

/**
 * Verify Discord's Ed25519 request signature.
 *
 * Without this anybody who learns the endpoint URL could start builds, so a
 * failure here is a 401 and nothing else happens.
 */
export async function verifySignature(request, body, publicKey) {
  const signature = request.headers.get("X-Signature-Ed25519");
  const timestamp = request.headers.get("X-Signature-Timestamp");
  if (!signature || !timestamp || !publicKey) return false;
  try {
    const key = await crypto.subtle.importKey(
      "raw",
      hexToBytes(publicKey),
      { name: "Ed25519" },
      false,
      ["verify"],
    );
    return await crypto.subtle.verify(
      { name: "Ed25519" },
      key,
      hexToBytes(signature),
      new TextEncoder().encode(timestamp + body),
    );
  } catch {
    return false;
  }
}

async function startWorkflow(env, payload) {
  const response = await fetch(
    `https://api.github.com/repos/${env.GITHUB_REPOSITORY}` +
      "/actions/workflows/pr-test-build.yml/dispatches",
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "frostguard-build-command/1.0",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    },
  );
  // 204 is the documented success status; anything else is reported to the user
  // rather than swallowed, so a rotated token does not look like a hung build.
  return { ok: response.status === 204, status: response.status };
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("This endpoint only accepts Discord interactions.", {
        status: 405,
      });
    }

    const body = await request.text();
    if (!(await verifySignature(request, body, env.DISCORD_PUBLIC_KEY))) {
      return new Response("invalid request signature", { status: 401 });
    }

    let interaction;
    try {
      interaction = JSON.parse(body);
    } catch {
      return new Response("malformed payload", { status: 400 });
    }

    if (interaction.type === INTERACTION_PING) {
      return Response.json({ type: INTERACTION_PING });
    }

    const runsUrl =
      `https://github.com/${env.GITHUB_REPOSITORY}/actions/workflows/pr-test-build.yml`;

    if (interaction.type === INTERACTION_COMPONENT) {
      const customId = interaction.data?.custom_id ?? "";
      if (customId === "cancel") {
        return reply({ content: "Cancelled. Nothing was built." }, true);
      }
      if (!customId.startsWith("build:")) {
        return reply({ content: "Unknown button." }, true);
      }
      const gate = isAllowed(interaction, env);
      if (!gate.allowed) return reply({ content: gate.reason }, true);

      const request_ = { prs: parsePrsOption(customId.slice("build:".length)), confirm: true, union: false };
      const result = await startWorkflow(env, dispatchPayload(request_, env.GITHUB_REF));
      if (!result.ok) {
        return reply(
          { content: `Could not start the build (GitHub returned ${result.status}).` },
          true,
        );
      }
      return reply(acknowledgement(request_, runsUrl));
    }

    if (interaction.type === INTERACTION_COMMAND) {
      if (interaction.data?.name !== "build-pr") {
        return reply({ content: "Unknown command." }, true);
      }
      const gate = isAllowed(interaction, env);
      if (!gate.allowed) return reply({ content: gate.reason }, true);

      const options = commandOptions(interaction);
      if (options.prs.length === 0) {
        return reply(
          {
            content:
              "Give me at least one open pull request number, for example `/build-pr prs: 47 48 49 65`.",
          },
          true,
        );
      }
      const result = await startWorkflow(env, dispatchPayload(options, env.GITHUB_REF));
      if (!result.ok) {
        return reply(
          { content: `Could not start the workflow (GitHub returned ${result.status}).` },
          true,
        );
      }
      return reply(acknowledgement(options, runsUrl));
    }

    return new Response("unsupported interaction type", { status: 400 });
  },
};
