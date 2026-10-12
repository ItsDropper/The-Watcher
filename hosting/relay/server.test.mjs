import test from "node:test";
import assert from "node:assert/strict";
import { createRelayServer, parseFrame, validRoomId } from "./server.mjs";

test("relay refuses to start without TLS credentials", () => {
  assert.throws(() => createRelayServer(), /TLS certificate and private key are required/);
});

test("accepts only a well-formed authenticated-control envelope", () => {
  const open = Buffer.alloc(37);
  open.writeUInt8(1, 0);
  open.writeUInt32BE(7, 1);
  assert.deepEqual(parseFrame(open), { type: 1, channelId: 7 });

  const close = Buffer.alloc(37);
  close.writeUInt8(3, 0);
  close.writeUInt32BE(7, 1);
  assert.deepEqual(parseFrame(close), { type: 3, channelId: 7 });

  const unsigned = Buffer.alloc(5);
  unsigned.writeUInt8(1, 0);
  unsigned.writeUInt32BE(7, 1);
  assert.equal(parseFrame(unsigned), null);
});

test("accepts bounded DATA envelopes but rejects undersized, invalid, or oversized frames", () => {
  const data = Buffer.alloc(29);
  data.writeUInt8(2, 0);
  data.writeUInt32BE(7, 1);
  assert.deepEqual(parseFrame(data), { type: 2, channelId: 7 });

  const undersized = Buffer.alloc(28);
  undersized.writeUInt8(2, 0);
  undersized.writeUInt32BE(7, 1);
  assert.equal(parseFrame(undersized), null);

  const zeroChannel = Buffer.alloc(29);
  zeroChannel.writeUInt8(2, 0);
  assert.equal(parseFrame(zeroChannel), null);

  const invalidType = Buffer.alloc(29);
  invalidType.writeUInt8(9, 0);
  invalidType.writeUInt32BE(7, 1);
  assert.equal(parseFrame(invalidType), null);

  const oversized = Buffer.alloc(64 * 1024 + 1);
  oversized.writeUInt8(2, 0);
  oversized.writeUInt32BE(7, 1);
  assert.equal(parseFrame(oversized), null);
});

test("room IDs must be exactly 128-bit base64url identifiers", () => {
  assert.equal(validRoomId("abcdefghijklmnopqrstuv"), true);
  assert.equal(validRoomId("short"), false);
  assert.equal(validRoomId("abcdefghijklmnopqrstuv!"), false);
  assert.equal(validRoomId(""), false);
});
