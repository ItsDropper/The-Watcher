package io.github.itsdropper.thewatcher;

import net.minecraft.world.World;

/**
 * Deterministic Blood Moon schedule based on world time. Every player in the
 * same world sees the event on the same nights; no packets or saved global state
 * are required. The first event cannot occur before day five.
 */
public final class BloodMoonSystem {
    private static final long CYCLE_DAYS = 8L;
    private static final long FIRST_ELIGIBLE_DAY = 5L;

    private BloodMoonSystem() {
    }

    public static boolean isActive(World world) {
        if (world == null || world.getRegistryKey() != World.OVERWORLD) {
            return false;
        }

        long timeOfDay = Math.floorMod(world.getTimeOfDay(), 24_000L);
        if (timeOfDay < 13_000L || timeOfDay >= 23_000L) {
            return false;
        }

        long day = Math.floorDiv(world.getTimeOfDay(), 24_000L);
        if (day < FIRST_ELIGIBLE_DAY) {
            return false;
        }

        // Avoid World.getSeed(): it is not available on the common World API.
        // Blood Moons occur on a predictable eight-day cycle instead.
        return Math.floorMod(day, CYCLE_DAYS) == 0L;
    }
}
