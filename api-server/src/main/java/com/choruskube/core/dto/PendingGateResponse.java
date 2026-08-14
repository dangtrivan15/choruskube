package com.choruskube.core.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PendingGateResponse(
        UUID nodeExecutionId,
        UUID runId,
        String runStatus,
        String runName,
        String nodeLabel,
        int iteration,
        Integer timeoutSeconds,
        Instant waitingSince,
        String status,
        List<PredecessorOutput> predecessorOutputs,
        List<ResolvedArtifactGroup> requiredArtifacts,
        List<String> decisionOptions,
        /**
         * The analyzer's structured candidate Epic/Story/Task breakdown (Decision 1), parsed from
         * {@code roadmap_candidates.json}, or {@code null} if this gate's template doesn't produce
         * one, or the artifact is missing/malformed (falls back to the raw-artifact display).
         */
        List<CandidateEpicProposal> candidateBreakdown,
        /**
         * Why this run was escalated to the Supervisor, or {@code null} for an ordinary gate — the
         * Supervisor has no inbound edges, so {@code predecessorOutputs} carries no context for it;
         * this is the replacement. Also {@code null} when the gate IS the Supervisor but nothing has
         * escalated yet.
         */
        EscalationContext escalation) {}
