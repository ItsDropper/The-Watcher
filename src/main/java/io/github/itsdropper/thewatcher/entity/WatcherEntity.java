package io.github.itsdropper.thewatcher.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.util.math.Vec3d;
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
    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int hiddenMoveCooldown;
    private int dreadEffectCooldown;
    private int attentionTicks;
    private int unseenTicks;
    private boolean attemptedEscalationAttack;
    private boolean debugPhaseLocked;
    private boolean debugSimulation;

    public WatcherEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void initGoals() {
        // No vanilla combat/target goals: the Watcher is controlled by its own state machine.
        this.goalSelector.add(0, new SwimGoal(this));
        // No look-around goal: The Watcher is deliberately unnaturally still.
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getWorld().isClient() || !(this.getWorld() instanceof ServerWorld world)) {
            return;
        }

        if (hiddenMoveCooldown > 0) hiddenMoveCooldown--;
        if (dreadEffectCooldown > 0) dreadEffectCooldown--;

        long timeOfDay = Math.floorMod(world.getTimeOfDay(), 24000L);
        boolean night = timeOfDay >= 13000L && timeOfDay < 23000L;

        // Debug simulation is a complete, accelerated encounter that works during daytime.
        if (!debugPhaseLocked && !debugSimulation && !night) {
            resetToIdle();
            return;
        }

        PlayerEntity player = null;
        if ((debugPhaseLocked || debugSimulation) && getTarget() instanceof PlayerEntity current
                && current.isAlive() && !current.isSpectator()) {
            player = current;
        }

        double targetRange = (debugPhaseLocked || debugSimulation) ? DEBUG_TARGET_RANGE : TARGET_RANGE;
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
        phaseTicks++;

        boolean watched = isPlayerWatching(player);
        if (watched) {
            attentionTicks = Math.min(attentionTicks + 1, 1200);
            unseenTicks = 0;
            getNavigation().stop();
            setVelocity(0.0D, getVelocity().y, 0.0D);
            if (phase != Phase.IDLE) tryDreadEffect(world, player);
            return;
        }

        unseenTicks = Math.min(unseenTicks + 1, 1200);
        attentionTicks = Math.max(0, attentionTicks - 2);
        if (phase != Phase.IDLE) tryDreadEffect(world, player);

        switch (phase) {
            case IDLE -> setPhase(Phase.OBSERVING);
            case OBSERVING -> {
                lookAtPlayer(player);
                getNavigation().stop();
                int duration = debugSimulation ? 50 : OBSERVE_TICKS;
                if (!debugPhaseLocked && phaseTicks >= duration) {
                    setPhase(Phase.STALKING);
                }
            }
            case STALKING -> {
                lookAtPlayer(player);
                boolean teleported = false;
                int attentionPenalty = Math.min(attentionTicks / 20, 10);
                int teleportChance = Math.max(8, 28 - attentionPenalty * 2);
                if (hiddenMoveCooldown <= 0 && unseenTicks >= 8 && random.nextInt(100) < teleportChance) {
                    teleported = tryHiddenTeleport(world, player, false);
                    hiddenMoveCooldown = (debugSimulation ? 16 : 35) + random.nextInt(46);
                    if (teleported) {
                        world.playSound(null, getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                                SoundCategory.HOSTILE, 0.65F, 0.55F + random.nextFloat() * 0.35F);
                        getNavigation().stop();
                    }
                }
                if (!teleported && squaredDistanceTo(player) > 4.0D * 4.0D) {
                    getNavigation().startMovingTo(player, attentionTicks > 100 ? 1.85D : 1.55D);
                } else {
                    getNavigation().stop();
                }

                int duration = debugSimulation ? 180 : STALK_TICKS;
                if (!debugPhaseLocked && phaseTicks >= duration) {
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

                int duration = debugSimulation ? 100 : ESCALATE_TICKS;
                if (!debugPhaseLocked && phaseTicks >= duration) {
                    setPhase(Phase.RETREATING);
                }
            }
            case RETREATING -> {
                lookAtPlayer(player);
                boolean teleported = false;
                if (hiddenMoveCooldown <= 0 && random.nextInt(100) < 62) {
                    teleported = tryHiddenTeleport(world, player, true);
                    hiddenMoveCooldown = 8 + random.nextInt(15);
                    if (teleported) {
                        world.playSound(null, getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                                SoundCategory.HOSTILE, 0.8F, 0.45F + random.nextFloat() * 0.25F);
                    }
                }
                if (!teleported) moveAwayFrom(player, 2.2D);
                else getNavigation().stop();

                int duration = debugSimulation ? 50 : RETREAT_TICKS;
                if (!debugPhaseLocked && phaseTicks >= duration) {
                    setPhase(Phase.OBSERVING);
                }
            }
        }
    }

    /**
     * Teleports only when the Watcher itself is already out of sight, and only to a
     * grounded candidate with solid cover between the player and the destination.
     */
    private boolean tryHiddenTeleport(ServerWorld world, PlayerEntity player, boolean retreat) {
        if (isPlayerWatching(player)) {
            return false;
        }

        int attempts = 14;
        for (int i = 0; i < attempts; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double minRadius = retreat ? 20.0D : 7.0D;
            double maxRadius = retreat ? 32.0D : 16.0D;
            double radius = minRadius + random.nextDouble() * (maxRadius - minRadius);
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
            if (sightCheck.getType() == BlockHitResult.Type.MISS) {
                continue;
            }

            requestTeleport(x + 0.5D, y, z + 0.5D);
            getNavigation().stop();
            lookAtPlayer(player);
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

    private void tryDreadEffect(ServerWorld world, PlayerEntity player) {
        if (dreadEffectCooldown > 0 || phase == Phase.IDLE || squaredDistanceTo(player) > 20.0D * 20.0D) return;
        dreadEffectCooldown = 100 + random.nextInt(220);
        if (random.nextFloat() < 0.58F) {
            world.playSound(null, player.getBlockPos(), SoundEvents.AMBIENT_CAVE,
                    SoundCategory.AMBIENT, 0.45F, 0.55F + random.nextFloat() * 0.55F);
        } else if (squaredDistanceTo(player) < 12.0D * 12.0D) {
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
        resetToIdle();
    }

    public void resumeAutomaticPhases() {
        debugSimulation = false;
        debugPhaseLocked = false;
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
