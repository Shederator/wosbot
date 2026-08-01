#!/usr/bin/env node
/**
 * Register (or update) the /build-pr slash command on the Discord application.
 *
 * Run once after creating the Discord application, and again whenever the
 * command definition changes. Registering is idempotent: Discord upserts by
 * command name.
 *
 * Usage:
 *   DISCORD_BOT_TOKEN=... DISCORD_APPLICATION_ID=... node register-command.mjs
 *
 * Optionally register to a single guild for instant availability (global
 * commands can take up to an hour to propagate):
 *   DISCORD_GUILD_ID=... node register-command.mjs
 */

const token = process.env.DISCORD_BOT_TOKEN;
const applicationId = process.env.DISCORD_APPLICATION_ID;
const guildId = process.env.DISCORD_GUILD_ID || "";

if (!token || !applicationId) {
  console.error(
    "Set DISCORD_BOT_TOKEN and DISCORD_APPLICATION_ID in the environment.",
  );
  process.exit(1);
}

const command = {
  name: "build-pr",
  description:
    "Request a temporary Windows test build combining one or more open PRs",
  options: [
    {
      type: 3, // STRING
      name: "prs",
      description: "PR numbers to combine, e.g. 47 48 49 65",
      required: true,
    },
    {
      type: 3, // STRING
      name: "order",
      description: "Optional explicit merge order, e.g. 49 47 (default: ascending)",
      required: false,
    },
  ],
  // No DMs: the access checks are channel/role based.
  dm_permission: false,
};

const url = guildId
  ? `https://discord.com/api/v10/applications/${applicationId}/guilds/${guildId}/commands`
  : `https://discord.com/api/v10/applications/${applicationId}/commands`;

const response = await fetch(url, {
  method: "POST",
  headers: {
    Authorization: `Bot ${token}`,
    "Content-Type": "application/json",
  },
  body: JSON.stringify(command),
});

if (!response.ok) {
  console.error(`Discord returned ${response.status}:`, await response.text());
  process.exit(1);
}

const data = await response.json();
console.log(
  `Registered /${data.name} (id ${data.id}) ` +
  (guildId ? `in guild ${guildId}` : "globally (may take up to 1 hour)"),
);
