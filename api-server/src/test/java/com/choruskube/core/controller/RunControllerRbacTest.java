package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests role-based access control enforcement via @PreAuthorize annotations.
 *
 * <p>Uses @MockitoBean to replace the no-op default with a mock that reflects role-specific
 * permission decisions, so that @PreAuthorize SpEL expressions are actually exercised. Restores
 * the default (all-allow) behavior for unrelated tests via @BeforeEach defaults.
 *
 * <p>Verifies that:
 * - Viewers can read but cannot create/mutate
 * - Operators can create/operate but cannot delete managed resources
 * - Admins can perform all operations
 */
@AutoConfigureMockMvc
@Transactional
class RunControllerRbacTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private OrgSecurity orgSecurity;

    private GraphTemplate template;
    private WorkflowRun run;
    private GitRepo gitRepo;

    @BeforeEach
    void setUp() {
        // Default: allow read, deny operate/admin (viewer-level baseline)
        when(orgSecurity.canRead()).thenReturn(true);
        when(orgSecurity.canOperate()).thenReturn(false);
        when(orgSecurity.canAdmin()).thenReturn(false);
        when(orgSecurity.isPlatformAdmin()).thenReturn(false);

        template = new GraphTemplate();
        template.setName("RBAC Test Template");
        template.setGraphId("rbac-test-template");
        template.setVersion(1);
        template.setInputSchema("[]");
        template.setSystem(true);
        template = graphTemplateRepo.save(template);

        NodeDefinition nd = new NodeDefinition();
        nd.setName("rbac-test-node");
        nd.setExecutorType(ExecutorType.ai);
        nd.setSkills("[]");
        nd.setInputSpec("{}");
        nd.setOutputSpec("{}");
        nd.setSecrets("[]");
        nd = nodeDefRepo.save(nd);

        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nd.getId());
        tn.setLabel("rbac-test-node");
        tn.setConfigOverrides("{}");
        tn.setEntrypoint(true);
        templateNodeRepo.save(tn);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setName("RBAC Run");
        run = runRepo.save(run);

        gitRepo = new GitRepo();
        gitRepo.setUrl("https://github.com/rbac-test/test-repo");
        gitRepo.setName(RepoNameUtil.deriveOwnerRepoName("https://github.com/rbac-test/test-repo"));
        gitRepo = gitRepoRepo.save(gitRepo);
    }

    // --- Viewer role tests ---

    @Test
    void listRuns_allowedForViewer() throws Exception {
        // canRead=true (default)
        mockMvc.perform(get("/api/v1/runs")).andExpect(status().isOk());
    }

    @Test
    void getRun_allowedForViewer() throws Exception {
        // canRead=true (default)
        mockMvc.perform(get("/api/v1/runs/" + run.getId())).andExpect(status().isOk());
    }

    @Test
    void startRun_forbiddenForViewer() throws Exception {
        // canOperate=false (default)
        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("graphTemplateId", template.getId().toString(), "inputs", Map.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelRun_forbiddenForViewer() throws Exception {
        // canOperate=false (default)
        mockMvc.perform(post("/api/v1/runs/" + run.getId() + "/cancel")).andExpect(status().isForbidden());
    }

    @Test
    void getReviewHistory_allowedForViewer() throws Exception {
        // canRead=true (default)
        mockMvc.perform(get("/api/v1/runs/" + run.getId() + "/review-history")).andExpect(status().isOk());
    }

    @Test
    void deleteGitRepo_forbiddenForViewer() throws Exception {
        // canAdmin=false (default)
        mockMvc.perform(delete("/api/v1/git-repos/" + gitRepo.getId())).andExpect(status().isForbidden());
    }

    // --- Operator role tests ---

    @Test
    void startRun_allowedForOperator() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(true);

        WorkflowStub stub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.eq("DAGExecutorWorkflow"), ArgumentMatchers.any()))
                .thenReturn(stub);

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("graphTemplateId", template.getId().toString(), "inputs", Map.of()))))
                .andExpect(status().isCreated());
    }

    @Test
    void deleteGitRepo_forbiddenForOperator() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(true);
        // canAdmin=false (default)
        mockMvc.perform(delete("/api/v1/git-repos/" + gitRepo.getId())).andExpect(status().isForbidden());
    }

    // --- Admin role tests ---

    @Test
    void deleteGitRepo_allowedForAdmin() throws Exception {
        when(orgSecurity.canAdmin()).thenReturn(true);
        mockMvc.perform(delete("/api/v1/git-repos/" + gitRepo.getId())).andExpect(status().isNoContent());
    }

    @Test
    void listRuns_allowedForAdmin() throws Exception {
        // GET /api/v1/runs is guarded by canRead (not canAdmin); canRead=true by default in setUp.
        // This test verifies that an admin (who also has canRead) can list runs.
        when(orgSecurity.canAdmin()).thenReturn(true);
        when(orgSecurity.canRead()).thenReturn(true);
        mockMvc.perform(get("/api/v1/runs")).andExpect(status().isOk());
    }
}
