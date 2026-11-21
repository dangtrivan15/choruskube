package com.choruskube.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.credential.TemporalBearerToken;
import com.choruskube.core.credential.TemporalTokenState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NamespaceCredentialResponseTest {

    // Matches Spring Boot's auto-configured ObjectMapper, which disables WRITE_DATES_AS_TIMESTAMPS
    // so an Instant serializes as ISO-8601 rather than an epoch-seconds number — the format the
    // cross-repo wire contract actually promises.
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void bearer_serializesStateTokenAndExpiresAt() {
        Instant expiry = Instant.parse("2026-11-21T15:04:05Z");
        NamespaceCredentialResponse response = NamespaceCredentialResponse.from(
                new TemporalTokenState.Bearer(new TemporalBearerToken("tok-a", expiry)));

        JsonNode json = mapper.valueToTree(response);
        assertThat(json.get("state").asText()).isEqualTo("bearer");
        assertThat(json.get("token").asText()).isEqualTo("tok-a");
        assertThat(json.get("expiresAt").asText()).isEqualTo("2026-11-21T15:04:05Z");
    }

    @Test
    void none_omitsTokenAndExpiresAt() {
        NamespaceCredentialResponse response = NamespaceCredentialResponse.from(new TemporalTokenState.None());

        JsonNode json = mapper.valueToTree(response);
        assertThat(json.get("state").asText()).isEqualTo("none");
        assertThat(json.has("token")).isFalse();
        assertThat(json.has("expiresAt")).isFalse();
    }

    @Test
    void unavailable_omitsTokenAndExpiresAt() {
        NamespaceCredentialResponse response = NamespaceCredentialResponse.from(new TemporalTokenState.Unavailable());

        JsonNode json = mapper.valueToTree(response);
        assertThat(json.get("state").asText()).isEqualTo("unavailable");
        assertThat(json.has("token")).isFalse();
        assertThat(json.has("expiresAt")).isFalse();
    }

    /** A missing state must fail the roster rather than tell the orchestrator to send no credential. */
    @Test
    void from_null_isRejectedRatherThanReportedNone() {
        assertThatThrownBy(() -> NamespaceCredentialResponse.from(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toString_neverContainsTheTokenValue() {
        NamespaceCredentialResponse response = NamespaceCredentialResponse.from(new TemporalTokenState.Bearer(
                new TemporalBearerToken("super-secret", Instant.parse("2026-11-21T15:04:05Z"))));

        assertThat(response.toString()).doesNotContain("super-secret");
        assertThat(response.toString()).contains("bearer");
    }

    @Test
    void internalNamespacesResponse_toString_listsNamespacesAndStatesOnly_neverATokenOrExpiry() {
        Instant expiry = Instant.parse("2026-11-21T15:04:05Z");
        InternalNamespacesResponse response = new InternalNamespacesResponse(
                List.of("a", "b"),
                Map.of(
                        "a",
                                NamespaceCredentialResponse.from(
                                        new TemporalTokenState.Bearer(new TemporalBearerToken("super-secret", expiry))),
                        "b", NamespaceCredentialResponse.none()));

        String rendered = response.toString();
        assertThat(rendered).doesNotContain("super-secret");
        assertThat(rendered).doesNotContain(expiry.toString());
        assertThat(rendered).contains("a").contains("bearer").contains("b").contains("none");
    }
}
