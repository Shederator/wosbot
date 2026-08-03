#!/usr/bin/env node
/**
 * Synchronise the guild-scoped /build-pr command and remove its global copy.
 *
 * Frostguard currently targets one Discord server. Keeping only the guild
 * command prevents Discord from showing a duplicate global + guild command.
 */

import { pathToFileURL } from "node:url";

const API = "https://discord.com/api/v10";

export const buildPrCommand = {
  name: "build-pr",
  description:
    "Request a temporary Windows test build combining one or more open PRs",
  options: [
    {
      type: 3,
      name: "prs",
      description: "PR numbers to combine, e.g. 47 48 49 65",
      required: true,
    },
    {
      type: 3,
      name: "order",
      description: "Optional explicit merge order, e.g. 49 47 (default: ascending)",
      required: false,
    },
  ],
  dm_permission: false,
};

async function discordRequest(fetchImpl, token, url, options = {}) {
  const response = await fetchImpl(url, {
    ...options,
    headers: {
      Authorization: `Bot ${token}`,
      ...(options.body ? { "Content-Type": "application/json" } : {}),
    },
  });
  if (!response.ok) {
    throw new Error(`Discord returned ${response.status}: ${await response.text()}`);
  }
  return response.status === 204 ? null : response.json();
}

export async function syncCommand(env = process.env, fetchImpl = fetch) {
  const token = env.DISCORD_BOT_TOKEN;
  const applicationId = env.DISCORD_APPLICATION_ID;
  const guildId = env.DISCORD_GUILD_ID;
  if (!token || !/^\d+$/.test(applicationId || "") || !/^\d+$/.test(guildId || "")) {
    throw new Error(
      "Set DISCORD_BOT_TOKEN plus numeric DISCORD_APPLICATION_ID and DISCORD_GUILD_ID values.",
    );
  }

  const guildUrl = `${API}/applications/${applicationId}/guilds/${guildId}/commands`;
  const registered = await discordRequest(fetchImpl, token, guildUrl, {
    method: "POST",
    body: JSON.stringify(buildPrCommand),
  });

  const globalUrl = `${API}/applications/${applicationId}/commands`;
  const globalCommands = await discordRequest(fetchImpl, token, globalUrl);
  const duplicates = globalCommands.filter(
    (candidate) => candidate.name === buildPrCommand.name && candidate.type === 1,
  );
  for (const duplicate of duplicates) {
    await discordRequest(
      fetchImpl,
      token,
      `${globalUrl}/${duplicate.id}`,
      { method: "DELETE" },
    );
  }

  return { registered, deletedGlobalIds: duplicates.map(({ id }) => id) };
}

async function main() {
  const result = await syncCommand();
  console.log(
    `Registered guild /${result.registered.name} (id ${result.registered.id}).`,
  );
  if (result.deletedGlobalIds.length) {
    console.log(
      `Deleted ${result.deletedGlobalIds.length} global /build-pr duplicate(s).`,
    );
  } else {
    console.log("No global /build-pr duplicate was present.");
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
