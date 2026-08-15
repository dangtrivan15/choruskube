package com.choruskube.core.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Template sub-resource reads must go through the authorized template read path, matching the
 * parent GET /graph-templates/{id}.
 */
@ExtendWith(MockitoExtension.class)
class TemplateSubResourceAuthorizationTest {

    @Mock
    private TemplateNodeRepository nodeRepo;

    @Mock
    private TemplateEdgeRepository edgeRepo;

    @Mock
    private NodeDefinitionRepository nodeDefRepo;

    @Mock
    private GraphTemplateService graphTemplateService;

    private TemplateNodeService nodeService;
    private TemplateEdgeService edgeService;

    private final UUID templateId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        nodeService = new TemplateNodeService(nodeRepo, nodeDefRepo, graphTemplateService, new ObjectMapper());
        edgeService = new TemplateEdgeService(edgeRepo, graphTemplateService);
    }

    @Test
    void listNodes_authorizesTemplateRead() {
        when(nodeRepo.findByGraphTemplateId(templateId)).thenReturn(List.of());
        nodeService.list(templateId);
        verify(graphTemplateService).get(templateId);
    }

    @Test
    void getNode_authorizesTemplateRead() {
        UUID nodeId = UUID.randomUUID();
        TemplateNode tn = new TemplateNode();
        tn.setId(nodeId);
        tn.setGraphTemplateId(templateId);
        when(nodeRepo.findById(nodeId)).thenReturn(Optional.of(tn));

        nodeService.get(templateId, nodeId);
        verify(graphTemplateService).get(templateId);
    }

    @Test
    void listEdges_authorizesTemplateRead() {
        when(edgeRepo.findByGraphTemplateId(templateId)).thenReturn(List.of());
        edgeService.list(templateId);
        verify(graphTemplateService).get(templateId);
    }

    @Test
    void getEdge_authorizesTemplateRead() {
        UUID edgeId = UUID.randomUUID();
        TemplateEdge edge = new TemplateEdge();
        edge.setId(edgeId);
        edge.setGraphTemplateId(templateId);
        when(edgeRepo.findById(edgeId)).thenReturn(Optional.of(edge));

        edgeService.get(templateId, edgeId);
        verify(graphTemplateService).get(templateId);
    }
}
