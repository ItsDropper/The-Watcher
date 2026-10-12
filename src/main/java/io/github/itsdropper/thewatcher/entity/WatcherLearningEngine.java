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
        double learnedDelay = averageUnwatchedTicks * 0.42D;
        return (int) Math.round(clamp(learnedDelay, 12.0D, 48.0D));
    }

    /**
     * Adjust the base chance using learned gaze habits. Players who stare for
     * long periods and then quickly check away create a stronger opportunity.
     */
    public int adjustTeleportChance(int baseChance) {
        double gazePattern = averageWatchedTicks - averageUnwatchedTicks;
        int adjustment = (int) Math.round(clamp(gazePattern * 0.08D, -8.0D, 12.0D));
        return (int) clamp(baseChance + adjustment, 5.0D, 70.0D);
    }

    /**
     * Record repeatable habits rather than only gaze timing: whether the player
     * camps, sprints, and tends to build/live at higher elevations.
     */
    public void observePlayerBehavior(PlayerEntity player) {
        double vx = player.getVelocity().x;
        double vz = player.getVelocity().z;
        double horizontalSpeed = Math.sqrt(vx * vx + vz * vz);
        averageHorizontalSpeed = blend(averageHorizontalSpeed, clamp(horizontalSpeed, 0.0D, 1.5D));
        averageSprintRatio = blend(averageSprintRatio, player.isSprinting() ? 1.0D : 0.0D);
        averagePlayerHeight = blend(averagePlayerHeight, clamp(player.getY(), -64.0D, 320.0D));

        if (horizontalSpeed < 0.025D) {
            currentStationaryTicks = Math.min(1200, currentStationaryTicks + 1);
        } else {
            currentStationaryTicks = Math.max(0, currentStationaryTicks - 3);
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
        return clamp(proximityPressure * 18.0D + campingBonus + cautiousBonus + movementBonus, 0.0D, 24.0D);
    }

    public double getAveragePlayerHeight() {
        return averagePlayerHeight;
    }

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
