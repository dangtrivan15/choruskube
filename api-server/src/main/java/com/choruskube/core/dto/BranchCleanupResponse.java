package com.choruskube.core.dto;

import java.util.List;

/** Per-repository results of a run's stale run-branch cleanup. See {@code BranchCleanupService}. */
public record BranchCleanupResponse(List<BranchCleanupResult> results) {}
