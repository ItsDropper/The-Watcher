package io.github.itsdropper.thewatcher;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Issues evidence papers authenticated with a per-world secret. Renaming ordinary
 * paper to an evidence name is not enough to satisfy the Watcher's evidence checks.
 */
public final class WatcherEvidence {
    private static final String DATA_KEY = "the_watcher_evidence";
    private static final String CATEGORY_KEY = "category";
    private static final String SIGNATURE_KEY = "signature";
    private static final Map<Path, byte[]> KEY_CACHE = new HashMap<>();

    private WatcherEvidence() {
    }

    public static ItemStack create(ServerWorld world, String category) {
        ItemStack evidence = new ItemStack(Items.PAPER);
        evidence.set(DataComponentTypes.CUSTOM_NAME,
                net.minecraft.text.Text.literal("Evidence: " + category));
        NbtCompound data = new NbtCompound();
        data.putString(CATEGORY_KEY, category);
        data.putString(SIGNATURE_KEY, sign(world, category));
        NbtCompound root = new NbtCompound();
        root.put(DATA_KEY, data);
        evidence.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        return evidence;
    }

    public static boolean isAuthentic(ServerWorld world, ItemStack stack, String category) {
        if (stack.isEmpty() || !stack.isOf(Items.PAPER)
                || !stack.getName().getString().equals("Evidence: " + category)) {
            return false;
        }
        NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (component == null) return false;
        NbtCompound root = component.copyNbt();
        if (!root.contains(DATA_KEY)) return false;
        NbtCompound data = root.getCompound(DATA_KEY);
        if (!data.getString(CATEGORY_KEY).equals(category)) return false;
        String supplied = data.getString(SIGNATURE_KEY);
        if (supplied.isBlank()) return false;
        try {
            byte[] expected = Base64.getDecoder().decode(sign(world, category));
            byte[] actual = Base64.getDecoder().decode(supplied);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String sign(ServerWorld world, String category) {
        byte[] key = loadOrCreateKey(world);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(("the-watcher-evidence:v1:" + category)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not authenticate Watcher evidence", exception);
        }
    }

    private static synchronized byte[] loadOrCreateKey(ServerWorld world) {
        Path path = world.getServer().getSavePath(WorldSavePath.ROOT)
                .resolve("the-watcher-evidence.key").toAbsolutePath().normalize();
        byte[] cached = KEY_CACHE.get(path);
        if (cached != null) return cached;

        try {
            if (Files.isRegularFile(path)) {
                byte[] key = Base64.getDecoder().decode(
                        Files.readString(path, StandardCharsets.UTF_8).trim());
                if (key.length < 32) throw new IOException("Evidence key is too short");
                KEY_CACHE.put(path, key);
                return key;
            }

            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            String encoded = Base64.getEncoder().encodeToString(key);
            try {
                Files.writeString(path, encoded, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException race) {
                key = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.UTF_8).trim());
            }
            if (key.length < 32) throw new IOException("Evidence key is too short");
            KEY_CACHE.put(path, key);
            return key;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load the Watcher evidence key at " + path, exception);
        }
    }
}
