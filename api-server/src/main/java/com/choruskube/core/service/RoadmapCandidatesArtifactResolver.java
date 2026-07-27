package com.choruskube.core.service;

import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.ResolvedArtifactEntry;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shared resolution of the Roadmap Provisioner analyzer's structured {@code roadmap_candidates.json}
 * artifact (Decision 1) into {@link CandidateEpicProposal} rows — used both by {@link
 * PendingGateService} (to populate {@code PendingGateResponse.candidateBreakdown} for display) and
 * by {@link RunService} (to resolve the materialization source when the reviewer submitted no
 * edits). Centralized here so the artifact-lookup and degrade-on-failure rules can't drift between
 * the two call sites.
 *
 * <p>Degrades to {@code null} — never throws — whenever the artifact isn't among the node's
 * resolved required artifacts, its content is missing/malformed, or it fails the same Bean
 * Validation constraints ({@code @NotBlank} title, {@code @Size(max = 8)} at every nesting level)
 * that {@code SignalRequest.editedCandidates} enforces on the reviewer-edited path. Without this
 * check an AI-authored artifact would materialize straight into Epic/Story/Task rows with none of
 * the guardrails a human-submitted edit is held to (e.g. a blank title silently becomes an empty
 * DB row instead of being rejected).
 */
@Service
public class RoadmapCandidatesArtifactResolver {

    private static final Logger logger = LoggerFactory.getLogger(RoadmapCandidatesArtifactResolver.class);

    static final String ARTIFACT_FILENAME = "roadmap_candidates.json";

    private final ArtifactResolutionService artifactResolutionService;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public RoadmapCandidatesArtifactResolver(
            ArtifactResolutionService artifactResolutionService,
            ArtifactService artifactService,
            ObjectMapper objectMapper,
            Validator validator) {
        this.artifactResolutionService = artifactResolutionService;
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /**
     * Mirrors {@code SignalRequest.editedCandidates}' own {@code @Valid @Size(max = 8)} field so an
     * artifact-sourced breakdown is validated against the identical constraint tree (top-level Epic
     * count plus the two-level {@code @Valid} cascade into Stories/Tasks) as a reviewer-edited one.
     */
    private record CandidateListEnvelope(
            @Valid @Size(max = 8) List<CandidateEpicProposal> candidates) {}

    /** Resolves the node's required artifacts fresh, then delegates to {@link #resolve(UUID, List)}. */
    public List<CandidateEpicProposal> resolve(UUID runId, UUID templateNodeId) {
        return resolve(runId, artifactResolutionService.resolveRequiredArtifacts(templateNodeId, runId));
    }

    /**
     * Resolves against an already-computed required-artifacts list, avoiding a redundant lookup
     * when the caller (e.g. {@link PendingGateService}) already resolved it for its own response.
     */
    public List<CandidateEpicProposal> resolve(UUID runId, List<ResolvedArtifactGroup> requiredArtifacts) {
        if (requiredArtifacts == null) {
            return null;
        }
        for (ResolvedArtifactGroup group : requiredArtifacts) {
            if (group.nodeExecutionId() == null) {
                continue;
            }
            boolean hasCandidatesFile =
                    group.artifacts().stream().map(ResolvedArtifactEntry::name).anyMatch(ARTIFACT_FILENAME::equals);
            if (!hasCandidatesFile) {
                continue;
            }
            try {
                String content = artifactService.getArtifactContent(runId, group.nodeExecutionId(), ARTIFACT_FILENAME);
                List<CandidateEpicProposal> candidates = objectMapper.readValue(
                        content,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, CandidateEpicProposal.class));
                Set<ConstraintViolation<CandidateListEnvelope>> violations =
                        validator.validate(new CandidateListEnvelope(candidates));
                if (!violations.isEmpty()) {
                    logger.warn(
                            "Rejected {} for run {}: failed validation ({})",
                            ARTIFACT_FILENAME,
                            runId,
                            violations.size());
                    return null;
                }
                return candidates;
            } catch (Exception e) {
                logger.warn("Failed to resolve {} for run {}: {}", ARTIFACT_FILENAME, runId, e.getMessage());
                return null;
            }
        }
        return null;
    }
}
