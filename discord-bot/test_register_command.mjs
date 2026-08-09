import assert from "node:assert/strict";
import test from "node:test";

import {
  guildCommands,
  reportingGuidanceCommand,
  syncCommands,
} from "./register-command.mjs";

const ENV = {
  DISCORD_CLIENT_SECRET: "test-client-secret",
  DISCORD_APPLICATION_ID: "1532693190879215767",
  DISCORD_GUILD_ID: "1475434539495981137",
};

function response(status, body = null) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  };
}

function oauthResponse() {
  return response(200, {
    access_token: "test-access-token",
    token_type: "Bearer",
    expires_in: 604800,
    scope: "applications.commands.update",
  });
}

test("registers both guild commands before deleting global duplicates", async () => {
  const calls = [];
  const fetchMock = async (url, options = {}) => {
    calls.push({ url, method: options.method || "GET", body: options.body,
      authorization: options.headers?.Authorization });
    if (url.endsWith("/oauth2/token")) return oauthResponse();
    if (options.method === "POST" && !url.endsWith("/oauth2/token")) {
      const command = JSON.parse(options.body);
      return response(200, { id: `guild-${command.name}`, name: command.name });
    }
    if ((options.method || "GET") === "GET") {
      return response(200, [
        { id: "global-command", name: "build-pr", type: 1 },
        { id: "global-guidance", name: reportingGuidanceCommand.name, type: 1 },
        { id: "other-command", name: "help", type: 1 },
      ]);
    }
    return response(204);
  };

  const result = await syncCommands(ENV, fetchMock);

  assert.deepEqual(result.deletedGlobalIds, ["global-command", "global-guidance"]);
  assert.deepEqual(
    calls.map(({ method }) => method),
    ["POST", "POST", "POST", "GET", "DELETE", "DELETE"],
  );
  assert.match(calls[0].url, /\/oauth2\/token$/);
  assert.match(String(calls[0].body), /scope=applications.commands.update/);
  assert.match(calls[0].authorization, /^Basic /);
  assert.match(calls[1].url, /\/guilds\/1475434539495981137\/commands$/);
  assert.match(calls[4].url, /\/commands\/global-command$/);
  assert.equal(JSON.parse(calls[1].body).name, "build-pr");
  assert.equal(JSON.parse(calls[2].body).name, reportingGuidanceCommand.name);
  assert.equal(calls[1].authorization, "Bearer test-access-token");
  assert.equal(result.registered.length, guildCommands.length);
});

test("does not delete unrelated global commands", async () => {
  const methods = [];
  const fetchMock = async (url, options = {}) => {
    methods.push(options.method || "GET");
    if (url.endsWith("/oauth2/token")) return oauthResponse();
    if (options.method === "POST") {
      const command = JSON.parse(options.body);
      return response(200, { id: `guild-${command.name}`, name: command.name });
    }
    return response(200, [{ id: "other-command", name: "help", type: 1 }]);
  };

  const result = await syncCommands(ENV, fetchMock);

  assert.deepEqual(result.deletedGlobalIds, []);
  assert.deepEqual(methods, ["POST", "POST", "POST", "GET"]);
});

test("rejects missing deployment identifiers before calling Discord", async () => {
  let called = false;
  await assert.rejects(
    syncCommands({}, async () => {
      called = true;
    }),
    /DISCORD_CLIENT_SECRET/,
  );
  assert.equal(called, false);
});

test("keeps the intentionally long command name within Discord's limit", () => {
  assert.equal(reportingGuidanceCommand.name.length, 32);
  assert.equal(reportingGuidanceCommand.default_member_permissions, "8192");
});
