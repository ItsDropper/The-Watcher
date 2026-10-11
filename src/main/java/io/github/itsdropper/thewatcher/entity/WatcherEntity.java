package io.github.itsdropper.thewatcher.entity;

import io.github.itsdropper.thewatcher.TheWatcher;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.util.math.Vec3d;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Comparator;

public class WatcherEntity extends PathAwareEntity implements GeoEntity {
    public enum Phase {
        IDLE,
        OBSERVING,
        STALKING,
        ESCALATING,
        RETREATING
    }

    private static final RawAnimation IDLE_ANIMATION =
            RawAnimation.begin().thenLoop("animation.watcher.idle");

    private static final int OBSERVE_TICKS = 100;
    private static final int STALK_TICKS = 500;
    private static final int ESCALATE_TICKS = 160;
    private static final int RETREAT_TICKS = 100;
    private static final double TARGET_RANGE = 48.0D;
    private static final double DEBUG_TARGET_RANGE = 128.0D;

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    private final WatcherLearningEngine learningEngine = new WatcherLearningEngine();
    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int hiddenMoveCooldown;
    private int dreadEffectCooldown;
    private int attentionTicks;
    private int unseenTicks;
    private boolean attemptedEscalationAttack;
    private boolean debugPhaseLocked;
    private boolean debugSimulation;
    private boolean debugPhaseAutoAdvance;

    public WatcherEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        // No look-around or movement goals: stalking should feel unnaturally still.
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getWorld().isClient() || !(this.getWorld() instanceof ServerWorld world)) {
            return;
        }

        int tickScale = debugSimulation ? 4 : 1;
        hiddenMoveCooldown = Math.max(0, hiddenMoveCooldown - tickScale);
        dreadEffectCooldown = Math.max(0, dreadEffectCooldown - tickScale);

        long timeOfDay = Math.floorMod(world.getTimeOfDay(), 24000L);
        boolean night = timeOfDay >= 13000L && timeOfDay < 23000L;

        if (!debugPhaseLocked && !debugSimulation && !night) {
            resetToIdle();
            return;
        }

        PlayerEntity player = null;
        if ((debugPhaseLocked || debugSimulation) && getTarget() instanceof PlayerEntity current
                && current.isAlive() && !current.isSpectator()) {
            player = current;
        }

        int renderChunks = WatcherStalkingDirector.renderDistanceChunks(
                TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS);
        double targetRange = Math.max(TARGET_RANGE, WatcherStalkingDirector.targetRange(renderChunks));
        if (player == null) {
            player = world.getPlayers().stream()
                    .filter(candidate -> candidate.isAlive() && !candidate.isSpectator())
                    .filter(candidate -> squaredDistanceTo(candidate) <= targetRange * targetRange)
                    .min(Comparator.comparingDouble(this::squaredDistanceTo))
                    .orElse(null);
        }

        if (player == null) {
            if (!debugPhaseLocked && !debugSimulation) {
                resetToIdle();
            } else {
                getNavigation().stop();
            }
            return;
        }

        setTarget(player);

        boolean watched = isPlayerWatching(player);
        learningEngine.observe(watched, Math.sqrt(squaredDistanceTo(player)));
        if (watched) {
            attentionTicks = Math.min(attentionTicks + 1, 1200);
            unseenTicks = 0;
            getNavigation().stop();
            setVelocity(0.0D, getVelocity().y, 0.0D);
            if (phase != Phase.IDLE) tryDreadEffect(world, player);
            return;
        }

        // A watched entity's phase timer pauses; it should not escalate just because the player stares.
        phaseTicks = Math.min(phaseTicks + tickScale, 72000);
        unseenTicks = Math.min(unseenTicks + tickScale, 1200);
        attentionTicks = Math.max(0, attentionTicks - 2);
        if (phase != Phase.IDLE) tryDreadEffect(world, player);

        switch (phase) {
            case IDLE -> setPhase(Phase.OBSERVING);
            case OBSERVING -> {
                // Keep the entity still; do not track the player with its head.
                getNavigation().stop();
                setVelocity(0.0D, getVelocity().y, 0.0D);
                int duration = OBSERVE_TICKS;
                if ((!debugPhaseLocked || debugPhaseAutoAdvance) && phaseTicks >= duration) {
                    setPhase(Phase.STALKING);
                }
            }
            case STALKING -> {
                // Stalking is stationary. It only relocates after leaving the player's view.
                getNavigation().stop();
                setVelocity(0.0D, getVelocity().y, 0.0D);
                int baseTeleportChance = 24;
                int teleportChance = learningEngine.adjustTeleportChance(baseTeleportChance);
                double distance = Math.sqrt(squaredDistanceTo(player));
                boolean tooClose = WatcherStalkingDirector.isTooCloseForStalking(distance, renderChunks);
                int unseenThreshold = tooClose ? 2 : learningEngine.getHiddenTeleportDelayTicks();
                boolean relocationRoll = tooClose || random.nextInt(100) < teleportChance;
                if (TheWatcher.TELEPORTS_ENABLED && hiddenMoveCooldown <= 0
                        && unseenTicks >= unseenThreshold && relocationRoll) {
                    boolean teleported = tryHiddenTeleport(world, player, false);
                    // Failed candidate searches retry quickly; successful relocations get a longer quiet period.
                    hiddenMoveCooldown = teleported ? 100 + random.nextInt(101) : 12 + random.nextInt(17);
                    if (teleported) {
                        // No teleport sound: the player should discover the new position, not hear the move.
                        getNavigation().stop();
                    }
                }

                int duration = learningEngine.adjustStalkDuration(STALK_TICKS);
                if ((!debugPhaseLocked || debugPhaseAutoAdvance) && phaseTicks >= duration) {
                    setPhase(Phase.ESCALATING);
                }
            }
            case ESCALATING -> {
                lookAtPlayer(player);
                getNavigation().startMovingTo(player, 1.05D);

                if (!attemptedEscalationAttack && squaredDistanceTo(player) <= 2.6D * 2.6D) {
                    attemptedEscalationAttack = true;
                    tryAttack(player);
                }

                int duration = debugPhaseAutoAdvance ? 45 : ESCALATE_TICKS;
                if ((!debugPhaseLocked || debugPhaseAutoAdvance) && phaseTicks >= duration) {
                    setPhase(Phase.RETREATING);
                }
            }
            case RETREATING -> {
                lookAtPlayer(player);
                boolean teleported = false;
                if (TheWatcher.TELEPORTS_ENABLED && hiddenMoveCooldown <= 0
                        && !isWithinPlayerView(player) && random.nextInt(100) < 62) {
                    teleported = tryHiddenTeleport(world, player, true);
                    hiddenMoveCooldown = 8 + random.nextInt(15);
                    if (teleported) {
                        world.playSound(null, getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                                SoundCategory.HOSTILE, 0.8F, 0.45F + random.nextFloat() * 0.25F);
                    }
                }
                if (!teleported) moveAwayFrom(player, 2.2D);
                else getNavigation().stop();

                int duration = RETREAT_TICKS;
                if ((!debugPhaseLocked || debugPhaseAutoAdvance) && phaseTicks >= duration) {
                    setPhase(Phase.OBSERVING);
                    debugPhaseAutoAdvance = false;
                    debugPhaseLocked = true;
                }
            }
        }
    }

    /**
     * Stalking teleports aim just inside the configured client render-distance edge.
     * A candidate must be loaded, safe to stand on, and hidden behind solid cover.
     */
    private boolean tryHiddenTeleport(ServerWorld world, PlayerEntity player, boolean retreat) {
        if (isWithinPlayerView(player)) {
            return false;
        }

        int attempts = 72;
        int renderChunks = WatcherStalkingDirector.renderDistanceChunks(
                TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS);
        int renderBlocks = (int) WatcherStalkingDirector.renderDistanceBlocks(renderChunks);
        for (int i = 0; i < attempts; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius;
            if (retreat) {
                // Retreats leave the immediate area, but do not use the extreme stalking ring.
                radius = Math.max(32.0D, Math.min(64.0D,
                        WatcherStalkingDirector.minimumStalkingDistance(renderChunks) * 0.38D))
                        + random.nextDouble() * 12.0D;
            } else {
                // Relocate just inside the render edge, with a narrow distance band for distant sightings.
                radius = WatcherStalkingDirector.candidateRadius(renderChunks, random.nextDouble());
            }
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * radius);

            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }

            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos ground = feet.down();

            if (!world.getBlockState(ground).isSolidBlock(world, ground)
                    || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                    || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
                continue;
            }

            Vec3d destinationEyes = new Vec3d(x + 0.5D, y + 1.4D, z + 0.5D);
            BlockHitResult sightCheck = world.raycast(new RaycastContext(
                    player.getEyePos(), destinationEyes,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, player));
            Vec3d destinationDirection = destinationEyes.subtract(player.getEyePos()).normalize();
            boolean destinationInView = player.getRotationVec(1.0F).normalize()
                    .dotProduct(destinationDirection) > 0.15D;
            // A destination may be visible from the player's current position only if solid cover hides it.
            if (sightCheck.getType() == BlockHitResult.Type.MISS && destinationInView) {
                continue;
            }

            requestTeleport(x + 0.5D, y, z + 0.5D);
            getNavigation().stop();
            return true;
        }

        return false;
    }

    private boolean isPlayerWatching(PlayerEntity player) {
        if (!player.canSee(this)) return false;
        Vec3d toWatcher = new Vec3d(getX() - player.getX(),
                getEyeY() - player.getEyeY(), getZ() - player.getZ()).normalize();
        Vec3d look = player.getRotationVec(1.0F).normalize();
        return look.dotProduct(toWatcher) > 0.965D;
    }

    /**
     * Conservative view-cone check: never teleport while the entity is visible in front of
     * the player, even if the player is not aiming the crosshair directly at it.
     */
    private boolean isWithinPlayerView(PlayerEntity player) {
        if (!player.canSee(this)) return false;
        Vec3d toWatcher = new Vec3d(getX() - player.getX(),
                getEyeY() - player.getEyeY(), getZ() - player.getZ()).normalize();
        Vec3d look = player.getRotationVec(1.0F).normalize();
        return look.dotProduct(toWatcher) > 0.15D;
    }

    private void tryDreadEffect(ServerWorld world, PlayerEntity player) {
        if (!TheWatcher.DREAD_EFFECTS_ENABLED || dreadEffectCooldown > 0 || phase == Phase.IDLE) {
            return;
        }

        double distance = Math.sqrt(squaredDistanceTo(player));
        int renderChunks = WatcherStalkingDirector.renderDistanceChunks(
                TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS);

        // A rare, quiet cave cue at the player's position makes the distant Watcher feel present
        // without giving away its exact location or making teleporting audible.
        if (phase == Phase.STALKING
                && WatcherHorrorDirector.shouldPlayDistantCue(distance, renderChunks, random.nextFloat())) {
            dreadEffectCooldown = WatcherHorrorDirector.nextDistantCueCooldown(random.nextInt(221));
            float cue = random.nextFloat();
            if (cue < 0.50F) {
                Vec3d look = player.getRotationVec(1.0F).normalize();
                BlockPos behind = BlockPos.ofFloored(player.getX() - look.x * 3.0D,
                        player.getY(), player.getZ() - look.z * 3.0D);
                world.playSound(null, behind, SoundEvents.BLOCK_STONE_STEP,
                        SoundCategory.PLAYERS, 0.22F, 0.75F + random.nextFloat() * 0.4F);
            } else if (cue < 0.85F) {
                world.playSound(null, player.getBlockPos(), SoundEvents.AMBIENT_CAVE.value(),
                        SoundCategory.AMBIENT, 0.13F, 0.62F + random.nextFloat() * 0.5F);
            } else {
                Vec3d look = player.getRotationVec(1.0F).normalize();
                world.spawnParticles(ParticleTypes.ASH,
                        player.getX() - look.x * 3.0D, player.getY() + 0.4D,
                        player.getZ() - look.z * 3.0D, 5, 0.25D, 0.25D, 0.25D, 0.005D);
            }
            return;
        }

        if (distance > 20.0D) {
            return;
        }

        dreadEffectCooldown = WatcherHorrorDirector.nextCloseCueCooldown(random.nextInt(221));
        if (random.nextFloat() < 0.58F) {
            world.playSound(null, player.getBlockPos(), SoundEvents.AMBIENT_CAVE.value(),
                    SoundCategory.AMBIENT, 0.45F, 0.55F + random.nextFloat() * 0.55F);
        } else if (distance < 12.0D) {
            player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.DARKNESS, 35 + random.nextInt(30), 0, true, false, false));
            world.playSound(null, getBlockPos(), SoundEvents.ENTITY_ENDERMAN_STARE,
                    SoundCategory.HOSTILE, 0.3F, 0.5F + random.nextFloat() * 0.3F);
        }
    }

    private void resetToIdle() {
        setPhase(Phase.IDLE);
        setTarget(null);
        getNavigation().stop();
    }

    private void lookAtPlayer(PlayerEntity player) {
        getLookControl().lookAt(player, 30.0F, 30.0F);
    }

    private void moveAwayFrom(PlayerEntity player, double speed) {
        double dx = getX() - player.getX();
        double dz = getZ() - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);

        if (length < 0.001D) {
            dx = 1.0D;
            dz = 0.0D;
            length = 1.0D;
        }

        double destinationX = getX() + (dx / length) * 24.0D;
        double destinationZ = getZ() + (dz / length) * 24.0D;
        getNavigation().startMovingTo(destinationX, getY(), destinationZ, speed);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        learningEngine.writeToNbt(nbt);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        learningEngine.readFromNbt(nbt);
    }

    public Phase getPhase() {
        return phase;
    }

    public int getPhaseTicks() {
        return phaseTicks;
    }

    public boolean isDebugPhaseLocked() {
        return debugPhaseLocked;
    }

    public boolean isDebugSimulation() {
        return debugSimulation;
    }

    public void forceDebugPhase(Phase newPhase, PlayerEntity player) {
        if (newPhase == null || player == null) {
            return;
        }

        debugSimulation = false;
        debugPhaseLocked = true;
        debugPhaseAutoAdvance = newPhase == Phase.ESCALATING;
        phase = newPhase;
        phaseTicks = 0;
        hiddenMoveCooldown = 0;
        dreadEffectCooldown = 0;
        attentionTicks = 0;
        unseenTicks = 0;
        attemptedEscalationAttack = false;
        setTarget(player);
        getNavigation().stop();
    }

    public void startDebugSimulation(PlayerEntity player) {
        if (player == null) {
            return;
        }

        debugPhaseLocked = false;
        debugSimulation = true;
        debugPhaseAutoAdvance = false;
        phase = Phase.OBSERVING;
        phaseTicks = 0;
        hiddenMoveCooldown = 0;
        attemptedEscalationAttack = false;
        setTarget(player);
        getNavigation().stop();
    }

    public void stopDebugSimulation() {
        debugSimulation = false;
        debugPhaseLocked = false;
        debugPhaseAutoAdvance = false;
        resetToIdle();
    }

    public void resumeAutomaticPhases() {
        debugSimulation = false;
        debugPhaseLocked = false;
        debugPhaseAutoAdvance = false;
        setPhase(Phase.IDLE);
    }

    public void setPhase(Phase newPhase) {
        if (newPhase == null) {
            return;
        }

        if (phase != newPhase) {
            phase = newPhase;
            phaseTicks = 0;
            if (newPhase == Phase.ESCALATING) {
                attemptedEscalationAttack = false;
            }
        }
    }

    public static DefaultAttributeContainer.Builder createWatcherAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0D)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.23D)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 6.0D)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, DEBUG_TARGET_RANGE);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "idle", 0,
                state -> state.setAndContinue(IDLE_ANIMATION)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animationCache;
    }
}
