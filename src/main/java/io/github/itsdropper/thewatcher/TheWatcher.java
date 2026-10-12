package io.github.itsdropper.thewatcher;

import io.github.itsdropper.thewatcher.command.WatcherCommands;
import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TheWatcher implements ModInitializer {
    public static final String MOD_ID = "the-watcher";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static volatile boolean NIGHT_DARKNESS_ENABLED = true;
    public static volatile boolean DREAD_EFFECTS_ENABLED = true;
    public static volatile boolean TELEPORTS_ENABLED = true;

    // Updated by the client settings screen from Minecraft's actual view-distance option.
    public static volatile int WATCHER_RENDER_DISTANCE_CHUNKS = 12;

    public static final EntityType<WatcherEntity> WATCHER = Registry.register(
            Registries.ENTITY_TYPE,
            id("watcher"),
            FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, WatcherEntity::new)
                    .dimensions(EntityDimensions.fixed(0.75F, 2.7F))
                    .trackRangeBlocks(512)
                    .trackedUpdateRate(2)
                    .build()
    );

    public static final Block SURVEILLANCE_CAMERA = Registry.register(
            Registries.BLOCK, id("surveillance_camera"),
            new SurveillanceCameraBlock(AbstractBlock.Settings.copy(Blocks.IRON_BLOCK)
                    .strength(1.8F).nonOpaque())
    );

    public static final BlockEntityType<SurveillanceCameraBlockEntity> CAMERA_BLOCK_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, id("surveillance_camera"),
            FabricBlockEntityTypeBuilder.create(SurveillanceCameraBlockEntity::new, SURVEILLANCE_CAMERA).build()
    );

    public static final Item SURVEILLANCE_CAMERA_ITEM = Registry.register(
            Registries.ITEM, id("surveillance_camera"),
            new BlockItem(SURVEILLANCE_CAMERA, new Item.Settings())
    );

    @Override
    public void onInitialize() {
        WatcherOllamaConfig.load();
        LOGGER.info("Surveillance camera block and item registered.");
        FabricDefaultAttributeRegistry.register(WATCHER, WatcherEntity.createWatcherAttributes());
        WatcherCommands.register();
        ServerTickEvents.END_WORLD_TICK.register(TheWatcher::applyNightDarkness);
        ServerTickEvents.END_WORLD_TICK.register(WatcherRunManager::tick);
        LOGGER.info("The Watcher entity and debug commands registered.");
    }

    private static void applyNightDarkness(ServerWorld world) {
        if (!NIGHT_DARKNESS_ENABLED || world.getRegistryKey() != World.OVERWORLD) {
            return;
        }

        long timeOfDay = Math.floorMod(world.getTimeOfDay(), 24000L);
        boolean night = timeOfDay >= 13000L && timeOfDay < 23000L;
        if (!night || world.getTime() % 240L != 0L) {
            return;
        }

        for (var player : world.getPlayers()) {
            player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.DARKNESS, 35, 0, true, false, false));
        }
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}
