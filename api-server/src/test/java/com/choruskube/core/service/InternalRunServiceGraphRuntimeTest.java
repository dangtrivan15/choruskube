package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.GraphRuntimeSnapshotResponse;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InternalRunServiceGraphRuntimeTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private StoryRepository storyRepo;

    @Mock
    private TaskRepository taskRepo;

    @Mock
    private EpicRepository epicRepo;

    @Mock
    private WorkItemDependencyRepository dependencyRepo;

    private ObjectMapper objectMapper = new ObjectMapper();
    private InternalRunService service;

    private static final UUID NODE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EDGE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TARGET_NODE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GIT_REPO_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final String SNAPSHOT_JSON = """
            {
              "nodes": [{
                "template_node_id": "11111111-1111-1111-1111-111111111111",
                "label": "Code Review",
                "executor_type": "ai",
                "image": "agent:latest",
                "prompt_template": "Review the code...",
                "input_spec": {},
                "output_spec": {},
                "timeout_seconds": 1800,
                "secrets": [{"name": "GITHUB_TOKEN"}],
                "skills": [],
                "is_entrypoint": true,
                "config_overrides": {"loop_group": "review"}
              }],
              "edges": [{
                "template_edge_id": "22222222-2222-2222-2222-222222222222",
                "source_node_id": "11111111-1111-1111-1111-111111111111",
                "target_node_id": "33333333-3333-3333-3333-333333333333",
                "condition": "approved"
              }],
              "enable_docker": true,
              "docker_config": {"registry_mirrors": ["mirror.local"]},
              "namespace": "agent-ns",
              "inputs": {"repo_url": "https://github.com/test/repo", "git_repo_id": "44444444-4444-4444-4444-444444444444"}
            }
            """;

    // Per-node-type model/effort config (Decision 2 in the accompanying spec): the four
    // new iteration-aware config_overrides keys must survive the raw-JSON-snapshot →
    // RuntimeNode.configOverrides() projection byte-for-byte, since dag_executor.go reads
    // them from exactly this DTO field via snapshotNode.ConfigOverrides.
    private static final String SNAPSHOT_WITH_ITERATION_AWARE_CONFIG_JSON = """
            {
              "nodes": [{
                "template_node_id": "11111111-1111-1111-1111-111111111111",
                "label": "Code Review",
                "executor_type": "ai",
                "image": "agent:latest",
                "prompt_template": "Review the code...",
                "input_spec": {},
                "output_spec": {},
                "timeout_seconds": 1800,
                "secrets": [],
                "skills": [],
                "is_entrypoint": true,
                "config_overrides": {
                  "loop_group": "review",
                  "model_first_iteration": "claude-opus-4-8",
                  "effort_first_iteration": "xhigh",
                  "model_subsequent_iteration": "claude-sonnet-5",
                  "effort_subsequent_iteration": "high"
                }
              }],
              "edges": [],
              "inputs": {}
            }
            """;

    private static final String SNAPSHOT_WITH_REPOS_JSON = """
            {
              "nodes": [{
                "template_node_id": "11111111-1111-1111-1111-111111111111",
                "label": "Implement",
                "executor_type": "ai",
                "image": "agent:latest",
                "prompt_template": "Implement...",
                "input_spec": {},
                "output_spec": {},
                "timeout_seconds": 3600,
                "secrets": [],
                "skills": [],
                "is_entrypoint": true,
                "config_overrides": {}
              }],
              "edges": [],
              "inputs": {"feature_request": "test"},
              "repos": [
                {"id": "aaaa0000-0000-0000-0000-000000000001", "url": "https://github.com/org/backend-api", "name": "backend-api", "test_command": "./gradlew test", "agent_image": "agent:v2"},
                {"id": "aaaa0000-0000-0000-0000-000000000002", "url": "https://github.com/org/frontend-app", "name": "frontend-app"}
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        service = new InternalRunService(
                runRepo,
                null,
                null,
                snapshotBuilder,
                null,
                objectMapper,
                null,
                null,
                null,
                null,
                Optional.empty(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                storyRepo,
                taskRepo,
                epicRepo,
                null,
                new DecisionOptionsResolver(),
                dependencyRepo);
    }

    @Test
    void getGraphRuntimeSnapshot_callsSnapshotBuilder() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        service.getGraphRuntimeSnapshot(runId);

        verify(snapshotBuilder).buildSnapshotForRun(run);
    }

    @Test
    void getGraphRuntimeSnapshot_projectsNodeWorkflowFields() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.nodes()).hasSize(1);
        GraphRuntimeSnapshotResponse.RuntimeNode node = response.nodes().get(0);
        assertThat(node.templateNodeId()).isEqualTo(NODE_ID);
        assertThat(node.label()).isEqualTo("Code Review");
        assertThat(node.executorType()).isEqualTo("ai");
        assertThat(node.promptTemplate()).isEqualTo("Review the code...");
        assertThat(node.timeoutSeconds()).isEqualTo(1800);
        assertThat(node.isEntrypoint()).isTrue();
        assertThat(node.configOverrides()).containsEntry("loop_group", "review");
    }

    @Test
    void getGraphRuntimeSnapshot_projectsIterationAwareModelEffortConfigOverridesUnchanged() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_WITH_ITERATION_AWARE_CONFIG_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        GraphRuntimeSnapshotResponse.RuntimeNode node = response.nodes().get(0);
        assertThat(node.configOverrides())
                .containsEntry("model_first_iteration", "claude-opus-4-8")
                .containsEntry("effort_first_iteration", "xhigh")
                .containsEntry("model_subsequent_iteration", "claude-sonnet-5")
                .containsEntry("effort_subsequent_iteration", "high");
    }

    @Test
    void getGraphRuntimeSnapshot_projectsEdgeFields() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.edges()).hasSize(1);
        GraphRuntimeSnapshotResponse.RuntimeEdge edge = response.edges().get(0);
        assertThat(edge.templateEdgeId()).isEqualTo(EDGE_ID);
        assertThat(edge.sourceNodeId()).isEqualTo(NODE_ID);
        assertThat(edge.targetNodeId()).isEqualTo(TARGET_NODE_ID);
        assertThat(edge.condition()).isEqualTo("approved");
    }

    @Test
    void getGraphRuntimeSnapshot_includesInputs() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.inputs()).containsEntry("repo_url", "https://github.com/test/repo");
        assertThat(response.inputs()).containsEntry("git_repo_id", GIT_REPO_ID.toString());
    }

    @Test
    void getGraphRuntimeSnapshot_doesNotExposeInfraFields() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        // RuntimeNode record does not have image, secrets, input_spec, output_spec, skills
        // Verifying by confirming the record type only has the workflow fields
        GraphRuntimeSnapshotResponse.RuntimeNode node = response.nodes().get(0);
        // These fields are simply not present on the record — the type system enforces this
        assertThat(node).isNotNull();
        assertThat(node.templateNodeId()).isNotNull();
        // namespace, docker_config, enable_docker are not on the response record either
        // (inputs map comes from the snapshot's "inputs" field, not from namespace/docker_config)
        assertThat(response.inputs()).doesNotContainKey("namespace");
        assertThat(response.inputs()).doesNotContainKey("enable_docker");
        assertThat(response.inputs()).doesNotContainKey("docker_config");
    }

    @Test
    void getGraphRuntimeSnapshot_projectsMultiRepoFields() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_WITH_REPOS_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.repos()).hasSize(2);

        GraphRuntimeSnapshotResponse.RuntimeRepo repo1 = response.repos().get(0);
        assertThat(repo1.id()).isEqualTo("aaaa0000-0000-0000-0000-000000000001");
        assertThat(repo1.url()).isEqualTo("https://github.com/org/backend-api");
        assertThat(repo1.name()).isEqualTo("backend-api");
        assertThat(repo1.testCommand()).isEqualTo("./gradlew test");
        assertThat(repo1.agentImage()).isEqualTo("agent:v2");

        GraphRuntimeSnapshotResponse.RuntimeRepo repo2 = response.repos().get(1);
        assertThat(repo2.id()).isEqualTo("aaaa0000-0000-0000-0000-000000000002");
        assertThat(repo2.url()).isEqualTo("https://github.com/org/frontend-app");
        assertThat(repo2.name()).isEqualTo("frontend-app");
        assertThat(repo2.testCommand()).isNull();
        assertThat(repo2.agentImage()).isNull();
    }

    @Test
    void getGraphRuntimeSnapshot_withNoRepos_returnsEmptyReposList() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        // SNAPSHOT_JSON has no repos array → should default to empty list
        assertThat(response.repos()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // taskContext — resolved live off run.getTaskId() -> task.storyId -> story.epicId
    // (Decision 1), broadcast into the snapshot for every node to consume (Decision 3).
    // -----------------------------------------------------------------------

    @Test
    void getGraphRuntimeSnapshot_noTaskLinked_taskContextIsNull() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.taskContext()).isNull();
    }

    @Test
    void getGraphRuntimeSnapshot_taskTriggeredRun_populatesFullTaskContext() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        UUID epicId = UUID.randomUUID();

        WorkflowRun run = new WorkflowRun();
        run.setTaskId(taskId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        Task task = new Task();
        task.setId(taskId);
        task.setTitle("Wire up task_context");
        task.setStoryId(storyId);

        Story story = new Story();
        story.setId(storyId);
        story.setTitle("Agent identity threading");
        story.setEpicId(epicId);

        Epic epic = new Epic();
        epic.setId(epicId);
        epic.setTitle("Roadmap-aware agents");

        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(storyRepo.findById(storyId)).thenReturn(Optional.of(story));
        when(epicRepo.findById(epicId)).thenReturn(Optional.of(epic));

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.taskContext()).isNotNull();
        GraphRuntimeSnapshotResponse.TaskContext taskContext = response.taskContext();
        assertThat(taskContext.taskId()).isEqualTo(taskId);
        assertThat(taskContext.taskTitle()).isEqualTo("Wire up task_context");
        assertThat(taskContext.storyId()).isEqualTo(storyId);
        assertThat(taskContext.storyTitle()).isEqualTo("Agent identity threading");
        assertThat(taskContext.epicId()).isEqualTo(epicId);
        assertThat(taskContext.epicTitle()).isEqualTo("Roadmap-aware agents");
    }

    @Test
    void getGraphRuntimeSnapshot_taskWithUnresolvableStory_partialTaskContextNotException() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();

        WorkflowRun run = new WorkflowRun();
        run.setTaskId(taskId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        Task task = new Task();
        task.setId(taskId);
        task.setTitle("Task with deleted story");
        task.setStoryId(storyId);

        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(storyRepo.findById(storyId)).thenReturn(Optional.empty());

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.taskContext()).isNotNull();
        GraphRuntimeSnapshotResponse.TaskContext taskContext = response.taskContext();
        assertThat(taskContext.taskId()).isEqualTo(taskId);
        assertThat(taskContext.storyId()).isNull();
        assertThat(taskContext.storyTitle()).isNull();
        assertThat(taskContext.epicId()).isNull();
        assertThat(taskContext.epicTitle()).isNull();
    }

    // -----------------------------------------------------------------------
    // openBlockers — the Task's actionable, root-cause open blocker(s), resolved by walking the
    // full blocking chain via TransitiveReadinessResolver#rootCauseBlockersOf, not just the direct
    // blocker (multi-step blocking chain feature, Decisions 3/4). The tests in this section use
    // stubMinimalTask, which deliberately leaves the Task's Epic unresolved, so resolveOpenBlockers
    // falls back to its no-epic-context path (a plain findByBlockingItemIdInOrBlockedItemIdIn on
    // just the Task's own id) — the epic-bounded chain walk itself is covered separately below by
    // getGraphRuntimeSnapshot_threeNodeChain_openBlockersReturnsOnlyTheRootNotTheMiddle.
    // -----------------------------------------------------------------------

    private void stubMinimalTask(UUID runId, UUID taskId) {
        WorkflowRun run = new WorkflowRun();
        run.setTaskId(taskId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        // No Story is fixtured for these blocker-focused tests — give the Task a distinct,
        // never-resolving storyId (rather than null) so buildTaskContext's own
        // storyRepo.findById(task.getStoryId()) call is an explicit stub, not an unstubbed
        // invocation that would trip Mockito's strict-stubbing PotentialStubbingProblem check
        // against the blocker-resolution tests' own storyRepo.findById(...) stubs below. Because
        // the Story never resolves, buildTaskContext's Epic also stays null, so
        // resolveOpenBlockers falls back to its no-epic-context path (see above).
        UUID unresolvedStoryId = UUID.randomUUID();
        Task task = new Task();
        task.setId(taskId);
        task.setTitle("Task under test");
        task.setStoryId(unresolvedStoryId);
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(storyRepo.findById(unresolvedStoryId)).thenReturn(Optional.empty());
    }

    private WorkItemDependency edgeBlocking(UUID taskId, BlockableItemType blockingType, UUID blockingId) {
        WorkItemDependency edge = new WorkItemDependency();
        edge.setBlockingItemType(blockingType);
        edge.setBlockingItemId(blockingId);
        edge.setBlockedItemType(BlockableItemType.task);
        edge.setBlockedItemId(taskId);
        return edge;
    }

    @Test
    void getGraphRuntimeSnapshot_taskWithOpenBlockers_populatesOpenBlockers() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID doneBlockerId = UUID.randomUUID();
        UUID openBlockerId = UUID.randomUUID();
        stubMinimalTask(runId, taskId);

        Task doneBlocker = new Task();
        doneBlocker.setId(doneBlockerId);
        doneBlocker.setTitle("Already finished prerequisite");
        doneBlocker.setStatus(WorkItemStatus.done);

        Task openBlocker = new Task();
        openBlocker.setId(openBlockerId);
        openBlocker.setTitle("Still in progress prerequisite");
        openBlocker.setStatus(WorkItemStatus.in_progress);

        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(taskId), Set.of(taskId)))
                .thenReturn(List.of(
                        edgeBlocking(taskId, BlockableItemType.task, doneBlockerId),
                        edgeBlocking(taskId, BlockableItemType.task, openBlockerId)));
        when(taskRepo.findById(doneBlockerId)).thenReturn(Optional.of(doneBlocker));
        when(taskRepo.findById(openBlockerId)).thenReturn(Optional.of(openBlocker));

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.taskContext().openBlockers()).hasSize(1);
        var blocker = response.taskContext().openBlockers().get(0);
        assertThat(blocker.itemType()).isEqualTo("task");
        assertThat(blocker.itemId()).isEqualTo(openBlockerId);
        assertThat(blocker.title()).isEqualTo("Still in progress prerequisite");
        assertThat(blocker.status()).isEqualTo("in_progress");
    }

    @Test
    void getGraphRuntimeSnapshot_taskWithNoBlockers_emptyOpenBlockersList() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        stubMinimalTask(runId, taskId);
        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(taskId), Set.of(taskId)))
                .thenReturn(List.of());

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.taskContext().openBlockers()).isNotNull().isEmpty();
    }

    @Test
    void getGraphRuntimeSnapshot_crossEpicBlocker_stillResolved() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID blockingStoryId = UUID.randomUUID();
        stubMinimalTask(runId, taskId);

        // The blocking Story belongs to a different Epic than the started Task — resolveOpenBlockers
        // queries work_item_dependency directly by the Task's id, with no Epic pre-fetch, so this
        // is naturally included (Decision 4).
        Story blockingStory = new Story();
        blockingStory.setId(blockingStoryId);
        blockingStory.setTitle("Cross-epic prerequisite Story");

        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(taskId), Set.of(taskId)))
                .thenReturn(List.of(edgeBlocking(taskId, BlockableItemType.story, blockingStoryId)));
        when(storyRepo.findById(blockingStoryId)).thenReturn(Optional.of(blockingStory));
        when(taskRepo.findByStoryIdOrderByCreatedAtDesc(blockingStoryId)).thenReturn(List.of());

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.taskContext().openBlockers()).hasSize(1);
        var blocker = response.taskContext().openBlockers().get(0);
        assertThat(blocker.itemType()).isEqualTo("story");
        assertThat(blocker.itemId()).isEqualTo(blockingStoryId);
        assertThat(blocker.title()).isEqualTo("Cross-epic prerequisite Story");
        // An empty Story (no Tasks) rolls up to "backlog" (RollupCalculator), i.e. not done.
        assertThat(blocker.status()).isEqualTo("backlog");
    }

    @Test
    void getGraphRuntimeSnapshot_blockerItemDeleted_skipsUnresolvableRow() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID orphanedBlockerId = UUID.randomUUID();
        UUID resolvableBlockerId = UUID.randomUUID();
        stubMinimalTask(runId, taskId);

        Task resolvableBlocker = new Task();
        resolvableBlocker.setId(resolvableBlockerId);
        resolvableBlocker.setTitle("Still resolvable prerequisite");
        resolvableBlocker.setStatus(WorkItemStatus.backlog);

        // Simulates a work_item_dependency row whose blocking_item_id no longer corresponds to any
        // existing task/story row — there is no DB-level FK enforcing this (V5__work_item_dependency.sql).
        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(taskId), Set.of(taskId)))
                .thenReturn(List.of(
                        edgeBlocking(taskId, BlockableItemType.task, orphanedBlockerId),
                        edgeBlocking(taskId, BlockableItemType.task, resolvableBlockerId)));
        when(taskRepo.findById(orphanedBlockerId)).thenReturn(Optional.empty());
        when(taskRepo.findById(resolvableBlockerId)).thenReturn(Optional.of(resolvableBlocker));

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.taskContext().openBlockers()).hasSize(1);
        assertThat(response.taskContext().openBlockers().get(0).itemId()).isEqualTo(resolvableBlockerId);
    }

    @Test
    void getGraphRuntimeSnapshot_threeNodeChain_openBlockersReturnsOnlyTheRootNotTheMiddle() throws Exception {
        // Root blocks Middle, Middle blocks Tail (the run's own Task). Middle is done, Root is
        // not — resolveOpenBlockers must walk past the done Middle to report Root, not Middle
        // (multi-step blocking chain feature, Decisions 3/4), mirroring
        // RoadmapGraphServiceTest#getGraph_threeNodeChain_middleDoneRootUndone_tailStillBlocked.
        // Unlike stubMinimalTask's tests above, this fixtures a real Story/Epic so the walk is
        // bounded to (and covers) that Epic's own candidate set (Decision 2).
        UUID runId = UUID.randomUUID();
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        UUID middleId = UUID.randomUUID();
        UUID tailId = UUID.randomUUID();

        WorkflowRun run = new WorkflowRun();
        run.setTaskId(tailId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        Task tailTask = new Task();
        tailTask.setId(tailId);
        tailTask.setTitle("Tail");
        tailTask.setStoryId(storyId);
        when(taskRepo.findById(tailId)).thenReturn(Optional.of(tailTask));

        Story story = new Story();
        story.setId(storyId);
        story.setTitle("Story");
        story.setEpicId(epicId);
        when(storyRepo.findById(storyId)).thenReturn(Optional.of(story));

        Epic epic = new Epic();
        epic.setId(epicId);
        epic.setTitle("Epic");
        when(epicRepo.findById(epicId)).thenReturn(Optional.of(epic));

        when(storyRepo.findByEpicIdOrderByCreatedAtDesc(epicId)).thenReturn(List.of(story));

        Task rootTask = new Task();
        rootTask.setId(rootId);
        rootTask.setTitle("Root");
        rootTask.setStatus(WorkItemStatus.backlog);
        rootTask.setStoryId(storyId);

        Task middleTask = new Task();
        middleTask.setId(middleId);
        middleTask.setTitle("Middle");
        middleTask.setStatus(WorkItemStatus.done);
        middleTask.setStoryId(storyId);

        when(taskRepo.findByStoryIdIn(List.of(storyId))).thenReturn(List.of(rootTask, middleTask, tailTask));

        Set<UUID> candidateIds = Set.of(storyId, rootId, middleId, tailId);
        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(candidateIds, candidateIds))
                .thenReturn(List.of(
                        edgeBlocking(middleId, BlockableItemType.task, rootId),
                        edgeBlocking(tailId, BlockableItemType.task, middleId)));
        when(taskRepo.findById(rootId)).thenReturn(Optional.of(rootTask));
        when(taskRepo.findById(middleId)).thenReturn(Optional.of(middleTask));

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.taskContext().openBlockers()).hasSize(1);
        var blocker = response.taskContext().openBlockers().get(0);
        assertThat(blocker.itemType()).isEqualTo("task");
        assertThat(blocker.itemId()).isEqualTo(rootId);
        assertThat(blocker.title()).isEqualTo("Root");
        assertThat(blocker.status()).isEqualTo("backlog");
    }
}
