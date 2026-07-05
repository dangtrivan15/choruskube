package com.choruskube.core.service;

import com.choruskube.core.config.GraphIds;
import com.choruskube.core.dto.CreateRunRequest;
import com.choruskube.core.dto.FeatureProposalRequest;
import com.choruskube.core.dto.FeatureProposalResponse;
import com.choruskube.core.dto.InternalUpdateFeatureProposalRequest;
import com.choruskube.core.dto.RepoRef;
import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.dto.SoftwareProjectRef;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.FeatureProposal;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.SoftwareProject;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.FeatureProposalStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.FeatureProposalRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.scope.ScopeProvider;
import com.choruskube.core.specification.LikePatterns;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeatureProposalService {

    private static final Logger log = LoggerFactory.getLogger(FeatureProposalService.class);

    private static final Set<WorkflowRunStatus> TERMINAL_STATUSES =
            Set.of(WorkflowRunStatus.completed, WorkflowRunStatus.failed, WorkflowRunStatus.cancelled);

    private final FeatureProposalRepository repo;
    private final SoftwareProjectRepository softwareProjectRepo;
    private final GitRepoRepository gitRepoRepo;
    private final GraphTemplateRepository graphTemplateRepo;
    private final WorkflowRunRepository runRepo;
    private final RunService runService;
    private final RunEventPublisher eventPublisher;
    private final AuthorizationService authService;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ScopeProvider scopeProvider;

    public FeatureProposalService(
            FeatureProposalRepository repo,
            SoftwareProjectRepository softwareProjectRepo,
            GitRepoRepository gitRepoRepo,
            GraphTemplateRepository graphTemplateRepo,
            WorkflowRunRepository runRepo,
            RunService runService,
            RunEventPublisher eventPublisher,
            AuthorizationService authService,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher,
            ScopeProvider scopeProvider) {
        this.repo = repo;
        this.softwareProjectRepo = softwareProjectRepo;
        this.gitRepoRepo = gitRepoRepo;
        this.graphTemplateRepo = graphTemplateRepo;
        this.runRepo = runRepo;
        this.runService = runService;
        this.eventPublisher = eventPublisher;
        this.authService = authService;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.scopeProvider = scopeProvider;
    }

    @Transactional
    public FeatureProposalResponse create(FeatureProposalRequest request) {
        SoftwareProject project = loadSoftwareProject(request.softwareProjectId());
        // Caller-vs-resource guard: the target project must belong to the caller's org (TenantContext
        // present on this request path). No-op under always-allow; throws ForbiddenException (403) on
        // mismatch under auth.
        authService.checkOrgAccess("software_project", project.getId());
        Persisted persisted = persistProposal(request, project);
        applicationEventPublisher.publishEvent(
                MappableCreated.of("feature_proposal", persisted.proposal().getId()));
        eventPublisher.publishFeatureProposalChanged(
                persisted.proposal().getId(), persisted.proposal().getStatus().name());
        auditSink.record(
                AuditSink.PROPOSAL_CREATED,
                "feature_proposal",
                persisted.proposal().getId(),
                proposalDetailJson(null, proposalSnapshot(persisted.proposal())));
        return toResponse(persisted.proposal(), persisted.project());
    }

    @Transactional
    public FeatureProposalResponse create(FeatureProposalRequest request, UUID runId) {
        // Agent/internal entry: no request-scoped TenantContext, so this path is unaudited.
        SoftwareProject project = loadSoftwareProject(request.softwareProjectId());
        // Cross-org guard: the target project and the originating run must belong to the same org.
        // Resolved from ownership (no TenantContext read) — safe on the agent / JOB_SECRET path; throws
        // ForbiddenException (403) on mismatch under auth, no-op under always-allow.
        authService.assertSameOrg("software_project", project.getId(), "workflow_run", runId);
        Persisted persisted = persistProposal(request, project);
        applicationEventPublisher.publishEvent(MappableCreated.withParent(
                "feature_proposal",
                persisted.proposal().getId(),
                "software_project",
                persisted.project().getId()));
        eventPublisher.publishFeatureProposalChanged(
                persisted.proposal().getId(), persisted.proposal().getStatus().name());
        return toResponse(persisted.proposal(), persisted.project());
    }

    private Persisted persistProposal(FeatureProposalRequest request, SoftwareProject project) {
        FeatureProposal proposal = new FeatureProposal();
        proposal.setTitle(request.title());
        proposal.setDescription(request.description());
        proposal.setMotivation(request.motivation());
        proposal.setSoftwareProjectId(project.getId());
        proposal = repo.save(proposal);
        return new Persisted(proposal, project);
    }

    private record Persisted(FeatureProposal proposal, SoftwareProject project) {}

    @Transactional(readOnly = true)
    public List<FeatureProposalResponse> list(FeatureProposalStatus status) {
        Specification<FeatureProposal> spec = scopeProvider.scope(FeatureProposal.class);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        List<FeatureProposal> proposals = repo.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
        return toResponses(proposals);
    }

    @Transactional(readOnly = true)
    public Page<FeatureProposalResponse> list(FeatureProposalStatus status, String title, Pageable pageable) {
        Specification<FeatureProposal> spec = scopeProvider.scope(FeatureProposal.class);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (title != null && !title.isBlank()) {
            String pattern = LikePatterns.containsIgnoreCase(title);
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern));
        }
        Page<FeatureProposal> page = repo.findAll(spec, pageable);
        List<FeatureProposalResponse> content = toResponses(page.getContent());
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public FeatureProposalResponse get(UUID id) {
        FeatureProposal proposal = findOrThrow(id);
        authService.checkOrgAccess("feature_proposal", id);
        return toResponse(proposal);
    }

    @Transactional
    public FeatureProposalResponse update(UUID id, FeatureProposalRequest request) {
        FeatureProposal proposal = findOrThrow(id);
        authService.checkOrgAccess("feature_proposal", id);
        if (proposal.getStatus() != FeatureProposalStatus.backlog) {
            throw new ConflictException("Can only update proposals in backlog status");
        }
        Map<String, Object> beforeSnapshot = proposalSnapshot(proposal);

        SoftwareProject newProject = loadSoftwareProject(request.softwareProjectId());
        // Caller-vs-resource guard: the new target project must belong to the caller's org. The
        // proposal itself was already checked via checkOrgAccess("feature_proposal", id) above, so the
        // caller's org == the proposal's org. No-op under always-allow; 403 on mismatch under auth.
        authService.checkOrgAccess("software_project", newProject.getId());
        proposal.setTitle(request.title());
        proposal.setDescription(request.description());
        proposal.setMotivation(request.motivation());
        proposal.setSoftwareProjectId(newProject.getId());
        proposal = repo.save(proposal);

        FeatureProposalResponse response = toResponse(proposal, newProject);
        auditSink.record(
                AuditSink.PROPOSAL_UPDATED,
                "feature_proposal",
                id,
                proposalDetailJson(beforeSnapshot, proposalSnapshot(proposal)));
        eventPublisher.publishFeatureProposalChanged(
                proposal.getId(), proposal.getStatus().name());
        return response;
    }

    @Transactional
    public void delete(UUID id) {
        FeatureProposal proposal = findOrThrow(id);
        authService.checkOrgAccess("feature_proposal", id);
        if (proposal.getStatus() != FeatureProposalStatus.backlog) {
            throw new ConflictException("Can only delete proposals in backlog status");
        }
        auditSink.record(
                AuditSink.PROPOSAL_DELETED,
                "feature_proposal",
                id,
                proposalDetailJson(proposalSnapshot(proposal), null));
        repo.delete(proposal);
        eventPublisher.publishFeatureProposalChanged(id, "deleted");
    }

    @Transactional
    public FeatureProposalResponse start(UUID id) {
        FeatureProposal proposal = findOrThrow(id);
        authService.checkOrgAccess("feature_proposal", id);

        if (proposal.getStatus() == FeatureProposalStatus.in_progress) {
            if (proposal.getWorkflowRunId() == null) {
                throw new ConflictException("Proposal is in progress but has no linked run");
            }
            WorkflowRun existingRun = runRepo.findById(proposal.getWorkflowRunId())
                    .orElseThrow(() ->
                            new NotFoundException("Linked workflow run not found: " + proposal.getWorkflowRunId()));
            if (!TERMINAL_STATUSES.contains(existingRun.getStatus())) {
                throw new ConflictException(
                        "Cannot re-trigger: linked run is still active (status: " + existingRun.getStatus() + ")");
            }
        } else if (proposal.getStatus() != FeatureProposalStatus.backlog) {
            throw new ConflictException("Can only start proposals in backlog status");
        }

        StringBuilder featureRequest = new StringBuilder();
        featureRequest.append("## ").append(proposal.getTitle()).append("\n\n");
        featureRequest.append(proposal.getDescription());
        if (proposal.getMotivation() != null && !proposal.getMotivation().isBlank()) {
            featureRequest.append("\n\n### Motivation\n").append(proposal.getMotivation());
        }

        GraphTemplate featureDevTemplate = graphTemplateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow(() -> new NotFoundException("Feature Development template not found"));

        CreateRunRequest runRequest = new CreateRunRequest(
                featureDevTemplate.getId(),
                Map.of(
                        "software_project_id", proposal.getSoftwareProjectId().toString(),
                        "feature_request", featureRequest.toString()),
                proposal.getTitle(),
                null);

        RunResponse runResponse = runService.startRun(runRequest);

        proposal.setWorkflowRunId(runResponse.id());
        proposal.setStatus(FeatureProposalStatus.in_progress);
        FeatureProposalResponse response = toResponse(repo.save(proposal));
        eventPublisher.publishFeatureProposalChanged(
                proposal.getId(), proposal.getStatus().name());
        return response;
    }

    @Transactional
    public FeatureProposalResponse rollOut(UUID id) {
        FeatureProposal proposal = findOrThrow(id);
        authService.checkOrgAccess("feature_proposal", id);
        if (proposal.getStatus() != FeatureProposalStatus.in_progress) {
            throw new ConflictException("Can only roll out proposals that are in progress");
        }
        if (proposal.getWorkflowRunId() == null) {
            throw new ConflictException("Proposal has no linked workflow run");
        }

        WorkflowRun run = runRepo.findById(proposal.getWorkflowRunId())
                .orElseThrow(
                        () -> new NotFoundException("Linked workflow run not found: " + proposal.getWorkflowRunId()));
        if (!TERMINAL_STATUSES.contains(run.getStatus())) {
            throw new ConflictException(
                    "Cannot roll out: linked run is still active (status: " + run.getStatus() + ")");
        }

        proposal.setStatus(FeatureProposalStatus.rolled_out);
        FeatureProposalResponse response = toResponse(repo.save(proposal));
        eventPublisher.publishFeatureProposalChanged(
                proposal.getId(), proposal.getStatus().name());
        return response;
    }

    /**
     * Lists proposals targeting the given software project. Used by the internal API so an agent
     * running against a project sees every proposal that targets that project.
     */
    @Transactional(readOnly = true)
    public List<FeatureProposalResponse> listBySoftwareProjectId(UUID softwareProjectId) {
        List<FeatureProposal> proposals = repo.findBySoftwareProjectIdOrderByCreatedAtDesc(softwareProjectId);
        if (proposals.isEmpty()) return List.of();
        return toResponses(proposals);
    }

    /**
     * Updates a feature proposal on behalf of an agent pod (PATCH semantics). The caller must
     * supply the run's id ({@code runId}) and resolved {@code runSoftwareProjectId}; both are checked
     * against the proposal before any mutation is applied. The proposal and the run must belong to the
     * same org — resolved from ownership data by the active authorization strategy (no TenantContext is
     * read, so this works on the agent / {@code JOB_SECRET} path).
     *
     * <p>Must be called inside an open transaction. {@code @Transactional} is required here because
     * {@code toResponse()} → {@code buildResponse()} calls {@code SoftwareProject.resolveRepos()},
     * which lazily loads {@code RepoGroup.members} and throws {@code LazyInitializationException}
     * without an open session — same reason as {@link #create(FeatureProposalRequest, UUID)}.
     *
     * @throws ForbiddenException if the proposal's org or project does not match the run's values
     * @throws ConflictException  if the proposal is not in {@code backlog} status
     * @throws BadRequestException if a non-null title or description is blank
     * @throws NotFoundException  if no proposal exists with the given {@code proposalId}
     */
    @Transactional
    public FeatureProposalResponse updateInternal(
            UUID proposalId, UUID runSoftwareProjectId, UUID runId, InternalUpdateFeatureProposalRequest req) {
        FeatureProposal proposal = findOrThrow(proposalId);
        // Cross-org guard: the proposal and the run must belong to the same org (resolved from
        // ownership; throws ForbiddenException (403) on mismatch under auth, no-op under always-allow).
        authService.assertSameOrg("feature_proposal", proposal.getId(), "workflow_run", runId);
        if (!proposal.getSoftwareProjectId().equals(runSoftwareProjectId)) {
            throw new ForbiddenException(
                    "Feature proposal " + proposalId + " does not belong to the run's software project");
        }
        if (proposal.getStatus() != FeatureProposalStatus.backlog) {
            throw new ConflictException("Can only update proposals in backlog status");
        }

        // PATCH semantics: null → skip; non-null → apply.
        if (req.title() != null) {
            if (req.title().isBlank()) {
                throw new BadRequestException("title must not be blank");
            }
            proposal.setTitle(req.title());
        }
        if (req.description() != null) {
            if (req.description().isBlank()) {
                throw new BadRequestException("description must not be blank");
            }
            proposal.setDescription(req.description());
        }
        if (req.motivation() != null) {
            // Empty/blank motivation clears the field; any non-blank value is stored as-is.
            proposal.setMotivation(req.motivation().isBlank() ? null : req.motivation());
        }

        proposal = repo.save(proposal);
        eventPublisher.publishFeatureProposalChanged(
                proposal.getId(), proposal.getStatus().name());
        return toResponse(proposal);
    }

    /**
     * Batch-projection of a list of {@link FeatureProposal} entities into response DTOs. Avoids
     * the N+1 that a per-proposal {@code toResponse} would incur by loading all software_project
     * rows referenced by the page in one query.
     */
    private List<FeatureProposalResponse> toResponses(List<FeatureProposal> proposals) {
        Set<UUID> projectIds = proposals.stream()
                .map(FeatureProposal::getSoftwareProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, SoftwareProject> projectsById = projectIds.isEmpty()
                ? Map.of()
                : softwareProjectRepo.findAllById(projectIds).stream()
                        .collect(Collectors.toMap(SoftwareProject::getId, p -> p));

        Set<UUID> runIds = proposals.stream()
                .map(FeatureProposal::getWorkflowRunId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, String> statusByRunId = runIds.isEmpty()
                ? Map.of()
                : runRepo.findAllById(runIds).stream()
                        .collect(Collectors.toMap(
                                WorkflowRun::getId,
                                wr -> wr.getStatus() != null ? wr.getStatus().name() : null,
                                (a, b) -> a));

        List<FeatureProposalResponse> out = new ArrayList<>(proposals.size());
        for (FeatureProposal p : proposals) {
            SoftwareProject project = projectsById.get(p.getSoftwareProjectId());
            if (project == null) {
                throw new NotFoundException(
                        "SoftwareProject not found for proposal " + p.getId() + ": " + p.getSoftwareProjectId());
            }
            String workflowRunStatus = p.getWorkflowRunId() == null ? null : statusByRunId.get(p.getWorkflowRunId());
            out.add(buildResponse(p, project, workflowRunStatus));
        }
        return out;
    }

    private FeatureProposalResponse buildResponse(
            FeatureProposal p, SoftwareProject project, String workflowRunStatus) {
        SoftwareProjectRef projectRef = toProjectRef(project);
        List<RepoRef> repos = project.resolveRepos().stream()
                .map(g -> new RepoRef(g.getId(), g.getUrl(), RepoNameUtil.deriveRepoName(g.getUrl())))
                .toList();
        return new FeatureProposalResponse(
                p.getId(),
                p.getTitle(),
                p.getDescription(),
                p.getMotivation(),
                p.getStatus().name(),
                projectRef,
                repos,
                p.getWorkflowRunId(),
                workflowRunStatus,
                p.getCreatedAt(),
                p.getUpdatedAt());
    }

    private SoftwareProjectRef toProjectRef(SoftwareProject project) {
        String type = (project instanceof RepoGroup) ? "repo_group" : "git_repo";
        return new SoftwareProjectRef(project.getId(), type, project.getName());
    }

    /**
     * Loads a non-deleted target software project by id (existence/soft-delete checks only). The
     * cross-org guard is intentionally NOT here: it differs per caller — request paths compare the
     * project against the caller's org ({@code checkOrgAccess}), while the agent create path compares
     * it against the run's org ({@code assertSameOrg}). Each caller runs the appropriate guard.
     */
    private SoftwareProject loadSoftwareProject(UUID softwareProjectId) {
        if (softwareProjectId == null) {
            throw new BadRequestException("softwareProjectId is required");
        }
        SoftwareProject project = softwareProjectRepo
                .findById(softwareProjectId)
                .orElseThrow(() -> new NotFoundException("SoftwareProject not found: " + softwareProjectId));
        // The @SQLRestriction("deleted_at IS NULL") on SoftwareProject already filters soft-deleted
        // rows from findById, so this branch is defense-in-depth and matches the project's pattern.
        if (project.getDeletedAt() != null) {
            throw new NotFoundException("SoftwareProject has been deleted: " + softwareProjectId);
        }
        return project;
    }

    private Map<String, Object> proposalSnapshot(FeatureProposal p) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", p.getTitle());
        snapshot.put("description", p.getDescription());
        snapshot.put("motivation", p.getMotivation());
        snapshot.put("status", p.getStatus() != null ? p.getStatus().name() : null);
        snapshot.put(
                "software_project_id",
                p.getSoftwareProjectId() != null ? p.getSoftwareProjectId().toString() : null);
        return snapshot;
    }

    private String proposalDetailJson(Object before, Object after) {
        return AuditDetail.json(objectMapper, before, after);
    }

    private FeatureProposal findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Feature proposal not found: " + id));
    }

    private FeatureProposalResponse toResponse(FeatureProposal p) {
        SoftwareProject project = softwareProjectRepo
                .findById(p.getSoftwareProjectId())
                .orElseThrow(() -> new NotFoundException(
                        "SoftwareProject not found for proposal " + p.getId() + ": " + p.getSoftwareProjectId()));
        return toResponse(p, project);
    }

    private FeatureProposalResponse toResponse(FeatureProposal p, SoftwareProject project) {
        String workflowRunStatus = null;
        if (p.getWorkflowRunId() != null) {
            workflowRunStatus = runRepo.findById(p.getWorkflowRunId())
                    .map(wr -> wr.getStatus() != null ? wr.getStatus().name() : null)
                    .orElse(null);
        }
        return buildResponse(p, project, workflowRunStatus);
    }
}
