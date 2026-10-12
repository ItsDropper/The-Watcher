# The Watcher: optional world hosting

This directory contains the **optional, database-free relay service** for world hosting. The relay is intentionally separate from the Minecraft mod: single-player and normal dedicated servers must keep working if the relay is unavailable.

## Security model

- **TLS is mandatory.** The relay refuses to start without `TLS_CERT_FILE` and `TLS_KEY_FILE`; there is no plaintext WebSocket fallback.
- **No database.** Room state lives in memory and is discarded when the process restarts. A host must create a new invite after a relay restart.
- **Unpredictable room identifiers.** The client must generate a cryptographically random 128-bit room ID. The room ID is the rendezvous address; do not use usernames or world names.
- **End-to-end encryption belongs in the client tunnel.** The invite's separate 256-bit secret must never be sent to this relay. Tunnel DATA frames must use authenticated encryption (AES-GCM) with distinct directional keys and per-channel sequence numbers. The relay routes encrypted frames by their visible type and channel ID only; it does not terminate the client-to-client encryption.
- **Bounded resources.** The service limits guests per room, message size, handshake rate, and per-connection bandwidth. Rooms and sockets are cleaned up when a host leaves.
- **No world data is uploaded.** The service only forwards tunnel frames. It must not receive world saves, player credentials, Minecraft account tokens, or the invite's encryption secret.

TLS protects each client-to-relay hop. The client tunnel's authenticated encryption is separately required for end-to-end confidentiality and integrity; TLS alone is not end-to-end encryption.

## Requirements

- Node.js 22+
- A public hostname and a valid TLS certificate for that hostname
- Port 9443 (or the configured `PORT`) reachable from clients
- The matching The Watcher client tunnel implementation configured to use this relay

The relay is not useful as a standalone public server until the mod's client tunnel connects to it. Do not expose a relay publicly until the end-to-end encrypted client implementation and integration tests are complete.

## Run

Set the TLS certificate paths and start:

```sh
TLS_CERT_FILE=/run/secrets/relay-fullchain.pem \
TLS_KEY_FILE=/run/secrets/relay-privkey.pem \
PORT=9443 \
node server.mjs
```

Health check: `GET https://your-hostname:9443/health`.

For a container, mount the certificate and key read-only at the paths supplied through `TLS_CERT_FILE` and `TLS_KEY_FILE`. Never commit private keys or tokens to this repository.

## Relay protocol (v1)

1. The host opens `wss://hostname:9443/tunnel` and sends a small text handshake `{"type":"host","room":"<22-char-base64url-id>"}`.
2. A guest opens the same endpoint and sends `{"type":"join","room":"<room-id>"}`. The guest parses the encryption secret locally from the invite code and never sends it to the relay.
3. The relay replies with a `ready` message. A guest can open one tunnel channel with a 5-byte binary frame: type byte `1`, then a nonzero unsigned 32-bit channel ID.
4. DATA frames use type byte `2`, channel ID, 64-bit sequence number, and authenticated-encrypted payload. The encrypted payload must include the GCM tag. The relay forwards DATA only between the channel owner and host.
5. CLOSE frames use type byte `3` and channel ID. A guest can only operate on its own channel.
6. If a guest disconnects without sending CLOSE, the relay sends the host a `channel-closed` text notification containing only that channel ID. The host treats it only as a channel teardown signal, never as authenticated game data.

The relay never interprets DATA payloads. It does inspect the type and channel ID needed for routing. The client must reject invalid tags, duplicate or out-of-order sequence numbers, and unexpected frames, and must close the tunnel on authentication failure.

## Operational notes

- The room directory and invite codes are ephemeral; relay restart invalidates all active rooms.
- Use firewall rules to expose only the TLS port.
- Monitor connection counts and resource use. This implementation uses in-memory rate limits, which are per process and not a substitute for network-level DDoS protection.
- Host and guest clients must ship with a trusted relay URL configuration. Avoid public, untrusted relay URLs for sensitive sessions.
