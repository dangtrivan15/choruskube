package com.choruskube.core.service;

import com.choruskube.core.dto.GraphTemplateRequest;
import com.choruskube.core.dto.GraphTemplateResponse;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.scope.ScopeProvider;
import com.choruskube.core.specification.LikePatterns;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GraphTemplateService {

    private static final Logger log = LoggerFactory.getLogger(GraphTemplateService.class);

    /**
     * Workflow run statuses that are considered "active" and block template deletion.
     * Only running, paused, pending, and awaiting_human runs prevent deletion;
     * completed, failed, and cancelled runs do not.
     */
    private static final Set<WorkflowRunStatus> ACTIVE_RUN_STATUSES = Set.of(
            WorkflowRunStatus.running,
            WorkflowRunStatus.paused,
            WorkflowRunStatus.pending,
            WorkflowRunStatus.awaiting_human);

    private final GraphTemplateRepository repo;
    private final WorkflowRunRepository workflowRunRepo;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ScopeProvider scopeProvider;

    public GraphTemplateService(
            GraphTemplateRepository repo,
            WorkflowRunRepository workflowRunRepo,
            ObjectMapper objectMapper,
            AuthorizationService authService,
            ApplicationEventPublisher applicationEventPublisher,
            ScopeProvider scopeProvider) {
        this.repo = repo;
        this.workflowRunRepo = workflowRunRepo;
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.scopeProvider = scopeProvider;
    }

    /**
     * Filters to only the latest version of each graphId via a correlated subquery:
     * {@code WHERE version = (SELECT MAX(version) FROM graph_template WHERE graphId = outer.graphId)}.
     * Composable with other specifications and paginated entirely at the DB level.
     */
    private static Specification<GraphTemplate> isLatestVersion() {
        return (root, query, cb) -> {
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<GraphTemplate> subRoot = subquery.from(GraphTemplate.class);
            subquery.select(cb.max(subRoot.get("version")))
                    .where(cb.equal(subRoot.get("graphId"), root.get("graphId")));
            return cb.equal(root.get("version"), subquery);
        };
    }

    @Transactional
    public GraphTemplateResponse create(GraphTemplateRequest request) {
        GraphTemplate gt = fromRequest(new GraphTemplate(), request);

        repo.findByGraphIdAndVersion(gt.getGraphId(), gt.getVersion()).ifPresent(existing -> {
            throw new ConflictException(
                    "Template already exists with graphId '" + gt.getGraphId() + "' version " + gt.getVersion());
        });

        GraphTemplate saved = repo.save(gt);
        applicationEventPublisher.publishEvent(MappableCreated.of("graph_template", saved.getId()));
        return toResponse(saved);
    }

    public List<GraphTemplateResponse> list(boolean latestOnly) {
        // Org-or-system scoping now lives in the ScopeProvider (OwnershipScopeProvider OR-s in
        // system templates; NoOp sees all rows in single-tenant mode).
        Specification<GraphTemplate> spec = scopeProvider.scope(GraphTemplate.class);
        if (latestOnly) {
            spec = spec.and(isLatestVersion());
        }
        return repo.findAll(spec).stream().map(this::toResponse).toList();
    }

    public Page<GraphTemplateResponse> list(boolean latestOnly, String name, Pageable pageable) {
        Specification<GraphTemplate> spec = scopeProvider.scope(GraphTemplate.class);
        if (name != null && !name.isBlank()) {
            String pattern = LikePatterns.containsIgnoreCase(name);
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
        }
        if (latestOnly) {
            // Use a correlated subquery specification to filter at the DB level,
            // so pagination and sorting are handled entirely by the database.
            spec = spec.and(isLatestVersion());
        }
        Page<GraphTemplate> page = repo.findAll(spec, pageable);
        return page.map(this::toResponse);
    }

    public GraphTemplateResponse get(UUID id) {
        GraphTemplate gt = findOrThrow(id);
        authService.checkTemplateReadAccess(gt.isSystem(), id);
        return toResponse(gt);
    }

    @Transactional
    public GraphTemplateResponse update(UUID id, GraphTemplateRequest request) {
        GraphTemplate existing = findOrThrow(id);
        authService.checkOrgAccess("graph_template", id);

        if (existing.isSystem()) {
            throw new ConflictException("Cannot modify system template: " + existing.getName());
        }

        String originalGraphId = existing.getGraphId();
        fromRequest(existing, request);
        existing.setGraphId(originalGraphId);
        return toResponse(repo.save(existing));
    }

    @Transactional
    public void delete(UUID id) {
        GraphTemplate gt = findOrThrow(id);
        authService.checkOrgAccess("graph_template", id);

        if (gt.isSystem()) {
            throw new ConflictException("Cannot modify system template: " + gt.getName());
        }

        List<GraphTemplate> allVersions = repo.findAllByGraphId(gt.getGraphId());
        for (GraphTemplate version : allVersions) {
            if (workflowRunRepo.existsByGraphTemplateIdAndStatusIn(version.getId(), ACTIVE_RUN_STATUSES)) {
                throw new ConflictException("Cannot delete template '" + gt.getName()
                        + "': it has active workflow runs referencing version " + version.getVersion());
            }
        }

        repo.deleteAll(allVersions);
    }

    public GraphTemplate findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Graph template not found: " + id));
    }

    private GraphTemplate fromRequest(GraphTemplate gt, GraphTemplateRequest req) {
        gt.setName(req.name());
        gt.setDescription(req.description());
        gt.setGraphId(
                req.graphId() != null ? req.graphId() : req.name().toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        gt.setVersion(req.version() != null ? req.version() : (gt.getVersion() != null ? gt.getVersion() : 1));
        if (req.inputSchema() != null) {
            gt.setInputSchema(req.inputSchema());
        }
        return gt;
    }

    private GraphTemplateResponse toResponse(GraphTemplate gt) {
        JsonNode schemaNode = parseJsonField(gt.getInputSchema(), "[]");

        return new GraphTemplateResponse(
                gt.getId(),
                gt.getGraphId(),
                gt.getVersion(),
                gt.getName(),
                gt.getDescription(),
                schemaNode,
                gt.isSystem(),
                gt.getCreatedAt(),
                gt.getUpdatedAt());
    }

    private JsonNode parseJsonField(String json, String fallback) {
        try {
            return objectMapper.readTree(json != null && !json.isBlank() ? json : fallback);
        } catch (Exception e) {
            try {
                return objectMapper.readTree(fallback);
            } catch (Exception ex) {
                return objectMapper.createObjectNode();
            }
        }
    }
}
