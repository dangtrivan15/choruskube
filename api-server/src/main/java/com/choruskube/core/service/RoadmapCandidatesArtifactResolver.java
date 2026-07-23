package com.choruskube.core.service;

import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.ResolvedArtifactEntry;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
 * resolved required artifacts, or its content is missing/malformed.
 */
@Service
public class RoadmapCandidatesArtifactResolver {

    private static final Logger logger = LoggerFactory.getLogger(RoadmapCandidatesArtifactResolver.class);

    static final String ARTIFACT_FILENAME = "roadmap_candidates.json";

    private final ArtifactResolutionService artifactResolutionService;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public RoadmapCandidatesArtifactResolver(
            ArtifactResolutionService artifactResolutionService,
            ArtifactService artifactService,
            ObjectMapper objectMapper) {
        this.artifactResolutionService = artifactResolutionService;
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

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
                return objectMapper.readValue(
                        content,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, CandidateEpicProposal.class));
            } catch (Exception e) {
                logger.warn("Failed to resolve {} for run {}: {}", ARTIFACT_FILENAME, runId, e.getMessage());
                return null;
            }
        }
        return null;
    }
}
