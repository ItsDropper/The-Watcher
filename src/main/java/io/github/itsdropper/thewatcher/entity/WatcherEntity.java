package io.github.itsdropper.thewatcher.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
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
import net.minecraft.util.math.Vec3d;
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
    private boolean attemptedEscalationAttack;
    private boolean debugPhaseLocked;

    public WatcherEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void initGoals() {
        // No vanilla combat/target goals: the Watcher is controlled by its own state machine.
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(5, new LookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getWorld().isClient() || !(this.getWorld() instanceof ServerWorld world)) {
            return;
        }

        long timeOfDay = Math.floorMod(world.getTimeOfDay(), 24000L);
        boolean night = timeOfDay >= 13000L && timeOfDay < 23000L;

        // Debug-forced phases deliberately work during daytime and outside the normal range.
        if (!debugPhaseLocked && !night) {
            resetToIdle();
            return;
        }

        PlayerEntity player = null;
        if (debugPhaseLocked && getTarget() instanceof PlayerEntity current
                && current.isAlive() && !current.isSpectator()) {
            player = current;
        }

        double targetRange = debugPhaseLocked ? DEBUG_TARGET_RANGE : TARGET_RANGE;
        if (player == null) {
            player = world.getPlayers().stream()
                    .filter(candidate -> candidate.isAlive() && !candidate.isSpectator())
                    .filter(candidate -> squaredDistanceTo(candidate) <= targetRange * targetRange)
                    .min(Comparator.comparingDouble(this::squaredDistanceTo))
                    .orElse(null);
        }

        if (player == null) {
            if (!debugPhaseLocked) {
                resetToIdle();
            } else {
                getNavigation().stop();
            }
            return;
        }

        setTarget(player);
        phaseTicks++;

        if (phase != Phase.IDLE && squaredDistanceTo(player) <= 24.0D * 24.0D
                && world.getTime() % 200L == 0L) {
            // A rare, spatially located cue makes the entity feel present without constant noise.
            world.playSound(null, getBlockPos(), SoundEvents.ENTITY_ENDERMAN_STARE,
                    SoundCategory.HOSTILE, 0.4F, 0.65F + random.nextFloat() * 0.25F);

            if (squaredDistanceTo(player) <= 18.0D * 18.0D) {
                player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.DARKNESS, 45, 0, true, false, false));
            }
        }

        switch (phase) {
            case IDLE -> setPhase(Phase.OBSERVING);
            case OBSERVING -> {
                lookAtPlayer(player);
                getNavigation().stop();
                if (!debugPhaseLocked && phaseTicks >= OBSERVE_TICKS) {
                    setPhase(Phase.STALKING);
                }
            }
            case STALKING -> {
                lookAtPlayer(player);
                // The Watcher advances while it is not being watched, then freezes when noticed.
                if (!isPlayerLookingAtWatcher(player)
                        && squaredDistanceTo(player) > 5.5D * 5.5D) {
                    getNavigation().startMovingTo(player, 0.55D);
                } else {
                    getNavigation().stop();
                }
                if (!debugPhaseLocked && phaseTicks >= STALK_TICKS) {
                    setPhase(Phase.ESCALATING);
                }
            }
            case ESCALATING -> {
                lookAtPlayer(player);
                getNavigation().startMovingTo(player, 0.82D);

                // One attack attempt per escalation; no ordinary continuous melee loop.
                if (!attemptedEscalationAttack && squaredDistanceTo(player) <= 2.6D * 2.6D) {
                    attemptedEscalationAttack = true;
                    tryAttack(player);
                }

                if (!debugPhaseLocked && phaseTicks >= ESCALATE_TICKS) {
                    setPhase(Phase.RETREATING);
                }
            }
            case RETREATING -> {
                if (phaseTicks == 1) {
                    moveAwayFrom(player);
                }
                lookAtPlayer(player);
                if (!debugPhaseLocked && phaseTicks >= RETREAT_TICKS) {
                    setPhase(Phase.OBSERVING);
                }
            }
        }
    }

    private boolean isPlayerLookingAtWatcher(PlayerEntity player) {
        Vec3d directionToWatcher = new Vec3d(
                getX() - player.getX(),
                getEyeY() - player.getEyeY(),
                getZ() - player.getZ()
        ).normalize();

        return player.getRotationVec(1.0F).dotProduct(directionToWatcher) > 0.92D;
    }

    private void resetToIdle() {
        setPhase(Phase.IDLE);
        setTarget(null);
        getNavigation().stop();
    }

    private void lookAtPlayer(PlayerEntity player) {
        getLookControl().lookAt(player, 30.0F, 30.0F);
    }

    private void moveAwayFrom(PlayerEntity player) {
        double dx = getX() - player.getX();
        double dz = getZ() - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);

        if (length < 0.001D) {
            dx = 1.0D;
            dz = 0.0D;
            length = 1.0D;
        }

        double destinationX = getX() + (dx / length) * 16.0D;
        double destinationZ = getZ() + (dz / length) * 16.0D;
        getNavigation().startMovingTo(destinationX, getY(), destinationZ, 0.75D);
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

    public void forceDebugPhase(Phase newPhase, PlayerEntity player) {
        if (newPhase == null || player == null) {
            return;
        }

        debugPhaseLocked = true;
        phase = newPhase;
        phaseTicks = 0;
        attemptedEscalationAttack = false;
        setTarget(player);
        getNavigation().stop();
    }

    public void resumeAutomaticPhases() {
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
