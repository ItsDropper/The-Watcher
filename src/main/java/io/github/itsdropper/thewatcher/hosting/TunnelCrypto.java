package io.github.itsdropper.thewatcher.hosting;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end tunnel framing cryptography. The relay receives only the frame
 * type/channel routing header; DATA payloads and their authentication tags
 * remain encrypted between the host and guest.
 */
final class TunnelCrypto {
    static final int CONTROL_FRAME_BYTES = 37;
    static final int DATA_HEADER_BYTES = 13;
    private static final int TAG_BITS = 128;
    private static final int MAX_FRAME_BYTES = 64 * 1024;

    private TunnelCrypto() {
    }

    static byte[] controlFrame(byte type, int channel, byte[] secret, String direction) {
        validateSecret(secret);
        validateDirection(direction);
        if ((type != 1 && type != 3) || channel == 0) {
            throw new IllegalArgumentException("Invalid control frame header.");
        }
        byte[] header = ByteBuffer.allocate(5).put(type).putInt(channel).array();
        byte[] mac = hmac(secret, concat(header, direction.getBytes(StandardCharsets.UTF_8)));
        return concat(header, mac);
    }

    static boolean verifyControlFrame(byte[] frame, byte[] secret, String direction) {
        if (frame == null || frame.length != CONTROL_FRAME_BYTES || secret == null || secret.length != 32) {
            return false;
        }
        byte type = frame[0];
        int channel = ByteBuffer.wrap(frame, 1, 4).getInt();
        if ((type != 1 && type != 3) || channel == 0) return false;
        try {
            return MessageDigest.isEqual(controlFrame(type, channel, secret, direction), frame);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static byte[] encrypt(byte[] plaintext, byte[] secret, String direction, int channel, long sequence)
            throws GeneralSecurityException {
        validateSecret(secret);
        validateDirection(direction);
        if (plaintext == null || plaintext.length > MAX_FRAME_BYTES - DATA_HEADER_BYTES - 16
                || channel == 0 || sequence < 0) {
            throw new GeneralSecurityException("Invalid tunnel data frame.");
        }

        byte[] header = dataHeader(channel, sequence);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(secret, direction, channel), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce(sequence)));
        cipher.updateAAD(header);
        return concat(header, cipher.doFinal(plaintext));
    }

    static byte[] decrypt(byte[] frame, byte[] secret, String direction) throws GeneralSecurityException {
        validateSecret(secret);
        validateDirection(direction);
        if (frame == null || frame.length < DATA_HEADER_BYTES + 16 || frame.length > MAX_FRAME_BYTES) {
            throw new GeneralSecurityException("Invalid tunnel data frame length.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        byte type = buffer.get();
        int channel = buffer.getInt();
        long sequence = buffer.getLong();
        if (type != 2 || channel == 0 || sequence < 0) {
            throw new GeneralSecurityException("Invalid tunnel data header.");
        }

        byte[] header = dataHeader(channel, sequence);
        byte[] encrypted = Arrays.copyOfRange(frame, DATA_HEADER_BYTES, frame.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(secret, direction, channel), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce(sequence)));
        cipher.updateAAD(header);
        return cipher.doFinal(encrypted);
    }

    private static byte[] deriveKey(byte[] secret, String direction, int channel) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("TheWatcher.Hosting.v1|".getBytes(StandardCharsets.UTF_8));
        digest.update(secret);
        digest.update(("|" + direction + "|" + channel).getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    private static byte[] hmac(byte[] secret, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Required HMAC algorithm is unavailable.", error);
        }
    }

    private static byte[] dataHeader(int channel, long sequence) {
        return ByteBuffer.allocate(DATA_HEADER_BYTES).put((byte) 2).putInt(channel).putLong(sequence).array();
    }

    private static byte[] nonce(long sequence) {
        return ByteBuffer.allocate(12).putInt(0).putLong(sequence).array();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void validateSecret(byte[] secret) {
        if (secret == null || secret.length != 32) {
            throw new IllegalArgumentException("Hosting invite secret must be 256 bits.");
        }
    }

    private static void validateDirection(String direction) {
        if (!"guest-to-host".equals(direction) && !"host-to-guest".equals(direction)) {
            throw new IllegalArgumentException("Invalid tunnel direction.");
        }
    }
}
