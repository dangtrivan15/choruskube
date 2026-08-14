package com.choruskube.core.service;

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
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.MilestoneRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.scope.ScopeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

/** Sole implementation of {@link MilestoneService} (Decision 8 of the roadmap-hierarchy feature). */
@Service
public class DefaultMilestoneService implements MilestoneService {

    private final MilestoneRepository repo;
    private final EpicRepository epicRepo;
    private final SoftwareProjectRepository softwareProjectRepo;
    private final AuthorizationService authService;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ScopeProvider scopeProvider;

    public DefaultMilestoneService(
            MilestoneRepository repo,
            EpicRepository epicRepo,
            SoftwareProjectRepository softwareProjectRepo,
            AuthorizationService authService,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher,
            ScopeProvider scopeProvider) {
        this.repo = repo;
        this.epicRepo = epicRepo;
        this.softwareProjectRepo = softwareProjectRepo;
        this.authService = authService;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.scopeProvider = scopeProvider;
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
     * batched-per-parent-aggregate pattern).
     */
    private List<MilestoneResponse> toResponses(List<Milestone> milestones) {
        Set<UUID> milestoneIds =
                milestones.stream().map(Milestone::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, Long> epicCountsByMilestoneId = milestoneIds.isEmpty()
                ? Map.of()
                : epicRepo.findByMilestoneIdIn(milestoneIds).stream()
                        .collect(Collectors.groupingBy(Epic::getMilestoneId, Collectors.counting()));
        return milestones.stream()
                .map(m -> buildResponse(m, epicCountsByMilestoneId.getOrDefault(m.getId(), 0L)))
                .toList();
    }

    private MilestoneResponse toResponse(Milestone milestone) {
        return buildResponse(milestone, epicRepo.countByMilestoneId(milestone.getId()));
    }

    private MilestoneResponse buildResponse(Milestone m, long epicCount) {
        return new MilestoneResponse(
                m.getId(),
                m.getName(),
                m.getDescription(),
                m.getSoftwareProjectId(),
                m.getTargetDate(),
                epicCount,
                m.getCreatedAt(),
                m.getUpdatedAt());
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
