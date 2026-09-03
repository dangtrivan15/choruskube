package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Direct cover for the comparison both Worker paths authenticate on. Its two callers reach it only
 * through their own refusal messages, which read the same whether the comparison is length-safe,
 * constant-time, or wrong in a way that admits a credential nobody issued.
 */
class TokenDigestTest {

    @Test
    void digestsDeterministicallyToThirtyTwoBytes() {
        assertThat(TokenDigest.of("ckf_token")).hasSize(32).isEqualTo(TokenDigest.of("ckf_token"));
    }

    @Test
    void digestsDifferentTokensDifferently() {
        assertThat(TokenDigest.of("ckf_token")).isNotEqualTo(TokenDigest.of("ckf_other"));
    }

    @Test
    void matchesOnlyTheTokenItWasDigestedFrom() {
        byte[] expected = TokenDigest.of("ckf_token");

        assertThat(TokenDigest.matches(expected, "ckf_token")).isTrue();
        assertThat(TokenDigest.matches(expected, "ckf_other")).isFalse();
        assertThat(TokenDigest.matches(expected, null)).isFalse();
        assertThat(TokenDigest.matches(expected, "")).isFalse();
    }
}
