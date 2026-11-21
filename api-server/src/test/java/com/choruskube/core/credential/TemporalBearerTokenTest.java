package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TemporalBearerTokenTest {

    private static final String VALUE = "super-secret-bearer-value-12345";
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void toString_omitsValueAndIncludesExpiry() {
        TemporalBearerToken token = new TemporalBearerToken(VALUE, EXPIRES_AT);

        String rendered = token.toString();

        assertThat(rendered).doesNotContain(VALUE);
        assertThat(rendered).contains(EXPIRES_AT.toString());
    }

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> new TemporalBearerToken(null, EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(VALUE);
    }

    @Test
    void rejectsBlankValue() {
        assertThatThrownBy(() -> new TemporalBearerToken("   ", EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(VALUE);
    }

    @Test
    void rejectsNullExpiry() {
        assertThatThrownBy(() -> new TemporalBearerToken(VALUE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(VALUE);
    }
}
