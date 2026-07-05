package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.TemplateNodeRequest;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemplateNodeServiceTest {

    @Mock
    private TemplateNodeRepository repo;

    @Mock
    private NodeDefinitionRepository nodeDefRepo;

    @Mock
    private GraphTemplateService graphTemplateService;

    private TemplateNodeService service;

    @BeforeEach
    void setUp() {
        service = new TemplateNodeService(repo, nodeDefRepo, graphTemplateService, new ObjectMapper());
    }

    private GraphTemplate systemTemplate() {
        GraphTemplate gt = new GraphTemplate();
        gt.setId(UUID.randomUUID());
        gt.setName("System Template");
        gt.setSystem(true);
        return gt;
    }

    private TemplateNode makeNode(UUID templateId) {
        TemplateNode tn = new TemplateNode();
        tn.setId(UUID.randomUUID());
        tn.setGraphTemplateId(templateId);
        tn.setNodeDefinitionId(UUID.randomUUID());
        tn.setLabel("test");
        tn.setConfigOverrides("{}");
        return tn;
    }

    @Test
    void create_onSystemTemplate_throwsConflict() {
        GraphTemplate gt = systemTemplate();
        when(graphTemplateService.findOrThrow(gt.getId())).thenReturn(gt);

        TemplateNodeRequest req = new TemplateNodeRequest(UUID.randomUUID(), "label", null, false);

        assertThatThrownBy(() -> service.create(gt.getId(), req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("system template");
    }

    @Test
    void update_onSystemTemplate_throwsConflict() {
        GraphTemplate gt = systemTemplate();
        TemplateNode tn = makeNode(gt.getId());
        when(graphTemplateService.findOrThrow(gt.getId())).thenReturn(gt);

        TemplateNodeRequest req = new TemplateNodeRequest(UUID.randomUUID(), "label", null, false);

        assertThatThrownBy(() -> service.update(gt.getId(), tn.getId(), req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("system template");
    }

    @Test
    void delete_onSystemTemplate_throwsConflict() {
        GraphTemplate gt = systemTemplate();
        TemplateNode tn = makeNode(gt.getId());
        when(graphTemplateService.findOrThrow(gt.getId())).thenReturn(gt);

        assertThatThrownBy(() -> service.delete(gt.getId(), tn.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("system template");
    }
}
