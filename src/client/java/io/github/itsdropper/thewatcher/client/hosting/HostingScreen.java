package io.github.itsdropper.thewatcher.client.hosting;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletionException;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.NetworkUtils;

public final class HostingScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget relayField;
    private TextFieldWidget inviteField;
    private String status = "Use a trusted WSS relay. Invite secrets are never sent to the relay.";
    private boolean busy;

    public HostingScreen(Screen parent) {
        super(Text.literal("The Watcher Hosting"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int center = width / 2;
        int top = Math.max(16, height / 4 - 18);
        int fieldWidth = Math.min(360, width - 28);
        int left = center - fieldWidth / 2;

        relayField = new TextFieldWidget(textRenderer, left, top + 30, fieldWidth, 20,
                Text.literal("Relay URL"));
        relayField.setMaxLength(512);
        relayField.setPlaceholder(Text.literal("wss://your-hostname:9443/tunnel"));
        relayField.setText(loadRelayUrl());
        addDrawableChild(relayField);

        inviteField = new TextFieldWidget(textRenderer, left, top + 76, fieldWidth, 20,
                Text.literal("Invite code"));
        inviteField.setMaxLength(160);
        inviteField.setPlaceholder(Text.literal("Paste the full invite code"));
        addDrawableChild(inviteField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Host this world"), button -> startHosting())
                .dimensions(center - 174, top + 108, 168, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Join invite"), button -> joinInvite())
                .dimensions(center + 6, top + 108, 168, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Stop hosting / tunnel"), button -> {
            HostingTunnelService.closeActiveSession();
            if (inviteField != null) inviteField.setText("");
            busy = false;
            status = "Hosting tunnel closed. The displayed invite was cleared.";
        }).dimensions(center - 174, top + 136, 348, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Copy invite"), button -> {
            if (!inviteField.getText().isBlank()) client.keyboard.setClipboard(inviteField.getText());
            status = "Invite code copied. Share it privately with people you trust.";
        }).dimensions(center - 174, top + 164, 168, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> client.setScreen(parent))
                .dimensions(center + 6, top + 164, 168, 20).build());
    }

    private void startHosting() {
        if (busy) return;
        final URI relay;
        try {
            relay = parseRelay();
        } catch (Exception error) {
            status = error.getMessage();
            return;
        }
        saveRelayUrl(relay.toString());
        IntegratedServer server = client.getServer();
        if (server == null) {
            status = "Open a single-player world first. Hosting from a remote server is not supported.";
            return;
        }

        int port = server.getServerPort();
        if (port < 1 || port > 65535) {
            port = NetworkUtils.findLocalPort();
            boolean opened = server.openToLan(client.interactionManager == null
                    ? null : client.interactionManager.getCurrentGameMode(), false, port);
            if (!opened) {
                status = "Minecraft could not open this world to LAN. Check whether the port is available.";
                return;
            }
            port = server.getServerPort();
        }
        if (port < 1 || port > 65535) {
            status = "Minecraft did not report a valid LAN port.";
            return;
        }

        busy = true;
        status = "Connecting securely to the relay…";
        final int minecraftPort = port;
        HostingTunnelService.host(minecraftPort, relay).whenComplete((session, error) ->
                client.execute(() -> {
                    busy = false;
                    if (error != null) {
                        status = "Host failed: " + safeMessage(error);
                        return;
                    }
                    inviteField.setText(session.inviteCode());
                    status = "Hosting is active. Copy the invite and share it privately.";
                }));
    }

    private void joinInvite() {
        if (busy) return;
        final URI relay;
        try {
            relay = parseRelay();
        } catch (Exception error) {
            status = error.getMessage();
            return;
        }
        saveRelayUrl(relay.toString());
        busy = true;
        status = "Authenticating with relay and opening the encrypted tunnel…";
        HostingTunnelService.join(inviteField.getText(), relay).whenComplete((session, error) ->
                client.execute(() -> {
                    busy = false;
                    if (error != null) {
                        status = "Join failed: " + safeMessage(error);
                        return;
                    }
                    status = "Tunnel ready. Connecting to the host…";
                    ServerAddress address = new ServerAddress("127.0.0.1", session.localPort());
                    ServerInfo info = new ServerInfo("The Watcher Invite", address.toString(),
                            ServerInfo.ServerType.OTHER);
                    ConnectScreen.connect(this, client, address, info, false, null);
                }));
    }

    private URI parseRelay() {
        String value = relayField.getText().trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Enter your trusted WSS relay URL first.");
        URI uri = URI.create(value);
        if (!"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || !"/tunnel".equals(uri.getPath())) {
            throw new IllegalArgumentException("Relay URL must use wss:// and end in /tunnel.");
        }
        return uri;
    }

    private static String loadRelayUrl() {
        try {
            Path path = configPath();
            if (Files.isRegularFile(path)) return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {}
        return System.getProperty("thewatcher.hostingRelay", "");
    }

    private static void saveRelayUrl(String value) {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ignored) {}
    }

    private static Path configPath() {
        return MinecraftClient.getInstance().runDirectory.toPath()
                .resolve("config").resolve("the-watcher-hosting-relay.txt");
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int center = width / 2;
        int top = Math.max(16, height / 4 - 18);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, "THE WATCHER • WORLD HOSTING",
                center, top - 18, 0xFFE8EDF4);
        context.drawTextWithShadow(textRenderer, "Trusted relay (WSS)", center - Math.min(180, (width - 28) / 2),
                top + 17, 0xFFB9C4D2);
        context.drawTextWithShadow(textRenderer, "Invite code (share privately)", center - Math.min(180, (width - 28) / 2),
                top + 63, 0xFFB9C4D2);
        int maxWidth = Math.max(80, width - 32);
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(status, maxWidth),
                center, Math.min(height - 42, top + 202), 0xFFCDD6E2);
        context.drawCenteredTextWithShadow(textRenderer,
                "The relay forwards encrypted traffic; it does not store your world or invite secret.",
                center, Math.min(height - 28, top + 216), 0xFF8D9AA9);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
