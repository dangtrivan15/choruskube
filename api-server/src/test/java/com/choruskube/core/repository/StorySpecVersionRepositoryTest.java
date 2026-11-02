package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.StorySpecFixtures;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.StoryConflict;
import com.choruskube.core.model.StorySpecVersion;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class StorySpecVersionRepositoryTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private StorySpecVersionRepository versionRepo;

    @Autowired
    private StoryConflictRepository conflictRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicRepository epicRepo;

    @Autowired
    private StoryRepository storyRepo;

    @Autowired
    private JdbcTemplate jdbc;

    private GitRepo project;
    private Epic epic;
    private GraphTemplate template;
    private TemplateNode templateNode;
    private int iteration;

    @BeforeEach
    void setUp() {
        iteration = 1;
        project = new GitRepo();
        project.setName("story-spec-repo-test-" + UUID.randomUUID());
        project.setUrl("https://github.com/acme/story-spec-repo-test.git");
        project = gitRepoRepo.save(project);

        epic = new Epic();
        epic.setSoftwareProjectId(project.getId());
        epic.setTitle("Story spec epic");
        epic.setDescription("d");
        epic = epicRepo.save(epic);

        template = new GraphTemplate();
        template.setName("Story Spec Repo Test Template");
        template.setGraphId("story-spec-repo-test-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("story-spec-repo-test-node-" + UUID.randomUUID());
        nodeDef.setExecutorType(ExecutorType.human);
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        templateNode = new TemplateNode();
        templateNode.setGraphTemplateId(template.getId());
        templateNode.setNodeDefinitionId(nodeDef.getId());
        templateNode.setLabel("analyze");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);
    }

    private Story newStory(String title) {
        Story story = new Story();
        story.setEpicId(epic.getId());
        story.setTitle(title);
        story.setDescription("d");
        return storyRepo.saveAndFlush(story);
    }

    private WorkflowRun newRun(UUID storyId, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setStoryId(storyId);
        run.setStatus(status);
        return runRepo.saveAndFlush(run);
    }

    private StorySpecVersion version(UUID storyId, int version) {
        StorySpecVersion row = new StorySpecVersion();
        row.setStoryId(storyId);
        row.setVersion(version);
        row.setContent("# spec v" + version);
        return row;
    }

    @Test
    void uniqueStoryAndVersion() {
        Story story = newStory("unique");
        versionRepo.saveAndFlush(version(story.getId(), 1));

        assertThatThrownBy(() -> versionRepo.saveAndFlush(version(story.getId(), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void currentVersions_returnsMaxPerStory() {
        Story a = newStory("A");
        Story b = newStory("B");
        versionRepo.saveAndFlush(version(a.getId(), 1));
        versionRepo.saveAndFlush(version(a.getId(), 2));
        versionRepo.saveAndFlush(version(b.getId(), 1));

        Map<UUID, Integer> current = versionRepo.findCurrentVersions(List.of(a.getId(), b.getId())).stream()
                .collect(Collectors.toMap(StoryVersionView::getStoryId, StoryVersionView::getVersion));

        assertThat(current).containsExactlyInAnyOrderEntriesOf(Map.of(a.getId(), 2, b.getId(), 1));
    }

    @Test
    void finders_resolveLatestAndSpecificVersions() {
        Story story = newStory("finders");
        UUID runId = newRun(story.getId(), WorkflowRunStatus.completed).getId();
        versionRepo.saveAndFlush(version(story.getId(), 1));
        StorySpecVersion v2 = version(story.getId(), 2);
        v2.setRunId(runId);
        versionRepo.saveAndFlush(v2);

        assertThat(versionRepo.findFirstByStoryIdOrderByVersionDesc(story.getId()))
                .get()
                .extracting(StorySpecVersion::getVersion)
                .isEqualTo(2);
        assertThat(versionRepo.findByStoryIdAndVersion(story.getId(), 1))
                .get()
                .extracting(StorySpecVersion::getContent)
                .isEqualTo("# spec v1");
        assertThat(versionRepo.findByStoryIdOrderByVersionAsc(story.getId()))
                .extracting(StorySpecVersion::getVersion)
                .containsExactly(1, 2);
        assertThat(versionRepo.findByRunId(runId))
                .get()
                .extracting(StorySpecVersion::getVersion)
                .isEqualTo(2);
        assertThat(versionRepo.findByStoryIdAndVersion(story.getId(), 3)).isEmpty();
    }

    @Test
    void approvedAt_isSetAndTruncatedToMicros() {
        Story story = newStory("approved-at");
        StorySpecVersion saved = versionRepo.saveAndFlush(version(story.getId(), 1));

        assertThat(saved.getApprovedAt()).isNotNull();
        assertThat(saved.getApprovedAt().getNano() % 1000).isZero();
    }

    @Test
    void deletingStory_cascadesVersionsAndConflicts_andNullsRunStoryId() {
        Story story = newStory("cascade");
        WorkflowRun designRun = newRun(story.getId(), WorkflowRunStatus.completed);
        WorkflowRun taskRun = newRun(null, WorkflowRunStatus.running);
        versionRepo.saveAndFlush(version(story.getId(), 1));
        conflictRepo.saveAndFlush(conflict(story, taskRun, 1));

        jdbc.update("DELETE FROM story WHERE id = ?", story.getId());

        assertThat(count("story_spec_version", story.getId())).isZero();
        assertThat(count("story_conflict", story.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT story_id FROM workflow_run WHERE id = ?", UUID.class, designRun.getId()))
                .isNull();
    }

    /** The revision agent answers conflicts in this order, so a Story's open ones must not come back newest first. */
    @Test
    void openConflicts_comeOldestFirstFromEveryFinder() {
        Story story = newStory("oldest-first");
        WorkflowRun taskRun = newRun(null, WorkflowRunStatus.running);
        StoryConflict newer = conflict(story, taskRun, 1);
        newer.setCreatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        newer = conflictRepo.saveAndFlush(newer);
        StoryConflict older = conflict(story, taskRun, 1);
        older.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        older = conflictRepo.saveAndFlush(older);
        List<UUID> both = List.of(newer.getId(), older.getId());

        assertThat(conflictRepo.findByStoryIdAndReleasedAtIsNullOrderByCreatedAtAsc(story.getId()))
                .extracting(StoryConflict::getId)
                .containsExactly(older.getId(), newer.getId());
        assertThat(conflictRepo.lockOpenUnassignedRaisedAgainst(story.getId(), 1, both))
                .extracting(StoryConflict::getId)
                .containsExactly(older.getId(), newer.getId());
    }

    /** Run deletes are hard deletes: a Task run takes its conflicts with it, a revision run only its references. */
    @Test
    void deletingRuns_cascadesTheirConflicts_andNullsTheReferencesToThem() {
        Story story = newStory("run-deletes");
        WorkflowRun revision = newRun(story.getId(), WorkflowRunStatus.completed);
        WorkflowRun endedTaskRun = newRun(null, WorkflowRunStatus.cancelled);
        WorkflowRun survivingTaskRun = newRun(null, WorkflowRunStatus.running);
        // The Analyze execution belongs to a third run, so only task_run_id can take the conflict away.
        WorkflowRun analyzeHolder = newRun(null, WorkflowRunStatus.running);
        StorySpecVersion approved = version(story.getId(), 1);
        approved.setRunId(revision.getId());
        versionRepo.saveAndFlush(approved);
        StoryConflict cascaded = conflict(story, analyzeHolder, 1);
        cascaded.setTaskRunId(endedTaskRun.getId());
        cascaded.setRevisionRunId(revision.getId());
        conflictRepo.saveAndFlush(cascaded);
        StoryConflict kept = conflict(story, survivingTaskRun, 1);
        kept.setRevisionRunId(revision.getId());
        kept = conflictRepo.saveAndFlush(kept);

        jdbc.update("DELETE FROM workflow_run WHERE id = ?", endedTaskRun.getId());

        assertThat(conflictIds(story.getId())).containsExactly(kept.getId());

        jdbc.update("DELETE FROM workflow_run WHERE id = ?", revision.getId());

        assertThat(jdbc.queryForObject(
                        "SELECT revision_run_id FROM story_conflict WHERE id = ?", UUID.class, kept.getId()))
                .isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT run_id FROM story_spec_version WHERE id = ?", UUID.class, approved.getId()))
                .isNull();
        assertThat(count("story_spec_version", story.getId())).isEqualTo(1);
    }

    @Test
    void conflicts_openOnesAreFoundAndReleasedOnesAreNot() {
        Story story = newStory("conflicts");
        WorkflowRun taskRun = newRun(null, WorkflowRunStatus.running);
        StoryConflict open = conflictRepo.saveAndFlush(conflict(story, taskRun, 1));
        conflictRepo.saveAndFlush(conflict(story, taskRun, 1));
        StoryConflict released = conflict(story, taskRun, 1);
        released.setReleasedAt(Instant.now());
        released = conflictRepo.saveAndFlush(released);
        Story allReleased = newStory("conflicts-all-released");
        StoryConflict releasedOnly = conflict(allReleased, taskRun, 1);
        releasedOnly.setReleasedAt(Instant.now());
        conflictRepo.saveAndFlush(releasedOnly);

        assertThat(conflictRepo.findStoryIdsWithOpenConflicts()).containsOnlyOnce(story.getId());
        assertThat(conflictRepo.findStoryIdsWithOpenConflicts()).doesNotContain(allReleased.getId());
        assertThat(conflictRepo.findByStoryIdAndReleasedAtIsNullOrderByCreatedAtAsc(story.getId()))
                .extracting(StoryConflict::getId)
                .contains(open.getId())
                .doesNotContain(released.getId());
        assertThat(conflictRepo.existsByAnalyzeNodeExecutionId(open.getAnalyzeNodeExecutionId()))
                .isTrue();
        assertThat(conflictRepo.existsByAnalyzeNodeExecutionId(UUID.randomUUID()))
                .isFalse();
    }

    @Test
    void openRaisedAgainst_takesOnlyTheVersionsUnreleasedConflictsOldestFirst() {
        Story story = newStory("open-raised-against");
        WorkflowRun taskRun = newRun(null, WorkflowRunStatus.running);
        WorkflowRun endedRevision = newRun(story.getId(), WorkflowRunStatus.cancelled);
        StoryConflict older = conflict(story, taskRun, 2);
        older.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        older = conflictRepo.saveAndFlush(older);
        StoryConflict assigned = conflict(story, taskRun, 2);
        assigned.setCreatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        assigned.setRevisionRunId(endedRevision.getId());
        assigned = conflictRepo.saveAndFlush(assigned);
        StoryConflict previousVersion = conflictRepo.saveAndFlush(conflict(story, taskRun, 1));
        StoryConflict released = conflict(story, taskRun, 2);
        released.setReleasedAt(Instant.now());
        released = conflictRepo.saveAndFlush(released);

        assertThat(conflictRepo.findOpenRaisedAgainst(story.getId(), 2))
                .extracting(StoryConflict::getId)
                .containsExactly(older.getId(), assigned.getId())
                .doesNotContain(previousVersion.getId(), released.getId());
    }

    @Test
    void lockOpenUnassignedRaisedAgainst_selectsOnlyRequestedOpenUnassignedConflictsOfTheVersion() {
        Story story = newStory("lock-open-unassigned");
        Story other = newStory("lock-open-unassigned-other");
        WorkflowRun taskRun = newRun(null, WorkflowRunStatus.running);
        WorkflowRun revision = newRun(story.getId(), WorkflowRunStatus.cancelled);
        StoryConflict open = conflictRepo.saveAndFlush(conflict(story, taskRun, 2));
        StoryConflict assigned = conflict(story, taskRun, 2);
        assigned.setRevisionRunId(revision.getId());
        assigned = conflictRepo.saveAndFlush(assigned);
        StoryConflict released = conflict(story, taskRun, 2);
        released.setReleasedAt(Instant.now());
        released = conflictRepo.saveAndFlush(released);
        StoryConflict previousVersion = conflictRepo.saveAndFlush(conflict(story, taskRun, 1));
        StoryConflict otherStory = conflictRepo.saveAndFlush(conflict(other, taskRun, 2));
        StoryConflict notRequested = conflictRepo.saveAndFlush(conflict(story, taskRun, 2));

        List<StoryConflict> locked = conflictRepo.lockOpenUnassignedRaisedAgainst(
                story.getId(),
                2,
                List.of(open.getId(), assigned.getId(), released.getId(), previousVersion.getId(), otherStory.getId()));

        assertThat(locked).extracting(StoryConflict::getId).containsExactly(open.getId());
        assertThat(conflictRepo.countByIdInAndRevisionRunId(
                        List.of(open.getId(), assigned.getId(), notRequested.getId()), revision.getId()))
                .isEqualTo(1);
    }

    @Test
    void reassignToRevision_takesAssignedConflictsButNeverReleasedOrOtherVersionOnes() {
        Story story = newStory("reassign");
        WorkflowRun taskRun = newRun(null, WorkflowRunStatus.running);
        WorkflowRun endedRevision = newRun(story.getId(), WorkflowRunStatus.failed);
        WorkflowRun revision = newRun(story.getId(), WorkflowRunStatus.running);
        StoryConflict waiting = conflictRepo.saveAndFlush(conflict(story, taskRun, 2));
        StoryConflict stranded = conflict(story, taskRun, 2);
        stranded.setRevisionRunId(endedRevision.getId());
        stranded = conflictRepo.saveAndFlush(stranded);
        StoryConflict previousVersion = conflictRepo.saveAndFlush(conflict(story, taskRun, 1));
        StoryConflict released = conflict(story, taskRun, 2);
        released.setReleasedAt(Instant.now());
        released = conflictRepo.saveAndFlush(released);

        int reassigned = conflictRepo.reassignToRevision(
                List.of(waiting.getId(), stranded.getId(), previousVersion.getId(), released.getId()),
                story.getId(),
                2,
                revision.getId());

        assertThat(reassigned).isEqualTo(2);
        assertThat(conflictRepo.findById(waiting.getId()).orElseThrow().getRevisionRunId())
                .isEqualTo(revision.getId());
        assertThat(conflictRepo.findById(stranded.getId()).orElseThrow().getRevisionRunId())
                .isEqualTo(revision.getId());
        assertThat(conflictRepo.findById(previousVersion.getId()).orElseThrow().getRevisionRunId())
                .isNull();
        assertThat(conflictRepo.findById(released.getId()).orElseThrow().getRevisionRunId())
                .isNull();
    }

    @Test
    void workflowRun_findsRunsByStoryAndStatus_skippingDeleted() {
        Story story = newStory("run-finders");
        WorkflowRun running = newRun(story.getId(), WorkflowRunStatus.running);
        newRun(story.getId(), WorkflowRunStatus.completed);
        WorkflowRun deleted = newRun(story.getId(), WorkflowRunStatus.running);
        jdbc.update("UPDATE workflow_run SET deleted_at = now() WHERE id = ?", deleted.getId());

        assertThat(runRepo.findByStoryIdInAndStatusInAndDeletedAtIsNull(
                        List.of(story.getId()), List.of(WorkflowRunStatus.running, WorkflowRunStatus.pending)))
                .extracting(WorkflowRun::getId)
                .containsExactly(running.getId());
    }

    @Test
    void completedDesignRunsWithoutVersion_listsOnlyUnrecordedCompletedDesignRuns() {
        Story story = newStory("unrecorded");
        WorkflowRun unrecorded = newRun(story.getId(), WorkflowRunStatus.completed);
        WorkflowRun recorded = newRun(story.getId(), WorkflowRunStatus.completed);
        WorkflowRun stillRunning = newRun(story.getId(), WorkflowRunStatus.running);
        WorkflowRun taskRun = newRun(null, WorkflowRunStatus.completed);
        StorySpecVersion row = version(story.getId(), 1);
        row.setRunId(recorded.getId());
        versionRepo.saveAndFlush(row);

        assertThat(runRepo.findCompletedDesignRunsWithoutVersion())
                .extracting(WorkflowRun::getId)
                .contains(unrecorded.getId())
                .doesNotContain(recorded.getId(), stillRunning.getId(), taskRun.getId());
    }

    @Test
    void fixtureApprove_incrementsVersion() {
        Story story = newStory("fixture");

        assertThat(StorySpecFixtures.approve(jdbc, story.getId())).isEqualTo(1);
        assertThat(StorySpecFixtures.approve(jdbc, story.getId())).isEqualTo(2);
        assertThat(versionRepo.findByStoryIdAndVersion(story.getId(), 2))
                .get()
                .extracting(StorySpecVersion::getContent)
                .isEqualTo("# Story spec (test fixture)");
    }

    @Test
    void storyConflictRunReferences_areIndexed() {
        // Deleting a run checks story_conflict through both references; unindexed, each run
        // delete scans the whole table.
        List<String> indexDefs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'story_conflict'",
                String.class);

        assertThat(indexDefs)
                .anySatisfy(def -> assertThat(def).endsWith("(task_run_id)"))
                .anySatisfy(def -> assertThat(def).endsWith("(revision_run_id)"));
    }

    private StoryConflict conflict(Story story, WorkflowRun taskRun, int raisedAgainst) {
        NodeExecution analyze = new NodeExecution();
        analyze.setWorkflowRunId(taskRun.getId());
        analyze.setTemplateNodeId(templateNode.getId());
        analyze.setGraphVersion(1);
        analyze.setIteration(iteration++);
        analyze.setStatus(NodeExecutionStatus.completed);
        analyze = execRepo.saveAndFlush(analyze);
        StoryConflict conflict = new StoryConflict();
        conflict.setStoryId(story.getId());
        conflict.setTaskRunId(taskRun.getId());
        conflict.setAnalyzeNodeExecutionId(analyze.getId());
        conflict.setRaisedAgainstVersion(raisedAgainst);
        conflict.setReport("report");
        return conflict;
    }

    private List<UUID> conflictIds(UUID storyId) {
        return jdbc.queryForList("SELECT id FROM story_conflict WHERE story_id = ?", UUID.class, storyId);
    }

    private long count(String table, UUID storyId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE story_id = ?", Long.class, storyId);
    }
}
