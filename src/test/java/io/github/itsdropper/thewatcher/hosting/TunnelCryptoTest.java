package io.github.itsdropper.thewatcher.hosting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class TunnelCryptoTest {
    private static byte[] secret() {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) (i * 7 + 3);
        return secret;
    }

    @Test
    void dataRoundTripsWithAuthenticatedEncryption() throws Exception {
        byte[] secret = secret();
        byte[] plaintext = "minecraft login packet sample".getBytes(StandardCharsets.UTF_8);

        byte[] frame = TunnelCrypto.encrypt(plaintext, secret, "guest-to-host", 77, 0);

        assertArrayEquals(plaintext, TunnelCrypto.decrypt(frame, secret, "guest-to-host"));
        assertNotEquals(plaintext.length, frame.length);
    }

    @Test
    void payloadAndHeaderTamperingAreRejected() throws Exception {
        byte[] secret = secret();
        byte[] plaintext = "authenticated".getBytes(StandardCharsets.UTF_8);
        byte[] payloadTamper = TunnelCrypto.encrypt(plaintext, secret, "guest-to-host", 12, 0);
        payloadTamper[13] ^= 0x40;
        assertThrows(GeneralSecurityException.class,
                () -> TunnelCrypto.decrypt(payloadTamper, secret, "guest-to-host"));

        byte[] headerTamper = TunnelCrypto.encrypt(plaintext, secret, "guest-to-host", 12, 0);
        headerTamper[12] ^= 0x01;
        assertThrows(GeneralSecurityException.class,
                () -> TunnelCrypto.decrypt(headerTamper, secret, "guest-to-host"));
    }

    @Test
    void keysAreSeparatedByDirectionChannelAndSequence() throws Exception {
        byte[] secret = secret();
        byte[] plaintext = new byte[] { 1, 2, 3, 4 };

        byte[] guestToHost = TunnelCrypto.encrypt(plaintext, secret, "guest-to-host", 9, 0);
        byte[] nextSequence = TunnelCrypto.encrypt(plaintext, secret, "guest-to-host", 9, 1);
        byte[] otherChannel = TunnelCrypto.encrypt(plaintext, secret, "guest-to-host", 10, 0);

        assertFalse(Arrays.equals(guestToHost, nextSequence));
        assertFalse(Arrays.equals(guestToHost, otherChannel));
        assertThrows(GeneralSecurityException.class,
                () -> TunnelCrypto.decrypt(guestToHost, secret, "host-to-guest"));
    }

    @Test
    void controlFramesAuthenticateDirectionTypeAndChannel() {
        byte[] secret = secret();
        byte[] open = TunnelCrypto.controlFrame((byte) 1, 77, secret, "guest-to-host");

        assertTrue(TunnelCrypto.verifyControlFrame(open, secret, "guest-to-host"));
        assertFalse(TunnelCrypto.verifyControlFrame(open, secret, "host-to-guest"));

        byte[] changedChannel = open.clone();
        changedChannel[4] ^= 1;
        assertFalse(TunnelCrypto.verifyControlFrame(changedChannel, secret, "guest-to-host"));

        byte[] close = TunnelCrypto.controlFrame((byte) 3, 77, secret, "host-to-guest");
        assertTrue(TunnelCrypto.verifyControlFrame(close, secret, "host-to-guest"));
        assertFalse(TunnelCrypto.verifyControlFrame(close, secret, "guest-to-host"));
    }

    @Test
    void malformedSecretsAndDirectionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TunnelCrypto.controlFrame((byte) 1, 1, new byte[8], "guest-to-host"));
        assertThrows(IllegalArgumentException.class,
                () -> TunnelCrypto.controlFrame((byte) 1, 1, secret(), "wrong-direction"));
        assertThrows(IllegalArgumentException.class,
                () -> TunnelCrypto.controlFrame((byte) 9, 1, secret(), "guest-to-host"));
    }
}
