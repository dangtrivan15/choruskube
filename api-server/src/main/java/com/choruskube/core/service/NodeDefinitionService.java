package com.choruskube.core.service;

import com.choruskube.core.dto.NodeDefinitionRequest;
import com.choruskube.core.dto.NodeDefinitionResponse;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.scope.ScopeProvider;
import com.choruskube.core.specification.LikePatterns;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NodeDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(NodeDefinitionService.class);

    private final NodeDefinitionRepository repo;
    private final TemplateNodeRepository templateNodeRepo;
    private final AuthorizationService authService;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ScopeProvider scopeProvider;

    public NodeDefinitionService(
            NodeDefinitionRepository repo,
            TemplateNodeRepository templateNodeRepo,
            AuthorizationService authService,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher,
            ScopeProvider scopeProvider) {
        this.repo = repo;
        this.templateNodeRepo = templateNodeRepo;
        this.authService = authService;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.scopeProvider = scopeProvider;
    }

    @Transactional
    public NodeDefinitionResponse create(NodeDefinitionRequest request) {
        NodeDefinition nd = fromRequest(new NodeDefinition(), request);
        nd = repo.save(nd);
        applicationEventPublisher.publishEvent(MappableCreated.of("node_definition", nd.getId()));
        auditSink.record(AuditSink.NODE_DEF_CREATED, "node_definition", nd.getId(), ndDetailJson(null, nd));
        return toResponse(nd);
    }

    public List<NodeDefinitionResponse> list() {
        Specification<NodeDefinition> spec = scopeProvider.scope(NodeDefinition.class);
        return repo.findAll(spec, Sort.unsorted()).stream()
                .map(this::toResponse)
                .toList();
    }

    public Page<NodeDefinitionResponse> list(String name, String executorType, Pageable pageable) {
        Specification<NodeDefinition> spec = scopeProvider.scope(NodeDefinition.class);
        if (name != null && !name.isBlank()) {
            String pattern = LikePatterns.containsIgnoreCase(name);
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
        }
        if (executorType != null && !executorType.isBlank()) {
            ExecutorType parsed = ExecutorType.valueOf(executorType);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("executorType"), parsed));
        }
        return repo.findAll(spec, pageable).map(this::toResponse);
    }

    public NodeDefinitionResponse get(UUID id) {
        NodeDefinition nd = findOrThrow(id);
        authService.checkOrgAccess("node_definition", id);
        return toResponse(nd);
    }

    public NodeDefinitionResponse update(UUID id, NodeDefinitionRequest request) {
        NodeDefinition nd = findOrThrow(id);
        authService.checkOrgAccess("node_definition", id);
        rejectIfUsedBySystemTemplate(id, nd.getName());
        Map<String, Object> beforeSnapshot = ndSnapshot(nd);
        fromRequest(nd, request);
        nd = repo.save(nd);
        auditSink.record(
                AuditSink.NODE_DEF_UPDATED, "node_definition", id, ndDetailJson(beforeSnapshot, ndSnapshot(nd)));
        return toResponse(nd);
    }

    public void delete(UUID id) {
        NodeDefinition nd = findOrThrow(id);
        authService.checkOrgAccess("node_definition", id);
        rejectIfUsedBySystemTemplate(id, nd.getName());
        if (templateNodeRepo.existsByNodeDefinitionId(id)) {
            throw new ConflictException("Node definition is referenced by template nodes");
        }
        auditSink.record(AuditSink.NODE_DEF_DELETED, "node_definition", id, ndDetailJson(ndSnapshot(nd), null));
        repo.delete(nd);
    }

    private Map<String, Object> ndSnapshot(NodeDefinition nd) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", nd.getName());
        snapshot.put("executorType", nd.getExecutorType().name());
        return snapshot;
    }

    private String ndDetailJson(Object before, Object after) {
        return AuditDetail.json(objectMapper, before, after);
    }

    private void rejectIfUsedBySystemTemplate(UUID id, String name) {
        if (templateNodeRepo.existsInSystemTemplate(id)) {
            throw new ConflictException("Cannot modify node definition used by a system template: " + name);
        }
    }

    private NodeDefinition findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Node definition not found: " + id));
    }

    private NodeDefinition fromRequest(NodeDefinition nd, NodeDefinitionRequest req) {
        nd.setName(req.name());
        nd.setExecutorType(ExecutorType.valueOf(req.executorType()));
        nd.setImage(req.image());
        nd.setPromptTemplate(req.promptTemplate());
        nd.setSkills(req.skills() != null ? req.skills() : "[]");
        nd.setInputSpec(req.inputSpec() != null ? req.inputSpec() : "{}");
        nd.setOutputSpec(req.outputSpec() != null ? req.outputSpec() : "{}");
        nd.setTimeoutSeconds(req.timeoutSeconds() != null ? req.timeoutSeconds() : 1800);
        nd.setSecrets(req.secrets() != null ? req.secrets() : "[]");
        return nd;
    }

    private NodeDefinitionResponse toResponse(NodeDefinition nd) {
        return new NodeDefinitionResponse(
                nd.getId(),
                nd.getName(),
                nd.getExecutorType().name(),
                nd.getImage(),
                nd.getPromptTemplate(),
                nd.getSkills(),
                nd.getInputSpec(),
                nd.getOutputSpec(),
                nd.getTimeoutSeconds(),
                nd.getSecrets(),
                nd.getCreatedAt(),
                nd.getUpdatedAt());
    }
}
