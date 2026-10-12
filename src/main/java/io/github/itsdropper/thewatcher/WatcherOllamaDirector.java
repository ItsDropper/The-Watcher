package io.github.itsdropper.thewatcher;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional local AI director. The model may choose only a small allowlist of
 * atmosphere events; its output can never execute commands or mutate arbitrary blocks.
 */
public final class WatcherOllamaDirector {
    private static final long REQUEST_INTERVAL_TICKS = 6_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final Map<ServerWorld, WorldState> STATES = new WeakHashMap<>();

    private WatcherOllamaDirector() {
    }

    public static void tick(ServerWorld world, boolean runRevealed) {
        WatcherOllamaConfig.load();
        if (!WatcherOllamaConfig.ENABLED || !runRevealed || world.getRegistryKey() != World.OVERWORLD) {
            return;
        }

        WorldState state = STATES.computeIfAbsent(world, ignored -> new WorldState());
        Directive ready = state.pending.getAndSet(null);
        if (ready != null) {
            apply(world, ready);
        }

        if (state.inFlight.get() || world.getTime() < state.nextRequestAt
                || world.getPlayers().stream().noneMatch(player -> player.isAlive() && !player.isSpectator())) {
            return;
        }

        state.nextRequestAt = world.getTime() + REQUEST_INTERVAL_TICKS;
        state.inFlight.set(true);
        String context = buildContext(world);
        String endpoint = WatcherOllamaConfig.ENDPOINT;
        String model = WatcherOllamaConfig.MODEL;
        String systemPrompt = WatcherOllamaConfig.SYSTEM_PROMPT;
        String body = "{\"model\":\"" + jsonEscape(model) + "\",\"stream\":false,\"format\":\"json\","
                + "\"messages\":[{\"role\":\"system\",\"content\":\"" + jsonEscape(systemPrompt)
                + "\\nReturn exactly JSON with keys event and message. Allowed event values: whisper, darkness, footsteps, none.\"},"
                + "{\"role\":\"user\",\"content\":\"" + jsonEscape(context) + "\"}]}";

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api/chat"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .whenComplete((response, error) -> {
                        try {
                            if (error == null && response.statusCode() >= 200 && response.statusCode() < 300) {
                                String content = extractJsonString(response.body(), "content");
                                if (content != null) {
                                    Directive directive = parseDirective(content);
                                    if (directive != null) state.pending.set(directive);
                                } else {
                                    TheWatcher.LOGGER.debug("Ollama response did not contain message content.");
                                }
                            } else if (error != null) {
                                TheWatcher.LOGGER.debug("Local Ollama request failed: {}", error.toString());
                            } else {
                                TheWatcher.LOGGER.debug("Local Ollama returned HTTP {}", response.statusCode());
                            }
                        } finally {
                            state.inFlight.set(false);
                        }
                    });
        } catch (IllegalArgumentException exception) {
            state.inFlight.set(false);
            TheWatcher.LOGGER.warn("Invalid Ollama endpoint '{}'. Check The Watcher settings.", endpoint);
        }
    }

    private static String buildContext(ServerWorld world) {
        long day = Math.max(1L, world.getTimeOfDay() / 24_000L + 1L);
        long time = Math.floorMod(world.getTimeOfDay(), 24_000L);
        String period = time >= 13_000L && time < 23_000L ? "night" : "day";
        long alive = world.getPlayers().stream().filter(player -> player.isAlive() && !player.isSpectator()).count();
        return "Game: Minecraft Overworld. In-game day: " + day + ". Time: " + period
                + ". Living participants: " + alive + ". Choose one restrained horror event. "
                + "Use none often; do not imply the players are in real danger.";
    }

    private static void apply(ServerWorld world, Directive directive) {
        if (world.getPlayers().isEmpty()) return;
        java.util.List<ServerPlayerEntity> participants = world.getPlayers().stream()
                .filter(candidate -> candidate.isAlive() && !candidate.isSpectator()).toList();
        ServerPlayerEntity player = participants.isEmpty() ? null
                : participants.get(world.random.nextInt(participants.size()));
        if (player == null) return;

        switch (directive.event) {
            case "whisper" -> {
                if (!directive.message.isBlank()) {
                    player.sendMessage(Text.literal(directive.message), true);
                }
            }
            case "darkness" -> player.addStatusEffect(
                    new StatusEffectInstance(StatusEffects.DARKNESS, 35, 0, true, false, false));
            case "footsteps" -> {
                double angle = world.random.nextDouble() * Math.PI * 2.0D;
                double distance = 5.0D + world.random.nextDouble() * 4.0D;
                BlockPos soundPos = BlockPos.ofFloored(player.getX() + Math.cos(angle) * distance,
                        player.getY(), player.getZ() + Math.sin(angle) * distance);
                world.playSound(null, soundPos, SoundEvents.BLOCK_WOOD_STEP, SoundCategory.AMBIENT,
                        0.45F, 0.55F + world.random.nextFloat() * 0.2F);
            }
            default -> { /* Silence is a valid director decision. */ }
        }
    }

    private static Directive parseDirective(String content) {
        String event = extractJsonString(content, "event");
        String message = extractJsonString(content, "message");
        if (event == null) return null;
        event = event.trim().toLowerCase(java.util.Locale.ROOT);
        if (!event.equals("whisper") && !event.equals("darkness")
                && !event.equals("footsteps") && !event.equals("none")) {
            event = "none";
        }
        if (message == null) message = "";
        message = message.replaceAll("[\\r\\n\\t]", " ").replaceAll("§.", "").trim();
        if (message.length() > 100) message = message.substring(0, 100);
        return new Directive(event, message);
    }

    private static String extractJsonString(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) return null;
        return jsonUnescape(matcher.group(1));
    }

    private static String jsonUnescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'u' -> {
                        if (i + 4 < value.length()) {
                            try {
                                result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                result.append('u');
                            }
                        }
                    }
                    default -> result.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                result.append(c);
            }
        }
        if (escaped) result.append('\\');
        return result.toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(' ');
                    else result.append(c);
                }
            }
        }
        return result.toString();
    }

    private record Directive(String event, String message) {
    }

    private static final class WorldState {
        private long nextRequestAt;
        private final AtomicBoolean inFlight = new AtomicBoolean(false);
        private final AtomicReference<Directive> pending = new AtomicReference<>();
    }
}