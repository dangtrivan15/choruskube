package com.choruskube.core.executor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JobSecretGeneratorTest {

    @Test
    void generate_producesSecretAndHash() {
        var result = JobSecretGenerator.generate();

        assertNotNull(result.secret());
        assertNotNull(result.hash());

        // Secret is 32 bytes hex-encoded = 64 chars
        assertEquals(64, result.secret().length());

        // Hash is SHA-256 hex-encoded = 64 chars
        assertEquals(64, result.hash().length());
    }

    @Test
    void generate_producesUniqueSecrets() {
        var first = JobSecretGenerator.generate();
        var second = JobSecretGenerator.generate();

        assertNotEquals(first.secret(), second.secret());
        assertNotEquals(first.hash(), second.hash());
    }

    @Test
    void generate_hashMatchesSha256OfSecret() {
        var result = JobSecretGenerator.generate();

        String expectedHash = JobSecretGenerator.sha256Hex(result.secret());
        assertEquals(expectedHash, result.hash());
    }

    @Test
    void sha256Hex_producesCorrectHash() {
        // Known SHA-256 hash of "test"
        String hash = JobSecretGenerator.sha256Hex("test");
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", hash);
    }
}
