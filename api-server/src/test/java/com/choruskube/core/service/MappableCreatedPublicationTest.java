package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateRunPullRequestRequest;
import com.choruskube.core.dto.GitRepoRequest;
import com.choruskube.core.dto.NodeDefinitionRequest;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies that MappableCreated events are published at the creation sites. In core
 * (auth.enabled=false) there is no domain listener acting on the event, so we register a
 * test-only capturing listener to assert that the publish calls are in place.
 *
 * <p>Covers: - request-scoped site: GitRepoService.create → of("software_project", id) -
 * request-scoped site: NodeDefinitionService.create → of("node_definition", id) - agent path site:
 * RunPullRequestService.createPullRequest → withParent("run_pull_request", id, "workflow_run",
 * runId)
 */
@Transactional
class MappableCreatedPublicationTest extends BaseTest {

    // -----------------------------------------------------------------------
    // Test-only event capturing configuration
    // -----------------------------------------------------------------------

    /**
     * Simple collector that captures every MappableCreated event published within the test's
     * transaction. Registered as a bean via the inner @TestConfiguration so it is wired into the
     * Spring context without polluting the main application scan.
     */
    static class MappableEventCollector {

        private final List<MappableCreated> captured = new ArrayList<>();

        @EventListener
        public void on(MappableCreated event) {
            captured.add(event);
        }

        public List<MappableCreated> getCaptured() {
            return captured;
        }

        public void clear() {
            captured.clear();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        MappableEventCollector mappableEventCollector() {
            return new MappableEventCollector();
        }
    }

    // -----------------------------------------------------------------------
    // Infrastructure mocks
    // -----------------------------------------------------------------------

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private com.choruskube.core.observability.AuditSink auditSink;

    @MockitoBean
    private com.choruskube.core.observability.UsageSink usageSink;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    // -----------------------------------------------------------------------
    // Services under test
    // -----------------------------------------------------------------------

    @Autowired
    private GitRepoService gitRepoService;

    @Autowired
    private NodeDefinitionService nodeDefinitionService;

    @Autowired
    private RunPullRequestService runPullRequestService;

    @Autowired
    private RepoGroupService repoGroupService;

    @Autowired
    private AutopilotService autopilotService;

    // -----------------------------------------------------------------------
    // Repos
    // -----------------------------------------------------------------------

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private AutopilotRepository autopilotRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    // -----------------------------------------------------------------------
    // Captured events
    // -----------------------------------------------------------------------

    @Autowired
    private MappableEventCollector collector;

    @BeforeEach
    void setUpTenantContext() {
        collector.clear();
    }

    @AfterEach
    void clearEvents() {
        collector.clear();
    }

    // -----------------------------------------------------------------------
    // Request-scoped site: GitRepoService.create → of("software_project", id)
    // -----------------------------------------------------------------------

    @Test
    void gitRepoCreate_publishesMappableCreated_withSoftwareProjectType_andNoParent() {
        var resp = gitRepoService.create(
                new GitRepoRequest("https://github.com/test/event-pub-test.git", "main", null, null, "[]", false));

        List<MappableCreated> events = collector.getCaptured();
        assertThat(events)
                .as("exactly one MappableCreated should be published for GitRepo creation")
                .hasSize(1);

        MappableCreated evt = events.get(0);
        assertThat(evt.resourceType())
                .as("resourceType must be the table name 'software_project'")
                .isEqualTo("software_project");
        assertThat(evt.resourceId())
                .as("resourceId must match the saved entity id")
                .isEqualTo(resp.id());
        assertThat(evt.parent()).as("of(...) factory → parent must be null").isNull();
    }

    // -----------------------------------------------------------------------
    // Request-scoped site: NodeDefinitionService.create → of("node_definition", id)
    // -----------------------------------------------------------------------

    @Test
    void nodeDefinitionCreate_publishesMappableCreated_withNodeDefinitionType_andNoParent() {
        var resp = nodeDefinitionService.create(new NodeDefinitionRequest(
                "test-nd-" + UUID.randomUUID(), "script", null, null, "[]", "{}", "{}", 300, "[]"));

        List<MappableCreated> events = collector.getCaptured();
        assertThat(events)
                .as("exactly one MappableCreated should be published for NodeDefinition creation")
                .hasSize(1);

        MappableCreated evt = events.get(0);
        assertThat(evt.resourceType())
                .as("resourceType must be the table name 'node_definition'")
                .isEqualTo("node_definition");
        assertThat(evt.resourceId())
                .as("resourceId must match the saved entity id")
                .isEqualTo(resp.id());
        assertThat(evt.parent()).as("of(...) factory → parent must be null").isNull();
    }

    // -----------------------------------------------------------------------
    // Agent-path site: RunPullRequestService.createPullRequest →
    //                  withParent("run_pull_request", id, "workflow_run", runId)
    // -----------------------------------------------------------------------

    @Test
    void runPullRequestCreate_publishesMappableCreated_withRunPullRequestType_andWorkflowRunParent() {
        // Build a real GraphTemplate row to satisfy the workflow_run.graph_template_id FK.
        GraphTemplate template = new GraphTemplate();
        template.setGraphId("test-graph-" + UUID.randomUUID());
        template.setVersion(1);
        template.setName("Test Template for PR Event Test");
        template.setInputSchema("[]");
        template = graphTemplateRepo.save(template);

        // Build a minimal WorkflowRun row so createPullRequest can load it.
        WorkflowRun run = new WorkflowRun();
        run.setStatus(WorkflowRunStatus.running);
        run.setGraphTemplateId(template.getId());
        run.setExternalRunId("choruskube-run-test-" + UUID.randomUUID());
        run.setInputs("{}");
        run.setGraphVersion(1);
        run = runRepo.save(run);
        UUID runId = run.getId();

        // Build a minimal GitRepo row so createPullRequest can load it.
        GitRepo repo = new GitRepo();
        repo.setUrl("https://github.com/test/pr-event-test.git");
        repo.setName(RepoNameUtil.deriveOwnerRepoName(repo.getUrl()));
        repo.setDefaultBranch("main");
        repo.setSecrets("[]");
        repo = gitRepoRepo.save(repo);
        UUID gitRepoId = repo.getId();

        // Build a real NodeExecution row belonging to this run so it satisfies createPullRequest's
        // run-scoping guard, which requires a genuine node_execution row (FK'd to template_node).
        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("pr-event-test-node-" + UUID.randomUUID());
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("test:latest");
        nodeDef.setPromptTemplate("test");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode templateNode = new TemplateNode();
        templateNode.setGraphTemplateId(template.getId());
        templateNode.setNodeDefinitionId(nodeDef.getId());
        templateNode.setLabel("PR Event Test Node");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);
        UUID nodeExecId = exec.getId();

        collector.clear(); // discard any events from the setup above

        var req = new CreateRunPullRequestRequest(
                gitRepoId, "https://github.com/test/pr-event-test/pull/1", 1, "feat: test PR", "pr-event-test");

        runPullRequestService.createPullRequest(runId, nodeExecId, req);

        List<MappableCreated> events = collector.getCaptured();
        assertThat(events)
                .as("exactly one MappableCreated should be published for RunPullRequest creation")
                .hasSize(1);

        MappableCreated evt = events.get(0);
        assertThat(evt.resourceType())
                .as("resourceType must be the table name 'run_pull_request'")
                .isEqualTo("run_pull_request");
        assertThat(evt.resourceId()).as("resourceId must not be null").isNotNull();
        assertThat(evt.parent())
                .as("withParent(...) factory → parent must not be null")
                .isNotNull();
        assertThat(evt.parent().parentType())
                .as("parentType must be 'workflow_run'")
                .isEqualTo("workflow_run");
        assertThat(evt.parent().parentId()).as("parentId must be the run's id").isEqualTo(runId);
    }

    // -----------------------------------------------------------------------
    // Request-scoped site: RepoGroupService.create → of("software_project", id)
    // -----------------------------------------------------------------------

    @Test
    void repoGroupCreate_publishesMappableCreated_withSoftwareProjectType_andNoParent() {
        GitRepo repo =
                seedGitRepo("rg-event-pub-" + UUID.randomUUID().toString().substring(0, 8));
        collector.clear(); // discard the GitRepo creation event

        RepoGroup group = repoGroupService.create(
                "rg-event-pub-" + UUID.randomUUID().toString().substring(0, 8),
                "registry/agent:latest",
                "Test group for event publication",
                List.of(repo.getId()));

        List<MappableCreated> events = collector.getCaptured();
        assertThat(events)
                .as("exactly one MappableCreated should be published for RepoGroup creation (request path)")
                .hasSize(1);

        MappableCreated evt = events.get(0);
        assertThat(evt.resourceType())
                .as("resourceType must be 'software_project' (RepoGroup shares the SP table PK)")
                .isEqualTo("software_project");
        assertThat(evt.resourceId())
                .as("resourceId must match the saved RepoGroup id")
                .isEqualTo(group.getId());
        assertThat(evt.parent()).as("of(...) factory → parent must be null").isNull();
    }

    // -----------------------------------------------------------------------
    // Request-scoped site: AutopilotResolver.getOrCreateForCurrentScope →
    //                      of("autopilot", id)
    // -----------------------------------------------------------------------

    /**
     * The Autopilot row is created lazily by the first mutation, not by a create endpoint, which is
     * how it went without an ownership event at all. Downstream this event is what gives the row an
     * owner; a row created without one is one the scope provider cannot resolve afterwards.
     *
     * <p>{@code update(null)} is the cheapest way through get-or-create — it inserts and then
     * changes nothing. {@code engage()} would reach the same insert and then sweep readiness across
     * every Epic in the shared test database on its way back out.
     */
    @Test
    void autopilotGetOrCreate_publishesMappableCreated_withAutopilotType_andNoParent() {
        autopilotService.update(null);

        List<MappableCreated> events = collector.getCaptured();
        assertThat(events)
                .as("exactly one MappableCreated should be published for Autopilot creation")
                .hasSize(1);

        MappableCreated evt = events.get(0);
        assertThat(evt.resourceType())
                .as("resourceType must be the table name 'autopilot' — the downstream ownership "
                        + "writer switches on this string")
                .isEqualTo("autopilot");
        assertThat(evt.resourceId())
                .as("resourceId must be the inserted row")
                .isEqualTo(autopilotRepo.findAll().getFirst().getId());
        assertThat(evt.parent()).as("of(...) factory → parent must be null").isNull();
    }

    @Test
    void autopilotGetOrCreate_onAnExistingRow_publishesNothing() {
        autopilotService.update(null);
        collector.clear();

        autopilotService.update(2);

        assertThat(collector.getCaptured())
                .as("nothing was created, so nothing acquired an owner")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Seeder/system path: RepoGroupService.createInternal → no event published
    // -----------------------------------------------------------------------

    @Test
    void repoGroupCreateInternal_doesNotPublishAnyMappableCreated() {
        GitRepo repo = seedGitRepo("rg-noevent-" + UUID.randomUUID().toString().substring(0, 8));
        collector.clear(); // discard the GitRepo creation event

        repoGroupService.createInternal(
                "rg-noevent-" + UUID.randomUUID().toString().substring(0, 8),
                "registry/agent:latest",
                "Test group — seeder path, must emit no event",
                List.of(repo.getId()));

        assertThat(collector.getCaptured())
                .as("createInternal (seeder path) must NOT publish any MappableCreated event")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private GitRepo seedGitRepo(String shortName) {
        GitRepo repo = new GitRepo();
        repo.setName(shortName);
        repo.setUrl("https://github.com/owner/" + shortName + ".git");
        repo.setDefaultBranch("main");
        repo.setSecrets("[]");
        return gitRepoRepo.save(repo);
    }
}
