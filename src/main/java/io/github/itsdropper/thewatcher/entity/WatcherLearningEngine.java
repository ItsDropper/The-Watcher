package io.github.itsdropper.thewatcher.entity;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.entity.player.PlayerEntity;
import java.util.Properties;

/**
 * A small, deterministic, offline learning model for the Watcher.
 *
 * It learns the player's typical gaze-on and gaze-off windows, then uses those
 * estimates to time hidden relocations. The model is intentionally bounded and
 * saved with the entity, so it requires neither a service nor an AI model.
 */
public final class WatcherLearningEngine {
    private static final double EMA_WEIGHT = 0.18D;
    private static final int MAX_RUN_TICKS = 72000;

    private double averageWatchedTicks = 100.0D;
    private double averageUnwatchedTicks = 36.0D;
    private double averagePlayerDistance = 48.0D;
    // Rises after repeated close encounters and slowly fades when the Watcher maintains distance.
    private double proximityPressure;
    private int currentRunTicks;
    private long observedTicks;
    private boolean hasPreviousState;
    private boolean previousWatched;
    private double averageHorizontalSpeed = 0.08D;
    private double averageStationaryTicks;
    private double averageSprintRatio = 0.25D;
    private double averagePlayerHeight = 64.0D;
    private double averageSneakRatio = 0.0D;
    private double averageItemUseRatio = 0.0D;
    private double averageJumpRatio = 0.0D;
    private double averageLookBackRatio = 0.15D;
    private float previousYaw;
    private boolean hasPreviousYaw;
    private int currentStationaryTicks;

    public void observe(boolean watched, double distance) {
        if (observedTicks < Long.MAX_VALUE) {
            observedTicks++;
        }
        averagePlayerDistance = blend(averagePlayerDistance, clamp(distance, 0.0D, 512.0D));

        if (!hasPreviousState) {
            hasPreviousState = true;
            previousWatched = watched;
            currentRunTicks = 1;
            return;
        }

        if (watched == previousWatched) {
            currentRunTicks = Math.min(MAX_RUN_TICKS, currentRunTicks + 1);
            return;
        }

        if (previousWatched) {
            averageWatchedTicks = blend(averageWatchedTicks, currentRunTicks);
        } else {
            averageUnwatchedTicks = blend(averageUnwatchedTicks, currentRunTicks);
        }

        previousWatched = watched;
        currentRunTicks = 1;
    }

    /**
     * Aim for a relocation inside the player's learned usual gaze-off window.
     * The hard lower bound prevents twitchy, instantly repeated teleports.
     */
    public int getHiddenTeleportDelayTicks() {
        // Players who repeatedly check behind themselves create fewer safe relocation
        // windows, so the Watcher waits longer instead of visibly cheating the rule.
        double vigilancePenalty = averageLookBackRatio * 16.0D;
        double learnedDelay = averageUnwatchedTicks * 0.42D + vigilancePenalty;
        return (int) Math.round(clamp(learnedDelay, 12.0D, 64.0D));
    }

    /**
     * Adjust the base chance using learned gaze habits. Players who stare for
     * long periods and then quickly check away create a stronger opportunity.
     */
    public int adjustTeleportChance(int baseChance) {
        double gazePattern = averageWatchedTicks - averageUnwatchedTicks;
        double campingOpportunity = clamp((averageStationaryTicks - 80.0D) * 0.018D, 0.0D, 8.0D);
        double vigilancePenalty = averageLookBackRatio * 16.0D;
        int adjustment = (int) Math.round(clamp(gazePattern * 0.08D + campingOpportunity
                - vigilancePenalty, -18.0D, 14.0D));
        return (int) clamp(baseChance + adjustment, 4.0D, 68.0D);
    }

    /**
     * Record repeatable habits rather than only gaze timing: whether the player
     * camps, sprints, and tends to build/live at higher elevations.
     */
    public void observePlayerBehavior(PlayerEntity player) {
        observePlayerBehavior(player, 1);
    }

    /**
     * Samples at a caller-defined interval. The run manager uses 20 ticks when the
     * entity is unloaded, so stationary habits still represent seconds rather than samples.
     */
    public void observePlayerBehavior(PlayerEntity player, int elapsedTicks) {
        int sampleTicks = Math.max(1, Math.min(20, elapsedTicks));
        double vx = player.getVelocity().x;
        double vz = player.getVelocity().z;
        double horizontalSpeed = Math.sqrt(vx * vx + vz * vz);
        averageHorizontalSpeed = blend(averageHorizontalSpeed, clamp(horizontalSpeed, 0.0D, 1.5D));
        averageSprintRatio = blend(averageSprintRatio, player.isSprinting() ? 1.0D : 0.0D);
        averagePlayerHeight = blend(averagePlayerHeight, clamp(player.getY(), -64.0D, 320.0D));
        averageSneakRatio = blend(averageSneakRatio, player.isSneaking() ? 1.0D : 0.0D);
        averageItemUseRatio = blend(averageItemUseRatio, player.isUsingItem() ? 1.0D : 0.0D);
        averageJumpRatio = blend(averageJumpRatio, player.getVelocity().y > 0.12D ? 1.0D : 0.0D);
        if (hasPreviousYaw) {
            double turn = Math.abs(player.getYaw() - previousYaw) % 360.0D;
            turn = Math.min(turn, 360.0D - turn);
            averageLookBackRatio = blend(averageLookBackRatio, turn >= 95.0D ? 1.0D : 0.0D);
        }
        previousYaw = player.getYaw();
        hasPreviousYaw = true;

        if (horizontalSpeed < 0.025D) {
            currentStationaryTicks = Math.min(1200, currentStationaryTicks + sampleTicks);
        } else {
            currentStationaryTicks = Math.max(0, currentStationaryTicks - 3 * sampleTicks);
        }
        averageStationaryTicks = blend(averageStationaryTicks, currentStationaryTicks);
    }

    /**
     * Repeated close sightings make the Watcher bolder. Campers and slow-moving
     * players also teach it that it can approach without being chased away.
     */
    public void observeStalkingDistance(double distance, int configuredChunks) {
        double baseline = WatcherStalkingDirector.minimumStalkingDistance(configuredChunks) + 8.0D;
        if (distance < baseline) {
            proximityPressure += (1.0D - proximityPressure) * 0.004D;
        } else {
            proximityPressure *= 0.9995D;
        }
        proximityPressure = clamp(proximityPressure, 0.0D, 1.0D);
    }

    public double getLearnedApproachBonus() {
        double campingBonus = clamp((averageStationaryTicks - 20.0D) * 0.025D, 0.0D, 6.0D);
        double cautiousBonus = clamp((0.35D - averageSprintRatio) * 8.0D, 0.0D, 2.8D);
        double movementBonus = clamp(averageHorizontalSpeed * 8.0D, 0.0D, 5.0D);
        double sneakingBonus = clamp(averageSneakRatio * 2.5D, 0.0D, 2.5D);
        double itemFocusBonus = clamp(averageItemUseRatio * 2.0D, 0.0D, 2.0D);
        double vigilanceReduction = clamp(averageLookBackRatio * 5.0D, 0.0D, 5.0D);
        return clamp(proximityPressure * 18.0D + campingBonus + cautiousBonus + movementBonus
                + sneakingBonus + itemFocusBonus - vigilanceReduction, 0.0D, 24.0D);
    }

    public double getAveragePlayerHeight() {
        return averagePlayerHeight;
    }

    public double getAverageSneakRatio() { return averageSneakRatio; }
    public double getAverageItemUseRatio() { return averageItemUseRatio; }
    public double getAverageJumpRatio() { return averageJumpRatio; }
    public double getAverageLookBackRatio() { return averageLookBackRatio; }
    public double getAverageStationaryTicks() { return averageStationaryTicks; }
    public double getAverageWatchedTicks() { return averageWatchedTicks; }
    public double getAverageUnwatchedTicks() { return averageUnwatchedTicks; }
    public double getAveragePlayerDistance() { return averagePlayerDistance; }
    public long getObservedTicks() { return observedTicks; }

    public double getAverageHorizontalSpeed() {
        return averageHorizontalSpeed;
    }

    public double getAverageSprintRatio() {
        return averageSprintRatio;
    }

    /**
     * Add a bounded offset to the normal stalking ring after repeated close encounters.
     * The director caps this offset to the available render-distance band.
     */
    public double getLearnedSafetyDistanceBonus(int configuredChunks) {
        double desiredBonus = proximityPressure * 12.0D;
        return WatcherStalkingDirector.clampLearnedOffset(configuredChunks, desiredBonus);
    }

    public double getProximityPressure() {
        return proximityPressure;
    }

    /**
     * Slightly changes the stalking phase length based on how close the player
     * tends to remain. It stays within a narrow range to avoid erratic pacing.
     */
    public int adjustStalkDuration(int baseTicks) {
        double distanceFactor = clamp(averagePlayerDistance / 48.0D, 0.75D, 1.25D);
        return (int) Math.round(baseTicks * distanceFactor);
    }

    public void writeToNbt(NbtCompound nbt) {
        nbt.putDouble("WatcherLearningAverageWatched", averageWatchedTicks);
        nbt.putDouble("WatcherLearningAverageUnwatched", averageUnwatchedTicks);
        nbt.putDouble("WatcherLearningAverageDistance", averagePlayerDistance);
        nbt.putDouble("WatcherLearningProximityPressure", proximityPressure);
        nbt.putInt("WatcherLearningCurrentRun", currentRunTicks);
        nbt.putLong("WatcherLearningObservedTicks", observedTicks);
        nbt.putBoolean("WatcherLearningHasPreviousState", hasPreviousState);
        nbt.putBoolean("WatcherLearningPreviousWatched", previousWatched);
        nbt.putDouble("WatcherLearningAverageHorizontalSpeed", averageHorizontalSpeed);
        nbt.putDouble("WatcherLearningAverageStationaryTicks", averageStationaryTicks);
        nbt.putDouble("WatcherLearningAverageSprintRatio", averageSprintRatio);
        nbt.putDouble("WatcherLearningAveragePlayerHeight", averagePlayerHeight);
        nbt.putDouble("WatcherLearningAverageSneakRatio", averageSneakRatio);
        nbt.putDouble("WatcherLearningAverageItemUseRatio", averageItemUseRatio);
        nbt.putDouble("WatcherLearningAverageJumpRatio", averageJumpRatio);
        nbt.putDouble("WatcherLearningAverageLookBackRatio", averageLookBackRatio);
        nbt.putFloat("WatcherLearningPreviousYaw", previousYaw);
        nbt.putBoolean("WatcherLearningHasPreviousYaw", hasPreviousYaw);
        nbt.putInt("WatcherLearningCurrentStationaryTicks", currentStationaryTicks);
    }

    public void readFromNbt(NbtCompound nbt) {
        if (nbt.contains("WatcherLearningAverageWatched")) {
            averageWatchedTicks = clamp(nbt.getDouble("WatcherLearningAverageWatched"), 1.0D, MAX_RUN_TICKS);
        }
        if (nbt.contains("WatcherLearningAverageUnwatched")) {
            averageUnwatchedTicks = clamp(nbt.getDouble("WatcherLearningAverageUnwatched"), 1.0D, MAX_RUN_TICKS);
        }
        if (nbt.contains("WatcherLearningAverageDistance")) {
            averagePlayerDistance = clamp(nbt.getDouble("WatcherLearningAverageDistance"), 0.0D, 512.0D);
        }
        if (nbt.contains("WatcherLearningProximityPressure")) {
            proximityPressure = clamp(nbt.getDouble("WatcherLearningProximityPressure"), 0.0D, 1.0D);
        }
        currentRunTicks = (int) clamp(nbt.getInt("WatcherLearningCurrentRun"), 0.0D, MAX_RUN_TICKS);
        observedTicks = Math.max(0L, nbt.getLong("WatcherLearningObservedTicks"));
        hasPreviousState = nbt.getBoolean("WatcherLearningHasPreviousState");
        previousWatched = nbt.getBoolean("WatcherLearningPreviousWatched");
        if (nbt.contains("WatcherLearningAverageHorizontalSpeed")) {
            averageHorizontalSpeed = clamp(nbt.getDouble("WatcherLearningAverageHorizontalSpeed"), 0.0D, 1.5D);
        }
        if (nbt.contains("WatcherLearningAverageStationaryTicks")) {
            averageStationaryTicks = clamp(nbt.getDouble("WatcherLearningAverageStationaryTicks"), 0.0D, 1200.0D);
        }
        if (nbt.contains("WatcherLearningAverageSprintRatio")) {
            averageSprintRatio = clamp(nbt.getDouble("WatcherLearningAverageSprintRatio"), 0.0D, 1.0D);
        }
        if (nbt.contains("WatcherLearningAveragePlayerHeight")) {
            averagePlayerHeight = clamp(nbt.getDouble("WatcherLearningAveragePlayerHeight"), -64.0D, 320.0D);
        }
        if (nbt.contains("WatcherLearningAverageSneakRatio")) averageSneakRatio = clamp(nbt.getDouble("WatcherLearningAverageSneakRatio"), 0.0D, 1.0D);
        if (nbt.contains("WatcherLearningAverageItemUseRatio")) averageItemUseRatio = clamp(nbt.getDouble("WatcherLearningAverageItemUseRatio"), 0.0D, 1.0D);
        if (nbt.contains("WatcherLearningAverageJumpRatio")) averageJumpRatio = clamp(nbt.getDouble("WatcherLearningAverageJumpRatio"), 0.0D, 1.0D);
        if (nbt.contains("WatcherLearningAverageLookBackRatio")) averageLookBackRatio = clamp(nbt.getDouble("WatcherLearningAverageLookBackRatio"), 0.0D, 1.0D);
        previousYaw = nbt.getFloat("WatcherLearningPreviousYaw");
        hasPreviousYaw = nbt.getBoolean("WatcherLearningHasPreviousYaw");
        currentStationaryTicks = (int) clamp(nbt.getInt("WatcherLearningCurrentStationaryTicks"), 0.0D, 1200.0D);
    }

    private static double blend(double current, double sample) {
        return current + (sample - current) * EMA_WEIGHT;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    public void writeToProperties(Properties properties, String prefix) {
        properties.setProperty(prefix + "averageWatchedTicks", Double.toString(averageWatchedTicks));
        properties.setProperty(prefix + "averageUnwatchedTicks", Double.toString(averageUnwatchedTicks));
        properties.setProperty(prefix + "averagePlayerDistance", Double.toString(averagePlayerDistance));
        properties.setProperty(prefix + "proximityPressure", Double.toString(proximityPressure));
        properties.setProperty(prefix + "currentRunTicks", Integer.toString(currentRunTicks));
        properties.setProperty(prefix + "observedTicks", Long.toString(observedTicks));
        properties.setProperty(prefix + "hasPreviousState", Boolean.toString(hasPreviousState));
        properties.setProperty(prefix + "previousWatched", Boolean.toString(previousWatched));
        properties.setProperty(prefix + "averageHorizontalSpeed", Double.toString(averageHorizontalSpeed));
        properties.setProperty(prefix + "averageStationaryTicks", Double.toString(averageStationaryTicks));
        properties.setProperty(prefix + "averageSprintRatio", Double.toString(averageSprintRatio));
        properties.setProperty(prefix + "averagePlayerHeight", Double.toString(averagePlayerHeight));
        properties.setProperty(prefix + "averageSneakRatio", Double.toString(averageSneakRatio));
        properties.setProperty(prefix + "averageItemUseRatio", Double.toString(averageItemUseRatio));
        properties.setProperty(prefix + "averageJumpRatio", Double.toString(averageJumpRatio));
        properties.setProperty(prefix + "averageLookBackRatio", Double.toString(averageLookBackRatio));
        properties.setProperty(prefix + "previousYaw", Float.toString(previousYaw));
        properties.setProperty(prefix + "hasPreviousYaw", Boolean.toString(hasPreviousYaw));
        properties.setProperty(prefix + "currentStationaryTicks", Integer.toString(currentStationaryTicks));
    }

    public void readFromProperties(Properties properties, String prefix) {
        averageWatchedTicks = readDouble(properties, prefix + "averageWatchedTicks", averageWatchedTicks, 1.0D, MAX_RUN_TICKS);
        averageUnwatchedTicks = readDouble(properties, prefix + "averageUnwatchedTicks", averageUnwatchedTicks, 1.0D, MAX_RUN_TICKS);
        averagePlayerDistance = readDouble(properties, prefix + "averagePlayerDistance", averagePlayerDistance, 0.0D, 512.0D);
        proximityPressure = readDouble(properties, prefix + "proximityPressure", proximityPressure, 0.0D, 1.0D);
        currentRunTicks = readInt(properties, prefix + "currentRunTicks", currentRunTicks, 0, MAX_RUN_TICKS);
        observedTicks = readLong(properties, prefix + "observedTicks", observedTicks, 0L, Long.MAX_VALUE);
        hasPreviousState = Boolean.parseBoolean(properties.getProperty(prefix + "hasPreviousState",
                Boolean.toString(hasPreviousState)));
        previousWatched = Boolean.parseBoolean(properties.getProperty(prefix + "previousWatched",
                Boolean.toString(previousWatched)));
        averageHorizontalSpeed = readDouble(properties, prefix + "averageHorizontalSpeed", averageHorizontalSpeed, 0.0D, 1.5D);
        averageStationaryTicks = readDouble(properties, prefix + "averageStationaryTicks", averageStationaryTicks, 0.0D, 1200.0D);
        averageSprintRatio = readDouble(properties, prefix + "averageSprintRatio", averageSprintRatio, 0.0D, 1.0D);
        averagePlayerHeight = readDouble(properties, prefix + "averagePlayerHeight", averagePlayerHeight, -64.0D, 320.0D);
        averageSneakRatio = readDouble(properties, prefix + "averageSneakRatio", averageSneakRatio, 0.0D, 1.0D);
        averageItemUseRatio = readDouble(properties, prefix + "averageItemUseRatio", averageItemUseRatio, 0.0D, 1.0D);
        averageJumpRatio = readDouble(properties, prefix + "averageJumpRatio", averageJumpRatio, 0.0D, 1.0D);
        averageLookBackRatio = readDouble(properties, prefix + "averageLookBackRatio", averageLookBackRatio, 0.0D, 1.0D);
        try {
            previousYaw = Float.parseFloat(properties.getProperty(prefix + "previousYaw", Float.toString(previousYaw)));
        } catch (NumberFormatException ignored) {
            // Keep the last valid yaw sample.
        }
        hasPreviousYaw = Boolean.parseBoolean(properties.getProperty(prefix + "hasPreviousYaw", Boolean.toString(hasPreviousYaw)));
        currentStationaryTicks = readInt(properties, prefix + "currentStationaryTicks", currentStationaryTicks, 0, 1200);
    }

    private static double readDouble(Properties properties, String key, double fallback, double min, double max) {
        try {
            return clamp(Double.parseDouble(properties.getProperty(key, Double.toString(fallback))), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int readInt(Properties properties, String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long readLong(Properties properties, String key, long fallback, long min, long max) {
        try {
            return Math.max(min, Math.min(max, Long.parseLong(properties.getProperty(key, Long.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

}
