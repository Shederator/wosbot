/**
 * Frostguard Discord commands — Cloudflare Worker.
 *
 * The Discord half of the combined-PR test build feature (issue #68). This
 * worker receives Discord interaction webhooks, validates the request, shows
 * the effective plan (open PRs only, pinned head SHAs, merge order) and asks
 * the requester to confirm with a Build button before any build resource is
 * consumed. Confirming dispatches the `pr-test-build.yml` GitHub Actions
 * workflow, which does the authoritative planning (stacked-PR containment,
 * trial merge, conflict reporting), building, verification and publishing.
 *
 * Trust boundaries:
 * - This worker holds a fine-grained GitHub token whose ONLY permissions are
 *   Actions: read/write (to dispatch the workflow) and Pull requests: read,
 *   on this one repository. It cannot push, merge or create releases.
 * - The untrusted build job in the workflow never sees this token or the
 *   Discord credentials; the workflow enforces that separation itself.
 * - Every interaction is Ed25519-signature-verified against Discord's public
 *   key, so nobody can forge build requests by POSTing to the worker URL.
 *
 * Guardrails:
 * - Requests are limited to configured channels.
 * - PR numbers are deduplicated; non-numeric, closed and merged entries are
 *   rejected with a per-entry explanation.
 * - Head SHAs are pinned at confirmation time and passed to the workflow,
 *   which re-verifies them, so a later push cannot change a running build.
 * - Only the requester can press their own Build/Cancel buttons.
 * - A cooldown plus a one-build-at-a-time check prevents build floods.
 */

// ---------------------------------------------------------------------------
// Small pure helpers (exported for tests)
// ---------------------------------------------------------------------------

export const MAX_PRS = 6; // must match MAX_PRS_PER_REQUEST in ci/pr_build_plan.py

export function parsePrNumbers(raw) {
  const numbers = [];
  const errors = [];
  const seen = new Set();
  for (const token of String(raw || "").trim().split(/[\s,;]+/)) {
    if (!token) continue;
    const cleaned = token.replace(/^#/, "");
    if (!/^\d+$/.test(cleaned)) {
      errors.push(`\`${token}\` is not a PR number.`);
      continue;
    }
    const number = parseInt(cleaned, 10);
    if (number <= 0) {
      errors.push(`\`${token}\` is not a valid PR number.`);
      continue;
    }
    if (seen.has(number)) continue;
    seen.add(number);
    numbers.push(number);
  }
  if (numbers.length === 0 && errors.length === 0) {
    errors.push("No PR numbers were given.");
  }
  if (numbers.length > MAX_PRS) {
    errors.push(`${numbers.length} PRs requested; the limit is ${MAX_PRS} per test build.`);
  }
  return { numbers, errors };
}

/** Encode confirmation state into the embed footer. It survives the
 * roundtrip to the button click; a button custom_id is capped at 100 chars,
 * far too small for six pinned SHAs. */
export function encodeState(state) {
  const pins = state.pins.map((p) => `${p.number}@${p.sha.slice(0, 12)}`).join(",");
  return `pins=${pins};order=${state.order.join(",")};req=${state.requesterId}`;
}

export function decodeState(footerText) {
  const out = { pins: [], order: [], requesterId: "" };
  for (const part of String(footerText || "").split(";")) {
    const [key, value] = part.split("=", 2);
    if (key === "pins" && value) {
      for (const entry of value.split(",")) {
        const [number, sha] = entry.split("@", 2);
        if (/^\d+$/.test(number) && /^[0-9a-f]{7,40}$/.test(sha || "")) {
          out.pins.push({ number: parseInt(number, 10), sha });
        }
      }
    } else if (key === "order" && value) {
      out.order = value.split(",").filter((n) => /^\d+$/.test(n)).map(Number);
    } else if (key === "req" && value) {
      out.requesterId = value;
    }
  }
  return out;
}

export function hexToBytes(hex) {
  const clean = String(hex || "").trim();
  if (!/^[0-9a-fA-F]*$/.test(clean) || clean.length % 2 !== 0 || clean.length === 0) {
    return null;
  }
  const bytes = new Uint8Array(clean.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

// ---------------------------------------------------------------------------
// Discord signature verification
// ---------------------------------------------------------------------------

async function verifySignature(request, bodyText, publicKeyHex) {
  const signature = hexToBytes(request.headers.get("X-Signature-Ed25519"));
  const timestamp = request.headers.get("X-Signature-Timestamp");
  const publicKey = hexToBytes(publicKeyHex);
  if (!signature || !timestamp || !publicKey) return false;
  try {
    const key = await crypto.subtle.importKey(
      "raw", publicKey, { name: "Ed25519" }, false, ["verify"],
    );
    return await crypto.subtle.verify(
      "Ed25519", key, signature,
      new TextEncoder().encode(timestamp + bodyText),
    );
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------
// GitHub API
// ---------------------------------------------------------------------------

function githubHeaders(env) {
  return {
    Accept: "application/vnd.github+json",
    Authorization: `Bearer ${env.GITHUB_TOKEN}`,
    "User-Agent": "frostguard-build-pr-bot/1.0 (+https://github.com/Shederator/wosbot)",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}

async function fetchPull(env, number) {
  const response = await fetch(
    `https://api.github.com/repos/${env.GITHUB_REPO}/pulls/${number}`,
    { headers: githubHeaders(env) },
  );
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new Error(`GitHub API returned ${response.status} for PR #${number}`);
  }
  return response.json();
}

/** Returns "" when a build may start, otherwise a human explanation. */
async function checkCooldownAndConcurrency(env) {
  const cooldownMinutes = parseInt(env.COOLDOWN_MINUTES || "10", 10);
  const response = await fetch(
    `https://api.github.com/repos/${env.GITHUB_REPO}/actions/workflows/pr-test-build.yml/runs?per_page=5`,
    { headers: githubHeaders(env) },
  );
  if (!response.ok) {
    // Fail closed: if the flood-control check cannot run, neither can a build.
    return `Could not check running builds (GitHub returned ${response.status}); try again shortly.`;
  }
  const data = await response.json();
  const runs = data.workflow_runs || [];
  for (const run of runs) {
    if (run.status === "queued" || run.status === "in_progress") {
      return `A test build is already ${run.status.replace("_", " ")}: <${run.html_url}>. One combined build runs at a time; please wait for it.`;
    }
  }
  if (runs.length > 0 && cooldownMinutes > 0) {
    const last = new Date(runs[0].created_at).getTime();
    const elapsed = (Date.now() - last) / 60000;
    if (elapsed < cooldownMinutes) {
      const wait = Math.ceil(cooldownMinutes - elapsed);
      return `Cooldown: the last test build started ${Math.floor(elapsed)} min ago. Try again in ~${wait} min.`;
    }
  }
  return "";
}

async function dispatchWorkflow(env, { prs, order, pinned, requester, discordContext }) {
  const response = await fetch(
    `https://api.github.com/repos/${env.GITHUB_REPO}/actions/workflows/pr-test-build.yml/dispatches`,
    {
      method: "POST",
      headers: { ...githubHeaders(env), "Content-Type": "application/json" },
      body: JSON.stringify({
        ref: env.GITHUB_DEFAULT_BRANCH || "main",
        inputs: {
          prs,
          order,
          pinned,
          requester,
          discord_guild_id: discordContext.guildId,
          discord_channel_id: discordContext.channelId,
          discord_requester_id: discordContext.requesterId,
          discord_message_id: discordContext.messageId,
          discord_request_id: discordContext.requestId,
        },
      }),
    },
  );
  if (response.status !== 204) {
    const detail = await response.text().catch(() => "");
    throw new Error(`workflow dispatch failed with ${response.status}: ${detail.slice(0, 200)}`);
  }
}

// ---------------------------------------------------------------------------
// Discord responses
// ---------------------------------------------------------------------------

const InteractionType = { PING: 1, APPLICATION_COMMAND: 2, MESSAGE_COMPONENT: 3 };
const ResponseType = {
  PONG: 1,
  CHANNEL_MESSAGE: 4,
  DEFERRED_CHANNEL_MESSAGE: 5,
  UPDATE_MESSAGE: 7,
};
const EPHEMERAL = 64;

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function ephemeralReply(content) {
  return json({
    type: ResponseType.CHANNEL_MESSAGE,
    data: { content, flags: EPHEMERAL, allowed_mentions: { parse: [] } },
  });
}

function configuredChannel(id, fallback) {
  return /^\d+$/.test(String(id || "")) ? `<#${id}>` : `#${fallback}`;
}

export function reportingGuidanceResponse(env) {
  const bugs = configuredChannel(env.BUG_REPORT_CHANNEL_ID, "bug-reports");
  const suggestions = configuredChannel(env.SUGGESTIONS_CHANNEL_ID, "suggestions");
  return {
    type: ResponseType.CHANNEL_MESSAGE,
    data: {
      embeds: [{
        title: "Please don't make me say it again™",
        color: 0xf1c40f,
        description: [
          `🐛 Found a bug? Please post it in ${bugs}.`,
          `💡 Have an idea? Please post it in ${suggestions}.`,
          "",
          "And one tiny favor: tell us **why**, not only what you think",
          "Frostguard should do. What problem are you running into? What are",
          "you trying to achieve, and why would it help?",
          "",
          "That context lets us solve the actual problem — sometimes with a",
          "better answer than the first solution that came to mind.",
          "",
          "Thanks for helping us keep general chat general. My remaining",
          "sanity appreciates it. ❤️",
        ].join("\n"),
      }],
      allowed_mentions: { parse: [] },
    },
  };
}

async function editOriginal(env, interaction, payload) {
  await fetch(
    `https://discord.com/api/v10/webhooks/${env.DISCORD_APPLICATION_ID}/${interaction.token}/messages/@original`,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ allowed_mentions: { parse: [] }, ...payload }),
    },
  );
}

function planButtons(disabled = false) {
  return [{
    type: 1, // action row
    components: [
      { type: 2, style: 3, label: "Build", custom_id: "buildpr:confirm", disabled },
      { type: 2, style: 4, label: "Cancel", custom_id: "buildpr:cancel", disabled },
    ],
  }];
}

// ---------------------------------------------------------------------------
// Access control
// ---------------------------------------------------------------------------

function csv(value) {
  return String(value || "").split(",").map((s) => s.trim()).filter(Boolean);
}

export function accessError(env, interaction) {
  const channels = csv(env.ALLOWED_CHANNEL_IDS);
  if (channels.length && !channels.includes(String(interaction.channel_id))) {
    return "`/build-pr` only works in the designated test-build channel.";
  }
  return "";
}

function requesterOf(interaction) {
  const user = (interaction.member && interaction.member.user) || interaction.user || {};
  return { id: String(user.id || ""), name: String(user.username || "unknown") };
}

// ---------------------------------------------------------------------------
// /build-pr command
// ---------------------------------------------------------------------------

async function buildPlanPreview(env, interaction) {
  const options = Object.fromEntries(
    ((interaction.data && interaction.data.options) || []).map((o) => [o.name, o.value]),
  );
  const requester = requesterOf(interaction);
  const { numbers, errors } = parsePrNumbers(options.prs);

  let orderNumbers = [];
  if (options.order) {
    const parsedOrder = parsePrNumbers(options.order);
    errors.push(...parsedOrder.errors);
    orderNumbers = parsedOrder.numbers;
  }

  const pins = [];
  const lines = [];
  if (errors.length === 0) {
    for (const number of numbers) {
      let pull;
      try {
        pull = await fetchPull(env, number);
      } catch {
        errors.push(`GitHub was unreachable while checking PR #${number}; try again.`);
        break;
      }
      if (!pull) {
        errors.push(`PR #${number} does not exist in this repository.`);
      } else if (pull.merged_at) {
        errors.push(`PR #${number} is already merged; its changes are in \`main\`.`);
      } else if (pull.state !== "open") {
        errors.push(`PR #${number} is closed and cannot be test-built.`);
      } else {
        pins.push({ number, sha: pull.head.sha });
        lines.push(
          `**[#${number}](https://github.com/${env.GITHUB_REPO}/pull/${number})** ` +
          `\`${pull.head.sha.slice(0, 12)}\` — ${String(pull.title).slice(0, 80)}`,
        );
      }
    }
  }
  if (errors.length === 0 && orderNumbers.length) {
    const kept = pins.map((p) => p.number);
    const sameSet = orderNumbers.length === kept.length &&
      kept.every((n) => orderNumbers.includes(n));
    if (!sameSet) {
      errors.push("`order` must list exactly the same PR numbers as `prs`.");
    }
  }

  if (errors.length > 0) {
    await editOriginal(env, interaction, {
      embeds: [{
        title: "/build-pr — request rejected",
        color: 0xe74c3c,
        description:
          "Nothing was built.\n\n" + errors.map((e) => `• ${e}`).join("\n"),
      }],
    });
    return;
  }

  const order = orderNumbers.length
    ? orderNumbers
    : pins.map((p) => p.number).sort((a, b) => a - b);
  const orderedLines = order.map(
    (n) => lines[pins.findIndex((p) => p.number === n)],
  );

  await editOriginal(env, interaction, {
    embeds: [{
      title: "/build-pr — confirm the merge plan",
      color: 0x3498db,
      description: [
        "The following open PRs will be combined **in this order** on top of",
        "`main` in a disposable workspace (no branch is modified), then built",
        "and published as a temporary **unmerged test build**.",
        "",
        orderedLines.join("\n"),
        "",
        "Head SHAs are **pinned**: pushes after this point will not enter the",
        "build. Stacked PRs already contained in a later head are dropped",
        "automatically during planning. If the PRs conflict, the build stops",
        "and reports the conflicting files here — nothing is auto-resolved.",
        "",
        "To use a different order, cancel and re-run `/build-pr` with the",
        "`order` option.",
      ].join("\n"),
      footer: { text: encodeState({ pins, order, requesterId: requester.id }) },
    }],
    components: planButtons(),
  });
}

async function handleButton(env, interaction) {
  const customId = String((interaction.data && interaction.data.custom_id) || "");
  const requester = requesterOf(interaction);
  const message = interaction.message || {};
  const embed = (message.embeds && message.embeds[0]) || {};
  const state = decodeState(embed.footer && embed.footer.text);

  if (!state.requesterId || state.pins.length === 0) {
    return json({
      type: ResponseType.UPDATE_MESSAGE,
      data: {
        embeds: [{
          title: "/build-pr — expired",
          color: 0x95a5a6,
          description: "This confirmation is no longer valid. Run `/build-pr` again.",
        }],
        components: [],
      },
    });
  }
  if (requester.id !== state.requesterId) {
    return ephemeralReply("Only the person who requested this plan can confirm or cancel it.");
  }

  if (customId === "buildpr:cancel") {
    return json({
      type: ResponseType.UPDATE_MESSAGE,
      data: {
        embeds: [{ ...embed, title: "/build-pr — cancelled", color: 0x95a5a6, footer: undefined }],
        components: [],
      },
    });
  }

  if (customId !== "buildpr:confirm") {
    return ephemeralReply("Unknown button.");
  }

  const throttle = await checkCooldownAndConcurrency(env);
  if (throttle) {
    return ephemeralReply(throttle);
  }

  const pinned = state.pins.map((p) => `${p.number}:${p.sha}`).join(",");
  try {
    await dispatchWorkflow(env, {
      prs: state.order.join(","),
      order: state.order.join(","),
      pinned,
      requester: `${requester.name} (Discord)`,
      discordContext: {
        guildId: String(interaction.guild_id || ""),
        channelId: String(interaction.channel_id || ""),
        requesterId: requester.id,
        messageId: String(message.id || ""),
        requestId: String(interaction.id || ""),
      },
    });
  } catch (error) {
    return ephemeralReply(`Could not start the build: ${String(error.message).slice(0, 300)}`);
  }

  return json({
    type: ResponseType.UPDATE_MESSAGE,
    data: {
      embeds: [{
        title: "/build-pr — build started",
        color: 0x2ecc71,
        description: [
          `Building PRs ${state.order.map((n) => `#${n}`).join(", ")} with pinned heads.`,
          "",
          "The result (download link, conflict report or failure) will be",
          "posted here and you will be mentioned when the workflow finishes. Progress: " +
          `<https://github.com/${env.GITHUB_REPO}/actions/workflows/pr-test-build.yml>`,
        ].join("\n"),
        footer: undefined,
      }],
      components: [],
    },
  });
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

export default {
  async fetch(request, env, ctx) {
    if (request.method === "GET") {
      return new Response("frostguard Discord interaction endpoint", { status: 200 });
    }
    if (request.method !== "POST") {
      return new Response("method not allowed", { status: 405 });
    }

    const bodyText = await request.text();
    const valid = await verifySignature(request, bodyText, env.DISCORD_PUBLIC_KEY);
    if (!valid) {
      return new Response("invalid request signature", { status: 401 });
    }

    let interaction;
    try {
      interaction = JSON.parse(bodyText);
    } catch {
      return new Response("bad request", { status: 400 });
    }

    if (interaction.type === InteractionType.PING) {
      return json({ type: ResponseType.PONG });
    }

    if (interaction.type === InteractionType.APPLICATION_COMMAND &&
        interaction.data &&
        interaction.data.name === "please-dont-make-me-say-it-again") {
      return json(reportingGuidanceResponse(env));
    }

    if (interaction.type === InteractionType.APPLICATION_COMMAND &&
        interaction.data && interaction.data.name === "build-pr") {
      const denied = accessError(env, interaction);
      if (denied) return ephemeralReply(denied);
      // Validating up to six PRs against GitHub can exceed Discord's 3-second
      // budget, so defer immediately and edit the reply when the checks land.
      ctx.waitUntil(buildPlanPreview(env, interaction));
      return json({ type: ResponseType.DEFERRED_CHANNEL_MESSAGE });
    }

    if (interaction.type === InteractionType.MESSAGE_COMPONENT &&
        String((interaction.data || {}).custom_id || "").startsWith("buildpr:")) {
      const denied = accessError(env, interaction);
      if (denied) return ephemeralReply(denied);
      return handleButton(env, interaction);
    }

    return ephemeralReply("Unsupported interaction.");
  },
};
