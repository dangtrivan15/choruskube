package com.choruskube.core.service;

import com.choruskube.core.dto.TemplateEdgeRequest;
import com.choruskube.core.dto.TemplateEdgeResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.repository.TemplateEdgeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TemplateEdgeService {

    private final TemplateEdgeRepository repo;
    private final GraphTemplateService graphTemplateService;

    public TemplateEdgeService(TemplateEdgeRepository repo, GraphTemplateService graphTemplateService) {
        this.repo = repo;
        this.graphTemplateService = graphTemplateService;
    }

    public TemplateEdgeResponse create(UUID templateId, TemplateEdgeRequest request) {
        rejectIfSystemTemplate(templateId);
        TemplateEdge edge = fromRequest(new TemplateEdge(), templateId, request);
        return toResponse(repo.save(edge));
    }

    public List<TemplateEdgeResponse> list(UUID templateId) {
        graphTemplateService.get(templateId); // authorizes the template read (404/403)
        return repo.findByGraphTemplateId(templateId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<TemplateEdge> listEntities(UUID templateId) {
        return repo.findByGraphTemplateId(templateId);
    }

    public TemplateEdgeResponse get(UUID templateId, UUID edgeId) {
        return toResponse(findOrThrow(templateId, edgeId));
    }

    public TemplateEdgeResponse update(UUID templateId, UUID edgeId, TemplateEdgeRequest request) {
        rejectIfSystemTemplate(templateId);
        TemplateEdge edge = findOrThrow(templateId, edgeId);
        fromRequest(edge, templateId, request);
        return toResponse(repo.save(edge));
    }

    public void delete(UUID templateId, UUID edgeId) {
        rejectIfSystemTemplate(templateId);
        TemplateEdge edge = findOrThrow(templateId, edgeId);
        repo.delete(edge);
    }

    private void rejectIfSystemTemplate(UUID templateId) {
        var template = graphTemplateService.findOrThrow(templateId);
        if (template.isSystem()) {
            throw new ConflictException("Cannot modify edges on system template: " + template.getName());
        }
    }

    private TemplateEdge findOrThrow(UUID templateId, UUID edgeId) {
        graphTemplateService.get(templateId); // authorizes the template read (404/403)
        TemplateEdge edge =
                repo.findById(edgeId).orElseThrow(() -> new NotFoundException("Template edge not found: " + edgeId));
        if (!edge.getGraphTemplateId().equals(templateId)) {
            throw new NotFoundException("Template edge " + edgeId + " does not belong to template " + templateId);
        }
        return edge;
    }

    private TemplateEdge fromRequest(TemplateEdge edge, UUID templateId, TemplateEdgeRequest req) {
        edge.setGraphTemplateId(templateId);
        edge.setSourceNodeId(req.sourceNodeId());
        edge.setTargetNodeId(req.targetNodeId());
        edge.setCondition(req.condition());
        return edge;
    }

    private TemplateEdgeResponse toResponse(TemplateEdge edge) {
        return new TemplateEdgeResponse(
                edge.getId(),
                edge.getGraphTemplateId(),
                edge.getSourceNodeId(),
                edge.getTargetNodeId(),
                edge.getCondition());
    }
}
