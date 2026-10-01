package com.choruskube.core.service;

import com.choruskube.core.dto.CandidateDependency;
import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateMilestone;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.ResolvedArtifactEntry;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.Priority;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Shared resolution of the Roadmap Provisioner analyzer's structured {@code roadmap_candidates.json}
 * artifact into a {@link RoadmapCandidatesDocument} — used both by {@link
 * PendingGateService} (to populate {@code PendingGateResponse.candidateBreakdown} for display) and
 * by {@link RunService} (to resolve the materialization source when the reviewer submitted no
 * edits). Centralized here so the artifact-lookup and degrade-on-failure rules can't drift between
 * the two call sites.
 *
 * <p>Degrades to {@code null} — never throws — whenever the artifact isn't among the node's
 * resolved required artifacts, its content is missing/malformed, it fails the same Bean Validation
 * constraints ({@code @NotBlank} title, {@code @Size(max = 8)} at every nesting level) that {@code
 * SignalRequest.editedCandidates} enforces on the reviewer-edited path, or it declares a duplicate
 * {@code key} anywhere in the document — an ambiguous key makes every reference into
 * it unsafe to resolve, so unlike a single bad edge (below) this degrades the whole artifact rather
 * than picking an arbitrary winner. Without these checks an AI-authored artifact would materialize
 * straight into Epic/Story/Task rows with none of the guardrails a human-submitted edit is held to.
 *
 * <p>A single {@link CandidateDependency} whose {@code blocking}/{@code blocked} key doesn't
 * resolve, or that would close a within-artifact cycle, or a {@link CandidateEpicProposal#milestone()}
 * reference that doesn't resolve to a declared {@link CandidateMilestone#key()}, is instead DROPPED
 * — the rest of the document still resolves — mirroring {@code
 * DefaultRoadmapCandidateMaterializer}'s own best-effort stance on the same categories of problem,
 * so a reviewer sees the same kind of "some pieces were skipped" outcome whether it
 * surfaces at gate-display time or at materialization time.
 */
@Service
public class RoadmapCandidatesArtifactResolver {

    private static final Logger logger = LoggerFactory.getLogger(RoadmapCandidatesArtifactResolver.class);

    static final String ARTIFACT_FILENAME = "roadmap_candidates.json";

    private final ArtifactResolutionService artifactResolutionService;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final RoadmapAnchorLookup anchorLookup;
    private final InternalRunService internalRunService;

    public RoadmapCandidatesArtifactResolver(
            ArtifactResolutionService artifactResolutionService,
            ArtifactService artifactService,
            ObjectMapper objectMapper,
            Validator validator,
            RoadmapAnchorLookup anchorLookup,
            @Lazy InternalRunService internalRunService) {
        this.artifactResolutionService = artifactResolutionService;
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.anchorLookup = anchorLookup;
        this.internalRunService = internalRunService;
    }

    /** Resolves the node's required artifacts fresh, then delegates to {@link #resolve(UUID, List)}. */
    public RoadmapCandidatesDocument resolve(UUID runId, UUID templateNodeId) {
        return resolve(runId, artifactResolutionService.resolveRequiredArtifacts(templateNodeId, runId));
    }

    /**
     * Resolves against an already-computed required-artifacts list, avoiding a redundant lookup
     * when the caller (e.g. {@link PendingGateService}) already resolved it for its own response.
     */
    public RoadmapCandidatesDocument resolve(UUID runId, List<ResolvedArtifactGroup> requiredArtifacts) {
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
                RoadmapCandidatesDocument document = parseDocument(content);
                Set<ConstraintViolation<RoadmapCandidatesDocument>> violations = validator.validate(document);
                if (!violations.isEmpty()) {
                    logger.warn(
                            "Rejected {} for run {}: failed validation ({})",
                            ARTIFACT_FILENAME,
                            runId,
                            violations.size());
                    return null;
                }
                return fillExisting(runId, validateReferencesAndCycles(runId, document));
            } catch (Exception e) {
                logger.warn("Failed to resolve {} for run {}: {}", ARTIFACT_FILENAME, runId, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * Parses the artifact content into a {@link RoadmapCandidatesDocument}. A root JSON array is
     * the legacy shape (a bare array of candidate Epics) and is wrapped as {@code {
     * epics: [...] }} for back-compat — an artifact written before this feature shipped still
     * resolves.
     */
    private RoadmapCandidatesDocument parseDocument(String content) throws Exception {
        JsonNode root = objectMapper.readTree(content);
        if (root.isArray()) {
            List<CandidateEpicProposal> epics =
                    objectMapper.convertValue(root, new TypeReference<List<CandidateEpicProposal>>() {});
            return new RoadmapCandidatesDocument(null, epics, null);
        }
        return objectMapper.treeToValue(root, RoadmapCandidatesDocument.class);
    }

    /**
     * Post-Bean-Validation pass: rejects (degrades to {@code null}) the whole
     * document on a duplicate {@code key}; otherwise drops individual unresolved/cyclic references
     * and returns the rest of the document intact.
     */
    private RoadmapCandidatesDocument validateReferencesAndCycles(UUID runId, RoadmapCandidatesDocument document) {
        List<CandidateMilestone> milestones = document.milestones() != null ? document.milestones() : List.of();
        List<CandidateEpicProposal> epics = document.epics() != null ? document.epics() : List.of();
        List<CandidateDependency> dependencies = document.dependencies() != null ? document.dependencies() : List.of();

        Set<String> keys = new HashSet<>();
        for (CandidateMilestone m : milestones) {
            if (!addKeyIfAbsent(keys, m.key())) {
                logger.warn("Rejected {} for run {}: duplicate key '{}'", ARTIFACT_FILENAME, runId, m.key());
                return null;
            }
        }
        for (CandidateEpicProposal e : epics) {
            if (!addKeyIfAbsent(keys, e.key())) {
                logger.warn("Rejected {} for run {}: duplicate key '{}'", ARTIFACT_FILENAME, runId, e.key());
                return null;
            }
            for (CandidateStoryProposal s : e.stories() != null ? e.stories() : List.<CandidateStoryProposal>of()) {
                if (!addKeyIfAbsent(keys, s.key())) {
                    logger.warn("Rejected {} for run {}: duplicate key '{}'", ARTIFACT_FILENAME, runId, s.key());
                    return null;
                }
                for (CandidateTaskProposal t : s.tasks() != null ? s.tasks() : List.<CandidateTaskProposal>of()) {
                    if (!addKeyIfAbsent(keys, t.key())) {
                        logger.warn("Rejected {} for run {}: duplicate key '{}'", ARTIFACT_FILENAME, runId, t.key());
                        return null;
                    }
                }
            }
        }

        Set<String> milestoneKeys = new HashSet<>();
        for (CandidateMilestone m : milestones) {
            if (m.key() != null) {
                milestoneKeys.add(m.key());
            }
        }

        List<CandidateEpicProposal> fixedEpics = new ArrayList<>();
        for (CandidateEpicProposal e : epics) {
            if (e.milestone() != null && !milestoneKeys.contains(e.milestone())) {
                logger.warn(
                        "Dropping unresolved milestone reference '{}' on candidate Epic '{}' for run {}",
                        e.milestone(),
                        e.title(),
                        runId);
                fixedEpics.add(new CandidateEpicProposal(
                        e.title(),
                        e.description(),
                        e.motivation(),
                        e.repos(),
                        e.priority(),
                        e.stories(),
                        e.key(),
                        null,
                        e.existingId()));
            } else {
                fixedEpics.add(e);
            }
        }

        List<CandidateDependency> validEdges = new ArrayList<>();
        Map<String, Set<String>> declaredEdges = new HashMap<>();
        for (CandidateDependency dep : dependencies) {
            if (!keys.contains(dep.blocking()) || !keys.contains(dep.blocked())) {
                logger.warn(
                        "Dropping dependency edge with unresolved key for run {}: '{}' -> '{}'",
                        runId,
                        dep.blocking(),
                        dep.blocked());
                continue;
            }
            if (dep.blocking().equals(dep.blocked())) {
                logger.warn("Dropping self-referential dependency edge for run {}: '{}'", runId, dep.blocking());
                continue;
            }
            if (CandidateDependencyCycles.wouldCreateCycle(declaredEdges, dep.blocking(), dep.blocked())) {
                logger.warn(
                        "Dropping cyclic dependency edge for run {}: '{}' -> '{}'",
                        runId,
                        dep.blocking(),
                        dep.blocked());
                continue;
            }
            declaredEdges.computeIfAbsent(dep.blocking(), k -> new HashSet<>()).add(dep.blocked());
            validEdges.add(dep);
        }

        return new RoadmapCandidatesDocument(milestones, fixedEpics, validEdges);
    }

    private static boolean addKeyIfAbsent(Set<String> keys, String key) {
        if (key == null) {
            return true;
        }
        return keys.add(key);
    }

    /**
     * Fills every {@code existingId} anchor's display fields with its live title/description (and,
     * for an Epic, motivation and priority) — project-checked, through {@link RoadmapAnchorLookup},
     * so an anchor outside the run's own project is blanked rather than shown. Never throws: a
     * document with no anchor is returned unchanged with no project resolution and no lookup at
     * all, which keeps a legacy or Roadmap Provisioner document (and the many tests that build this
     * resolver with bare mocks) on the pre-existing path.
     */
    RoadmapCandidatesDocument fillExisting(UUID runId, RoadmapCandidatesDocument document) {
        if (document == null || !hasAnyAnchor(document.epics())) {
            return document;
        }
        UUID projectId;
        try {
            projectId = internalRunService.resolveSoftwareProjectId(runId);
        } catch (Exception e) {
            logger.warn(
                    "Could not resolve software project for run {} while filling anchors: {}", runId, e.getMessage());
            projectId = null;
        }
        List<CandidateEpicProposal> filledEpics = new ArrayList<>();
        for (CandidateEpicProposal epic :
                document.epics() != null ? document.epics() : List.<CandidateEpicProposal>of()) {
            filledEpics.add(fillEpic(epic, projectId));
        }
        return new RoadmapCandidatesDocument(document.milestones(), filledEpics, document.dependencies());
    }

    private CandidateEpicProposal fillEpic(CandidateEpicProposal epic, UUID projectId) {
        List<CandidateStoryProposal> filledStories = new ArrayList<>();
        for (CandidateStoryProposal story :
                epic.stories() != null ? epic.stories() : List.<CandidateStoryProposal>of()) {
            filledStories.add(fillStory(story, projectId));
        }
        if (epic.existingId() == null) {
            return new CandidateEpicProposal(
                    epic.title(),
                    epic.description(),
                    epic.motivation(),
                    epic.repos(),
                    epic.priority(),
                    filledStories,
                    epic.key(),
                    epic.milestone(),
                    null);
        }
        Optional<Epic> found = projectId != null ? anchorLookup.epic(epic.existingId(), projectId) : Optional.empty();
        if (found.isEmpty()) {
            return new CandidateEpicProposal(
                    "", "", null, epic.repos(), null, filledStories, epic.key(), epic.milestone(), epic.existingId());
        }
        Epic live = found.get();
        return new CandidateEpicProposal(
                live.getTitle(),
                live.getDescription(),
                live.getMotivation(),
                epic.repos(),
                displayPriority(live.getPriority()),
                filledStories,
                epic.key(),
                epic.milestone(),
                epic.existingId());
    }

    private CandidateStoryProposal fillStory(CandidateStoryProposal story, UUID projectId) {
        List<CandidateTaskProposal> filledTasks = new ArrayList<>();
        for (CandidateTaskProposal task : story.tasks() != null ? story.tasks() : List.<CandidateTaskProposal>of()) {
            filledTasks.add(fillTask(task, projectId));
        }
        if (story.existingId() == null) {
            return new CandidateStoryProposal(
                    story.title(), story.description(), filledTasks, story.key(), story.priority(), null);
        }
        Optional<Story> found =
                projectId != null ? anchorLookup.story(story.existingId(), projectId) : Optional.empty();
        if (found.isEmpty()) {
            return new CandidateStoryProposal("", "", filledTasks, story.key(), story.priority(), story.existingId());
        }
        Story live = found.get();
        return new CandidateStoryProposal(
                live.getTitle(), live.getDescription(), filledTasks, story.key(), story.priority(), story.existingId());
    }

    private CandidateTaskProposal fillTask(CandidateTaskProposal task, UUID projectId) {
        if (task.existingId() == null) {
            return task;
        }
        Optional<Task> found = projectId != null ? anchorLookup.task(task.existingId(), projectId) : Optional.empty();
        if (found.isEmpty()) {
            return new CandidateTaskProposal("", "", task.key(), task.priority(), task.existingId(), task.repoId());
        }
        Task live = found.get();
        return new CandidateTaskProposal(
                live.getTitle(), live.getDescription(), task.key(), task.priority(), task.existingId(), task.repoId());
    }

    private boolean hasAnyAnchor(List<CandidateEpicProposal> epics) {
        if (epics == null) {
            return false;
        }
        for (CandidateEpicProposal epic : epics) {
            if (epic == null) {
                continue;
            }
            if (epic.existingId() != null) {
                return true;
            }
            for (CandidateStoryProposal story :
                    epic.stories() != null ? epic.stories() : List.<CandidateStoryProposal>of()) {
                if (story == null) {
                    continue;
                }
                if (story.existingId() != null) {
                    return true;
                }
                for (CandidateTaskProposal task :
                        story.tasks() != null ? story.tasks() : List.<CandidateTaskProposal>of()) {
                    if (task != null && task.existingId() != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Capitalizes a {@link Priority}'s lower-case enum name to match the candidate DTO's free-text convention. */
    private static String displayPriority(Priority priority) {
        if (priority == null) {
            return null;
        }
        String name = priority.name();
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
