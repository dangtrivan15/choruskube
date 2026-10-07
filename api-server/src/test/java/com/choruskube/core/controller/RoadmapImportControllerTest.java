package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.MilestoneRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import com.choruskube.core.service.RunEventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/v1/roadmap/import}: a person imports a roadmap document into a software
 * project — validated in full, then materialized all-or-nothing through the public write path.
 */
@AutoConfigureMockMvc
@Transactional
class RoadmapImportControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicRepository epicRepo;

    @Autowired
    private StoryRepository storyRepo;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private MilestoneRepository milestoneRepo;

    @Autowired
    private WorkItemDependencyRepository dependencyRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private OrgSecurity orgSecurity;

    @BeforeEach
    void setUp() {
        when(orgSecurity.canRead()).thenReturn(true);
        when(orgSecurity.canOperate()).thenReturn(true);
        when(orgSecurity.canAdmin()).thenReturn(true);
    }

    @Test
    void dryRun_returnsCountsAndCreatesNothing() throws Exception {
        GitRepo project = makeRepo("dry-run");

        importDoc(project.getId(), true, """
                        {"milestones": [{"key": "m1", "name": "Q4"}],
                         "epics": [{"title": "Checkout", "description": "d", "key": "e1", "milestone": "m1",
                                    "stories": [{"title": "Cart", "description": "d", "key": "s1",
                                                 "tasks": [{"title": "API", "description": "d", "key": "t1"},
                                                           {"title": "UI", "description": "d", "key": "t2"}]}]}],
                         "dependencies": [{"blocking": "t1", "blocked": "t2"}]}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.milestones").value(1))
                .andExpect(jsonPath("$.newEpics").value(1))
                .andExpect(jsonPath("$.newStories").value(1))
                .andExpect(jsonPath("$.newTasks").value(2))
                .andExpect(jsonPath("$.dependencies").value(1))
                .andExpect(jsonPath("$.createdEpicIds", hasSize(0)));

        assertThat(epicRepo.findBySoftwareProjectIdOrderByCreatedAtDesc(project.getId()))
                .isEmpty();
        assertThat(milestoneRepo.existsBySoftwareProjectIdAndNameIgnoreCase(project.getId(), "Q4"))
                .isFalse();
    }

    @Test
    void import_createsTreeMilestoneAndEdgeToAnAnchoredEpic() throws Exception {
        GitRepo project = makeRepo("create");
        Epic existing = makeEpic(project.getId(), "Existing platform work");

        String body = importDoc(project.getId(), false, """
                        {"milestones": [{"key": "m1", "name": "Q4"}],
                         "epics": [{"title": "Checkout", "description": "d", "priority": "High", "key": "new",
                                    "milestone": "m1",
                                    "stories": [{"title": "Cart", "description": "d",
                                                 "tasks": [{"title": "API", "description": "d"}]}]},
                                   {"existingId": "%s", "key": "old"}],
                         "dependencies": [{"blocking": "old", "blocked": "new"}]}
                        """.formatted(existing.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.existingItems").value(1))
                .andExpect(jsonPath("$.createdEpicIds", hasSize(1)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID createdId = UUID.fromString(
                objectMapper.readTree(body).get("createdEpicIds").get(0).asText());
        Epic created = epicRepo.findById(createdId).orElseThrow();
        assertThat(created.getSoftwareProjectId()).isEqualTo(project.getId());
        assertThat(created.getTitle()).isEqualTo("Checkout");
        assertThat(created.getPriority().name()).isEqualTo("high");
        assertThat(created.getMilestoneId()).isNotNull();

        List<Story> stories = storyRepo.findAll().stream()
                .filter(s -> s.getEpicId().equals(createdId))
                .toList();
        assertThat(stories).extracting(Story::getTitle).containsExactly("Cart");
        assertThat(taskRepo.findAll().stream()
                        .filter(t -> t.getStoryId().equals(stories.get(0).getId())))
                .hasSize(1);

        List<WorkItemDependency> edges =
                dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(createdId), Set.of(createdId));
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).getBlockingItemType()).isEqualTo(BlockableItemType.epic);
        assertThat(edges.get(0).getBlockingItemId()).isEqualTo(existing.getId());
        assertThat(edges.get(0).getBlockedItemId()).isEqualTo(createdId);
    }

    @Test
    void invalidDocument_returnsEveryErrorAndCreatesNothing() throws Exception {
        GitRepo project = makeRepo("invalid");
        GitRepo other = makeRepo("invalid-other");
        Epic foreign = makeEpic(other.getId(), "Someone else's");

        importDoc(project.getId(), false, """
                        {"epics": [{"title": "Checkout", "description": "d", "key": "new", "stories": []},
                                   {"existingId": "%s", "key": "foreign"}],
                         "dependencies": [{"blocking": "new", "blocked": "missing"}]}
                        """.formatted(foreign.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors", hasItem("epics[0]: a new epic needs at least one story")))
                .andExpect(jsonPath(
                        "$.errors",
                        hasItem("epics[1]: existing epic " + foreign.getId() + " not found in this software project")))
                .andExpect(jsonPath("$.errors", hasItem("dependencies[0]: unknown key 'missing'")));

        assertThat(epicRepo.findBySoftwareProjectIdOrderByCreatedAtDesc(project.getId()))
                .isEmpty();
    }

    @Test
    void typeError_isReportedWithItsJsonPath() throws Exception {
        GitRepo project = makeRepo("type-error");

        importDoc(project.getId(), false, """
                        {"epics": [{"existingId": "not-a-uuid"}]}
                        """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0]", startsWith("epics[0].existingId: ")));
    }

    @Test
    void emptyDocument_hasNothingToImport() throws Exception {
        GitRepo project = makeRepo("empty");

        importDoc(project.getId(), false, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.errors", contains("document: nothing to import — add at least one epic or milestone")));
    }

    @Test
    void unknownProject_returns404() throws Exception {
        importDoc(UUID.randomUUID(), true, "{}").andExpect(status().isNotFound());
    }

    @Test
    void callerWhoCannotOperate_isForbidden() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);
        GitRepo project = makeRepo("forbidden");

        importDoc(project.getId(), true, "{}").andExpect(status().isForbidden());
    }

    /**
     * Runs outside the test-managed transaction: rollback is only observable once the import's
     * own transaction really commits or rolls back. Everything it seeds is deleted at the end,
     * because the shared test database is never reset between classes.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anyErrorWhileMaterializing_rollsBackTheWholeImport() throws Exception {
        GitRepo project = makeRepo("rollback");
        Epic first = makeEpic(project.getId(), "First");
        Epic second = makeEpic(project.getId(), "Second");
        WorkItemDependency existingEdge = new WorkItemDependency();
        existingEdge.setBlockingItemType(BlockableItemType.epic);
        existingEdge.setBlockingItemId(first.getId());
        existingEdge.setBlockedItemType(BlockableItemType.epic);
        existingEdge.setBlockedItemId(second.getId());
        existingEdge = dependencyRepo.save(existingEdge);
        try {
            // Valid on its own, but closes a cycle through the existing edge — only the write path
            // can see that, after the new Epic has already been inserted.
            importDoc(project.getId(), false, """
                            {"epics": [{"title": "Rolled back", "description": "d",
                                        "stories": [{"title": "S", "description": "d",
                                                     "tasks": [{"title": "T", "description": "d"}]}]},
                                       {"existingId": "%s", "key": "first"},
                                       {"existingId": "%s", "key": "second"}],
                             "dependencies": [{"blocking": "second", "blocked": "first"}]}
                            """.formatted(first.getId(), second.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath(
                            "$.errors[0]", startsWith("Failed to materialize dependency edge 'second' -> 'first'")));

            assertThat(epicRepo.findBySoftwareProjectIdOrderByCreatedAtDesc(project.getId()))
                    .extracting(Epic::getTitle)
                    .containsExactlyInAnyOrder("First", "Second");
        } finally {
            dependencyRepo.deleteById(existingEdge.getId());
            epicRepo.deleteAll(List.of(first, second));
            gitRepoRepo.deleteById(project.getId());
        }
    }

    private ResultActions importDoc(UUID projectId, boolean dryRun, String json) throws Exception {
        JsonNode parsed = objectMapper.readTree(json);
        return mockMvc.perform(post("/api/v1/roadmap/import")
                .param("softwareProjectId", projectId.toString())
                .param("dryRun", String.valueOf(dryRun))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(parsed)));
    }

    private GitRepo makeRepo(String label) {
        String url = "https://github.com/test/roadmap-import-" + label + "-" + UUID.randomUUID() + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(url);
        r.setSecrets("[]");
        return gitRepoRepo.save(r);
    }

    private Epic makeEpic(UUID projectId, String title) {
        Epic e = new Epic();
        e.setSoftwareProjectId(projectId);
        e.setTitle(title);
        e.setDescription("d");
        return epicRepo.save(e);
    }
}
