package com.choruskube.core.executor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Generates JOB_SECRET and its SHA-256 hash for agent pod authentication.
 *
 * <p>The secret is 32 random bytes, hex-encoded (64 chars).
 * The hash is SHA-256 of the secret, hex-encoded (64 chars).
 */
public final class JobSecretGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private JobSecretGenerator() {}

    /** Result of secret generation: the raw secret and its SHA-256 hash. */
    public record SecretAndHash(String secret, String hash) {}

    /** Generates a new JOB_SECRET and its SHA-256 hash. */
    public static SecretAndHash generate() {
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = hexEncode(secretBytes);
        String hash = sha256Hex(secret);
        return new SecretAndHash(secret, hash);
    }

    /** Computes SHA-256 hash of the input string, returning hex-encoded result. */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return hexEncode(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }
}
