package io.github.itsdropper.thewatcher.client.hosting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;


public final class HostingTunnelService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService IO = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "TheWatcher-Hosting");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile TunnelSession activeSession;

    private HostingTunnelService() {
    }

    public static CompletableFuture<HostSession> host(int localMinecraftPort, URI relayUri) {
        if (localMinecraftPort < 1 || localMinecraftPort > 65535) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Open your world to LAN first."));
        }
        try {
            validateRelayUri(relayUri);
            byte[] roomBytes = new byte[16];
            byte[] secret = new byte[32];
            RANDOM.nextBytes(roomBytes);
            RANDOM.nextBytes(secret);
            String room = Base64.getUrlEncoder().withoutPadding().encodeToString(roomBytes);
            String secretText = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            HostSession session = new HostSession(room + "." + secretText, room, secret, localMinecraftPort, relayUri);
            return session.start().thenApply(ignored -> {
                replaceActive(session);
                return session;
            }).whenComplete((value, error) -> {
                if (error != null) session.close();
            });
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public static CompletableFuture<JoinSession> join(String inviteCode, URI relayUri) {
        try {
            validateRelayUri(relayUri);
            String[] parts = inviteCode == null ? new String[0] : inviteCode.trim().split("\\.", -1);
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]{22}")
                    || !parts[1].matches("[A-Za-z0-9_-]{43}")) {
                throw new IllegalArgumentException("Invalid invite code. Paste the full code, including the dot.");
            }
            byte[] secret = Base64.getUrlDecoder().decode(parts[1]);
            if (secret.length != 32) throw new IllegalArgumentException("Invalid invite secret.");
            JoinSession session = new JoinSession(parts[0], secret, relayUri);
            return session.start().thenApply(ignored -> {
                replaceActive(session);
                return session;
            }).whenComplete((value, error) -> {
                if (error != null) session.close();
            });
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public static void closeActiveSession() {
        TunnelSession current = activeSession;
        activeSession = null;
        if (current != null) current.close();
    }

    private static void replaceActive(TunnelSession session) {
        TunnelSession previous = activeSession;
        activeSession = session;
        if (previous != null && previous != session) previous.close();
    }

    private static void validateRelayUri(URI uri) {
        if (uri == null || !"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || !"/tunnel".equals(uri.getPath())) {
            throw new IllegalArgumentException("Relay must be a trusted wss:// URL ending in /tunnel.");
        }
    }

    public interface TunnelSession extends AutoCloseable {
        @Override void close();
    }

    public static final class HostSession implements TunnelSession {
        private final String inviteCode;
        private final String room;
        private final byte[] secret;
        private final int localPort;
        private final URI relayUri;
        private final HostEndpoint endpoint;

        private HostSession(String inviteCode, String room, byte[] secret, int localPort, URI relayUri) {
            this.inviteCode = inviteCode;
            this.room = room;
            this.secret = secret.clone();
            this.localPort = localPort;
            this.relayUri = relayUri;
            this.endpoint = new HostEndpoint(room, this.secret, localPort);
        }

        private CompletableFuture<Void> start() {
            return endpoint.connect(relayUri);
        }

        public String inviteCode() { return inviteCode; }

        @Override public void close() {
            endpoint.close();
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    public static final class JoinSession implements TunnelSession {
        private final String room;
        private final byte[] secret;
        private final URI relayUri;
        private final JoinEndpoint endpoint;

        private JoinSession(String room, byte[] secret, URI relayUri) {
            this.room = room;
            this.secret = secret.clone();
            this.relayUri = relayUri;
            this.endpoint = new JoinEndpoint(room, this.secret);
        }

        private CompletableFuture<Void> start() {
            return endpoint.openProxy().thenCompose(ignored -> endpoint.connect(relayUri));
        }

        public int localPort() { return endpoint.localPort(); }

        @Override public void close() {
            endpoint.close();
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    private abstract static class Endpoint implements java.net.http.WebSocket.Listener, AutoCloseable {
        protected final String room;
        protected final byte[] secret;
        protected final CompletableFuture<Void> ready = new CompletableFuture<>();
        protected final ExecutorService serial = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "TheWatcher-TunnelFrames");
            thread.setDaemon(true);
            return thread;
        });
        private final Object sendLock = new Object();
        private CompletableFuture<?> sendTail = CompletableFuture.completedFuture(null);
        private final ByteArrayOutputStream textParts = new ByteArrayOutputStream();
        private final ByteArrayOutputStream binaryParts = new ByteArrayOutputStream();
        protected volatile java.net.http.WebSocket webSocket;
        protected volatile boolean closed;

        protected Endpoint(String room, byte[] secret) {
            this.room = room;
            this.secret = secret.clone();
        }

        protected CompletableFuture<Void> connect(URI uri) {
            return java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .buildAsync(uri, this)
                    .thenCompose(socket -> {
                        webSocket = socket;
                        return ready;
                    })
                    .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS);
        }

        @Override
        public void onOpen(java.net.http.WebSocket socket) {
            webSocket = socket;
            socket.request(1);
            String role = this instanceof HostEndpoint ? "host" : "join";
            socket.sendText("{\"type\":\"" + role + "\",\"room\":\"" + room + "\"}", true);
        }

        @Override
        public CompletionStage<?> onText(java.net.http.WebSocket socket, CharSequence data, boolean last) {
            synchronized (textParts) {
                byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
                if (textParts.size() + bytes.length > 4096) {
                    socket.abort();
                    ready.completeExceptionally(new IOException("Relay control message too large."));
                    return CompletableFuture.completedFuture(null);
                }
                textParts.writeBytes(bytes);
                if (last) {
                    String message = textParts.toString(StandardCharsets.UTF_8);
                    textParts.reset();
                    if (message.contains("\\"type\\":\\"ready\\"")) {
                        String expectedRole = this instanceof HostEndpoint ? "host" : "guest";
                        if (!message.contains("\\"role\\":\\"" + expectedRole + "\\"")) {
                            ready.completeExceptionally(new IOException("Relay returned an invalid role."));
                            socket.abort();
                            close();
                            return CompletableFuture.completedFuture(null);
                        }
                        ready.complete(null);
                        onReadyMessage(message);
                    } else {
                        onControlMessage(message);
                    }                }
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(java.net.http.WebSocket socket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            byte[] complete = null;
            synchronized (binaryParts) {
                if (binaryParts.size() + bytes.length > 64 * 1024) {
                    socket.abort();
                    return CompletableFuture.completedFuture(null);
                }
                binaryParts.writeBytes(bytes);
                if (last) {
                    complete = binaryParts.toByteArray();
                    binaryParts.reset();
                }
            }
            if (complete != null) {
                byte[] frame = complete;
                serial.execute(() -> {
                    try { handleFrame(frame); }
                    catch (Exception error) { close(); }
                });
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(java.net.http.WebSocket socket, int statusCode, String reason) {
            closed = true;
            ready.completeExceptionally(new IOException("Hosting relay closed the connection (" + statusCode + ")."));
            cleanupChannels();
            serial.shutdownNow();
            java.util.Arrays.fill(secret, (byte) 0);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(java.net.http.WebSocket socket, Throwable error) {
            ready.completeExceptionally(error);
            close();
        }

        protected void onReadyMessage(String message) {}
        protected void onControlMessage(String message) {}

        protected abstract void handleFrame(byte[] frame) throws Exception;
        protected abstract void cleanupChannels();

        protected void sendFrame(byte[] frame) {
            java.net.http.WebSocket socket = webSocket;
            if (closed || socket == null || socket.isOutputClosed()) return;
            synchronized (sendLock) {
                sendTail = sendTail.handle((ignored, error) -> null)
                        .thenCompose(ignored -> socket.sendBinary(ByteBuffer.wrap(frame), true));
            }
        }

        protected void sendControlFrame(byte type, int channel, String direction) {
            sendFrame(controlFrame(type, channel, secret, direction));
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            java.net.http.WebSocket socket = webSocket;
            if (socket != null) {
                try { socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "Hosting session closed"); }
                catch (Exception ignored) { socket.abort(); }
            }
            cleanupChannels();
            serial.shutdownNow();
            java.util.Arrays.fill(secret, (byte) 0);
            ready.completeExceptionally(new IOException("Hosting session closed."));
        }
    }

    private static final class HostEndpoint extends Endpoint {
        private final int localPort;
        private final Map<Integer, Channel> channels = new ConcurrentHashMap<>();
        private final java.util.Set<Integer> seenChannels = ConcurrentHashMap.newKeySet();

        private HostEndpoint(String room, byte[] secret, int localPort) {
            super(room, secret);
            this.localPort = localPort;
        }

        @Override protected void handleFrame(byte[] frame) throws Exception {
            Frame parsed = parseFrame(frame);
            if (parsed == null) throw new IOException("Invalid relay frame.");
            if (parsed.type == 1) {
                if (!verifyControlFrame(frame, secret, "guest-to-host") || channels.size() >= 8
                        || seenChannels.size() >= 4096 || !seenChannels.add(parsed.channel)) throw new IOException("Invalid channel open.");
                Socket local = new Socket();
                try {
                    local.connect(new java.net.InetSocketAddress("127.0.0.1", localPort), 5000);
                    local.setTcpNoDelay(true);
                } catch (IOException connectionError) {
                    closeSocket(local);
                    sendControlFrame((byte) 3, parsed.channel, "host-to-guest");
                    return;
                }
                Channel channel = new Channel(parsed.channel, local);
                if (channels.putIfAbsent(parsed.channel, channel) != null) {
                    local.close();
                    throw new IOException("Duplicate channel.");
                }
                startSocketReader(this, channel, "host-to-guest");
                return;
            }
            Channel channel = channels.get(parsed.channel);
            if (channel == null) throw new IOException("Unknown channel.");
            if (parsed.type == 2) {
                if (parsed.sequence != channel.incomingSequence.getAndIncrement()) {
                    throw new IOException("Invalid data sequence.");
                }
                byte[] plaintext = decrypt(frame, secret, "guest-to-host");
                channel.socket.getOutputStream().write(plaintext);
                channel.socket.getOutputStream().flush();
            } else if (parsed.type == 3) {
                if (!verifyControlFrame(frame, secret, "guest-to-host")) throw new IOException("Invalid close frame.");
                closeChannel(channel, false);
            }
        }

        @Override protected void cleanupChannels() {
            for (Channel channel : channels.values()) closeChannel(channel, false);
            channels.clear();
        }

        private void closeChannel(Channel channel, boolean notifyPeer) {
            if (!channels.remove(channel.id, channel)) return;
            if (notifyPeer) sendControlFrame((byte) 3, channel.id, "host-to-guest");
            closeSocket(channel.socket);
        }
    }

    private static final class JoinEndpoint extends Endpoint {
        private ServerSocket proxy;
        private volatile Socket localClient;
        private volatile int boundPort;
        private final Map<Integer, Channel> channels = new ConcurrentHashMap<>();

        private JoinEndpoint(String room, byte[] secret) {
            super(room, secret);
        }

        private CompletableFuture<Void> openProxy() {
            return CompletableFuture.runAsync(() -> {
                try {
                    proxy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                    boundPort = proxy.getLocalPort();
                } catch (IOException error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            }, IO);
        }

        private int localPort() { return boundPort; }

        @Override protected void onReadyMessage(String message) {
            IO.execute(() -> {
                try {
                    Socket socket = proxy.accept();
                    localClient = socket;
                    socket.setTcpNoDelay(true);
                    proxy.close();
                    int channelId;
                    do { channelId = RANDOM.nextInt() & 0x7fffffff; }
                    while (channelId == 0);
                    Channel channel = new Channel(channelId, socket);
                    channels.put(channelId, channel);
                    sendControlFrame((byte) 1, channelId, "guest-to-host");
                    startSocketReader(this, channel, "guest-to-host");
                } catch (IOException error) {
                    close();
                }
            });
        }

        @Override protected void handleFrame(byte[] frame) throws Exception {
            Frame parsed = parseFrame(frame);
            if (parsed == null) throw new IOException("Invalid relay frame.");
            Channel channel = channels.get(parsed.channel);
            if (channel == null) throw new IOException("Unknown channel.");
            if (parsed.type == 2) {
                if (parsed.sequence != channel.incomingSequence.getAndIncrement()) {
                    throw new IOException("Invalid data sequence.");
                }
                byte[] plaintext = decrypt(frame, secret, "host-to-guest");
                channel.socket.getOutputStream().write(plaintext);
                channel.socket.getOutputStream().flush();
            } else if (parsed.type == 3) {
                if (!verifyControlFrame(frame, secret, "host-to-guest")) throw new IOException("Invalid close frame.");
                closeChannel(channel, false);
            } else {
                throw new IOException("Unexpected open frame.");
            }
        }

        @Override protected void cleanupChannels() {
            for (Channel channel : channels.values()) closeChannel(channel, false);
            channels.clear();
            closeSocket(localClient);
            try { if (proxy != null) proxy.close(); } catch (IOException ignored) {}
        }

        private void closeChannel(Channel channel, boolean notifyPeer) {
            if (!channels.remove(channel.id, channel)) return;
            if (notifyPeer) sendControlFrame((byte) 3, channel.id, "guest-to-host");
            closeSocket(channel.socket);
        }

        @Override public void close() {
            super.close();
            closeSocket(localClient);
            try { if (proxy != null) proxy.close(); } catch (IOException ignored) {}
        }
    }

    private static final class Channel {
        private final int id;
        private final Socket socket;
        private final AtomicLong outgoingSequence = new AtomicLong();
        private final AtomicLong incomingSequence = new AtomicLong();

        private Channel(int id, Socket socket) {
            this.id = id;
            this.socket = socket;
        }
    }

    private record Frame(byte type, int channel, long sequence) {}

    private static Frame parseFrame(byte[] frame) {
        if (frame.length < 5 || frame.length > 64 * 1024) return null;
        ByteBuffer header = ByteBuffer.wrap(frame);
        byte type = header.get();
        int channel = header.getInt();
        if (channel == 0 || type < 1 || type > 3) return null;
        if ((type == 1 || type == 3) && frame.length != 37) return null;
        if (type == 2 && frame.length < 29) return null;
        long sequence = type == 2 ? header.getLong() : -1;
        if (type == 2 && sequence < 0) return null;
        return new Frame(type, channel, sequence);
    }

    private static byte[] controlFrame(byte type, int channel, byte[] secret, String direction) {
        ByteBuffer header = ByteBuffer.allocate(5);
        header.put(type).putInt(channel);
        byte[] prefix = header.array();
        byte[] mac = hmac(secret, concat(prefix, direction.getBytes(StandardCharsets.UTF_8)));
        return concat(prefix, mac);
    }

    private static boolean verifyControlFrame(byte[] frame, byte[] secret, String direction) {
        if (frame.length != 37) return false;
        byte type = frame[0];
        int channel = ByteBuffer.wrap(frame, 1, 4).getInt();
        byte[] expected = controlFrame(type, channel, secret, direction);
        return MessageDigest.isEqual(expected, frame);
    }

    private static byte[] encrypt(byte[] plaintext, byte[] secret, String direction, int channel, long sequence)
            throws GeneralSecurityException {
        byte[] header = dataHeader(channel, sequence);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(secret, direction, channel), "AES"),
                new GCMParameterSpec(128, nonce(sequence)));
        cipher.updateAAD(header);
        return concat(header, cipher.doFinal(plaintext));
    }

    private static byte[] decrypt(byte[] frame, byte[] secret, String direction) throws GeneralSecurityException {
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        byte type = buffer.get();
        int channel = buffer.getInt();
        long sequence = buffer.getLong();
        if (type != 2 || channel == 0 || sequence < 0) throw new GeneralSecurityException("Invalid data header.");
        byte[] header = dataHeader(channel, sequence);
        byte[] encrypted = java.util.Arrays.copyOfRange(frame, 13, frame.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(secret, direction, channel), "AES"),
                new GCMParameterSpec(128, nonce(sequence)));
        cipher.updateAAD(header);
        return cipher.doFinal(encrypted);
    }

    private static byte[] deriveKey(byte[] secret, String direction, int channel) throws GeneralSecurityException {
        java.security.MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("TheWatcher.Hosting.v1|".getBytes(StandardCharsets.UTF_8));
        digest.update(secret);
        digest.update(("|" + direction + "|" + channel).getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    private static byte[] hmac(byte[] secret, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Required HMAC algorithm is unavailable.", error);
        }
    }

    private static byte[] dataHeader(int channel, long sequence) {
        return ByteBuffer.allocate(13).put((byte) 2).putInt(channel).putLong(sequence).array();
    }

    private static byte[] nonce(long sequence) {
        return ByteBuffer.allocate(12).putInt(0).putLong(sequence).array();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void startSocketReader(Endpoint endpoint, Channel channel, String direction) {
        IO.execute(() -> {
            byte[] buffer = new byte[16 * 1024];
            try (InputStream input = channel.socket.getInputStream()) {
                int read;
                while (!endpoint.closed && (read = input.read(buffer)) != -1) {
                    if (read == 0) continue;
                    long sequence = channel.outgoingSequence.getAndIncrement();
                    if (sequence < 0) throw new IOException("Tunnel sequence exhausted.");
                    byte[] payload = encrypt(java.util.Arrays.copyOf(buffer, read), endpoint.secret,
                            direction, channel.id, sequence);
                    endpoint.sendFrame(payload);
                }
            } catch (Exception ignored) {
                // Socket closure is a normal end-of-channel condition.
            } finally {
                endpoint.serial.execute(() -> {
                    if (endpoint instanceof HostEndpoint host) host.closeChannel(channel, true);
                    else if (endpoint instanceof JoinEndpoint join) join.closeChannel(channel, true);
                });
            }
        });
    }

    private static void closeSocket(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
