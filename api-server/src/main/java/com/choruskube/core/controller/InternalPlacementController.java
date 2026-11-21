package com.choruskube.core.controller;

import com.choruskube.core.credential.ControlPlaneTemporalTokens;
import com.choruskube.core.credential.TemporalTokenState;
import com.choruskube.core.dto.InternalNamespacesResponse;
import com.choruskube.core.dto.InternalRunNamespaceResponse;
import com.choruskube.core.dto.NamespaceCredentialResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.RunPlacementService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the orchestrator learns which Temporal namespaces to serve, and where a run's workflow
 * lives.
 *
 * <p>Neither path carries a {@code node-executions/{id}} segment, so neither is reachable with an
 * agent's per-execution secret — only the orchestrator's. A namespace name tells its holder which
 * deployment operates a Fleet, which an agent has no reason to learn.
 *
 * <p>{@code /placements} carries each namespace's bearer token when a control-plane token source
 * is wired in, so only the orchestrator's secret opens it, and it is never logged.
 */
@RestController
@RequestMapping("/internal")
public class InternalPlacementController {

    /**
     * Below the orchestrator's 30-second roster HTTP timeout, so a namespace the token source
     * cannot answer in time is reported unavailable rather than failing the whole roster fetch.
     */
    static final Duration ROSTER_TOKEN_WAIT = Duration.ofSeconds(10);

    private final RunPlacementService placements;
    private final WorkflowRunRepository runRepo;
    private final String defaultNamespace;
    private final ControlPlaneTemporalTokens tokens;

    public InternalPlacementController(
            RunPlacementService placements,
            WorkflowRunRepository runRepo,
            @Value("${temporal.namespace}") String defaultNamespace,
            ControlPlaneTemporalTokens tokens) {
        this.placements = placements;
        this.runRepo = runRepo;
        this.defaultNamespace = defaultNamespace;
        this.tokens = tokens;
    }

    @GetMapping("/placements")
    public InternalNamespacesResponse placements() {
        List<String> namespaces = placements.namespaces().stream().sorted().toList();
        Map<String, TemporalTokenState> states = tokens.states(namespaces, ROSTER_TOKEN_WAIT);
        Map<String, NamespaceCredentialResponse> credentials = namespaces.stream()
                .collect(Collectors.toMap(
                        ns -> ns,
                        ns -> NamespaceCredentialResponse.from(states.get(ns)),
                        (a, b) -> a,
                        LinkedHashMap::new));
        return new InternalNamespacesResponse(namespaces, credentials);
    }

    /**
     * Reports where an existing run already runs — never {@link RunPlacementService#placeFor},
     * which decides and records a placement and requires the caller's own transaction. A run
     * predating the {@code temporal_namespace} column reports the deployment's configured
     * namespace, which is where it actually is, by construction.
     */
    @GetMapping("/runs/{runId}/placement")
    public InternalRunNamespaceResponse placement(@PathVariable UUID runId) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        String namespace = run.getTemporalNamespace();
        return new InternalRunNamespaceResponse(
                namespace == null || namespace.isBlank() ? defaultNamespace : namespace);
    }
}
