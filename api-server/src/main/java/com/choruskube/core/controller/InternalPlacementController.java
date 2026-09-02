package com.choruskube.core.controller;

import com.choruskube.core.dto.InternalNamespacesResponse;
import com.choruskube.core.dto.InternalRunNamespaceResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.RunPlacementService;
import java.util.UUID;
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
 */
@RestController
@RequestMapping("/internal")
public class InternalPlacementController {

    private final RunPlacementService placements;
    private final WorkflowRunRepository runRepo;
    private final String defaultNamespace;

    public InternalPlacementController(
            RunPlacementService placements,
            WorkflowRunRepository runRepo,
            @Value("${temporal.namespace}") String defaultNamespace) {
        this.placements = placements;
        this.runRepo = runRepo;
        this.defaultNamespace = defaultNamespace;
    }

    @GetMapping("/placements")
    public InternalNamespacesResponse placements() {
        return new InternalNamespacesResponse(
                placements.namespaces().stream().sorted().toList());
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
