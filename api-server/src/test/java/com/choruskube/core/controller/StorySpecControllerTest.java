package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.StorySpecFixtures;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.config.TaskDeliveryGraph;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.StoryConflict;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.StoryConflictRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.GitHubAppService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.service.StoryDesignService;
import com.choruskube.core.service.StoryService;
import com.choruskube.core.service.TaskService;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

@AutoConfigureMockMvc
@Transactional
class StorySpecControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private StoryDesignService storyDesignService;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private StoryConflictRepository conflictRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private GitHubAppService gitHubAppService;

    @MockitoBean
    private GitHubCredentialResolver gitHubCredentialResolver;

    @MockitoBean
    private S3Client s3Client;

    @MockitoBean
    private OrgSecurity orgSecurity;

    @BeforeEach
    void setUp() {
        when(orgSecurity.canRead()).thenReturn(true);
        when(orgSecurity.canOperate()).thenReturn(true);
        when(orgSecurity.canAdmin()).thenReturn(true);
        when(orgSecurity.isPlatformAdmin()).thenReturn(true);
        WorkflowStub mockStub = Mockito.mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(mockStub);
    }

    @Test
    void getSpec_returnsTheCurrentVersion() throws Exception {
        StoryResponse story = makeStory("spec-get-current", "Story");
        approveSpec(story.id());
        approveSpec(story.id());

        mockMvc.perform(get("/api/v1/stories/" + story.id() + "/spec"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storyId").value(story.id().toString()))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.content").value("# Story spec (test fixture)"))
                .andExpect(jsonPath("$.approvedAt").exists());
    }

    @Test
    void getSpec_none_returns404() throws Exception {
        StoryResponse story = makeStory("spec-get-none", "Story");

        mockMvc.perform(get("/api/v1/stories/" + story.id() + "/spec")).andExpect(status().isNotFound());
    }

    @Test
    void getSpec_unknownStory_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/stories/" + UUID.randomUUID() + "/spec")).andExpect(status().isNotFound());
    }

    @Test
    void getSpec_belowCanReadPermission_returns403() throws Exception {
        when(orgSecurity.canRead()).thenReturn(false);
        StoryResponse story = makeStory("spec-get-forbidden", "Story");

        mockMvc.perform(get("/api/v1/stories/" + story.id() + "/spec")).andExpect(status().isForbidden());
    }

    @Test
    void listSpecVersions_returnsSummariesOldestFirst() throws Exception {
        StoryResponse story = makeStory("spec-versions", "Story");
        approveSpec(story.id());
        approveSpec(story.id());

        mockMvc.perform(get("/api/v1/stories/" + story.id() + "/spec/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[0].approvedAt").exists())
                .andExpect(jsonPath("$[0].content").doesNotExist());
    }

    @Test
    void getSpecVersion_returnsThatVersion_and404ForAnUnknownOne() throws Exception {
        StoryResponse story = makeStory("spec-version-one", "Story");
        approveSpec(story.id());
        approveSpec(story.id());

        mockMvc.perform(get("/api/v1/stories/" + story.id() + "/spec/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.storyId").value(story.id().toString()));
        mockMvc.perform(get("/api/v1/stories/" + story.id() + "/spec/versions/7"))
                .andExpect(status().isNotFound());
    }

    @Test
    void design_returns201WithTheRun() throws Exception {
        StoryResponse story = makeStory("spec-design-201", "Widget backend");

        mockMvc.perform(post("/api/v1/stories/" + story.id() + "/design"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Design: Widget backend"))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void design_whenSpecExists_returns409() throws Exception {
        StoryResponse story = makeStory("spec-design-409", "Story");
        approveSpec(story.id());

        mockMvc.perform(post("/api/v1/stories/" + story.id() + "/design")).andExpect(status().isConflict());
    }

    @Test
    void design_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);
        StoryResponse story = makeStory("spec-design-forbidden", "Story");

        mockMvc.perform(post("/api/v1/stories/" + story.id() + "/design")).andExpect(status().isForbidden());
    }

    @Test
    void revise_blankGuidance_returns400() throws Exception {
        StoryResponse story = makeStory("spec-revise-blank", "Story");
        approveSpec(story.id());

        mockMvc.perform(post("/api/v1/stories/" + story.id() + "/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("guidance", "  "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revise_returns201WithTheRevisionRun() throws Exception {
        StoryResponse story = makeStory("spec-revise-201", "Story");
        approveSpec(story.id());

        mockMvc.perform(post("/api/v1/stories/" + story.id() + "/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("guidance", "Use a hard cutoff"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Revise spec v1: Story"));
    }

    @Test
    void revise_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);
        StoryResponse story = makeStory("spec-revise-forbidden", "Story");

        mockMvc.perform(post("/api/v1/stories/" + story.id() + "/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("guidance", "G"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getStory_reportsSpecVersionAndOpenRevision() throws Exception {
        StoryResponse story = makeStory("spec-story-fields", "Story");
        approveSpec(story.id());
        RunResponse revision = storyDesignService.startRevision(story.id(), "Tighten it");

        mockMvc.perform(get("/api/v1/stories/" + story.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specVersion").value(1))
                .andExpect(jsonPath("$.specUnderRevision").value(true))
                .andExpect(jsonPath("$.openDesignRunId").value(revision.id().toString()));
    }

    @Test
    void getStory_completedButUnrecordedRevision_staysUnderRevision() throws Exception {
        StoryResponse story = makeStory("spec-story-unrecorded", "Story");
        approveSpec(story.id());
        RunResponse revision = storyDesignService.startRevision(story.id(), "Tighten it");
        WorkflowRun run = runRepo.findById(revision.id()).orElseThrow();
        run.setStatus(WorkflowRunStatus.completed);
        runRepo.saveAndFlush(run);

        mockMvc.perform(get("/api/v1/stories/" + story.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specVersion").value(1))
                .andExpect(jsonPath("$.specUnderRevision").value(true))
                .andExpect(jsonPath("$.openDesignRunId").value(revision.id().toString()));
        mockMvc.perform(post("/api/v1/stories/" + story.id() + "/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("guidance", "Again"))))
                .andExpect(status().isConflict());
    }

    @Test
    void getStory_initialDesignOpen_isNotUnderRevision() throws Exception {
        StoryResponse story = makeStory("spec-story-initial", "Story");
        RunResponse design = storyDesignService.startDesign(story.id());

        mockMvc.perform(get("/api/v1/stories/" + story.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specVersion").doesNotExist())
                .andExpect(jsonPath("$.specUnderRevision").value(false))
                .andExpect(jsonPath("$.openDesignRunId").value(design.id().toString()));
    }

    @Test
    void listStories_carriesEachStorysSpecState() throws Exception {
        EpicResponse epic = makeEpic("spec-list-state");
        StoryResponse designed = storyService.create(epic.id(), new StoryRequest("Designed", "D"));
        StoryResponse designing = storyService.create(epic.id(), new StoryRequest("Designing", "D"));
        approveSpec(designed.id());
        RunResponse design = storyDesignService.startDesign(designing.id());

        mockMvc.perform(get("/api/v1/epics/" + epic.id() + "/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + designed.id() + "')].specVersion")
                        .value(1))
                .andExpect(jsonPath("$[?(@.id=='" + designed.id() + "')].specUnderRevision")
                        .value(false))
                .andExpect(jsonPath("$[?(@.id=='" + designing.id() + "')].openDesignRunId")
                        .value(design.id().toString()));
        mockMvc.perform(get("/api/v1/stories").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + designed.id() + "')].specVersion")
                        .value(1));
    }

    @Test
    void conflicts_listsOpenConflictsWithTheirTask() throws Exception {
        StoryResponse story = makeStory("spec-conflicts", "Story");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("Add health check", "D"));
        approveSpec(story.id());
        WorkflowRun taskRun = taskRun(task.id());
        StoryConflict open = conflict(story.id(), taskRun, false);
        conflict(story.id(), taskRun, true);

        mockMvc.perform(get("/api/v1/stories/" + story.id() + "/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(open.getId().toString()))
                .andExpect(jsonPath("$[0].taskRunId").value(taskRun.getId().toString()))
                .andExpect(jsonPath("$[0].taskId").value(task.id().toString()))
                .andExpect(jsonPath("$[0].taskTitle").value("Add health check"))
                .andExpect(jsonPath("$[0].raisedAgainstVersion").value(1))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].report").doesNotExist());
    }

    // ---- helpers ----

    /** The fixture writes through JDBC, so the Story row JPA still holds must reach the database first. */
    private void approveSpec(UUID storyId) {
        entityManager.flush();
        StorySpecFixtures.approve(jdbc, storyId);
    }

    private WorkflowRun taskRun(UUID taskId) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow()
                .getId());
        run.setStatus(WorkflowRunStatus.awaiting_human);
        run.setTaskId(taskId);
        return runRepo.saveAndFlush(run);
    }

    private StoryConflict conflict(UUID storyId, WorkflowRun taskRun, boolean released) {
        TemplateNode anyNode = templateNodeRepo.findByGraphTemplateId(taskRun.getGraphTemplateId()).stream()
                .findFirst()
                .orElseThrow();
        NodeExecution analyze = new NodeExecution();
        analyze.setWorkflowRunId(taskRun.getId());
        analyze.setTemplateNodeId(anyNode.getId());
        analyze.setLabel(TaskDeliveryGraph.ANALYZE);
        analyze.setStatus(NodeExecutionStatus.completed);
        analyze.setIteration(released ? 1 : 2);
        analyze = execRepo.saveAndFlush(analyze);
        StoryConflict c = new StoryConflict();
        c.setStoryId(storyId);
        c.setTaskRunId(taskRun.getId());
        c.setAnalyzeNodeExecutionId(analyze.getId());
        c.setRaisedAgainstVersion(1);
        c.setReport("The spec is wrong about X.");
        if (released) {
            c.setReleasedAt(java.time.Instant.now());
        }
        return conflictRepo.saveAndFlush(c);
    }

    private StoryResponse makeStory(String slug, String title) {
        return storyService.create(makeEpic(slug).id(), new StoryRequest(title, "Story desc"));
    }

    private EpicResponse makeEpic(String slug) {
        String url = "https://github.com/test/" + slug + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }
}
