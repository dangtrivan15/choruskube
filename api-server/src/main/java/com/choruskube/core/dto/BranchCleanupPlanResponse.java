package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * The repos a completed run touched, for the orchestrator to carry through to the Worker's
 * branch-cleanup step. Read-only: resolves no credential and makes no GitHub call.
 */
public record BranchCleanupPlanResponse(List<Repo> repos) {

    public record Repo(UUID gitRepoId, String ownerRepo, String defaultBranch) {}
}
