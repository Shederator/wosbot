#!/usr/bin/env node
/**
 * Tests for the pure helpers of the /build-pr worker.
 *
 * Run with: node discord-bot/test_worker.mjs
 * (No framework: keeps the bot dependency-free, like ci/*.py.)
 */

import assert from "node:assert/strict";
import {
  MAX_PRS,
  accessError,
  decodeState,
  encodeState,
  hexToBytes,
  parsePrNumbers,
  reportingGuidanceResponse,
} from "./worker.js";

let passed = 0;
function test(name, fn) {
  fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

// --- parsePrNumbers --------------------------------------------------------

test("accepts spaces, commas and hash prefixes", () => {
  const { numbers, errors } = parsePrNumbers("47, 48 #49;65");
  assert.deepEqual(numbers, [47, 48, 49, 65]);
  assert.deepEqual(errors, []);
});

test("deduplicates and keeps first-appearance order", () => {
  const { numbers, errors } = parsePrNumbers("49 47 49 47 48");
  assert.deepEqual(numbers, [49, 47, 48]);
  assert.deepEqual(errors, []);
});

test("rejects non-numeric tokens with an explanation", () => {
  const { numbers, errors } = parsePrNumbers("47 main 48");
  assert.deepEqual(numbers, [47, 48]);
  assert.equal(errors.length, 1);
  assert.ok(errors[0].includes("main"));
});

test("empty input is an error, not a silent no-op", () => {
  const { numbers, errors } = parsePrNumbers("   ");
  assert.deepEqual(numbers, []);
  assert.ok(errors.length > 0);
});

test("enforces the per-request ceiling, matching the planner", () => {
  const raw = Array.from({ length: MAX_PRS + 1 }, (_, i) => i + 1).join(" ");
  const { errors } = parsePrNumbers(raw);
  assert.ok(errors.some((e) => e.includes("limit")));
});

// --- encodeState / decodeState ---------------------------------------------

test("state survives an encode/decode roundtrip", () => {
  const state = {
    pins: [
      { number: 47, sha: "a".repeat(40) },
      { number: 49, sha: "b".repeat(40) },
    ],
    order: [47, 49],
    requesterId: "123456789012345678",
  };
  const decoded = decodeState(encodeState(state));
  assert.deepEqual(decoded.order, [47, 49]);
  assert.equal(decoded.requesterId, "123456789012345678");
  assert.deepEqual(
    decoded.pins,
    [
      { number: 47, sha: "a".repeat(12) },
      { number: 49, sha: "b".repeat(12) },
    ],
  );
});

test("a tampered or garbage footer decodes to an unusable state", () => {
  for (const garbage of ["", "hello", "pins=47@nothex;order=x;req="]) {
    const decoded = decodeState(garbage);
    assert.equal(decoded.pins.length, 0);
  }
});

test("encoded state stays inside Discord's 2048-char footer limit", () => {
  const state = {
    pins: Array.from({ length: MAX_PRS }, (_, i) => ({
      number: 99999 + i,
      sha: "f".repeat(40),
    })),
    order: Array.from({ length: MAX_PRS }, (_, i) => 99999 + i),
    requesterId: "9".repeat(20),
  };
  assert.ok(encodeState(state).length <= 2048);
});

// --- hexToBytes -------------------------------------------------------------

test("parses valid hex and rejects everything else", () => {
  assert.deepEqual(Array.from(hexToBytes("00ff10")), [0, 255, 16]);
  assert.equal(hexToBytes("xyz"), null);
  assert.equal(hexToBytes("abc"), null); // odd length
  assert.equal(hexToBytes(""), null);
  assert.equal(hexToBytes(null), null);
});

// --- accessError -------------------------------------------------------------

test("wrong channel is refused when channels are configured", () => {
  const env = { ALLOWED_CHANNEL_IDS: "111,222" };
  assert.ok(accessError(env, { channel_id: "333", member: { roles: [] } }));
  assert.equal(accessError(env, { channel_id: "222", member: { roles: [] } }), "");
});

test("no configuration means open access", () => {
  const env = { ALLOWED_CHANNEL_IDS: "" };
  assert.equal(accessError(env, { channel_id: "1", member: { roles: [] } }), "");
});

// --- reporting guidance ----------------------------------------------------

test("reporting guidance links configured channels and explains why", () => {
  const response = reportingGuidanceResponse({
    BUG_REPORT_CHANNEL_ID: "111",
    SUGGESTIONS_CHANNEL_ID: "222",
  });
  const description = response.data.embeds[0].description;
  assert.equal(response.type, 4);
  assert.match(description, /<#111>/);
  assert.match(description, /<#222>/);
  assert.match(description, /why/i);
  assert.match(description, /problem/i);
  assert.deepEqual(response.data.allowed_mentions, { parse: [] });
});

test("reporting guidance has readable fallbacks for missing channel IDs", () => {
  const description = reportingGuidanceResponse({}).data.embeds[0].description;
  assert.match(description, /#bug-reports/);
  assert.match(description, /#suggestions/);
});

console.log(`\n${passed} tests passed`);
