package com.choruskube.core.service;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The single place that turns "this deployment may have a placement policy" into an answer.
 * Every caller gets a real namespace and queue, so no call site repeats the absent-bean case.
 */
@Service
public class RunPlacementService {

    private final Optional<RunPlacementResolver> resolver;
    private final String defaultNamespace;
    private final String defaultTaskQueue;

    public RunPlacementService(
            Optional<RunPlacementResolver> resolver,
            @Value("${temporal.namespace}") String defaultNamespace,
            @Value("${temporal.task-queue}") String defaultTaskQueue) {
        this.resolver = resolver;
        this.defaultNamespace = defaultNamespace;
        this.defaultTaskQueue = defaultTaskQueue;
    }

    public RunPlacement placeFor(UUID runId) {
        return resolver.map(r -> r.placeFor(runId))
                .orElseGet(() -> new RunPlacement(defaultNamespace, defaultTaskQueue));
    }

    /**
     * The configured namespace is always in the roster. Workflows already running there survive
     * any change of policy, and nothing else would tell the orchestrator to keep serving them.
     */
    public Set<String> namespaces() {
        Set<String> all = new HashSet<>();
        all.add(defaultNamespace);
        resolver.ifPresent(r -> all.addAll(r.namespaces()));
        return Set.copyOf(all);
    }
}
