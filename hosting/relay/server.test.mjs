import test from "node:test";
import assert from "node:assert/strict";
import { createRelayServer, parseFrame, validRoomId } from "./server.mjs";
import { WebSocket } from "ws";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

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

function waitForMessage(socket, predicate, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.off("message", onMessage);
      reject(new Error("Timed out waiting for relay message."));
    }, timeoutMs);
    const onMessage = (data, isBinary) => {
      if (!predicate(data, isBinary)) return;
      clearTimeout(timeout);
      socket.off("message", onMessage);
      resolve({ data: Buffer.isBuffer(data) ? Buffer.from(data) : Buffer.from(data), isBinary });
    };
    socket.on("message", onMessage);
  });
}

async function connectRole(url, handshake) {
  const socket = new WebSocket(url, { rejectUnauthorized: false });
  await new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
  const ready = waitForMessage(socket, (data, binary) =>
    !binary && data.toString("utf8").includes('"type":"ready"'));
  socket.send(JSON.stringify(handshake));
  await ready;
  return socket;
}

function controlFrame(type, channel) {
  const frame = Buffer.alloc(37);
  frame.writeUInt8(type, 0);
  frame.writeUInt32BE(channel, 1);
  return frame;
}

function dataFrame(channel, sequence, marker) {
  const frame = Buffer.alloc(29);
  frame.writeUInt8(2, 0);
  frame.writeUInt32BE(channel, 1);
  frame.writeBigUInt64BE(BigInt(sequence), 5);
  frame.writeUInt8(marker, 13);
  return frame;
}

test("TLS relay routes channel frames only between the host and owning guest", async t => {
  const directory = mkdtempSync(path.join(tmpdir(), "watcher-relay-test-"));
  const keyPath = path.join(directory, "key.pem");
  const certPath = path.join(directory, "cert.pem");
  const generated = spawnSync("openssl", [
    "req", "-x509", "-newkey", "rsa:2048", "-nodes",
    "-keyout", keyPath, "-out", certPath, "-subj", "/CN=localhost", "-days", "1"
  ], { encoding: "utf8" });
  assert.equal(generated.status, 0, generated.stderr || "OpenSSL failed to generate test certificate.");

  const relay = createRelayServer({ cert: readFileSync(certPath), key: readFileSync(keyPath), logger: { warn() {} } });
  const address = await relay.listen(0, "127.0.0.1");
  const url = `wss://127.0.0.1:${address.port}/tunnel`;
  let host;
  let guest;
  t.after(async () => {
    try { host?.close(); } catch {}
    try { guest?.close(); } catch {}
    await relay.close();
    rmSync(directory, { recursive: true, force: true });
  });

  const room = "abcdefghijklmnopqrstuv";
  host = await connectRole(url, { type: "host", room });
  guest = await connectRole(url, { type: "join", room });

  const open = controlFrame(1, 77);
  const hostOpen = waitForMessage(host, (data, binary) => binary && data[0] === 1);
  guest.send(open);
  assert.deepEqual((await hostOpen).data, open);

  const guestData = dataFrame(77, 0, 0x41);
  const hostData = waitForMessage(host, (data, binary) => binary && data[0] === 2);
  guest.send(guestData);
  assert.deepEqual((await hostData).data, guestData);

  const hostDataFrame = dataFrame(77, 0, 0x42);
  const guestDataReceived = waitForMessage(guest, (data, binary) => binary && data[0] === 2);
  host.send(hostDataFrame);
  assert.deepEqual((await guestDataReceived).data, hostDataFrame);

  const close = controlFrame(3, 77);
  const hostClose = waitForMessage(host, (data, binary) => binary && data[0] === 3);
  guest.send(close);
  assert.deepEqual((await hostClose).data, close);

  const secondOpen = controlFrame(1, 88);
  const hostSecondOpen = waitForMessage(host, (data, binary) => binary && data[0] === 1 && data.readUInt32BE(1) === 88);
  guest.send(secondOpen);
  assert.deepEqual((await hostSecondOpen).data, secondOpen);

  const disconnectNotice = waitForMessage(host, (data, binary) =>
    !binary && data.toString("utf8").includes('"type":"channel-closed"'));
  guest.close();
  const notice = await disconnectNotice;
  assert.match(notice.data.toString("utf8"), /"channelId":88/);
});
