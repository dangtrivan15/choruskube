package com.choruskube.core.service;

import com.choruskube.core.dto.AtRiskItem;
import com.choruskube.core.dto.MilestoneAtRiskItemsResponse;
import com.choruskube.core.dto.MilestoneRequest;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.MilestoneUpdateRequest;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Milestone;
import com.choruskube.core.model.SoftwareProject;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.MilestoneRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.scope.ScopeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sole implementation of {@link MilestoneService} for the "Group Epics under a named Milestone /
 * Release" feature (Decisions 1–5).
 */
@Service
public class DefaultMilestoneService implements MilestoneService {

    private final MilestoneRepository repo;
    private final EpicRepository epicRepo;
    private final StoryRepository storyRepo;
    private final TaskRepository taskRepo;
    private final SoftwareProjectRepository softwareProjectRepo;
    private final AuthorizationService authService;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ScopeProvider scopeProvider;
    private final Clock clock;

    public DefaultMilestoneService(
            MilestoneRepository repo,
            EpicRepository epicRepo,
            StoryRepository storyRepo,
            TaskRepository taskRepo,
            SoftwareProjectRepository softwareProjectRepo,
            AuthorizationService authService,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher,
            ScopeProvider scopeProvider,
            Clock clock) {
        this.repo = repo;
        this.epicRepo = epicRepo;
        this.storyRepo = storyRepo;
        this.taskRepo = taskRepo;
        this.softwareProjectRepo = softwareProjectRepo;
        this.authService = authService;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.scopeProvider = scopeProvider;
        this.clock = clock;
    }

    @Override
    @Transactional
    public MilestoneResponse create(MilestoneRequest request) {
        SoftwareProject project = loadSoftwareProject(request.softwareProjectId());
        // Caller-vs-resource guard: the target project must belong to the caller's org. No-op
        // under always-allow; throws ForbiddenException (403) on mismatch under auth.
        authService.checkOrgAccess("software_project", project.getId());
        assertNameAvailable(project.getId(), request.name(), null);

        Milestone milestone = new Milestone();
        milestone.setSoftwareProjectId(project.getId());
        milestone.setName(request.name());
        milestone.setDescription(request.description());
        milestone.setTargetDate(request.targetDate());
        milestone = repo.save(milestone);

        applicationEventPublisher.publishEvent(MappableCreated.of("milestone", milestone.getId()));
        auditSink.record(
                AuditSink.MILESTONE_CREATED, "milestone", milestone.getId(), detailJson(null, snapshot(milestone)));
        return toResponse(milestone);
    }

    @Override
    @Transactional
    public MilestoneResponse findOrCreate(
            UUID softwareProjectId, String name, String description, LocalDate targetDate) {
        // Agent/internal entry (RoadmapCandidateMaterializer): no request-scoped TenantContext,
        // so — same as InternalRunService's create-family — this reads via a ScopeProvider-scoped
        // Specification rather than checkOrgAccess (§3.3 of the roadmap dependencies/priorities/
        // milestones spec), and is unaudited on the create branch below, mirroring
        // EpicService#create(EpicRequest, UUID)'s identical tradeoff.
        Specification<Milestone> spec = scopeProvider
                .scope(Milestone.class)
                .and((root, query, cb) -> cb.equal(root.get("softwareProjectId"), softwareProjectId))
                .and((root, query, cb) -> cb.equal(cb.lower(root.get("name")), name.toLowerCase(Locale.ROOT)));
        Optional<Milestone> existing = repo.findOne(spec);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        Milestone milestone = new Milestone();
        milestone.setSoftwareProjectId(softwareProjectId);
        milestone.setName(name);
        milestone.setDescription(description);
        milestone.setTargetDate(targetDate);
        milestone = repo.save(milestone);

        applicationEventPublisher.publishEvent(MappableCreated.of("milestone", milestone.getId()));
        return toResponse(milestone);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MilestoneResponse> list(UUID softwareProjectId, Pageable pageable) {
        // Always routed through ScopeProvider (§3.4): a derived findBySoftwareProjectId… finder
        // would skip tenant scoping entirely and leak another org's Milestones when a caller
        // supplies a foreign softwareProjectId, so the optional project filter is `.and`-ed onto
        // the scoped Specification rather than backing the list with its own finder.
        Specification<Milestone> spec = scopeProvider.scope(Milestone.class);
        if (softwareProjectId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("softwareProjectId"), softwareProjectId));
        }
        Page<Milestone> page = repo.findAll(spec, pageable);
        List<MilestoneResponse> content = toResponses(page.getContent());
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public MilestoneResponse get(UUID id) {
        Milestone milestone = findOrThrow(id);
        authService.checkOrgAccess("milestone", id);
        return toResponse(milestone);
    }

    @Override
    @Transactional
    public MilestoneResponse update(UUID id, MilestoneUpdateRequest request) {
        Milestone milestone = findOrThrow(id);
        authService.checkOrgAccess("milestone", id);
        Map<String, Object> beforeSnapshot = snapshot(milestone);

        assertNameAvailable(milestone.getSoftwareProjectId(), request.name(), milestone.getName());
        milestone.setName(request.name());
        milestone.setDescription(request.description());
        milestone.setTargetDate(request.targetDate());
        milestone = repo.save(milestone);

        auditSink.record(AuditSink.MILESTONE_UPDATED, "milestone", id, detailJson(beforeSnapshot, snapshot(milestone)));
        return toResponse(milestone);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Milestone milestone = findOrThrow(id);
        authService.checkOrgAccess("milestone", id);
        auditSink.record(AuditSink.MILESTONE_DELETED, "milestone", id, detailJson(snapshot(milestone), null));
        // No application-level un-tagging of Epics: epic.milestone_id is ON DELETE SET NULL
        // (Decision 2), so the DB itself nulls every referencing Epic's milestone_id when this
        // row is removed.
        repo.delete(milestone);
    }

    @Override
    @Transactional(readOnly = true)
    public MilestoneAtRiskItemsResponse getAtRiskItems(UUID id) {
        Milestone milestone = findOrThrow(id);
        authService.checkOrgAccess("milestone", id);
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

        List<Epic> epics = epicRepo.findByMilestoneId(id);
        if (epics.isEmpty()) {
            return new MilestoneAtRiskItemsResponse(List.of());
        }
        Set<UUID> epicIds = epics.stream().map(Epic::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Story> stories = storyRepo.findByEpicIdIn(epicIds);
        Map<UUID, List<Story>> storiesByEpicId = stories.stream().collect(Collectors.groupingBy(Story::getEpicId));
        Set<UUID> storyIds = stories.stream().map(Story::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Task> tasks = storyIds.isEmpty() ? List.of() : taskRepo.findByStoryIdIn(storyIds);
        Map<UUID, List<Task>> tasksByStoryId = tasks.stream().collect(Collectors.groupingBy(Task::getStoryId));

        List<AtRiskItem> items = new ArrayList<>();
        for (Epic epic : epics) {
            List<Story> epicStories = storiesByEpicId.getOrDefault(epic.getId(), List.of());
            List<Task> epicTasks = new ArrayList<>();
            for (Story story : epicStories) {
                List<Task> storyTasks = tasksByStoryId.getOrDefault(story.getId(), List.of());
                epicTasks.addAll(storyTasks);
                String storyStatus = RollupCalculator.effectiveStatus(story.getStage(), storyTasks);
                if (isAtRisk(story.getTargetDate(), storyStatus, today)) {
                    items.add(new AtRiskItem(
                            story.getId(), "STORY", story.getTitle(), story.getTargetDate(), storyStatus));
                }
            }
            String epicStatus = RollupCalculator.effectiveStatus(epic.getStage(), epicTasks);
            if (isAtRisk(epic.getTargetDate(), epicStatus, today)) {
                items.add(new AtRiskItem(epic.getId(), "EPIC", epic.getTitle(), epic.getTargetDate(), epicStatus));
            }
        }
        // Every item in this list was added because it is overdue (isAtRisk requires targetDate !=
        // null), so targetDate is never null here — no null-safe Comparator needed.
        items.sort(
                Comparator.comparing(AtRiskItem::targetDate).thenComparing(item -> "EPIC".equals(item.tier()) ? 0 : 1));
        return new MilestoneAtRiskItemsResponse(items);
    }

    /**
     * Rejects a create/rename when another Milestone in the same project already uses {@code
     * name} (case-insensitive, Decision 3). {@code currentName} is the Milestone's own current
     * name on an update call (renaming to the same name, in a different case, is not a
     * collision); {@code null} on create.
     */
    private void assertNameAvailable(UUID softwareProjectId, String name, String currentName) {
        if (currentName != null && currentName.equalsIgnoreCase(name)) {
            return;
        }
        if (repo.existsBySoftwareProjectIdAndNameIgnoreCase(softwareProjectId, name)) {
            throw new ConflictException("A Milestone named '" + name + "' already exists in this project");
        }
    }

    /**
     * Batch-projection of a list of {@link Milestone} entities into response DTOs. Avoids the N+1
     * a per-Milestone {@code countByMilestoneId} call would incur by loading every Epic tagged
     * with any Milestone on the page in one query and grouping in memory (mirrors {@code
     * DefaultEpicService#computeRollups}/{@code DefaultRoadmapTimelineService.getTimeline}'s own
     * batched-per-parent-aggregate pattern). {@code epicCount}, {@code progress}, {@code atRisk}
     * and {@code atRiskItemCount} are all derived from the same {@link #computeAggregates} walk,
     * so this page costs a fixed three queries (Epic → Story → Task) regardless of page size.
     */
    private List<MilestoneResponse> toResponses(List<Milestone> milestones) {
        Map<UUID, MilestoneAggregate> aggregatesByMilestoneId = computeAggregates(milestones);
        return milestones.stream()
                .map(m -> buildResponse(m, aggregatesByMilestoneId.get(m.getId())))
                .toList();
    }

    private MilestoneResponse toResponse(Milestone milestone) {
        MilestoneAggregate aggregate = computeAggregates(List.of(milestone)).get(milestone.getId());
        return buildResponse(milestone, aggregate);
    }

    private MilestoneResponse buildResponse(Milestone m, MilestoneAggregate aggregate) {
        return new MilestoneResponse(
                m.getId(),
                m.getName(),
                m.getDescription(),
                m.getSoftwareProjectId(),
                m.getTargetDate(),
                aggregate.epicCount(),
                aggregate.progress(),
                aggregate.atRisk(),
                aggregate.atRiskItemCount(),
                m.getCreatedAt(),
                m.getUpdatedAt());
    }

    /**
     * Per-Milestone aggregate bundle: how many Epics are tagged with it, its Task-count {@link
     * MilestoneResponse.Progress} rollup, and its at-risk verdict/count — everything {@link
     * #buildResponse} needs beyond the Milestone row itself.
     */
    private record MilestoneAggregate(
            long epicCount, MilestoneResponse.Progress progress, boolean atRisk, long atRiskItemCount) {}

    /**
     * Batch-computes {@link MilestoneAggregate} for every given Milestone in a fixed three queries
     * (Epic → Story → Task), mirroring {@code DefaultEpicService#computeRollups}'s own
     * batched-per-parent pattern one level up the hierarchy. Every Milestone passed in gets an
     * entry, including ones with no Epics (all-zero aggregate, never at risk).
     *
     * <p>Per Epic/Story, "at risk" is {@code targetDate} strictly before today (per the injected
     * {@link Clock}, never {@link Clock#getZone()} — see {@code DefaultRoadmapTimelineService}) AND
     * {@link RollupCalculator#effectiveStatus} not {@code done}. A Milestone itself is at risk iff
     * its own {@code targetDate} is overdue AND at least one of its Epics is incomplete;
     * {@code atRiskItemCount} separately counts every at-risk Epic and Story under it, regardless
     * of the Milestone-level verdict.
     */
    private Map<UUID, MilestoneAggregate> computeAggregates(List<Milestone> milestones) {
        if (milestones.isEmpty()) {
            return Map.of();
        }
        Set<UUID> milestoneIds =
                milestones.stream().map(Milestone::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

        List<Epic> epics = epicRepo.findByMilestoneIdIn(milestoneIds);
        Map<UUID, List<Epic>> epicsByMilestoneId = epics.stream().collect(Collectors.groupingBy(Epic::getMilestoneId));

        Set<UUID> epicIds = epics.stream().map(Epic::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Story> stories = epicIds.isEmpty() ? List.of() : storyRepo.findByEpicIdIn(epicIds);
        Map<UUID, List<Story>> storiesByEpicId = stories.stream().collect(Collectors.groupingBy(Story::getEpicId));

        Set<UUID> storyIds = stories.stream().map(Story::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Task> tasks = storyIds.isEmpty() ? List.of() : taskRepo.findByStoryIdIn(storyIds);
        Map<UUID, List<Task>> tasksByStoryId = tasks.stream().collect(Collectors.groupingBy(Task::getStoryId));

        Map<UUID, MilestoneAggregate> result = new HashMap<>();
        for (Milestone milestone : milestones) {
            List<Epic> milestoneEpics = epicsByMilestoneId.getOrDefault(milestone.getId(), List.of());
            List<Task> milestoneTasks = new ArrayList<>();
            boolean anyIncompleteEpic = false;
            long atRiskItemCount = 0;
            for (Epic epic : milestoneEpics) {
                List<Story> epicStories = storiesByEpicId.getOrDefault(epic.getId(), List.of());
                List<Task> epicTasks = new ArrayList<>();
                for (Story story : epicStories) {
                    List<Task> storyTasks = tasksByStoryId.getOrDefault(story.getId(), List.of());
                    epicTasks.addAll(storyTasks);
                    String storyStatus = RollupCalculator.effectiveStatus(story.getStage(), storyTasks);
                    if (isAtRisk(story.getTargetDate(), storyStatus, today)) {
                        atRiskItemCount++;
                    }
                }
                milestoneTasks.addAll(epicTasks);
                String epicStatus = RollupCalculator.effectiveStatus(epic.getStage(), epicTasks);
                if (isAtRisk(epic.getTargetDate(), epicStatus, today)) {
                    atRiskItemCount++;
                }
                if (!WorkItemStatus.done.name().equals(epicStatus)) {
                    anyIncompleteEpic = true;
                }
            }
            RollupCalculator.Rollup rollup = RollupCalculator.compute(milestoneTasks);
            MilestoneResponse.Progress progress = new MilestoneResponse.Progress(
                    rollup.totalTasks(),
                    rollup.doneTasks(),
                    rollup.startedTasks() - rollup.doneTasks(),
                    rollup.totalTasks() - rollup.startedTasks());
            boolean milestoneOverdue = milestone.getTargetDate() != null
                    && milestone.getTargetDate().isBefore(today);
            boolean atRisk = milestoneOverdue && anyIncompleteEpic;
            result.put(
                    milestone.getId(),
                    new MilestoneAggregate(milestoneEpics.size(), progress, atRisk, atRiskItemCount));
        }
        return result;
    }

    /**
     * {@code true} iff {@code targetDate} is strictly before {@code today} AND {@code
     * effectiveStatus} is not {@code done} — the same rule applied at the Epic/Story tier in {@link
     * #computeAggregates} and in {@link #getAtRiskItems}.
     */
    private static boolean isAtRisk(LocalDate targetDate, String effectiveStatus, LocalDate today) {
        boolean overdue = targetDate != null && targetDate.isBefore(today);
        boolean incomplete = !WorkItemStatus.done.name().equals(effectiveStatus);
        return overdue && incomplete;
    }

    private SoftwareProject loadSoftwareProject(UUID softwareProjectId) {
        if (softwareProjectId == null) {
            throw new BadRequestException("softwareProjectId is required");
        }
        SoftwareProject project = softwareProjectRepo
                .findById(softwareProjectId)
                .orElseThrow(() -> new NotFoundException("SoftwareProject not found: " + softwareProjectId));
        if (project.getDeletedAt() != null) {
            throw new NotFoundException("SoftwareProject has been deleted: " + softwareProjectId);
        }
        return project;
    }

    private Milestone findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Milestone not found: " + id));
    }

    private Map<String, Object> snapshot(Milestone m) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", m.getName());
        snap.put("description", m.getDescription());
        snap.put(
                "software_project_id",
                m.getSoftwareProjectId() != null ? m.getSoftwareProjectId().toString() : null);
        snap.put("target_date", m.getTargetDate() != null ? m.getTargetDate().toString() : null);
        return snap;
    }

    private String detailJson(Object before, Object after) {
        return AuditDetail.json(objectMapper, before, after);
    }
}
