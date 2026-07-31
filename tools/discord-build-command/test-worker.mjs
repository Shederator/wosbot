// Self-tests for the optional Discord command endpoint.
//
// The endpoint holds a GitHub token, so the tests concentrate on the checks that
// stand between a Discord message and a workflow run: input sanitising, the
// channel and role allowlists, and the shape of the dispatch payload.
//
// Run with:  node tools/discord-build-command/test-worker.mjs

import assert from "node:assert/strict";
import { test } from "node:test";

import {
  acknowledgement,
  commandOptions,
  dispatchPayload,
  isAllowed,
  parseIdList,
  parsePrsOption,
} from "./worker.js";

test("only pull request numbers survive the option parser", () => {
  assert.deepEqual(parsePrsOption("47 48,49 #65"), [47, 48, 49, 65]);
  assert.deepEqual(parsePrsOption("47 47 48"), [47, 48]);
  assert.deepEqual(parsePrsOption(""), []);
  assert.deepEqual(parsePrsOption(undefined), []);
});

test("shell metacharacters cannot reach the workflow inputs", () => {
  // The Worker holds a GitHub token; anything but digits must be dropped here.
  assert.deepEqual(parsePrsOption("47; rm -rf / && echo 48"), [47, 48]);
  assert.deepEqual(parsePrsOption("$(whoami)"), []);
  assert.deepEqual(parsePrsOption("`id`"), []);
});

test("a request is capped so one command cannot queue endless builds", () => {
  const many = Array.from({ length: 40 }, (_, index) => index + 1).join(" ");
  assert.equal(parsePrsOption(many).length, 10);
});

test("an empty allowlist means the restriction is not configured", () => {
  const interaction = { channel_id: "1", member: { roles: [] } };
  assert.equal(isAllowed(interaction, {}).allowed, true);
});

test("a channel allowlist keeps the command out of other channels", () => {
  const env = { ALLOWED_CHANNEL_IDS: "111, 222" };
  assert.equal(isAllowed({ channel_id: "111" }, env).allowed, true);
  const denied = isAllowed({ channel_id: "999" }, env);
  assert.equal(denied.allowed, false);
  assert.match(denied.reason, /channel/);
});

test("a role allowlist keeps the command away from every other member", () => {
  const env = { ALLOWED_ROLE_IDS: "role-tester" };
  assert.equal(
    isAllowed({ channel_id: "1", member: { roles: ["role-tester"] } }, env).allowed,
    true,
  );
  const denied = isAllowed({ channel_id: "1", member: { roles: ["role-other"] } }, env);
  assert.equal(denied.allowed, false);
  assert.match(denied.reason, /tester role/);
});

test("a member with no roles at all is refused when a role is required", () => {
  const env = { ALLOWED_ROLE_IDS: "role-tester" };
  assert.equal(isAllowed({ channel_id: "1" }, env).allowed, false);
});

test("id lists tolerate the separators people paste", () => {
  assert.deepEqual(parseIdList(" 1, 2\n3 "), ["1", "2", "3"]);
  assert.deepEqual(parseIdList(undefined), []);
});

test("command options are read into a plain request", () => {
  const interaction = {
    data: {
      options: [
        { name: "prs", value: "47 48" },
        { name: "confirm", value: true },
      ],
    },
  };
  assert.deepEqual(commandOptions(interaction), {
    prs: [47, 48],
    confirm: true,
    union: false,
  });
});

test("the dispatch payload only ever contains strings", () => {
  const payload = dispatchPayload({ prs: [47, 48], confirm: true, union: false }, "main");
  assert.equal(payload.ref, "main");
  for (const value of Object.values(payload.inputs)) {
    assert.equal(typeof value, "string");
  }
  assert.equal(payload.inputs.prs, "47 48");
  assert.equal(payload.inputs.confirm, "true");
  assert.equal(payload.inputs.conflict_resolution, "stop");
});

test("union mode is passed through", () => {
  const payload = dispatchPayload({ prs: [47], confirm: true, union: true }, "main");
  assert.equal(payload.inputs.conflict_resolution, "union");
});

test("an unconfirmed request offers a Build it button and does not claim to build", () => {
  const message = acknowledgement({ prs: [47, 48], confirm: false }, "https://example.invalid");
  assert.match(message.content, /Nothing is built yet/);
  const ids = message.components[0].components.map((component) => component.custom_id);
  assert.deepEqual(ids, ["build:47-48", "cancel"]);
});

test("a confirmed request has no buttons and warns that the build is unmerged", () => {
  const message = acknowledgement({ prs: [47], confirm: true }, "");
  assert.equal(message.components, undefined);
  assert.match(message.content, /unmerged test build/);
});

test("no message can ping the channel", () => {
  for (const confirm of [true, false]) {
    const message = acknowledgement({ prs: [47], confirm }, "");
    assert.deepEqual(message.allowed_mentions, { parse: [] });
  }
});
