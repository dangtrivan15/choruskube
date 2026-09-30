package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CandidateDependency;
import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.RepoGroupRequest;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule-by-rule coverage of {@link RoadmapProposalValidator} against a real database: two projects
 * (P, Q), each with an Epic/Story/Task, and runs with and without a triggering Task.
 */
@Transactional
class RoadmapProposalValidatorTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private RoadmapProposalValidator validator;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicRepository epicRepo;

    @Autowired
    private StoryRepository storyRepo;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private RepoGroupService repoGroupService;

    private GitRepo projectP;
    private Epic epicP;
    private Story storyP;
    private Task taskP;

    private GitRepo projectQ;
    private Epic epicQ;
    private Story storyQ;
    private Task taskQ;

    // A second Epic/Story/Task under P itself — "elsewhere in the run's own project", as opposed
    // to Q which is a wholly different project. An item elsewhere in the run's own project may
    // still appear as a dependency endpoint (via its own anchor chain); a foreign project's item
    // cannot appear at all, since its anchors never resolve there.
    private Epic epicP2;
    private Story storyP2;
    private Task taskP2;

    private UUID templateId;

    @BeforeEach
    void seedFixture() {
        projectP = makeRepo("https://github.com/acme/project-p.git");
        epicP = makeEpic(projectP.getId(), "Epic P");
        storyP = makeStory(epicP.getId(), "Story P");
        taskP = makeTask(storyP.getId(), projectP.getId(), "Task P");

        epicP2 = makeEpic(projectP.getId(), "Epic P2");
        storyP2 = makeStory(epicP2.getId(), "Story P2");
        taskP2 = makeTask(storyP2.getId(), projectP.getId(), "Task P2");

        projectQ = makeRepo("https://github.com/acme/project-q.git");
        epicQ = makeEpic(projectQ.getId(), "Epic Q");
        storyQ = makeStory(epicQ.getId(), "Story Q");
        taskQ = makeTask(storyQ.getId(), projectQ.getId(), "Task Q");

        GraphTemplate template = new GraphTemplate();
        template.setGraphId("roadmap-proposal-validator-test-" + UUID.randomUUID());
        template.setVersion(1);
        template.setName("Validator Test Template");
        template.setInputSchema("[]");
        templateId = templateRepo.save(template).getId();
    }

    private GitRepo makeRepo(String url) {
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

    private Story makeStory(UUID epicId, String title) {
        Story s = new Story();
        s.setEpicId(epicId);
        s.setTitle(title);
        s.setDescription("d");
        return storyRepo.save(s);
    }

    private Task makeTask(UUID storyId, UUID projectId, String title) {
        Task t = new Task();
        t.setStoryId(storyId);
        t.setSoftwareProjectId(projectId);
        t.setTitle(title);
        t.setDescription("d");
        return taskRepo.save(t);
    }

    private WorkflowRun saveRun(UUID projectId, UUID taskId) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateId);
        run.setTaskId(taskId);
        run.setInputs(projectId == null ? "{}" : "{\"software_project_id\":\"" + projectId + "\"}");
        return runRepo.save(run);
    }

    private UUID runUnderP_withTask() {
        return saveRun(projectP.getId(), taskP.getId()).getId();
    }

    private UUID runUnderP_noTask() {
        return saveRun(projectP.getId(), null).getId();
    }

    private static CandidateEpicProposal newEpic(String key, List<CandidateStoryProposal> stories) {
        return new CandidateEpicProposal("New Epic", "d", "m", null, null, stories, key, null, null);
    }

    private static CandidateEpicProposal anchorEpic(UUID id, String key, List<CandidateStoryProposal> stories) {
        return new CandidateEpicProposal(null, null, null, null, null, stories, key, null, id);
    }

    private static CandidateStoryProposal newStory(String key, List<CandidateTaskProposal> tasks) {
        return new CandidateStoryProposal("New Story", "d", tasks, key, null, null);
    }

    private static CandidateStoryProposal anchorStory(UUID id, String key, List<CandidateTaskProposal> tasks) {
        return new CandidateStoryProposal(null, null, tasks, key, null, id);
    }

    private static CandidateTaskProposal newTask(String key) {
        return new CandidateTaskProposal("New Task", "d", key, null, null, null);
    }

    private static CandidateTaskProposal newTask(String key, UUID repoId) {
        return new CandidateTaskProposal("New Task", "d", key, null, null, repoId);
    }

    private static CandidateTaskProposal anchorTask(UUID id, String key) {
        return new CandidateTaskProposal(null, null, key, null, id, null);
    }

    private static RoadmapCandidatesDocument doc(List<CandidateEpicProposal> epics, List<CandidateDependency> deps) {
        return new RoadmapCandidatesDocument(null, epics, deps);
    }

    // -----------------------------------------------------------------------
    // (a) bean violations / conditional title
    // -----------------------------------------------------------------------

    @Test
    void newItemWithNoTitle_reportsBeanViolation() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal blankEpic = new CandidateEpicProposal(
                null, "d", "m", null, null, List.of(newStory("s", List.of(newTask("t")))), "e", null, null);

        var errors = validator.validate(
                runId,
                doc(List.of(blankEpic), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).anyMatch(e -> e.contains("title is required for a new item"));
    }

    // -----------------------------------------------------------------------
    // (b) unique keys
    // -----------------------------------------------------------------------

    @Test
    void duplicateKeyAcrossEntries_isRejected() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal e1 = newEpic("dup", List.of(newStory("s1", List.of(newTask("t1")))));
        CandidateEpicProposal e2 = newEpic("dup", List.of(newStory("s2", List.of(newTask("t2")))));

        var errors = validator.validate(
                runId,
                doc(List.of(e1, e2), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).anyMatch(e -> e.contains("duplicate key 'dup'"));
    }

    // -----------------------------------------------------------------------
    // (c) anchors
    // -----------------------------------------------------------------------

    @Test
    void foreignAnchorAndMissingAnchor_produceIdenticalMessages() {
        UUID runId = runUnderP_noTask();
        UUID missingId = UUID.randomUUID();

        var foreignErrors = validator.validate(
                runId,
                doc(List.of(anchorEpic(epicQ.getId(), "a", List.of())), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);
        var missingErrors = validator.validate(
                runId,
                doc(List.of(anchorEpic(missingId, "a", List.of())), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        String expectedForeign =
                "epics[0]: existing epic " + epicQ.getId() + " not found in this run's software project";
        String expectedMissing = "epics[0]: existing epic " + missingId + " not found in this run's software project";
        assertThat(foreignErrors).contains(expectedForeign);
        assertThat(missingErrors).contains(expectedMissing);
    }

    @Test
    void storyAnchorUnderNewEpic_isRejected() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal candidate = newEpic("e", List.of(anchorStory(storyP.getId(), "s", List.of())));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).anyMatch(e -> e.contains("an existing story can only be listed under its existing epic"));
    }

    @Test
    void storyAnchorUnderWrongEpicAnchor_isRejected() {
        UUID runId = runUnderP_noTask();
        // storyP really belongs to epicP, but the document nests it under epicQ's anchor.
        CandidateEpicProposal candidate =
                anchorEpic(epicQ.getId(), "e", List.of(anchorStory(storyP.getId(), "s", List.of())));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors)
                .anyMatch(e -> e.contains("story " + storyP.getId() + " does not belong to epic " + epicQ.getId()));
    }

    @Test
    void foreignStoryAnchor_underRunsEpic_isNotFound() {
        // A Story has no project of its own: the lookup must resolve it through its parent Epic,
        // or a foreign project's Story could be anchored (and its title surfaced) here.
        UUID runId = runUnderP_withTask();
        CandidateEpicProposal candidate =
                anchorEpic(epicP.getId(), "e", List.of(anchorStory(storyQ.getId(), "s", List.of(newTask("t")))));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors)
                .containsExactly("epics[0].stories[0]: existing story " + storyQ.getId()
                        + " not found in this run's software project");
    }

    @Test
    void taskAnchorUnderWrongStoryAnchor_isRejected() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal candidate = anchorEpic(
                epicP.getId(),
                "e",
                List.of(anchorStory(storyP.getId(), "s", List.of(anchorTask(taskP2.getId(), "t")))));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors)
                .containsExactly("epics[0].stories[0].tasks[0]: task " + taskP2.getId() + " does not belong to story "
                        + storyP.getId());
    }

    @Test
    void taskAnchorUnderNewStory_isRejected() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal candidate =
                anchorEpic(epicP.getId(), "e", List.of(newStory("s", List.of(anchorTask(taskP.getId(), "t")))));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).anyMatch(e -> e.contains("an existing task can only be listed under its existing story"));
    }

    @Test
    void duplicateExistingAnchor_isRejected() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal e1 = anchorEpic(epicP.getId(), "e1", List.of());
        CandidateEpicProposal e2 = anchorEpic(epicP.getId(), "e2", List.of());

        var errors = validator.validate(
                runId,
                doc(List.of(e1, e2), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).anyMatch(e -> e.contains("existing epic " + epicP.getId() + " is listed more than once"));
    }

    @Test
    void existingIdAnchor_isRejected_inCandidatesMode() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal anchoredEpic =
                anchorEpic(epicP.getId(), "e", List.of(newStory("s", List.of(newTask("t")))));

        var errors = validator.validate(
                runId,
                doc(List.of(anchoredEpic), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).contains("existingId anchors are not allowed in roadmap_candidates mode");
    }

    @Test
    void existingIdAnchor_onDescendant_isRejected_inCandidatesMode() {
        UUID runId = runUnderP_noTask();
        // The epic itself is new; only its story is anchored. hasAnyAnchor still catches it.
        CandidateEpicProposal candidate = newEpic("e", List.of(anchorStory(storyP.getId(), "s", List.of())));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).contains("existingId anchors are not allowed in roadmap_candidates mode");
    }

    // -----------------------------------------------------------------------
    // (d) addressable invariant
    // -----------------------------------------------------------------------

    @Test
    void newEpicWithNoStories_isRejected_inBothModes() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal candidate = newEpic("e", List.of());

        var candidatesErrors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);
        assertThat(candidatesErrors).anyMatch(e -> e.contains("a new epic needs at least one story"));
    }

    @Test
    void newStoryWithNoTasks_isRejected() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal candidate = newEpic("e", List.of(newStory("s", List.of())));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).anyMatch(e -> e.contains("a new story needs at least one task"));
    }

    // -----------------------------------------------------------------------
    // (e) extension mode: milestones, scope
    // -----------------------------------------------------------------------

    @Test
    void milestonesForbidden_inExtensionMode() {
        UUID runId = runUnderP_noTask();
        RoadmapCandidatesDocument document = new RoadmapCandidatesDocument(
                List.of(new com.choruskube.core.dto.CandidateMilestone("m1", "Q3", null, null)), List.of(), null);

        var errors = validator.validate(
                runId, document, RoadmapMaterializeMode.roadmap_extension, RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).anyMatch(e -> e.contains("milestones are not allowed in a roadmap extension"));
    }

    @Test
    void wholelyNewTopLevelEpic_inTaskTriggeredRun_isAccepted() {
        UUID runId = runUnderP_withTask();
        CandidateEpicProposal candidate = newEpic("e", List.of(newStory("s", List.of(newTask("t")))));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).isEmpty();
    }

    @Test
    void anchorToDifferentExistingEpic_withNewDescendant_isRejected() {
        UUID runId = runUnderP_withTask();
        CandidateEpicProposal candidate =
                anchorEpic(epicP2.getId(), "e", List.of(newStory("s", List.of(newTask("t")))));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors)
                .anyMatch(e -> e.contains("new items may only be added under this run's epic " + epicP.getId()));
    }

    @Test
    void dependencyBetweenTwoOtherEpicAnchors_isRejected() {
        UUID runId = runUnderP_withTask();
        // An Epic P2 anchor chain used purely to expose a dependency key (no new descendants) is
        // structurally allowed, but an edge wholly between two such out-of-scope items still fails
        // the scope rule — this is the pinned message for it.
        CandidateEpicProposal otherEpicChain = anchorEpic(
                epicP2.getId(),
                "fe",
                List.of(anchorStory(storyP2.getId(), "fs", List.of(anchorTask(taskP2.getId(), "ft1")))));

        var errors = validator.validate(
                runId,
                doc(List.of(otherEpicChain), List.of(new CandidateDependency("ft1", "ft1"))),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);

        // ft1 -> ft1 self-edges aren't checked at GATE strictness (that's rule (f), AGENT-only), so
        // the scope rule alone must reject this: both sides are the same out-of-scope item.
        assertThat(errors).anyMatch(e -> e.contains("at least one side must be an item in this run's epic"));
    }

    @Test
    void dependencyTouchingRunsEpic_isAccepted() {
        UUID runId = runUnderP_withTask();
        CandidateEpicProposal ownEpic = anchorEpic(
                epicP.getId(),
                "own",
                List.of(anchorStory(storyP.getId(), "s", List.of(anchorTask(taskP.getId(), "t")))));
        CandidateEpicProposal otherEpicChain = anchorEpic(
                epicP2.getId(),
                "fe",
                List.of(anchorStory(storyP2.getId(), "fs", List.of(anchorTask(taskP2.getId(), "ft")))));

        var errors = validator.validate(
                runId,
                doc(List.of(ownEpic, otherEpicChain), List.of(new CandidateDependency("t", "ft"))),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).isEmpty();
    }

    @Test
    void dependencyBetweenTwoForeignProjectAnchors_isNotFound_bothSidesUnresolvable() {
        // Q is an entirely different project — its anchors are "not found" outright, distinct from
        // the same-project-out-of-scope case above.
        UUID runId = runUnderP_withTask();
        CandidateEpicProposal foreignChain = anchorEpic(
                epicQ.getId(),
                "fq",
                List.of(anchorStory(storyQ.getId(), "fs", List.of(anchorTask(taskQ.getId(), "ft")))));

        var errors = validator.validate(
                runId,
                doc(List.of(foreignChain), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors)
                .anyMatch(e ->
                        e.contains("existing epic " + epicQ.getId() + " not found in this run's software project"));
    }

    @Test
    void noTriggeringTask_noScopeRuleApplies() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal anchorToOther =
                anchorEpic(epicP2.getId(), "e", List.of(newStory("s", List.of(newTask("t")))));

        var errors = validator.validate(
                runId,
                doc(List.of(anchorToOther), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).isEmpty();
    }

    // -----------------------------------------------------------------------
    // (f) AGENT-only dependency checks
    // -----------------------------------------------------------------------

    @Test
    void unknownDependencyKey_reportedAtAgentStrictness_ignoredAtGate() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal candidate = newEpic("e", List.of(newStory("s", List.of(newTask("t")))));
        var document = doc(List.of(candidate), List.of(new CandidateDependency("t", "does-not-exist")));

        var agentErrors = validator.validate(
                runId, document, RoadmapMaterializeMode.roadmap_candidates, RoadmapProposalValidator.Strictness.AGENT);
        var gateErrors = validator.validate(
                runId, document, RoadmapMaterializeMode.roadmap_candidates, RoadmapProposalValidator.Strictness.GATE);

        assertThat(agentErrors).anyMatch(e -> e.contains("unknown key 'does-not-exist'"));
        assertThat(gateErrors).isEmpty();
    }

    @Test
    void selfEdge_rejectedAtAgentStrictness() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal candidate = newEpic("e", List.of(newStory("s", List.of(newTask("t")))));
        var document = doc(List.of(candidate), List.of(new CandidateDependency("t", "t")));

        var errors = validator.validate(
                runId, document, RoadmapMaterializeMode.roadmap_candidates, RoadmapProposalValidator.Strictness.AGENT);

        assertThat(errors).anyMatch(e -> e.contains("an item cannot block itself"));
    }

    @Test
    void duplicateDependencyPair_rejectedAtAgentStrictness() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal candidate = newEpic("e", List.of(newStory("s", List.of(newTask("t1"), newTask("t2")))));
        var document = doc(
                List.of(candidate), List.of(new CandidateDependency("t1", "t2"), new CandidateDependency("t1", "t2")));

        var errors = validator.validate(
                runId, document, RoadmapMaterializeMode.roadmap_candidates, RoadmapProposalValidator.Strictness.AGENT);

        assertThat(errors).anyMatch(e -> e.contains("duplicate dependency"));
    }

    @Test
    void cycleAmongDocumentEdges_rejectedAtAgentStrictness() {
        UUID runId = runUnderP_noTask();
        CandidateEpicProposal candidate = newEpic("e", List.of(newStory("s", List.of(newTask("t1"), newTask("t2")))));
        var document = doc(
                List.of(candidate), List.of(new CandidateDependency("t1", "t2"), new CandidateDependency("t2", "t1")));

        var errors = validator.validate(
                runId, document, RoadmapMaterializeMode.roadmap_candidates, RoadmapProposalValidator.Strictness.AGENT);

        assertThat(errors).anyMatch(e -> e.contains("would create a cycle"));
    }

    // -----------------------------------------------------------------------
    // (g) repoId
    // -----------------------------------------------------------------------

    @Test
    void singleRepoProject_acceptsTaskWithNoRepoId() {
        UUID runId = runUnderP_withTask();
        CandidateEpicProposal candidate = anchorEpic(epicP.getId(), "e", List.of(newStory("s", List.of(newTask("t")))));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).isEmpty();
    }

    @Test
    void singleRepoProject_rejectsTaskWithForeignRepoId() {
        UUID runId = runUnderP_withTask();
        CandidateEpicProposal candidate =
                anchorEpic(epicP.getId(), "e", List.of(newStory("s", List.of(newTask("t", projectQ.getId())))));

        var errors = validator.validate(
                runId,
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors)
                .containsExactly("epics[0].stories[0].tasks[0]: repoId " + projectQ.getId()
                        + " is not part of this run's software project");
    }

    @Test
    void multiRepoProject_rejectsTaskWithNoRepoIdAndForeignRepoId() {
        GitRepo repo1 = makeRepo("https://github.com/acme/multi-repo-a.git");
        GitRepo repo2 = makeRepo("https://github.com/acme/multi-repo-b.git");
        RepoGroup group = repoGroupService.createInternal(new RepoGroupRequest(
                "multi-repo-" + UUID.randomUUID(), null, null, List.of(repo1.getId(), repo2.getId()), null, null));
        Epic groupEpic = makeEpic(group.getId(), "Group Epic");
        Story groupStory = makeStory(groupEpic.getId(), "Group Story");
        Task groupTask = makeTask(groupStory.getId(), group.getId(), "Group Task");
        WorkflowRun run = saveRun(group.getId(), groupTask.getId());

        CandidateEpicProposal noRepoId =
                anchorEpic(groupEpic.getId(), "e", List.of(newStory("s", List.of(newTask("t")))));
        var noRepoIdErrors = validator.validate(
                run.getId(),
                doc(List.of(noRepoId), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);
        assertThat(noRepoIdErrors)
                .anyMatch(e -> e.contains(
                        "a repoId is required for a new task when the run's project spans more than one repository"));

        CandidateEpicProposal foreignRepoId =
                anchorEpic(groupEpic.getId(), "e", List.of(newStory("s", List.of(newTask("t", projectP.getId())))));
        var foreignRepoIdErrors = validator.validate(
                run.getId(),
                doc(List.of(foreignRepoId), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);
        assertThat(foreignRepoIdErrors)
                .anyMatch(
                        e -> e.contains("repoId " + projectP.getId() + " is not part of this run's software project"));

        CandidateEpicProposal validRepoId =
                anchorEpic(groupEpic.getId(), "e", List.of(newStory("s", List.of(newTask("t", repo1.getId())))));
        var validRepoIdErrors = validator.validate(
                run.getId(),
                doc(List.of(validRepoId), null),
                RoadmapMaterializeMode.roadmap_extension,
                RoadmapProposalValidator.Strictness.GATE);
        assertThat(validRepoIdErrors).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Lazy project resolution
    // -----------------------------------------------------------------------

    @Test
    void candidatesDocWithNoAnchors_onRunWithNoProject_isValid() {
        WorkflowRun run = saveRun(null, null);
        CandidateEpicProposal candidate = newEpic("e", List.of(newStory("s", List.of(newTask("t")))));

        var errors = validator.validate(
                run.getId(),
                doc(List.of(candidate), null),
                RoadmapMaterializeMode.roadmap_candidates,
                RoadmapProposalValidator.Strictness.GATE);

        assertThat(errors).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Summary counts
    // -----------------------------------------------------------------------

    @Test
    void summarize_countsNewAndExistingItemsAndDeclaredDependencies() {
        CandidateEpicProposal anchor =
                anchorEpic(epicP.getId(), "anchor", List.of(newStory("s1", List.of(newTask("t1")))));
        CandidateEpicProposal fresh = newEpic("e2", List.of(newStory("s2", List.of(newTask("t2")))));
        var document = doc(List.of(anchor, fresh), List.of(new CandidateDependency("t1", "t2")));

        var summary = validator.summarize(document);

        assertThat(summary.newEpics()).isEqualTo(1);
        assertThat(summary.newStories()).isEqualTo(2);
        assertThat(summary.newTasks()).isEqualTo(2);
        assertThat(summary.existingItems()).isEqualTo(1);
        assertThat(summary.dependencies()).isEqualTo(1);
    }
}
