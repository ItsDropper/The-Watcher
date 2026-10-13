package io.github.itsdropper.thewatcher.entity;

import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.SurveillanceCameraBlockEntity;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.item.ItemStack;
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
import java.util.Properties;

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
    private static final int ESCALATE_TICKS = 400; // Keep pressure on the player for 20 seconds.
    private static final int RETREAT_TICKS = 100;
    private static final double TARGET_RANGE = 48.0D;
    private static final double DEBUG_TARGET_RANGE = 128.0D;

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    private final WatcherLearningEngine learningEngine = new WatcherLearningEngine();
    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int hiddenMoveCooldown;
    private int proximityCorrectionCooldown;
    private int dreadEffectCooldown;
    private int attentionTicks;
    private int unseenTicks;
    // Long, uninterrupted eye contact lets the Watcher advance in short, deliberate steps.
    // This creates pressure without allowing it to teleport while visible or rush unfairly.
    private int gazeApproachCooldown;
    private boolean attemptedEscalationAttack;
    private int ambushCooldown;
    private int closeJumpscareCooldown;
    private int watchedAttackCooldown;
    private int escalationAttackCooldown;
    private boolean debugPhaseLocked;
    private boolean debugSimulation;
    private boolean debugPhaseAutoAdvance;
    private boolean naturalRunEntity;
    private int navigationCheckCooldown;
    private int cameraWanderCooldown;
    private int navigationStuckChecks;
    private Vec3d lastNavigationCheckPosition;

    public WatcherEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        // The Watcher is currently immortal and persistent while encounters are being tested.
        setPersistent();
        setInvulnerable(true);
    }

    /**
     * Prevent all ordinary damage from reducing health or killing the Watcher.
     * Keep this override as well as the invulnerable entity flag so damage immunity
     * does not depend solely on the entity's saved invulnerability state.
     */
    @Override
    public boolean damage(DamageSource source, float amount) {
        if (!(source.getAttacker() instanceof PlayerEntity player) || !hasAllEvidence(player)) {
            return false;
        }

        // The evidence makes the Watcher vulnerable; before this point, ordinary attacks do nothing.
        setInvulnerable(false);
        boolean damaged = super.damage(source, amount);
        if (damaged && !isAlive() && getWorld() instanceof ServerWorld world
                && player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
            io.github.itsdropper.thewatcher.WatcherRunManager.onWatcherDefeated(world, serverPlayer);
        }
        return damaged;
    }

    public void setNaturalRunEntity(boolean naturalRunEntity) {
        this.naturalRunEntity = naturalRunEntity;
    }

    public boolean isNaturalRunEntity() {
        return naturalRunEntity;
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

        if (naturalRunEntity && io.github.itsdropper.thewatcher.WatcherRunManager.shouldDiscardNaturalEntity(world, getUuid())) {
            discard();
            return;
        }
        if (naturalRunEntity) {
            io.github.itsdropper.thewatcher.WatcherRunManager.captureLearning(world, this);
        }

        int tickScale = debugSimulation ? 4 : 1;
        hiddenMoveCooldown = Math.max(0, hiddenMoveCooldown - tickScale);
        proximityCorrectionCooldown = Math.max(0, proximityCorrectionCooldown - tickScale);
        dreadEffectCooldown = Math.max(0, dreadEffectCooldown - tickScale);
        gazeApproachCooldown = Math.max(0, gazeApproachCooldown - tickScale);
        ambushCooldown = Math.max(0, ambushCooldown - tickScale);
        closeJumpscareCooldown = Math.max(0, closeJumpscareCooldown - tickScale);
        watchedAttackCooldown = Math.max(0, watchedAttackCooldown - tickScale);
        escalationAttackCooldown = Math.max(0, escalationAttackCooldown - tickScale);

        long timeOfDay = Math.floorMod(world.getTimeOfDay(), 24000L);
        boolean night = timeOfDay >= 13000L && timeOfDay < 23000L;
        // Surveillance cameras are persistent player infrastructure; The Watcher must not
        // destroy them when their chunks unload or during normal stalking.

        if (!debugPhaseLocked && !debugSimulation && !night) {
            // Daytime is the Watcher's learning phase, not a free pass for the player.
            // It observes every tick but stays at least 200 blocks away whenever a safe,
            // unseen destination is available. No daytime attacks or close stalking.
            if (naturalRunEntity) {
                PlayerEntity daytimeTarget = world.getPlayers().stream()
                        .filter(candidate -> candidate.isAlive() && !candidate.isSpectator())
                        .min(Comparator.comparingDouble(this::squaredDistanceTo))
                        .orElse(null);
                if (daytimeTarget != null) {
                    setTarget(daytimeTarget);
                    double daytimeDistance = Math.sqrt(squaredDistanceTo(daytimeTarget));
                    learningEngine.observe(isPlayerWatching(daytimeTarget), daytimeDistance);

                    if (daytimeDistance < 200.0D) {
                        // Daytime is a hard separation rule: teleport away as soon as the
                        // Watcher gets inside 200 blocks, even if the player is looking at it.
                        if (TheWatcher.TELEPORTS_ENABLED && tryDaytimeRelocation(world, daytimeTarget)) {
                            hiddenMoveCooldown = 0;
                            proximityCorrectionCooldown = 0;
                            getNavigation().stop();
                            setVelocity(0.0D, getVelocity().y, 0.0D);
                            resetNavigationRecovery();
                        } else {
                            // If no safe destination exists in already-loaded chunks, retreat
                            // physically until a valid relocation becomes available.
                            getNavigation().stop();
                            moveAwayFrom(daytimeTarget, 2.4D);
                        }
                    } else {
                        getNavigation().stop();
                        setVelocity(0.0D, getVelocity().y, 0.0D);
                    }
                } else {
                    setTarget(null);
                    getNavigation().stop();
                }
            }
            // Do not call resetToIdle here: it would cancel the daytime withdrawal path.
            setPhase(Phase.IDLE);
            phaseTicks = 0;
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

        // Keep the Watcher's head and body oriented toward its current player target,
        // including while it is standing still in OBSERVING/STALKING.
        lookAtPlayer(player);

        boolean watched = isPlayerWatching(player);
        double distance = Math.sqrt(squaredDistanceTo(player));
        learningEngine.observe(watched, distance);
        // Player habits and proximity pressure are sampled by WatcherRunManager once per second.
        // The simulation deliberately starts close and visible; normal gameplay still enforces distance in OBSERVING.
        boolean distanceSensitivePhase = phase == Phase.STALKING
                || (phase == Phase.OBSERVING && !debugSimulation);
        long localTime = Math.floorMod(world.getTimeOfDay(), 24_000L);
        boolean nightStalking = localTime >= 13_000L && localTime < 23_000L;
        double minimumSafeDistance = Math.max(26.0D,
                WatcherStalkingDirector.minimumStalkingDistance(renderChunks)
                        - learningEngine.getLearnedApproachBonus()
                        + (nightStalking ? 7.0D : 0.0D));
        boolean tooCloseForPhase = distanceSensitivePhase && distance < minimumSafeDistance;
        boolean proximityRelocated = false;

        if (watched) {
            attentionTicks = Math.min(attentionTicks + 1, 1200);
            unseenTicks = 0;

            // Being watched still freezes the stalking movement, but it must not make the
            // Watcher harmless forever. If it has closed the gap, it can strike while visible.
            if (phase == Phase.STALKING && attentionTicks >= 100 && distance <= 3.2D
                    && watchedAttackCooldown <= 0) {
                watchedAttackCooldown = 60;
                boolean hit = tryAttack(player);
                world.playSound(null, player.getBlockPos(),
                        hit ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                        SoundCategory.HOSTILE, 0.9F, hit ? 0.65F : 0.8F);
                if (hit) {
                    player.addStatusEffect(new StatusEffectInstance(
                            StatusEffects.DARKNESS, 30, 0, false, false, true));
                }
            }

            if (phase == Phase.ESCALATING) {
                lookAtPlayer(player);
                getNavigation().startMovingTo(player, 1.2D);
                if (squaredDistanceTo(player) <= 2.9D * 2.9D && escalationAttackCooldown <= 0) {
                    // Keep attacking throughout escalation instead of permanently consuming one attempt.
                    escalationAttackCooldown = 12;
                    boolean hit = tryAttack(player);
                    world.playSound(null, player.getBlockPos(),
                            hit ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                            SoundCategory.HOSTILE, hit ? 0.95F : 0.55F, hit ? 0.65F : 0.9F);
                    if (hit) {
                        player.addStatusEffect(new StatusEffectInstance(
                                StatusEffects.DARKNESS, 35, 0, false, false, true));
                    }
                }
            }

            if (phase == Phase.ESCALATING) {
                // Do not apply the stalking freeze/retreat rules during an active attack.
                // The Watcher must keep pathing toward the player even while being watched.
            } else if (tooCloseForPhase) {
                // A nearby Watcher must not freeze in the player's face during stalking.
                moveAwayFrom(player, 1.65D);
                recoverStuckRetreat(world, player);
            } else if (phase == Phase.STALKING && attentionTicks >= 80) {
                // It used to freeze indefinitely when watched, which made eye contact a perfect
                // safety button. Now, after four seconds of uninterrupted direct eye contact,
                // it advances slowly while visible. Each advance is short and separated by a
                // pause; it never teleports in view and still withdraws if it gets too close.
                if (gazeApproachCooldown <= 0 && getNavigation().isIdle()) {
                    getNavigation().startMovingTo(player, 0.32D);
                    gazeApproachCooldown = 80 + random.nextInt(41);
                    if (random.nextFloat() < 0.45F) {
                        world.playSound(null, getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                                SoundCategory.HOSTILE, 0.22F, 0.55F + random.nextFloat() * 0.18F);
                    }
                }
            } else {
                getNavigation().stop();
                setVelocity(0.0D, getVelocity().y, 0.0D);
            }
            if (phase != Phase.IDLE) tryDreadEffect(world, player);
            return;
        }

        // Only the STALKING phase may teleport. During OBSERVING, proximity is corrected
        // by walking away, keeping the Watcher's movement visible and less aggressive.
        if (tooCloseForPhase && (proximityCorrectionCooldown <= 0 || distance < 24.0D)) {
            if (phase == Phase.STALKING) {
                proximityRelocated = TheWatcher.TELEPORTS_ENABLED
                        && (hiddenMoveCooldown <= 0 || distance < 24.0D)
                        && !isWithinPlayerView(player)
                        && tryHiddenTeleport(world, player, false);
            }
            if (proximityRelocated) {
                hiddenMoveCooldown = 240 + random.nextInt(241);
                proximityCorrectionCooldown = 80 + random.nextInt(61);
                getNavigation().stop();
                resetNavigationRecovery();
            } else {
                moveAwayFrom(player, 1.8D);
                proximityCorrectionCooldown = 20 + random.nextInt(21);
            }
        }

        // A watched entity's phase timer pauses; it should not escalate just because the player stares.
        phaseTicks = Math.min(phaseTicks + tickScale, 72000);
        unseenTicks = Math.min(unseenTicks + tickScale, 1200);
        attentionTicks = Math.max(0, attentionTicks - 2);
        if (phase != Phase.IDLE) tryDreadEffect(world, player);

        switch (phase) {
            case IDLE -> setPhase(Phase.OBSERVING);
            case OBSERVING -> {
                // While unobserved, the Watcher patrols around nearby surveillance cameras.
                // It does not teleport in this phase; its movements should be physically traceable.
                if (tooCloseForPhase && !proximityRelocated) {
                    if (getNavigation().isIdle()) moveAwayFrom(player, 1.8D);
                } else {
                    wanderNearCamera(world);
                }
                int duration = OBSERVE_TICKS;
                if ((!debugPhaseLocked || debugPhaseAutoAdvance) && phaseTicks >= duration) {
                    setPhase(Phase.STALKING);
                }
            }
            case STALKING -> {
                // Rare close ambush: it only happens after the player has lost sight of the Watcher.
                // A safe position behind the player creates a deliberate jump scare instead of a random teleport.
                if (ambushCooldown <= 0 && unseenTicks >= 80 && distance >= 7.0D && distance <= 22.0D
                        && random.nextInt(100) < 2 && tryAmbushLunge(world, player)) {
                    ambushCooldown = 1_200 + random.nextInt(1_201);
                    closeJumpscareCooldown = Math.max(closeJumpscareCooldown, 100);
                    phase = Phase.ESCALATING;
                    phaseTicks = 0;
                    attemptedEscalationAttack = false;
                    player.addStatusEffect(new StatusEffectInstance(
                            StatusEffects.DARKNESS, 45, 0, false, false, true));
                    player.addStatusEffect(new StatusEffectInstance(
                            StatusEffects.SLOWNESS, 35, 0, false, false, true));
                    world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_SCREAM,
                            SoundCategory.HOSTILE, 0.95F, 0.65F + random.nextFloat() * 0.25F);
                    world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                            SoundCategory.HOSTILE, 0.75F, 0.7F);
                    return;
                }

                // A second scare style: a brief, close-range blackout and an abrupt sound cue.
                if (closeJumpscareCooldown <= 0 && unseenTicks >= 40 && distance < 11.0D
                        && random.nextInt(100) < 3) {
                    closeJumpscareCooldown = 900 + random.nextInt(901);
                    player.addStatusEffect(new StatusEffectInstance(
                            StatusEffects.DARKNESS, 25 + random.nextInt(25), 0, false, false, true));
                    world.playSound(null, player.getBlockPos(),
                            random.nextBoolean() ? SoundEvents.ENTITY_ENDERMAN_STARE : SoundEvents.ENTITY_GHAST_WARN,
                            SoundCategory.HOSTILE, 0.8F, 0.55F + random.nextFloat() * 0.35F);
                    if (distance < 5.0D) {
                        player.damage(world.getDamageSources().mobAttack(this), 2.0F);
                    }
                }

                // Stillness is part of the scare, but the Watcher keeps its distance first.
                if (!tooCloseForPhase || proximityRelocated) {
                    getNavigation().stop();
                    setVelocity(0.0D, getVelocity().y, 0.0D);
                } else if (getNavigation().isIdle()) {
                    moveAwayFrom(player, 1.8D);
                }

                int baseTeleportChance = 32;
                int teleportChance = learningEngine.adjustTeleportChance(baseTeleportChance);
                boolean relocationRoll = random.nextInt(100) < teleportChance;
                if (!tooCloseForPhase && TheWatcher.TELEPORTS_ENABLED && hiddenMoveCooldown <= 0
                        && unseenTicks >= learningEngine.getHiddenTeleportDelayTicks() && relocationRoll) {
                    boolean teleported = tryHiddenTeleport(world, player, false);
                    // Failed candidate searches retry quickly; successful relocations get a longer quiet period.
                    // Natural sightings should be rare enough to feel discovered, not constantly refreshed.
                    hiddenMoveCooldown = teleported ? 240 + random.nextInt(361) : 20 + random.nextInt(31);
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

                if (squaredDistanceTo(player) <= 2.9D * 2.9D && escalationAttackCooldown <= 0) {
                    // A short attack interval allows repeated real melee damage while in range.
                    escalationAttackCooldown = 12;
                    boolean hit = tryAttack(player);
                    world.playSound(null, player.getBlockPos(),
                            hit ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                            SoundCategory.HOSTILE, hit ? 0.95F : 0.55F, hit ? 0.65F : 0.9F);
                    if (hit) {
                        player.addStatusEffect(new StatusEffectInstance(
                                StatusEffects.DARKNESS, 35, 0, false, false, true));
                    }
                }

                int duration = debugPhaseAutoAdvance ? 45 : ESCALATE_TICKS;
                if ((!debugPhaseLocked || debugPhaseAutoAdvance) && phaseTicks >= duration) {
                    setPhase(Phase.RETREATING);
                }
            }
            case RETREATING -> {
                // Retreating is physical movement, not a teleport phase.
                lookAtPlayer(player);
                moveAwayFrom(player, 2.2D);
                recoverStuckRetreat(world, player);

                int duration = RETREAT_TICKS;
                if ((!debugPhaseLocked || debugPhaseAutoAdvance) && phaseTicks >= duration) {
                    boolean finishingDebugAutoAdvance = debugPhaseAutoAdvance;
                    setPhase(Phase.OBSERVING);
                    if (finishingDebugAutoAdvance) {
                        // Only the explicit debug auto-test should lock after one full cycle.
                        // Natural encounters must remain free to progress into stalking again.
                        debugPhaseAutoAdvance = false;
                        debugPhaseLocked = true;
                    }
                }
            }
        }
    }

    /**
     * Daytime withdrawal uses a much wider ring than ordinary night stalking.
     * It only selects already-loaded, safe terrain and never relocates while visible.
     */
    private boolean tryDaytimeRelocation(ServerWorld world, PlayerEntity player) {
        double bestScore = -Double.MAX_VALUE;
        BlockPos best = null;

        for (int i = 0; i < 160; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius = 208.0D + random.nextDouble() * 72.0D;
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos ground = feet.down();
            if (!world.getBlockState(ground).isSolidBlock(world, ground)
                    || isUnsafeGround(world.getBlockState(ground))
                    || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                    || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                    || !world.getBlockState(feet).getFluidState().isEmpty()
                    || !world.getBlockState(feet.up()).getFluidState().isEmpty()
                    || !world.isSpaceEmpty(this, getBoundingBox().offset(
                            x + 0.5D - getX(), y - getY(), z + 0.5D - getZ()))) {
                continue;
            }

            // Destination visibility is intentionally irrelevant in daytime: the goal is
            // immediate separation, not a hidden stalking reposition.
            double distance = Math.sqrt(player.getBlockPos().getSquaredDistance(feet));
            if (distance < 200.0D) continue;
            double score = distance + random.nextDouble() * 20.0D;
            if (score > bestScore) {
                bestScore = score;
                best = feet;
            }
        }

        if (best == null) return false;
        requestTeleport(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D);
        lookAtPlayer(player);
        return true;
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
        double bestScore = -Double.MAX_VALUE;
        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        boolean found = false;

        for (int i = 0; i < attempts; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius;
            if (retreat) {
                radius = Math.max(30.0D, Math.min(52.0D,
                        WatcherStalkingDirector.minimumStalkingDistance(renderChunks) * 0.72D))
                        + random.nextDouble() * 10.0D;
            } else {
                radius = WatcherStalkingDirector.candidateRadius(renderChunks, random.nextDouble(),
                        -learningEngine.getLearnedApproachBonus());
                long time = Math.floorMod(world.getTimeOfDay(), 24_000L);
                boolean night = time >= 13_000L && time < 23_000L;
                if (night) {
                    // Most night sightings stay near the outer edge; rarely, the Watcher
                    // breaks that pattern and appears a little closer.
                    if (random.nextInt(100) < 12) {
                        radius = Math.max(30.0D, radius - 14.0D);
                    } else {
                        radius = Math.min(WatcherStalkingDirector.renderDistanceBlocks(renderChunks) - 3.0D,
                                radius + 8.0D);
                    }
                }
            }
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * radius);

            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }

            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos ground = feet.down();

            if (!(world.getBlockState(ground).isSolidBlock(world, ground)
                    || world.getBlockState(ground).isIn(BlockTags.LEAVES))
                    || isUnsafeGround(world.getBlockState(ground))
                    || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                    || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                    || !world.getBlockState(feet).getFluidState().isEmpty()
                    || !world.getBlockState(feet.up()).getFluidState().isEmpty()) {
                continue;
            }

            double offsetX = x + 0.5D - getX();
            double offsetY = y - getY();
            double offsetZ = z + 0.5D - getZ();
            if (!world.isSpaceEmpty(this, getBoundingBox().offset(offsetX, offsetY, offsetZ))) {
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
            if (sightCheck.getType() == BlockHitResult.Type.MISS && destinationInView) {
                continue;
            }

            // Retreats should be immediate. Stalking relocations compare every safe
            // candidate and favor ridgelines, hilltops, and positions above the player.
            if (retreat) {
                requestTeleport(x + 0.5D, y, z + 0.5D);
                getNavigation().stop();
                return true;
            }

            double elevation = Math.max(-16.0D, Math.min(24.0D, y - player.getY()));
            int surroundingHeight = 0;
            int heightSamples = 0;
            int[][] sampleOffsets = {{8, 0}, {-8, 0}, {0, 8}, {0, -8}};
            for (int[] sampleOffset : sampleOffsets) {
                int sx = x + sampleOffset[0];
                int sz = z + sampleOffset[1];
                if (world.isChunkLoaded(sx >> 4, sz >> 4)) {
                    surroundingHeight += world.getTopY(Heightmap.Type.MOTION_BLOCKING, sx, sz);
                    heightSamples++;
                }
            }
            double prominence = heightSamples == 0 ? 0.0D
                    : y - (surroundingHeight / (double) heightSamples);
            double learnedHeightBias = Math.max(-6.0D, Math.min(6.0D,
                    (y - learningEngine.getAveragePlayerHeight()) * 0.15D));
            double score = elevation * 0.8D + prominence * 1.6D + learnedHeightBias
                    + random.nextDouble() * 4.0D;
            if (score > bestScore) {
                bestScore = score;
                bestX = x;
                bestY = y;
                bestZ = z;
                found = true;
            }
        }

        if (!found) {
            return false;
        }
        requestTeleport(bestX + 0.5D, bestY, bestZ + 0.5D);
        getNavigation().stop();
        return true;
    }

    /**
     * Moves the Watcher behind an unwary player for a rare ambush.
     * The destination must be loaded, safe, and outside the player's current view cone.
     */
    private boolean tryAmbushLunge(ServerWorld world, PlayerEntity player) {
        if (isWithinPlayerView(player)) return false;
        Vec3d look = player.getRotationVec(1.0F).normalize();
        double side = random.nextBoolean() ? 0.65D : -0.65D;
        int x = (int) Math.floor(player.getX() - look.x * (3.2D + random.nextDouble() * 1.4D)
                - look.z * side);
        int z = (int) Math.floor(player.getZ() - look.z * (3.2D + random.nextDouble() * 1.4D)
                + look.x * side);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos ground = feet.down();
        if (!world.getBlockState(ground).isSolidBlock(world, ground)
                || isUnsafeGround(world.getBlockState(ground))
                || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                || !world.getBlockState(feet).getFluidState().isEmpty()
                || !world.getBlockState(feet.up()).getFluidState().isEmpty()) {
            return false;
        }
        Vec3d eyes = new Vec3d(x + 0.5D, y + 1.4D, z + 0.5D);
        Vec3d direction = eyes.subtract(player.getEyePos()).normalize();
        if (player.getRotationVec(1.0F).normalize().dotProduct(direction) > 0.0D) return false;
        if (!world.isSpaceEmpty(this, getBoundingBox().offset(
                x + 0.5D - getX(), y - getY(), z + 0.5D - getZ()))) return false;
        requestTeleport(x + 0.5D, y, z + 0.5D);
        getNavigation().stop();
        lookAtPlayer(player);
        return true;
    }

    private boolean hasAllEvidence(PlayerEntity player) {
        boolean tower = false, cabin = false, shrine = false, expedition = false;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty() || !stack.isOf(net.minecraft.item.Items.PAPER)) continue;
            String name = stack.getName().getString();
            tower |= name.equals("Evidence: Tower");
            cabin |= name.equals("Evidence: Cabin");
            shrine |= name.equals("Evidence: Shrine");
            expedition |= name.equals("Evidence: Expedition");
        }
        return tower && cabin && shrine && expedition;
    }

    private boolean isUnsafeGround(net.minecraft.block.BlockState state) {
        return state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.WITHER_ROSE)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.POWDER_SNOW);
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
            Vec3d look = player.getRotationVec(1.0F).normalize();
            if (cue < 0.50F) {
                BlockPos behind = BlockPos.ofFloored(player.getX() - look.x * 3.0D,
                        player.getY(), player.getZ() - look.z * 3.0D);
                world.playSound(null, behind, SoundEvents.BLOCK_STONE_STEP,
                        SoundCategory.PLAYERS, 0.22F, 0.75F + random.nextFloat() * 0.4F);
            } else if (cue < 0.85F) {
                world.playSound(null, player.getBlockPos(), SoundEvents.AMBIENT_CAVE.value(),
                        SoundCategory.AMBIENT, 0.13F, 0.62F + random.nextFloat() * 0.5F);
            } else {
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

        // Do not rebuild the same path every tick: that can reset navigation progress
        // and make the Watcher appear permanently stuck during retreat.
        if (!getNavigation().isIdle()) {
            return;
        }

        double destinationX = getX() + (dx / length) * 24.0D;
        double destinationZ = getZ() + (dz / length) * 24.0D;
        getNavigation().startMovingTo(destinationX, getY(), destinationZ, speed);
    }

    private void wanderNearCamera(ServerWorld world) {
        if (cameraWanderCooldown > 0) {
            cameraWanderCooldown--;
            return;
        }
        cameraWanderCooldown = 40 + random.nextInt(61);

        SurveillanceCameraBlockEntity nearest = null;
        double nearestDistance = 64.0D * 64.0D;
        int chunkX = getBlockPos().getX() >> 4;
        int chunkZ = getBlockPos().getZ() >> 4;
        for (int cx = chunkX - 4; cx <= chunkX + 4; cx++) {
            for (int cz = chunkZ - 4; cz <= chunkZ + 4; cz++) {
                net.minecraft.world.chunk.WorldChunk chunk =
                        world.getChunkManager().getWorldChunk(cx, cz, false);
                if (chunk == null) continue;
                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof SurveillanceCameraBlockEntity camera)) continue;
                    double distance = camera.getPos().getSquaredDistance(getBlockPos());
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = camera;
                    }
                }
            }
        }

        if (!getNavigation().isIdle()) return;
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double radius = nearest == null ? 8.0D + random.nextDouble() * 12.0D
                : 5.0D + random.nextDouble() * 11.0D;
        double anchorX = nearest == null ? getX() : nearest.getPos().getX() + 0.5D;
        double anchorZ = nearest == null ? getZ() : nearest.getPos().getZ() + 0.5D;
        int x = (int) Math.floor(anchorX + Math.cos(angle) * radius);
        int z = (int) Math.floor(anchorZ + Math.sin(angle) * radius);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos ground = feet.down();
        if (!world.getBlockState(ground).isSolidBlock(world, ground)
                || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                || !world.getBlockState(feet).getFluidState().isEmpty()
                || !world.getBlockState(feet.up()).getFluidState().isEmpty()) {
            return;
        }
        getNavigation().startMovingTo(x + 0.5D, y, z + 0.5D, 0.85D);
    }

    private void recoverStuckRetreat(ServerWorld world, PlayerEntity player) {
        if (navigationCheckCooldown > 0) {
            navigationCheckCooldown--;
            return;
        }
        navigationCheckCooldown = 20;

        Vec3d current = getPos();
        if (lastNavigationCheckPosition != null
                && current.squaredDistanceTo(lastNavigationCheckPosition) < 0.75D * 0.75D) {
            navigationStuckChecks++;
        } else {
            navigationStuckChecks = 0;
        }
        lastNavigationCheckPosition = current;

        if (navigationStuckChecks < 3) {
            return;
        }

        getNavigation().stop();
        boolean recovered = phase == Phase.STALKING
                && TheWatcher.TELEPORTS_ENABLED
                && !isWithinPlayerView(player)
                && tryHiddenTeleport(world, player, true);
        if (recovered) {
            hiddenMoveCooldown = 100;
        } else {
            // A new direction avoids repeatedly requesting the same blocked route.
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = 12.0D + random.nextDouble() * 12.0D;
            getNavigation().startMovingTo(
                    getX() + Math.cos(angle) * distance,
                    getY(),
                    getZ() + Math.sin(angle) * distance,
                    2.2D);
        }
        navigationStuckChecks = 0;
        lastNavigationCheckPosition = getPos();
        navigationCheckCooldown = 20;
    }

    private void resetNavigationRecovery() {
        navigationCheckCooldown = 0;
        navigationStuckChecks = 0;
        lastNavigationCheckPosition = null;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        boolean debugEncounter = debugPhaseLocked || debugSimulation;
        nbt.putString("WatcherPhase", (debugEncounter ? Phase.IDLE : phase).name());
        nbt.putInt("WatcherPhaseTicks", debugEncounter ? 0 : phaseTicks);
        nbt.putInt("WatcherHiddenMoveCooldown", hiddenMoveCooldown);
        nbt.putInt("WatcherProximityCorrectionCooldown", proximityCorrectionCooldown);
        nbt.putInt("WatcherDreadEffectCooldown", dreadEffectCooldown);
        nbt.putInt("WatcherAttentionTicks", attentionTicks);
        nbt.putInt("WatcherUnseenTicks", unseenTicks);
        nbt.putInt("WatcherGazeApproachCooldown", gazeApproachCooldown);
        nbt.putBoolean("WatcherAttemptedEscalationAttack", attemptedEscalationAttack);
        nbt.putInt("WatcherEscalationAttackCooldown", escalationAttackCooldown);
        nbt.putBoolean("WatcherNaturalRunEntity", naturalRunEntity);
        learningEngine.writeToNbt(nbt);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("WatcherPhase")) {
            try {
                phase = Phase.valueOf(nbt.getString("WatcherPhase"));
            } catch (IllegalArgumentException ignored) {
                phase = Phase.IDLE;
            }
        } else {
            phase = Phase.IDLE;
        }
        phaseTicks = Math.max(0, nbt.getInt("WatcherPhaseTicks"));
        hiddenMoveCooldown = Math.max(0, nbt.getInt("WatcherHiddenMoveCooldown"));
        proximityCorrectionCooldown = Math.max(0, nbt.getInt("WatcherProximityCorrectionCooldown"));
        dreadEffectCooldown = Math.max(0, nbt.getInt("WatcherDreadEffectCooldown"));
        attentionTicks = Math.max(0, Math.min(1200, nbt.getInt("WatcherAttentionTicks")));
        unseenTicks = Math.max(0, Math.min(1200, nbt.getInt("WatcherUnseenTicks")));
        gazeApproachCooldown = Math.max(0, nbt.getInt("WatcherGazeApproachCooldown"));
        attemptedEscalationAttack = nbt.getBoolean("WatcherAttemptedEscalationAttack");
        escalationAttackCooldown = Math.max(0, nbt.getInt("WatcherEscalationAttackCooldown"));
        naturalRunEntity = nbt.getBoolean("WatcherNaturalRunEntity");
        // Debug controls are session-only; never reload an entity stuck in a forced test phase.
        debugPhaseLocked = false;
        debugSimulation = false;
        debugPhaseAutoAdvance = false;
        resetNavigationRecovery();
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

    /**
     * Shared run-manager sample. The manager calls this once per second so learning remains
     * current even when the entity is outside the ticking area during daytime.
     */
    public void observePlayerForLearning(PlayerEntity player) {
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        double distance = Math.sqrt(squaredDistanceTo(player));
        int renderChunks = WatcherStalkingDirector.renderDistanceChunks(
                TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS);
        learningEngine.observe(isPlayerWatching(player), distance);
        learningEngine.observePlayerBehavior(player, 20);
        learningEngine.observeStalkingDistance(distance, renderChunks);
    }

    public double getLearnedSafetyDistanceBonus(int renderChunks) {
        return learningEngine.getLearnedSafetyDistanceBonus(renderChunks);
    }

    public double getLearnedApproachBonus() {
        return learningEngine.getLearnedApproachBonus();
    }

    public void writeLearningToProperties(Properties properties, String prefix) {
        learningEngine.writeToProperties(properties, prefix);
    }

    public void readLearningFromProperties(Properties properties, String prefix) {
        learningEngine.readFromProperties(properties, prefix);
    }

    public double getAverageHorizontalSpeed() {
        return learningEngine.getAverageHorizontalSpeed();
    }

    public double getAverageSprintRatio() {
        return learningEngine.getAverageSprintRatio();
    }

    public double getProximityPressure() {
        return learningEngine.getProximityPressure();
    }

    /**
     * Runs a specific attack on demand for operator testing.
     * Returns false only when the requested attack name is not recognized.
     */
    public boolean debugAttack(String attack, PlayerEntity player) {
        if (player == null || !(getWorld() instanceof ServerWorld world)) return false;
        debugSimulation = false;
        debugPhaseLocked = true;
        debugPhaseAutoAdvance = false;
        setTarget(player);

        switch (attack) {
            case "melee" -> {
                // Put the entity within melee range so the command tests the actual attack, not pathfinding.
                refreshPositionAndAngles(player.getX() + 1.4D, player.getY(), player.getZ(),
                        player.getYaw() + 180.0F, 0.0F);
                lookAtPlayer(player);
                boolean hit = tryAttack(player);
                world.playSound(null, player.getBlockPos(),
                        hit ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                        SoundCategory.HOSTILE, 1.0F, hit ? 0.7F : 0.8F);
                return true;
            }
            case "rush" -> {
                phase = Phase.ESCALATING;
                phaseTicks = 0;
                attemptedEscalationAttack = false;
                escalationAttackCooldown = 0;
                getNavigation().stop();
                getNavigation().startMovingTo(player, 2.8D);
                world.playSound(null, getBlockPos(), SoundEvents.ENTITY_WARDEN_ROAR,
                        SoundCategory.HOSTILE, 1.0F, 0.7F);
                return true;
            }
            case "ambush" -> {
                Vec3d look = player.getRotationVec(1.0F).normalize();
                int x = (int) Math.floor(player.getX() - look.x * 3.0D - look.z * 0.7D);
                int z = (int) Math.floor(player.getZ() - look.z * 3.0D + look.x * 0.7D);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                BlockPos feet = new BlockPos(x, y, z);
                BlockPos ground = feet.down();
                if (!world.getBlockState(ground).isSolidBlock(world, ground)
                        || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                        || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
                    return false;
                }
                requestTeleport(x + 0.5D, y, z + 0.5D);
                phase = Phase.ESCALATING;
                phaseTicks = 0;
                attemptedEscalationAttack = false;
                escalationAttackCooldown = 0;
                player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.DARKNESS, 50, 0, false, false, true));
                player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.SLOWNESS, 35, 0, false, false, true));
                world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_SCREAM,
                        SoundCategory.HOSTILE, 1.0F, 0.65F);
                world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                        SoundCategory.HOSTILE, 0.9F, 0.65F);
                world.spawnParticles(ParticleTypes.ASH, player.getX(), player.getEyeY(), player.getZ(),
                        32, 0.7D, 0.55D, 0.7D, 0.03D);
                return true;
            }
            case "jumpscare" -> {
                player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.DARKNESS, 55, 0, false, false, true));
                world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_SCREAM,
                        SoundCategory.HOSTILE, 1.0F, 0.55F);
                world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                        SoundCategory.HOSTILE, 1.0F, 0.6F);
                world.spawnParticles(ParticleTypes.ASH, player.getX(), player.getEyeY(), player.getZ(),
                        40, 0.8D, 0.6D, 0.8D, 0.035D);
                return true;
            }
            case "blackout" -> {
                player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.DARKNESS, 80, 0, false, false, true));
                world.playSound(null, player.getBlockPos(),
                        random.nextBoolean() ? SoundEvents.ENTITY_GHAST_WARN : SoundEvents.ENTITY_ENDERMAN_STARE,
                        SoundCategory.HOSTILE, 1.0F, 0.5F);
                world.spawnParticles(ParticleTypes.SMOKE, player.getX(), player.getEyeY(), player.getZ(),
                        24, 0.55D, 0.5D, 0.55D, 0.015D);
                return true;
            }
            default -> {
                return false;
            }
        }
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
        proximityCorrectionCooldown = 0;
        dreadEffectCooldown = 0;
        attentionTicks = 0;
        unseenTicks = 0;
        attemptedEscalationAttack = false;
        escalationAttackCooldown = 0;
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
        proximityCorrectionCooldown = 0;
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
                escalationAttackCooldown = 0;
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