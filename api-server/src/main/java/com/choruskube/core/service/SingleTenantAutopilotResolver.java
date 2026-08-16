package com.choruskube.core.service;

import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.model.Autopilot;
import com.choruskube.core.repository.AutopilotRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Single-tenant default: one installation, one Autopilot, and every caller means that one
 * (Decision 7).
 *
 * <p>Gated the way every other OSS seam here is, so a downstream implementation replaces this bean
 * rather than colliding with it — see {@code NoOpScopeProvider}, {@link AllEpicsCandidateSource},
 * {@code AlwaysAllowAuthorizationStrategy}, {@code NoOpOrgSecurity}, {@code
 * EnvGitHubCredentialResolver}.
 *
 * <p>{@code findAll()} rather than a {@code ScopeProvider} specification on purpose: {@link
 * #findAllEngaged()} is called from the tick's timer thread, and {@code ScopeProvider} reads a
 * request-scoped tenant context that would throw there.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class SingleTenantAutopilotResolver implements AutopilotResolver {

    private final AutopilotRepository autopilotRepo;
    private final ApplicationEventPublisher applicationEventPublisher;

    public SingleTenantAutopilotResolver(
            AutopilotRepository autopilotRepo, ApplicationEventPublisher applicationEventPublisher) {
        this.autopilotRepo = autopilotRepo;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public Optional<UUID> forCurrentScope() {
        return findSingleton().map(Autopilot::getId);
    }

    @Override
    public UUID getOrCreateForCurrentScope() {
        return forCurrentScope().orElseGet(() -> {
            UUID id = UUID.randomUUID();
            autopilotRepo.insertDefaults(id);
            // Same transaction as the insert, and immediately after it — the shape every other
            // creation site in this codebase uses. Core has no listener for it; downstream this is
            // what writes the row's ownership, and a row created without one is one the scope
            // provider cannot resolve afterwards.
            applicationEventPublisher.publishEvent(MappableCreated.of("autopilot", id));
            return id;
        });
    }

    @Override
    public List<UUID> findAllEngaged() {
        // The singleton, filtered — not every engaged row. A concurrent first-write can leave a
        // second row behind (see findSingleton), and that loser is inert by definition: ticking it
        // would give this installation two passes competing for one max_parallel budget.
        return findSingleton().filter(Autopilot::isEngaged).map(Autopilot::getId).stream()
                .toList();
    }

    /**
     * The singleton (Decision 7). Ordered rather than "whichever row came back first" so that if a
     * concurrent first-write ever does produce a second row, every replica still agrees on which
     * one is the Autopilot instead of alternating between them.
     *
     * <p>The loser of such a race is inert — it is never ticked and holds no runs — but it is not
     * invisible: the request that created it returns and publishes the ORPHAN's status, so that one
     * client briefly renders a row that will never tick. The next tick publishes the canonical row
     * within the scheduler interval and the display corrects itself.
     */
    private Optional<Autopilot> findSingleton() {
        return autopilotRepo.findAll().stream()
                .min(Comparator.comparing(Autopilot::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Autopilot::getId));
    }
}
