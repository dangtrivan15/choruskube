package com.choruskube.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.model.CredentialHealthStatus;
import com.choruskube.core.model.CredentialKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkerCredentialHealthTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Test
    void validEntry_isAccepted() {
        WorkerCredentialHealth health =
                new WorkerCredentialHealth(CredentialKind.GITHUB, CredentialHealthStatus.VALID, "ok", NOW);

        assertThat(health.kind()).isEqualTo(CredentialKind.GITHUB);
        assertThat(health.status()).isEqualTo(CredentialHealthStatus.VALID);
        assertThat(health.detail()).isEqualTo("ok");
        assertThat(health.checkedAt()).isEqualTo(NOW);
    }

    @Test
    void nullDetail_isAccepted() {
        assertThat(new WorkerCredentialHealth(CredentialKind.CLAUDE, CredentialHealthStatus.NOT_HELD, null, NOW)
                        .detail())
                .isNull();
    }

    @Test
    void nullKind_throws() {
        assertThatThrownBy(() -> new WorkerCredentialHealth(null, CredentialHealthStatus.VALID, "", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullStatus_throws() {
        assertThatThrownBy(() -> new WorkerCredentialHealth(CredentialKind.GITHUB, null, "", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullCheckedAt_throws() {
        assertThatThrownBy(
                        () -> new WorkerCredentialHealth(CredentialKind.GITHUB, CredentialHealthStatus.VALID, "", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailAtTheCap_isAccepted() {
        String atCap = "x".repeat(WorkerCredentialHealth.MAX_DETAIL_LENGTH);

        assertThat(new WorkerCredentialHealth(CredentialKind.GITHUB, CredentialHealthStatus.VALID, atCap, NOW).detail())
                .hasSize(WorkerCredentialHealth.MAX_DETAIL_LENGTH);
    }

    @Test
    void detailOverTheCap_throws() {
        String tooLong = "x".repeat(WorkerCredentialHealth.MAX_DETAIL_LENGTH + 1);

        assertThatThrownBy(() ->
                        new WorkerCredentialHealth(CredentialKind.GITHUB, CredentialHealthStatus.VALID, tooLong, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
