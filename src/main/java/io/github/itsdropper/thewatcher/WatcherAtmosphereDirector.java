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
                created.nextEventAt = world.getTime() + 2_400L + world.random.nextInt(3_601);
                created.lastYaw = player.getYaw();
                created.lastPosition = player.getPos();
                created.hasLastSample = true;
                return created;
            });

            updatePlayerHabits(world, player, state);
            if (state.lookBackCount >= 2 && world.getTime() >= state.nextAdaptiveCueAt) {
                // Repeatedly checking behind can itself become a trigger: answer only
                // after the player has turned away, and then leave another long quiet gap.
                playFootstep(world, player, state, 3.2D, false);
                state.lookBackCount = 0;
                state.events++;
                state.nextAdaptiveCueAt = world.getTime() + 1_200L + world.random.nextInt(1_801);
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
            if (roll >= 0.22F) {
                deliverCue(world, player, state);
                state.events++;
            }

            state.nextEventAt = world.getTime() + 1_800L + world.random.nextInt(4_801);
        }
    }

    private static void updatePlayerHabits(ServerWorld world, ServerPlayerEntity player, PlayerState state) {
        if (state.hasLastSample) {
            float turn = Math.abs(MathHelper.wrapDegrees(player.getYaw() - state.lastYaw));
            if (turn > 105.0F) {
                state.lookBackCount = Math.min(4, state.lookBackCount + 1);
            } else if (state.lookBackCount > 0) {
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

        if (event < 39) {
            playFootstep(world, player, state, 2.8D + world.random.nextDouble() * 2.4D, false);
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

        if (event < 91 && dark && state.events > 1) {
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
        private int events;
        private int lookBackCount;
        private int stillTicks;
        private float lastYaw;
        private Vec3d lastPosition = Vec3d.ZERO;
        private boolean hasLastSample;
    }
}
