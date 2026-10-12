import https from "node:https";
import fs from "node:fs";
import crypto from "node:crypto";
import { WebSocketServer, WebSocket } from "ws";

const MAX_GUESTS_PER_ROOM = 8;
const MAX_ACTIVE_ROOMS = 2048;
const MAX_ACTIVE_SOCKETS = 8192;
const MAX_BUFFERED_BYTES = 8 * 1024 * 1024;
const MAX_MESSAGE_BYTES = 64 * 1024;
const MAX_BYTES_PER_SECOND = 4 * 1024 * 1024;
const BURST_BYTES = 16 * 1024 * 1024;
const MAX_HANDSHAKES_PER_MINUTE = 30;
const HEARTBEAT_INTERVAL_MS = 30_000;
const ROOM_IDLE_TTL_MS = 60 * 60_000;
const ROOM_ID_PATTERN = /^[A-Za-z0-9_-]{22}$/;

export function validRoomId(value) {
  return typeof value === "string" && ROOM_ID_PATTERN.test(value);
}

export function parseFrame(data) {
  if (!Buffer.isBuffer(data) || data.length < 5 || data.length > MAX_MESSAGE_BYTES) {
    return null;
  }
  const type = data.readUInt8(0);
  const channelId = data.readUInt32BE(1);
  if (channelId === 0 || ![1, 2, 3].includes(type)) return null;
  if ((type === 1 || type === 3) && data.length !== 37) return null;
  if (type === 2 && data.length < 29) return null; // header + sequence + AES-GCM tag
  return { type, channelId };
}

function createRateLimiter() {
  const buckets = new Map();
  return (key) => {
    const now = Date.now();
    const current = buckets.get(key) ?? { count: 0, resetAt: now + 60_000 };
    if (now >= current.resetAt) {
      current.count = 0;
      current.resetAt = now + 60_000;
    }
    current.count += 1;
    buckets.set(key, current);
    if (buckets.size > 4096) {
      for (const [bucketKey, bucket] of buckets) {
        if (now >= bucket.resetAt) buckets.delete(bucketKey);
      }
    }
    return current.count <= MAX_HANDSHAKES_PER_MINUTE;
  };
}

function createByteBudget() {
  let tokens = BURST_BYTES;
  let last = Date.now();
  return (bytes) => {
    const now = Date.now();
    tokens = Math.min(BURST_BYTES, tokens + ((now - last) / 1000) * MAX_BYTES_PER_SECOND);
    last = now;
    if (bytes > tokens) return false;
    tokens -= bytes;
    return true;
  };
}

function safeSend(socket, data) {
  if (socket.readyState !== WebSocket.OPEN) return;
  if (socket.bufferedAmount > MAX_BUFFERED_BYTES) {
    socket.close(1013, "Slow peer");
    return;
  }
  socket.send(data, { binary: Buffer.isBuffer(data) });
}

export function createRelayServer({ cert, key, logger = console } = {}) {
  if (!cert || !key) {
    throw new Error("TLS certificate and private key are required; refusing to start an insecure hosting relay.");
  }

  const rooms = new Map();
  const allowHandshake = createRateLimiter();
  const server = https.createServer({ cert, key }, (request, response) => {
    if (request.method === "GET" && request.url === "/health") {
      response.writeHead(200, { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" });
      response.end("ok\n");
      return;
    }
    response.writeHead(404, { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" });
    response.end("not found\n");
  });

  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: MAX_MESSAGE_BYTES,
    perMessageDeflate: false,
  });

  function closeRoom(room, code = 1012, reason = "Host disconnected") {
    if (!rooms.has(room.id)) return;
    rooms.delete(room.id);
    for (const guest of room.guests) {
      try { guest.close(code, reason); } catch {}
    }
    try { room.host?.close(code, reason); } catch {}
    room.channels.clear();
  }

  function detachGuest(room, guest) {
    room.guests.delete(guest);
    for (const [channelId, owner] of room.channels) {
      if (owner === guest) {
        room.channels.delete(channelId);
        const closeFrame = Buffer.alloc(5);
        closeFrame.writeUInt8(3, 0);
        closeFrame.writeUInt32BE(channelId, 1);
        safeSend(room.host, closeFrame);
      }
    }
  }

  function attachSocket(socket, role, room) {
    socket.role = role;
    socket.room = room;
    socket.readyForFrames = false;
    socket.alive = true;
    socket.consumeBytes = createByteBudget();
    socket.on("pong", () => { socket.alive = true; });

    socket.on("message", (data, isBinary) => {
      if (!socket.readyForFrames) {
        if (isBinary || !Buffer.isBuffer(data) || data.length > 1024) {
          socket.close(1008, "Handshake required");
          return;
        }
        let message;
        try { message = JSON.parse(data.toString("utf8")); } catch {
          socket.close(1008, "Invalid handshake");
          return;
        }
        if (role === "pending") {
          if (message?.type === "host" && validRoomId(message.room)) {
            if (rooms.size >= MAX_ACTIVE_ROOMS) {
              socket.close(1013, "Relay is at capacity");
              return;
            }
            if (rooms.has(message.room)) {
              socket.close(1008, "Room ID already exists");
              return;
            }
            const hostRoom = {
              id: message.room,
              host: socket,
              guests: new Set(),
              channels: new Map(),
              createdAt: Date.now(),
              lastActivity: Date.now()
            };
            rooms.set(hostRoom.id, hostRoom);
            attachSocket(socket, "host", hostRoom);
            socket.readyForFrames = true;
            safeSend(socket, JSON.stringify({ type: "ready", role: "host", maxGuests: MAX_GUESTS_PER_ROOM }));
            return;
          }
          if (message?.type === "join" && validRoomId(message.room)) {
            const target = rooms.get(message.room);
            if (!target || target.host.readyState !== WebSocket.OPEN) {
              socket.close(1008, "Host is not online");
              return;
            }
            if (target.guests.size >= MAX_GUESTS_PER_ROOM) {
              socket.close(1013, "Room is full");
              return;
            }
            attachSocket(socket, "guest", target);
            target.guests.add(socket);
            socket.readyForFrames = true;
            target.lastActivity = Date.now();
            safeSend(socket, JSON.stringify({ type: "ready", role: "guest" }));
            safeSend(target.host, JSON.stringify({ type: "guest-count", count: target.guests.size }));
            return;
          }
          socket.close(1008, "Invalid handshake");
          return;
        }

        socket.close(1008, "Unexpected control message");
        return;
      }

      if (!isBinary || !Buffer.isBuffer(data) || !socket.consumeBytes(data.length)) {
        socket.close(1009, "Invalid frame or bandwidth limit exceeded");
        return;
      }
      const frame = parseFrame(data);
      if (!frame) {
        socket.close(1008, "Invalid tunnel frame");
        return;
      }
      const currentRoom = socket.room;
      if (!currentRoom || rooms.get(currentRoom.id) !== currentRoom) {
        socket.close(1012, "Room closed");
        return;
      }
      currentRoom.lastActivity = Date.now();

      if (frame.type === 1) {
        if (socket.role !== "guest" || currentRoom.channels.has(frame.channelId)
            || [...currentRoom.channels.values()].filter(owner => owner === socket).length >= 1) {
          socket.close(1008, "Invalid channel open");
          return;
        }
        currentRoom.channels.set(frame.channelId, socket);
        safeSend(currentRoom.host, data);
        return;
      }

      const owner = currentRoom.channels.get(frame.channelId);
      if (!owner || (socket.role === "guest" && owner !== socket)) {
        socket.close(1008, "Unknown channel");
        return;
      }
      if (frame.type === 2) {
        safeSend(socket.role === "host" ? owner : currentRoom.host, data);
      } else {
        currentRoom.channels.delete(frame.channelId);
        safeSend(socket.role === "host" ? owner : currentRoom.host, data);
      }
    });

    socket.on("close", () => {
      const currentRoom = socket.room;
      if (!currentRoom || rooms.get(currentRoom.id) !== currentRoom) return;
      if (socket.role === "host") {
        closeRoom(currentRoom);
      } else if (socket.role === "guest") {
        detachGuest(currentRoom, socket);
        currentRoom.lastActivity = Date.now();
        safeSend(currentRoom.host, JSON.stringify({ type: "guest-count", count: currentRoom.guests.size }));
      }
    });

    socket.on("error", error => logger.warn?.("Hosting socket error:", error.message));
  }

  server.on("upgrade", (request, socket, head) => {
    const address = request.socket.remoteAddress ?? "unknown";
    if (!allowHandshake(address)) {
      socket.write("HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    if (wss.clients.size >= MAX_ACTIVE_SOCKETS) {
      socket.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    // Native Java clients do not need a browser Origin. Reject browser-originated
    // cross-site WebSocket attempts so a malicious website cannot probe room IDs.
    if (request.headers.origin) {
      socket.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    if (request.url !== "/tunnel") {
      socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(request, socket, head, ws => {
      wss.clients.add(ws);
      ws.once("close", () => wss.clients.delete(ws));
      attachSocket(ws, "pending", null);
      const handshakeTimer = setTimeout(() => {
        if (!ws.readyForFrames) ws.close(1008, "Handshake timeout");
      }, 5000);
      handshakeTimer.unref();
      ws.once("close", () => clearTimeout(handshakeTimer));
    });
  });

  const heartbeat = setInterval(() => {
    const now = Date.now();
    for (const socket of wss.clients) {
      if (socket.alive === false) {
        socket.terminate();
        continue;
      }
      socket.alive = false;
      socket.ping();
    }
    for (const room of rooms.values()) {
      if (now - room.lastActivity > ROOM_IDLE_TTL_MS && room.guests.size === 0) {
        closeRoom(room, 1001, "Room idle timeout");
      }
    }
  }, HEARTBEAT_INTERVAL_MS);
  heartbeat.unref();

  return {
    server,
    wss,
    rooms,
    listen(port = Number(process.env.PORT ?? 9443), host = process.env.HOST ?? "0.0.0.0") {
      return new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(port, host, () => {
          server.off("error", reject);
          resolve(server.address());
        });
      });
    },
    close() {
      clearInterval(heartbeat);
      for (const room of rooms.values()) closeRoom(room, 1001, "Relay shutting down");
      for (const socket of wss.clients) socket.terminate();
      return new Promise(resolve => server.close(() => resolve()));
    }
  };
}

if (import.meta.url === new URL(process.argv[1] ?? "", "file:").href) {
  const certPath = process.env.TLS_CERT_FILE;
  const keyPath = process.env.TLS_KEY_FILE;
  if (!certPath || !keyPath) {
    console.error("TLS_CERT_FILE and TLS_KEY_FILE are required. No plaintext fallback is provided.");
    process.exit(1);
  }
  const relay = createRelayServer({
    cert: fs.readFileSync(certPath),
    key: fs.readFileSync(keyPath)
  });
  const port = Number(process.env.PORT ?? 9443);
  relay.listen(port).then(address => {
    console.log(`The Watcher relay listening with TLS on ${address.address}:${address.port}`);
  }).catch(error => {
    console.error("Failed to start relay:", error);
    process.exit(1);
  });
  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.on(signal, async () => {
      await relay.close();
      process.exit(0);
    });
  }
}
