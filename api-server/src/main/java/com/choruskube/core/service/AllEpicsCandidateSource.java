package com.choruskube.core.service;

import com.choruskube.core.model.Epic;
import com.choruskube.core.repository.EpicRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Single-tenant default: every Epic is a candidate (Decision 7 — one Autopilot, no scope filter).
 *
 * <p>{@code findAll()} rather than a {@code ScopeProvider} specification on purpose: the tick runs
 * on a timer thread, and {@code ScopeProvider} reads a request-scoped tenant context that would
 * throw there.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class AllEpicsCandidateSource implements AutopilotCandidateSource {

    private final EpicRepository epicRepo;

    public AllEpicsCandidateSource(EpicRepository epicRepo) {
        this.epicRepo = epicRepo;
    }

    @Override
    public List<UUID> candidateEpicIds(UUID autopilotId) {
        return epicRepo.findAll().stream().map(Epic::getId).toList();
    }
}
