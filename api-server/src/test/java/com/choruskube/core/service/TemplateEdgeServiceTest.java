package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.TemplateEdgeRequest;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.repository.TemplateEdgeRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemplateEdgeServiceTest {

    @Mock
    private TemplateEdgeRepository repo;

    @Mock
    private GraphTemplateService graphTemplateService;

    private TemplateEdgeService service;

    @BeforeEach
    void setUp() {
        service = new TemplateEdgeService(repo, graphTemplateService);
    }

    private GraphTemplate systemTemplate() {
        GraphTemplate gt = new GraphTemplate();
        gt.setId(UUID.randomUUID());
        gt.setName("System Template");
        gt.setSystem(true);
        return gt;
    }

    private TemplateEdge makeEdge(UUID templateId) {
        TemplateEdge te = new TemplateEdge();
        te.setId(UUID.randomUUID());
        te.setGraphTemplateId(templateId);
        te.setSourceNodeId(UUID.randomUUID());
        te.setTargetNodeId(UUID.randomUUID());
        return te;
    }

    @Test
    void create_onSystemTemplate_throwsConflict() {
        GraphTemplate gt = systemTemplate();
        when(graphTemplateService.findOrThrow(gt.getId())).thenReturn(gt);

        TemplateEdgeRequest req = new TemplateEdgeRequest(UUID.randomUUID(), UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.create(gt.getId(), req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("system template");
    }

    @Test
    void update_onSystemTemplate_throwsConflict() {
        GraphTemplate gt = systemTemplate();
        TemplateEdge edge = makeEdge(gt.getId());
        when(graphTemplateService.findOrThrow(gt.getId())).thenReturn(gt);

        TemplateEdgeRequest req = new TemplateEdgeRequest(UUID.randomUUID(), UUID.randomUUID(), "approved");

        assertThatThrownBy(() -> service.update(gt.getId(), edge.getId(), req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("system template");
    }

    @Test
    void delete_onSystemTemplate_throwsConflict() {
        GraphTemplate gt = systemTemplate();
        TemplateEdge edge = makeEdge(gt.getId());
        when(graphTemplateService.findOrThrow(gt.getId())).thenReturn(gt);

        assertThatThrownBy(() -> service.delete(gt.getId(), edge.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("system template");
    }
}
