package io.github.itsdropper.thewatcher;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * A restrained, offline atmosphere director for active Watcher runs.
 *
 * It creates rare, ambiguous cues instead of a constant stream of jump scares.
 * Events are local to one player, never deal damage, and never modify blocks.
 */
public final class WatcherAtmosphereDirector {
    private static final Map<ServerWorld, Map<UUID, PlayerState>> STATES = new WeakHashMap<>();

    private static final String[] WHISPERS = {
            "Something stopped when you stopped.",
            "You were not the first person here.",
            "The silence changed.",
            "You looked away longer than you think.",
            "There is a place nearby that remembers you.",
            "No footsteps followed you inside.",
            "You have already checked that corner.",
            "Something is waiting for you to leave.",
            "The path back feels different.",
            "You heard it before the sound happened."
    };

    private WatcherAtmosphereDirector() {
    }

    public static void tick(ServerWorld world, boolean runRevealed) {
        if (!runRevealed || world.getRegistryKey() != World.OVERWORLD || world.getTime() % 20L != 0L) {
            return;
        }

        Map<UUID, PlayerState> states = STATES.computeIfAbsent(world, ignored -> new HashMap<>());
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }

            PlayerState state = states.computeIfAbsent(player.getUuid(), ignored -> {
                PlayerState created = new PlayerState();
                created.nextEventAt = world.getTime() + 4_000L + world.random.nextInt(4_001);
                // Adaptive responses cannot fire immediately just because the player looked around
                // during the first few seconds of a run.
                created.nextAdaptiveCueAt = created.nextEventAt + 1_800L + world.random.nextInt(2_401);
                created.lastYaw = player.getYaw();
                created.lastPosition = player.getPos();
                created.hasLastSample = true;
                return created;
            });

            updatePlayerHabits(world, player, state);
            if (state.lookBackCount >= 2 && world.getTime() >= state.nextAdaptiveCueAt) {
                // Repeatedly checking behind can itself become a trigger: answer only
                // after the player has turned away, and then leave another long quiet gap.
                playFootstep(world, player, state, 5.5D + world.random.nextDouble() * 3.0D, true);
                state.lookBackCount = 0;
                state.events++;
                state.nextAdaptiveCueAt = world.getTime() + 1_800L + world.random.nextInt(2_401);
                state.nextEventAt = Math.max(state.nextEventAt, world.getTime() + 900L);
                continue;
            }

            // Remove stale per-player state occasionally; this also avoids a growing map
            // when people join a world once and never return.
            if (world.getTime() % 6_000L == 0L) {
                states.keySet().removeIf(id -> world.getServer().getPlayerManager().getPlayer(id) == null);
            }

            if (world.getTime() < state.nextEventAt) {
                continue;
            }

            // Sometimes the director chooses silence. The lack of a payoff is intentional.
            float roll = world.random.nextFloat();
            if (roll >= 0.42F) {
                deliverCue(world, player, state);
                state.events++;
            }

            state.nextEventAt = world.getTime() + 3_600L + world.random.nextInt(6_001);
        }
    }

    private static void updatePlayerHabits(ServerWorld world, ServerPlayerEntity player, PlayerState state) {
        if (state.hasLastSample) {
            float turn = Math.abs(MathHelper.wrapDegrees(player.getYaw() - state.lastYaw));
            if (turn > 105.0F) {
                state.lookBackCount = Math.min(4, state.lookBackCount + 1);
                state.lastBigTurnAt = world.getTime();
            } else if (state.lookBackCount > 0 && world.getTime() - state.lastBigTurnAt > 100L) {
                state.lookBackCount--;
            }

            if (player.getPos().squaredDistanceTo(state.lastPosition) < 0.04D) {
                state.stillTicks = Math.min(12_000, state.stillTicks + 20);
            } else {
                state.stillTicks = Math.max(0, state.stillTicks - 40);
            }
        }
        state.lastYaw = player.getYaw();
        state.lastPosition = player.getPos();
        state.hasLastSample = true;
    }

    private static void deliverCue(ServerWorld world, ServerPlayerEntity player, PlayerState state) {
        int light = world.getLightLevel(player.getBlockPos());
        boolean dark = light <= 7 || isNight(world);
        int event = world.random.nextInt(100);

        // Daylight horror must not depend on the Watcher or darkness. Keep cues physically
        // plausible enough to dismiss, but place them where the player cannot immediately
        // identify a source. The long per-player cooldown does the pacing work.
        if (!dark && event < 24) {
            playFootstep(world, player, state, 11.0D + world.random.nextDouble() * 8.0D, true);
            return;
        }
        if (!dark && event < 38) {
            Vec3d look = player.getRotationVec(1.0F).normalize();
            Vec3d right = new Vec3d(-look.z, 0.0D, look.x).normalize();
            double behind = 13.0D + world.random.nextDouble() * 9.0D;
            double side = (world.random.nextBoolean() ? 1.0D : -1.0D)
                    * (3.0D + world.random.nextDouble() * 5.0D);
            BlockPos soundPos = BlockPos.ofFloored(
                    player.getX() - look.x * behind + right.x * side,
                    player.getY(),
                    player.getZ() - look.z * behind + right.z * side);
            world.playSound(null, soundPos, SoundEvents.BLOCK_WOODEN_DOOR_CLOSE,
                    SoundCategory.AMBIENT, 0.10F, 0.62F + world.random.nextFloat() * 0.22F);
            return;
        }
        if (!dark && event < 46) {
            world.playSound(null, player.getBlockPos(), SoundEvents.AMBIENT_CAVE.value(),
                    SoundCategory.AMBIENT, 0.08F + world.random.nextFloat() * 0.05F,
                    0.55F + world.random.nextFloat() * 0.25F);
            return;
        }

        // A rare, unhelpful camera warning is more unsettling than constant narration.
        // It never gives coordinates and is separated by the long event cooldown above.
        if (event < 4 && isNight(world) && state.events >= 2) {
            String[] alerts = {
                    "CAMERA NETWORK: UNKNOWN MOVEMENT",
                    "CAMERA NETWORK: FRAME MISMATCH",
                    "CAMERA NETWORK: SIGNAL INTEGRITY LOST"
            };
            player.sendMessage(Text.literal(alerts[world.random.nextInt(alerts.length)]), true);
            return;
        }

        if (event < 39) {
            double distance = state.stillTicks > 600 ? 2.4D : 2.8D + world.random.nextDouble() * 2.4D;
            playFootstep(world, player, state, distance, false);
            return;
        }

        if (event < 59) {
            playFootstep(world, player, state, 7.0D + world.random.nextDouble() * 5.0D, true);
            return;
        }

        if (event < 77) {
            world.playSound(null, player.getBlockPos(), SoundEvents.AMBIENT_CAVE.value(),
                    SoundCategory.AMBIENT, 0.12F + world.random.nextFloat() * 0.08F,
                    0.65F + world.random.nextFloat() * 0.45F);
            return;
        }

        if (event < 86) {
            // A door-like sound comes from an unhelpful direction, never from a tracked entity.
            Vec3d look = player.getRotationVec(1.0F).normalize();
            double side = world.random.nextBoolean() ? 1.0D : -1.0D;
            BlockPos soundPos = BlockPos.ofFloored(
                    player.getX() - look.x * (6.0D + world.random.nextDouble() * 7.0D)
                            - look.z * side * 4.0D,
                    player.getY(),
                    player.getZ() - look.z * (6.0D + world.random.nextDouble() * 7.0D)
                            + look.x * side * 4.0D);
            world.playSound(null, soundPos, SoundEvents.BLOCK_WOODEN_DOOR_CLOSE,
                    SoundCategory.AMBIENT, 0.18F, 0.72F + world.random.nextFloat() * 0.28F);
            return;
        }

        if (event < 94 && dark && state.events > 1) {
            // A very short blackout, only after the run has established its atmosphere.
            player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.DARKNESS, 14 + world.random.nextInt(10), 0, true, false, false));
            return;
        }

        if (state.events >= 2) {
            String line = WHISPERS[world.random.nextInt(WHISPERS.length)];
            player.sendMessage(Text.literal(line), true);
        } else {
            playFootstep(world, player, state, 4.0D, false);
        }
    }

    private static void playFootstep(ServerWorld world, ServerPlayerEntity player,
                                     PlayerState state, double distance, boolean distant) {
        Vec3d look = player.getRotationVec(1.0F).normalize();
        Vec3d right = new Vec3d(-look.z, 0.0D, look.x).normalize();
        // Alternate sides and occasionally place the sound behind the player.
        double side = (state.events % 2 == 0 ? 1.0D : -1.0D)
                * (1.0D + world.random.nextDouble() * 2.0D);
        double behind = distance * (distant ? 0.9D : 0.65D);
        BlockPos soundPos = BlockPos.ofFloored(
                player.getX() - look.x * behind + right.x * side,
                player.getY(),
                player.getZ() - look.z * behind + right.z * side);

        float volume = distant ? 0.18F : 0.28F;
        float pitch = 0.58F + world.random.nextFloat() * 0.32F;
        world.playSound(null, soundPos, SoundEvents.BLOCK_STONE_STEP,
                SoundCategory.AMBIENT, volume, pitch);
    }

    private static boolean isNight(ServerWorld world) {
        long time = Math.floorMod(world.getTimeOfDay(), 24_000L);
        return time >= 13_000L && time < 23_000L;
    }

    private static final class PlayerState {
        private long nextEventAt;
        private long nextAdaptiveCueAt;
        private long lastBigTurnAt;
        private int events;
        private int lookBackCount;
        private int stillTicks;
        private float lastYaw;
        private Vec3d lastPosition = Vec3d.ZERO;
        private boolean hasLastSample;
    }
}
