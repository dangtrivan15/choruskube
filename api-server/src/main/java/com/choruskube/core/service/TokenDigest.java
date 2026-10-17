package com.choruskube.core.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 digest of a Worker credential, shared by {@link SingleFleetWorkerRegistrar}, {@link
 * SingleFleetWorkerAuthorizer}, and {@link SingleFleetWorkerCredentialSourceResolver} (by way of
 * {@link #hex}, which keys {@link com.choruskube.core.repository.SingleFleetCredentialSourceRepository})
 * so the hashing scheme and the comparison it requires have exactly one place to change.
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

    /** Hex form of {@link #of}, for storing a digest as a lookup key rather than comparing it in memory. */
    static String hex(String token) {
        return HexFormat.of().formatHex(of(token));
    }

    /**
     * Compare fixed-width digests, not the tokens: MessageDigest.isEqual is only time-constant
     * over equal-length inputs, and raw tokens differ in length.
     */
    static boolean matches(byte[] expected, String presented) {
        return MessageDigest.isEqual(expected, of(presented == null ? "" : presented));
    }
}
