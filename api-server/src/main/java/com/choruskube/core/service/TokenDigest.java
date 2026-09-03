package com.choruskube.core.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 digest of a Worker credential, shared by {@link SingleFleetWorkerRegistrar} and {@link
 * SingleFleetWorkerAuthorizer} so the hashing scheme and the comparison it requires have exactly
 * one place to change.
 */
final class TokenDigest {

    private TokenDigest() {}

    /** The SHA-256 of {@code token}: the fixed-width form {@link #matches} needs on both sides. */
    static byte[] of(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Compare fixed-width digests, not the tokens: MessageDigest.isEqual is only time-constant
     * over equal-length inputs, and raw tokens differ in length.
     */
    static boolean matches(byte[] expected, String presented) {
        return MessageDigest.isEqual(expected, of(presented == null ? "" : presented));
    }
}
