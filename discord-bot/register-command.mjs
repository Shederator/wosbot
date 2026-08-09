#!/usr/bin/env node
/**
 * Synchronise Frostguard's guild-scoped commands and remove global copies.
 *
 * Frostguard currently targets one Discord server. Keeping only the guild
 * commands prevents Discord from showing duplicate global + guild entries.
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

export const reportingGuidanceCommand = {
  name: "please-dont-make-me-say-it-again",
  description: "Politely point bug reports and suggestions in the right direction",
  dm_permission: false,
  // Keep the public reminder available to members who can moderate messages.
  default_member_permissions: "8192",
};

export const guildCommands = [buildPrCommand, reportingGuidanceCommand];

async function clientCredentialsToken(fetchImpl, applicationId, clientSecret) {
  const body = new URLSearchParams({
    grant_type: "client_credentials",
    scope: "applications.commands.update",
  });
  const credentials = Buffer.from(`${applicationId}:${clientSecret}`).toString("base64");
  const response = await fetchImpl(`${API}/oauth2/token`, {
    method: "POST",
    headers: {
      Authorization: `Basic ${credentials}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body,
  });
  if (!response.ok) {
    throw new Error(`Discord OAuth returned ${response.status}: ${await response.text()}`);
  }
  const result = await response.json();
  if (!result.access_token || result.token_type !== "Bearer") {
    throw new Error("Discord OAuth did not return a Bearer access token.");
  }
  return result.access_token;
}

async function discordRequest(fetchImpl, accessToken, url, options = {}) {
  const response = await fetchImpl(url, {
    ...options,
    headers: {
      Authorization: `Bearer ${accessToken}`,
      ...(options.body ? { "Content-Type": "application/json" } : {}),
    },
  });
  if (!response.ok) {
    throw new Error(`Discord returned ${response.status}: ${await response.text()}`);
  }
  return response.status === 204 ? null : response.json();
}

export async function syncCommands(env = process.env, fetchImpl = fetch) {
  const clientSecret = env.DISCORD_CLIENT_SECRET;
  const applicationId = env.DISCORD_APPLICATION_ID;
  const guildId = env.DISCORD_GUILD_ID;
  if (!clientSecret || !/^\d+$/.test(applicationId || "") || !/^\d+$/.test(guildId || "")) {
    throw new Error(
      "Set DISCORD_CLIENT_SECRET plus numeric DISCORD_APPLICATION_ID and DISCORD_GUILD_ID values.",
    );
  }
  const accessToken = await clientCredentialsToken(fetchImpl, applicationId, clientSecret);

  const guildUrl = `${API}/applications/${applicationId}/guilds/${guildId}/commands`;
  const registered = [];
  for (const command of guildCommands) {
    registered.push(await discordRequest(fetchImpl, accessToken, guildUrl, {
      method: "POST",
      body: JSON.stringify(command),
    }));
  }

  const globalUrl = `${API}/applications/${applicationId}/commands`;
  const globalCommands = await discordRequest(fetchImpl, accessToken, globalUrl);
  const guildCommandNames = new Set(guildCommands.map(({ name }) => name));
  const duplicates = globalCommands.filter(
    (candidate) => guildCommandNames.has(candidate.name) && candidate.type === 1,
  );
  for (const duplicate of duplicates) {
    await discordRequest(
      fetchImpl,
      accessToken,
      `${globalUrl}/${duplicate.id}`,
      { method: "DELETE" },
    );
  }

  return { registered, deletedGlobalIds: duplicates.map(({ id }) => id) };
}

// Retain the original export for callers that predate multiple commands.
export const syncCommand = syncCommands;

async function main() {
  const result = await syncCommands();
  for (const command of result.registered) {
    console.log(`Registered guild /${command.name} (id ${command.id}).`);
  }
  if (result.deletedGlobalIds.length) {
    console.log(
      `Deleted ${result.deletedGlobalIds.length} global command duplicate(s).`,
    );
  } else {
    console.log("No global command duplicate was present.");
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
