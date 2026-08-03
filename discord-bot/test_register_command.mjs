import assert from "node:assert/strict";
import test from "node:test";

import { syncCommand } from "./register-command.mjs";

const ENV = {
  DISCORD_BOT_TOKEN: "test-token",
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

test("registers the guild command before deleting the global duplicate", async () => {
  const calls = [];
  const fetchMock = async (url, options = {}) => {
    calls.push({ url, method: options.method || "GET", body: options.body });
    if (options.method === "POST") {
      return response(200, { id: "guild-command", name: "build-pr" });
    }
    if ((options.method || "GET") === "GET") {
      return response(200, [
        { id: "global-command", name: "build-pr", type: 1 },
        { id: "other-command", name: "help", type: 1 },
      ]);
    }
    return response(204);
  };

  const result = await syncCommand(ENV, fetchMock);

  assert.deepEqual(result.deletedGlobalIds, ["global-command"]);
  assert.deepEqual(calls.map(({ method }) => method), ["POST", "GET", "DELETE"]);
  assert.match(calls[0].url, /\/guilds\/1475434539495981137\/commands$/);
  assert.match(calls[2].url, /\/commands\/global-command$/);
  assert.equal(JSON.parse(calls[0].body).name, "build-pr");
});

test("does not delete unrelated global commands", async () => {
  const methods = [];
  const fetchMock = async (_url, options = {}) => {
    methods.push(options.method || "GET");
    if (options.method === "POST") {
      return response(200, { id: "guild-command", name: "build-pr" });
    }
    return response(200, [{ id: "other-command", name: "help", type: 1 }]);
  };

  const result = await syncCommand(ENV, fetchMock);

  assert.deepEqual(result.deletedGlobalIds, []);
  assert.deepEqual(methods, ["POST", "GET"]);
});

test("rejects missing deployment identifiers before calling Discord", async () => {
  let called = false;
  await assert.rejects(
    syncCommand({}, async () => {
      called = true;
    }),
    /DISCORD_BOT_TOKEN/,
  );
  assert.equal(called, false);
});
