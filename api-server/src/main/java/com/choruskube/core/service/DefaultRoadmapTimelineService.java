package com.choruskube.core.service;

import com.choruskube.core.dto.RoadmapTimelineResponse;
import com.choruskube.core.dto.TimelineEpicSummary;
import com.choruskube.core.dto.TimelineStorySummary;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.scope.ScopeProvider;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    private final EpicRepository epicRepo;
    private final StoryRepository storyRepo;
    private final ScopeProvider scopeProvider;

    public DefaultRoadmapTimelineService(
            EpicRepository epicRepo, StoryRepository storyRepo, ScopeProvider scopeProvider) {
        this.epicRepo = epicRepo;
        this.storyRepo = storyRepo;
        this.scopeProvider = scopeProvider;
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

        List<TimelineEpicSummary> epicSummaries =
                epics.stream().map(e -> toEpicSummary(e, storiesByEpicId)).toList();
        return new RoadmapTimelineResponse(epicSummaries);
    }

    private TimelineEpicSummary toEpicSummary(Epic epic, Map<UUID, List<Story>> storiesByEpicId) {
        List<TimelineStorySummary> stories = storiesByEpicId.getOrDefault(epic.getId(), List.of()).stream()
                .sorted(Comparator.comparing(Story::getCreatedAt))
                .map(DefaultRoadmapTimelineService::toStorySummary)
                .toList();
        return new TimelineEpicSummary(
                epic.getId(),
                epic.getTitle(),
                epic.getStage().name(),
                epic.getCreatedAt(),
                epic.getUpdatedAt(),
                stories);
    }

    private static TimelineStorySummary toStorySummary(Story story) {
        return new TimelineStorySummary(
                story.getId(),
                story.getEpicId(),
                story.getTitle(),
                story.getStage().name(),
                story.getCreatedAt(),
                story.getUpdatedAt());
    }
}
