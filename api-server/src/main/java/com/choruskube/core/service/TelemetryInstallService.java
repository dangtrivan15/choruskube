package com.choruskube.core.service;

import com.choruskube.core.model.TelemetryInstall;
import com.choruskube.core.repository.TelemetryInstallRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides the stable, anonymous install id used by telemetry. Returns the existing row's id,
 * or creates one if none exists. Idempotent and stable across calls/restarts.
 */
@Service
public class TelemetryInstallService {

    private final TelemetryInstallRepository repository;

    public TelemetryInstallService(TelemetryInstallRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UUID getOrCreateInstallId() {
        return repository.findAll().stream()
                .findFirst()
                .map(TelemetryInstall::getInstallId)
                .orElseGet(() ->
                        repository.save(new TelemetryInstall(UUID.randomUUID())).getInstallId());
    }
}
