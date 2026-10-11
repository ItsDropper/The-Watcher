package io.github.itsdropper.thewatcher.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
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
    private static final int ESCALATE_TICKS = 100;
    private static final int RETREAT_TICKS = 100;
    private static final double TARGET_RANGE = 48.0D;

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private boolean attemptedEscalationAttack;

    public WatcherEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void initGoals() {
        // Deliberately no vanilla melee or automatic target goals.
        // The Watcher's state machine controls when it observes, stalks, and escalates.
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
        if (timeOfDay < 13000L || timeOfDay >= 23000L) {
            setPhase(Phase.IDLE);
            setTarget(null);
            getNavigation().stop();
            return;
        }

        PlayerEntity player = world.getPlayers().stream()
                .filter(candidate -> candidate.isAlive() && !candidate.isSpectator())
                .filter(candidate -> squaredDistanceTo(candidate) <= TARGET_RANGE * TARGET_RANGE)
                .min(Comparator.comparingDouble(this::squaredDistanceTo))
                .orElse(null);

        if (player == null) {
            setPhase(Phase.IDLE);
            setTarget(null);
            getNavigation().stop();
            return;
        }

        setTarget(player);
        phaseTicks++;

        switch (phase) {
            case IDLE -> setPhase(Phase.OBSERVING);
            case OBSERVING -> {
                lookAtPlayer(player);
                getNavigation().stop();
                if (phaseTicks >= OBSERVE_TICKS) {
                    setPhase(Phase.STALKING);
                }
            }
            case STALKING -> {
                lookAtPlayer(player);
                if (squaredDistanceTo(player) > 12.0D * 12.0D) {
                    getNavigation().startMovingTo(player, 0.48D);
                } else {
                    getNavigation().stop();
                }
                if (phaseTicks >= STALK_TICKS) {
                    setPhase(Phase.ESCALATING);
                }
            }
            case ESCALATING -> {
                lookAtPlayer(player);
                getNavigation().startMovingTo(player, 0.78D);

                // One attack attempt per escalation, never continuous melee spam.
                if (!attemptedEscalationAttack && squaredDistanceTo(player) <= 2.4D * 2.4D) {
                    attemptedEscalationAttack = true;
                    tryAttack(player);
                }

                if (phaseTicks >= ESCALATE_TICKS) {
                    setPhase(Phase.RETREATING);
                }
            }
            case RETREATING -> {
                if (phaseTicks == 1) {
                    moveAwayFrom(player);
                }
                lookAtPlayer(player);
                if (phaseTicks >= RETREAT_TICKS) {
                    setPhase(Phase.OBSERVING);
                }
            }
        }
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
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, TARGET_RANGE);
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
