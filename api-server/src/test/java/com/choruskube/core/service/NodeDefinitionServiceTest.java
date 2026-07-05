package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.NodeDefinitionRequest;
import com.choruskube.core.dto.NodeDefinitionResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class NodeDefinitionServiceTest {

    @Mock
    private NodeDefinitionRepository repo;

    @Mock
    private TemplateNodeRepository templateNodeRepo;

    @Mock
    private AuditSink auditService;

    private NodeDefinitionService service;

    @BeforeEach
    void setUp() {
        service = new NodeDefinitionService(
                repo,
                templateNodeRepo,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                auditService,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(ApplicationEventPublisher.class),
                new com.choruskube.core.scope.NoOpScopeProvider());
    }

    private NodeDefinition makeNodeDef(String name) {
        NodeDefinition nd = new NodeDefinition();
        nd.setId(UUID.randomUUID());
        nd.setName(name);
        nd.setExecutorType(ExecutorType.script);
        nd.setTimeoutSeconds(600);
        nd.setSkills("[]");
        nd.setInputSpec("{}");
        nd.setOutputSpec("{}");
        nd.setSecrets("[]");
        return nd;
    }

    @Test
    void update_nodeDefUsedBySystemTemplate_throwsConflict() {
        NodeDefinition nd = makeNodeDef("Test");
        when(repo.findById(nd.getId())).thenReturn(Optional.of(nd));
        when(templateNodeRepo.existsInSystemTemplate(nd.getId())).thenReturn(true);

        NodeDefinitionRequest req =
                new NodeDefinitionRequest("Test", "script", null, null, null, null, null, 1800, null);

        assertThatThrownBy(() -> service.update(nd.getId(), req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("system template");
    }

    @Test
    void update_nodeDefNotUsedBySystemTemplate_succeeds() {
        NodeDefinition nd = makeNodeDef("Test");
        when(repo.findById(nd.getId())).thenReturn(Optional.of(nd));
        when(templateNodeRepo.existsInSystemTemplate(nd.getId())).thenReturn(false);
        when(repo.save(any(NodeDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        NodeDefinitionRequest req =
                new NodeDefinitionRequest("Test", "script", null, null, null, null, null, 1800, null);

        NodeDefinitionResponse response = service.update(nd.getId(), req);

        assertThat(response.timeoutSeconds()).isEqualTo(1800);
    }

    @Test
    void delete_nodeDefUsedBySystemTemplate_throwsConflict() {
        NodeDefinition nd = makeNodeDef("Test");
        when(repo.findById(nd.getId())).thenReturn(Optional.of(nd));
        when(templateNodeRepo.existsInSystemTemplate(nd.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(nd.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("system template");
    }
}
