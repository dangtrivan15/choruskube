package com.choruskube.core.service;

import com.choruskube.core.dto.TemplateNodeRequest;
import com.choruskube.core.dto.TemplateNodeResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TemplateNodeService {

    private final TemplateNodeRepository repo;
    private final NodeDefinitionRepository nodeDefRepo;
    private final GraphTemplateService graphTemplateService;
    private final ObjectMapper objectMapper;

    public TemplateNodeService(
            TemplateNodeRepository repo,
            NodeDefinitionRepository nodeDefRepo,
            GraphTemplateService graphTemplateService,
            ObjectMapper objectMapper) {
        this.repo = repo;
        this.nodeDefRepo = nodeDefRepo;
        this.graphTemplateService = graphTemplateService;
        this.objectMapper = objectMapper;
    }

    public TemplateNodeResponse create(UUID templateId, TemplateNodeRequest request) {
        rejectIfSystemTemplate(templateId);
        nodeDefRepo
                .findById(request.nodeDefinitionId())
                .orElseThrow(() -> new NotFoundException("Node definition not found: " + request.nodeDefinitionId()));
        TemplateNode tn = fromRequest(new TemplateNode(), templateId, request);
        return toResponse(repo.save(tn));
    }

    public List<TemplateNodeResponse> list(UUID templateId) {
        graphTemplateService.get(templateId); // authorizes the template read (404/403)
        return repo.findByGraphTemplateId(templateId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<TemplateNode> listEntities(UUID templateId) {
        return repo.findByGraphTemplateId(templateId);
    }

    public TemplateNodeResponse get(UUID templateId, UUID nodeId) {
        return toResponse(findOrThrow(templateId, nodeId));
    }

    public TemplateNodeResponse update(UUID templateId, UUID nodeId, TemplateNodeRequest request) {
        rejectIfSystemTemplate(templateId);
        TemplateNode tn = findOrThrow(templateId, nodeId);
        fromRequest(tn, templateId, request);
        return toResponse(repo.save(tn));
    }

    public void delete(UUID templateId, UUID nodeId) {
        rejectIfSystemTemplate(templateId);
        TemplateNode tn = findOrThrow(templateId, nodeId);
        repo.delete(tn); // cascade deletes connected edges
    }

    private void rejectIfSystemTemplate(UUID templateId) {
        var template = graphTemplateService.findOrThrow(templateId);
        if (template.isSystem()) {
            throw new ConflictException("Cannot modify nodes on system template: " + template.getName());
        }
    }

    private TemplateNode findOrThrow(UUID templateId, UUID nodeId) {
        graphTemplateService.get(templateId); // authorizes the template read (404/403)
        TemplateNode tn =
                repo.findById(nodeId).orElseThrow(() -> new NotFoundException("Template node not found: " + nodeId));
        if (!tn.getGraphTemplateId().equals(templateId)) {
            throw new NotFoundException("Template node " + nodeId + " does not belong to template " + templateId);
        }
        return tn;
    }

    private TemplateNode fromRequest(TemplateNode tn, UUID templateId, TemplateNodeRequest req) {
        tn.setGraphTemplateId(templateId);
        tn.setNodeDefinitionId(req.nodeDefinitionId());
        tn.setLabel(req.label());
        String overrides = req.configOverrides() != null ? req.configOverrides().toString() : "{}";
        tn.setConfigOverrides(overrides);
        tn.setEntrypoint(req.entrypoint() != null ? req.entrypoint() : false);
        return tn;
    }

    private TemplateNodeResponse toResponse(TemplateNode tn) {
        JsonNode configOverridesJson = null;
        if (tn.getConfigOverrides() != null) {
            try {
                configOverridesJson = objectMapper.readTree(tn.getConfigOverrides());
            } catch (Exception e) {
                // leave as null
            }
        }
        return new TemplateNodeResponse(
                tn.getId(),
                tn.getGraphTemplateId(),
                tn.getNodeDefinitionId(),
                tn.getLabel(),
                configOverridesJson,
                tn.isEntrypoint());
    }
}
