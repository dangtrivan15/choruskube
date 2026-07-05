package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.repository.TelemetryInstallRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test for {@link TelemetryInstallService}: the install id is generated once,
 * persisted, and stable across calls (idempotent).
 */
class TelemetryInstallServiceTest extends BaseTest {

    @Autowired
    private TelemetryInstallService service;

    @Autowired
    private TelemetryInstallRepository repository;

    @Test
    void getOrCreateInstallId_persistsOnceAndIsStableAcrossCalls() {
        long before = repository.count();

        UUID first = service.getOrCreateInstallId();
        UUID second = service.getOrCreateInstallId();

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
        // Exactly one row should have been created across the two calls (idempotent).
        assertThat(repository.count()).isEqualTo(before + 1);
        assertThat(repository.findById(first)).isPresent();
    }
}
