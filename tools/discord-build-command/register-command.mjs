// Register (or update) the `/build-pr` slash command for one Discord server.
//
// Run this once, and again whenever the options below change. Guild-scoped
// registration is used on purpose: it appears immediately, and the command stays
// invisible on every other server the application might be added to.
//
// Usage:
//   DISCORD_APPLICATION_ID=... DISCORD_BOT_TOKEN=... DISCORD_GUILD_ID=... \
//     node tools/discord-build-command/register-command.mjs
//
// The bot token is only needed for this one call. The Worker itself never sees
// it: it authenticates to Discord with the request signature instead.

const { DISCORD_APPLICATION_ID, DISCORD_BOT_TOKEN, DISCORD_GUILD_ID } = process.env;

if (!DISCORD_APPLICATION_ID || !DISCORD_BOT_TOKEN || !DISCORD_GUILD_ID) {
  console.error(
    "Set DISCORD_APPLICATION_ID, DISCORD_BOT_TOKEN and DISCORD_GUILD_ID first.",
  );
  process.exit(1);
}

const command = {
  name: "build-pr",
  description: "Build a temporary Windows test bundle from open pull requests",
  // 1 = CHAT_INPUT
  type: 1,
  options: [
    {
      name: "prs",
      description: 'Open PR numbers, for example "47 48 49 65"',
      type: 3, // STRING
      required: true,
    },
    {
      name: "confirm",
      description: "Skip the plan and build straight away",
      type: 5, // BOOLEAN
      required: false,
    },
    {
      name: "union",
      description: "On conflict, propose a resolution that keeps both sides",
      type: 5,
      required: false,
    },
  ],
};

const url =
  `https://discord.com/api/v10/applications/${DISCORD_APPLICATION_ID}` +
  `/guilds/${DISCORD_GUILD_ID}/commands`;

const response = await fetch(url, {
  method: "POST",
  headers: {
    Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
    "Content-Type": "application/json",
  },
  body: JSON.stringify(command),
});

const body = await response.text();
if (!response.ok) {
  console.error(`Discord rejected the registration (HTTP ${response.status}):`);
  console.error(body);
  process.exit(1);
}
console.log("Registered /build-pr for guild", DISCORD_GUILD_ID);
