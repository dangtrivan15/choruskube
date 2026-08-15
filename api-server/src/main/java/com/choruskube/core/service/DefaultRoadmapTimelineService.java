package com.choruskube.core.service;

import com.choruskube.core.dto.MilestoneRef;
import com.choruskube.core.dto.RoadmapTimelineResponse;
import com.choruskube.core.dto.TimelineEpicSummary;
import com.choruskube.core.dto.TimelineStorySummary;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Milestone;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.MilestoneRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.scope.ScopeProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole implementation of {@link RoadmapTimelineService} (Decision 8). */
@Service
public class DefaultRoadmapTimelineService implements RoadmapTimelineService {

    /**
     * "Stalled" threshold (visually flag blocked/stalled work feature): an {@code in_progress}
     * Epic/Story whose {@code updatedAt} is older than this is considered stalled, regardless of
     * dependency readiness.
     */
    private static final Duration STALL_THRESHOLD = Duration.ofDays(14);

    private final EpicRepository epicRepo;
    private final StoryRepository storyRepo;
    private final MilestoneRepository milestoneRepo;
    private final ScopeProvider scopeProvider;
    private final EpicReadinessAssembler readinessAssembler;
    private final Clock clock;

    public DefaultRoadmapTimelineService(
            EpicRepository epicRepo,
            StoryRepository storyRepo,
            MilestoneRepository milestoneRepo,
            ScopeProvider scopeProvider,
            EpicReadinessAssembler readinessAssembler,
            Clock clock) {
        this.epicRepo = epicRepo;
        this.storyRepo = storyRepo;
        this.milestoneRepo = milestoneRepo;
        this.scopeProvider = scopeProvider;
        this.readinessAssembler = readinessAssembler;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public RoadmapTimelineResponse getTimeline() {
        // Same scoping call DefaultEpicService.list() makes — DefaultRoadmapGraphService is NOT
        // the precedent here (it authorizes a single Epic via checkOrgAccess instead). Timeline
        // has no pagination param to source a Sort from, so it builds one explicitly rather than
        // relying on findAll(Specification)'s no-arg overload, which has no ORDER BY.
        Specification<Epic> spec = scopeProvider.scope(Epic.class);
        List<Epic> epics = epicRepo.findAll(spec, Sort.by(Sort.Direction.ASC, "createdAt"));
        if (epics.isEmpty()) {
            return new RoadmapTimelineResponse(List.of());
        }

        Set<UUID> epicIds = epics.stream().map(Epic::getId).collect(Collectors.toSet());
        // Batch query (mirrors DefaultEpicService#computeRollups) — avoids N+1 over the Epic page.
        List<Story> stories = storyRepo.findByEpicIdIn(epicIds);
        Map<UUID, List<Story>> storiesByEpicId = stories.stream().collect(Collectors.groupingBy(Story::getEpicId));

        // Batch-load Milestones referenced by any Epic on this page (mirrors DefaultEpicService's
        // own batch-load in #toResponses) — avoids an N+1 lookup per Epic lane.
        Set<UUID> milestoneIds = epics.stream()
                .map(Epic::getMilestoneId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, MilestoneRef> milestoneRefsById = milestoneIds.isEmpty()
                ? Map.of()
                : milestoneRepo.findAllById(milestoneIds).stream()
                        .collect(Collectors.toMap(Milestone::getId, m -> new MilestoneRef(m.getId(), m.getName())));

        List<TimelineEpicSummary> epicSummaries = epics.stream()
                .map(e -> toEpicSummary(e, storiesByEpicId, milestoneRefsById))
                .toList();
        return new RoadmapTimelineResponse(epicSummaries);
    }

    private TimelineEpicSummary toEpicSummary(
            Epic epic, Map<UUID, List<Story>> storiesByEpicId, Map<UUID, MilestoneRef> milestoneRefsById) {
        // Per-Epic readiness pass (accepted N+1-per-Epic tradeoff, Decision 1 of the blocked/
        // stalled feature) — mirrors DefaultRoadmapGraphService#assemble's own single-Epic call,
        // just run once per scoped Epic in this loop instead of once for a single requested Epic.
        // Public (non-run-scoped) read path: internal=false, runId=null.
        EpicReadinessAssembler.EpicCandidates candidates = readinessAssembler.loadEpicCandidates(epic.getId());
        EpicReadinessAssembler.Assembly assembly =
                readinessAssembler.assemble(candidates.candidateIds(), candidates.statusById(), false, null);

        List<TimelineStorySummary> stories = storiesByEpicId.getOrDefault(epic.getId(), List.of()).stream()
                .sorted(Comparator.comparing(Story::getCreatedAt))
                .map(s -> toStorySummary(s, assembly))
                .toList();
        MilestoneRef milestone = epic.getMilestoneId() != null ? milestoneRefsById.get(epic.getMilestoneId()) : null;
        return new TimelineEpicSummary(
                epic.getId(),
                epic.getTitle(),
                epic.getStage().name(),
                epic.getPriority().name(),
                epic.getCreatedAt(),
                epic.getUpdatedAt(),
                stories,
                stalled(epic.getStage(), epic.getUpdatedAt(), clock),
                milestone);
    }

    private TimelineStorySummary toStorySummary(Story story, EpicReadinessAssembler.Assembly assembly) {
        Readiness readiness = assembly.readinessById().getOrDefault(story.getId(), Readiness.READY);
        return new TimelineStorySummary(
                story.getId(),
                story.getEpicId(),
                story.getTitle(),
                story.getStage().name(),
                story.getPriority().name(),
                story.getCreatedAt(),
                story.getUpdatedAt(),
                readiness,
                stalled(story.getStage(), story.getUpdatedAt(), clock));
    }

    /**
     * {@code true} iff {@code stage} is {@code in_progress} and {@code updatedAt} is more than
     * {@link #STALL_THRESHOLD} in the past relative to {@code clock}. {@code backlog}, {@code
     * done}, and {@code rolled_out} items are never stalled regardless of age.
     */
    private static boolean stalled(WorkItemStatus stage, Instant updatedAt, Clock clock) {
        return stage == WorkItemStatus.in_progress
                && Duration.between(updatedAt, clock.instant()).compareTo(STALL_THRESHOLD) > 0;
    }
}
