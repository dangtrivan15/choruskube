package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.GraphTemplateRequest;
import com.choruskube.core.dto.GraphTemplateResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class GraphTemplateServiceTest {

    @Mock
    private GraphTemplateRepository repo;

    @Mock
    private WorkflowRunRepository workflowRunRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GraphTemplateService service;

    @BeforeEach
    void setUp() {
        service = new GraphTemplateService(
                repo,
                workflowRunRepo,
                objectMapper,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                mock(ApplicationEventPublisher.class),
                new com.choruskube.core.scope.NoOpScopeProvider());
    }

    // --- Helper ---

    private GraphTemplate makeTemplate(String name, String graphId, int version) {
        GraphTemplate gt = new GraphTemplate();
        gt.setId(UUID.randomUUID());
        gt.setName(name);
        gt.setGraphId(graphId);
        gt.setVersion(version);
        gt.setInputSchema("[]");
        return gt;
    }

    // --- create ---

    @Test
    void create_savesTemplateAndReturnsResponse() {
        GraphTemplateRequest req = new GraphTemplateRequest("My Template", "desc", null, null, null);
        when(repo.findByGraphIdAndVersion("my-template", 1)).thenReturn(Optional.empty());
        when(repo.save(any(GraphTemplate.class))).thenAnswer(inv -> {
            GraphTemplate gt = inv.getArgument(0);
            gt.setId(UUID.randomUUID());
            return gt;
        });

        GraphTemplateResponse response = service.create(req);

        assertThat(response.name()).isEqualTo("My Template");
        assertThat(response.graphId()).isEqualTo("my-template");
        assertThat(response.version()).isEqualTo(1);
        verify(repo).save(any(GraphTemplate.class));
    }

    @Test
    void create_withDuplicateGraphIdAndVersion_throwsConflict() {
        GraphTemplate existing = makeTemplate("Existing", "my-id", 1);
        when(repo.findByGraphIdAndVersion("my-id", 1)).thenReturn(Optional.of(existing));

        GraphTemplateRequest req = new GraphTemplateRequest("New", "desc", "my-id", 1, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("my-id");
    }

    // --- get ---

    @Test
    void get_returnsResponseForExistingTemplate() {
        UUID id = UUID.randomUUID();
        GraphTemplate gt = makeTemplate("Test", "test", 1);
        gt.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(gt));

        GraphTemplateResponse response = service.get(id);

        assertThat(response.name()).isEqualTo("Test");
        assertThat(response.graphId()).isEqualTo("test");
    }

    @Test
    void get_throwsNotFoundForMissingTemplate() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    // --- list ---

    @SuppressWarnings("unchecked")
    @Test
    void list_allTemplates_delegatesToFindAllWithSpec() {
        GraphTemplate gt = makeTemplate("A", "a", 1);
        when(repo.findAll(any(Specification.class))).thenReturn(List.of(gt));

        List<GraphTemplateResponse> result = service.list(false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("A");
        verify(repo).findAll(any(Specification.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void list_latestOnly_delegatesToFindAllWithSpec() {
        GraphTemplate gt = makeTemplate("B", "b", 2);
        when(repo.findAll(any(Specification.class))).thenReturn(List.of(gt));

        List<GraphTemplateResponse> result = service.list(true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).version()).isEqualTo(2);
        verify(repo).findAll(any(Specification.class));
    }

    // --- update ---

    @Test
    void update_modifiesAndSavesTemplate() {
        UUID id = UUID.randomUUID();
        GraphTemplate existing = makeTemplate("Old Name", "old-name", 1);
        existing.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.save(any(GraphTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        GraphTemplateRequest req = new GraphTemplateRequest("New Name", "new desc", "new-name", 2, null);
        GraphTemplateResponse response = service.update(id, req);

        assertThat(response.name()).isEqualTo("New Name");
        // graphId is preserved on update
        assertThat(response.graphId()).isEqualTo("old-name");
        assertThat(response.version()).isEqualTo(2);
        verify(repo).save(existing);
    }

    @Test
    void update_preservesGraphIdEvenWhenRequestSendsDifferentOne() {
        UUID id = UUID.randomUUID();
        GraphTemplate existing = makeTemplate("Base", "original-graph-id", 1);
        existing.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.save(any(GraphTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        GraphTemplateRequest req = new GraphTemplateRequest("Base Updated", "desc", "different-graph-id", null, null);
        GraphTemplateResponse response = service.update(id, req);

        assertThat(response.graphId()).isEqualTo("original-graph-id");
        assertThat(response.name()).isEqualTo("Base Updated");
        verify(repo).save(existing);
    }

    @Test
    void update_preservesGraphIdWhenNameChangesAndGraphIdIsNull() {
        UUID id = UUID.randomUUID();
        GraphTemplate existing = makeTemplate("Old Name", "old-name", 1);
        existing.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.save(any(GraphTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        GraphTemplateRequest req = new GraphTemplateRequest("Completely Different Name", "desc", null, null, null);
        GraphTemplateResponse response = service.update(id, req);

        assertThat(response.graphId()).isEqualTo("old-name");
        assertThat(response.name()).isEqualTo("Completely Different Name");
    }

    @Test
    void update_systemTemplate_throwsConflict() {
        UUID id = UUID.randomUUID();
        GraphTemplate gt = makeTemplate("System Base", "system-base", 1);
        gt.setId(id);
        gt.setSystem(true);
        when(repo.findById(id)).thenReturn(Optional.of(gt));

        GraphTemplateRequest req = new GraphTemplateRequest("Updated", "desc", null, null, null);

        assertThatThrownBy(() -> service.update(id, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot modify system template");
    }

    // --- delete ---

    @Test
    void delete_removesAllVersions() {
        UUID id = UUID.randomUUID();
        GraphTemplate gt = makeTemplate("ToDelete", "to-delete", 1);
        gt.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(gt));
        when(repo.findAllByGraphId("to-delete")).thenReturn(List.of(gt));
        when(workflowRunRepo.existsByGraphTemplateIdAndStatusIn(eq(id), anyCollection()))
                .thenReturn(false);

        service.delete(id);

        verify(repo).deleteAll(List.of(gt));
    }

    @Test
    void delete_throwsNotFoundForMissingTemplate() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_withActiveRuns_throwsConflict() {
        UUID id = UUID.randomUUID();
        GraphTemplate gt = makeTemplate("Active", "active-id", 1);
        gt.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(gt));
        when(repo.findAllByGraphId("active-id")).thenReturn(List.of(gt));
        when(workflowRunRepo.existsByGraphTemplateIdAndStatusIn(eq(id), anyCollection()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("active workflow runs");
    }

    @Test
    void delete_withOnlyCompletedRuns_succeeds() {
        UUID id = UUID.randomUUID();
        GraphTemplate gt = makeTemplate("Completed", "completed-id", 1);
        gt.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(gt));
        when(repo.findAllByGraphId("completed-id")).thenReturn(List.of(gt));
        when(workflowRunRepo.existsByGraphTemplateIdAndStatusIn(eq(id), anyCollection()))
                .thenReturn(false);

        service.delete(id);

        verify(repo).deleteAll(List.of(gt));
    }

    @Test
    void delete_allVersions_deletesAll() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        GraphTemplate v1 = makeTemplate("Template", "tpl-id", 1);
        v1.setId(id1);
        GraphTemplate v2 = makeTemplate("Template", "tpl-id", 2);
        v2.setId(id2);

        when(repo.findById(id1)).thenReturn(Optional.of(v1));
        when(repo.findAllByGraphId("tpl-id")).thenReturn(List.of(v1, v2));
        when(workflowRunRepo.existsByGraphTemplateIdAndStatusIn(eq(id1), anyCollection()))
                .thenReturn(false);
        when(workflowRunRepo.existsByGraphTemplateIdAndStatusIn(eq(id2), anyCollection()))
                .thenReturn(false);

        service.delete(id1);

        verify(repo).deleteAll(List.of(v1, v2));
    }

    @Test
    void delete_systemTemplate_throwsConflict() {
        UUID id = UUID.randomUUID();
        GraphTemplate gt = makeTemplate("System", "system", 1);
        gt.setId(id);
        gt.setSystem(true);
        when(repo.findById(id)).thenReturn(Optional.of(gt));

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot modify system template");
    }

    // --- slug generation (via fromRequest) ---

    @Test
    void create_slugifiesNameWhenGraphIdNotProvided() {
        GraphTemplateRequest req = new GraphTemplateRequest("My Cool Template!!", "desc", null, null, null);
        when(repo.findByGraphIdAndVersion("my-cool-template-", 1)).thenReturn(Optional.empty());
        when(repo.save(any(GraphTemplate.class))).thenAnswer(inv -> {
            GraphTemplate gt = inv.getArgument(0);
            gt.setId(UUID.randomUUID());
            return gt;
        });

        GraphTemplateResponse response = service.create(req);

        // "My Cool Template!!" -> "my-cool-template-"
        assertThat(response.graphId()).isEqualTo("my-cool-template-");
    }

    @Test
    void create_usesExplicitGraphIdWhenProvided() {
        GraphTemplateRequest req = new GraphTemplateRequest("My Template", "desc", "custom-id", null, null);
        when(repo.findByGraphIdAndVersion("custom-id", 1)).thenReturn(Optional.empty());
        when(repo.save(any(GraphTemplate.class))).thenAnswer(inv -> {
            GraphTemplate gt = inv.getArgument(0);
            gt.setId(UUID.randomUUID());
            return gt;
        });

        GraphTemplateResponse response = service.create(req);

        assertThat(response.graphId()).isEqualTo("custom-id");
    }

    // --- parseJsonField fallback ---

    @Test
    void toResponse_invalidInputSchema_fallsBackToEmptyArray() {
        GraphTemplate gt = makeTemplate("BadSchema", "bad-schema", 1);
        gt.setInputSchema("{not valid json!!");
        when(repo.findById(gt.getId())).thenReturn(Optional.of(gt));

        GraphTemplateResponse response = service.get(gt.getId());

        // Falls back to "[]" which is an empty array
        assertThat(response.inputSchema().isArray()).isTrue();
        assertThat(response.inputSchema()).isEmpty();
    }
}
