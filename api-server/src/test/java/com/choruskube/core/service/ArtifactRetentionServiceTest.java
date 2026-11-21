package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.SharedContextTest;
import com.choruskube.core.StorySpecFixtures;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ArtifactRetentionServiceTest extends SharedContextTest {

    private static final Duration PERIOD = Duration.ofDays(30);

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private RunStorageScope storageScope;

    @Autowired
    private StorySpecService specService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private RunService runService;

    @Autowired
    private ArtifactRetentionService wiredRetention;

    @Autowired
    private JdbcTemplate jdbc;

    private ObjectTreeDeleter deleter;
    private RunEventPublisher publisher;

    @BeforeEach
    void setUp() {
        // Earlier runs in the shared database would otherwise fall due alongside this test's own.
        jdbc.update("UPDATE workflow_run SET artifacts_expired_at = now() WHERE artifacts_expired_at IS NULL");
        deleter = mock(ObjectTreeDeleter.class);
        publisher = mock(RunEventPublisher.class);
        when(deleter.isEmpty(anyString())).thenReturn(true);
    }

    private ArtifactRetentionService retention(Duration period) {
        return new ArtifactRetentionService(runRepo, storageScope, deleter, publisher, period, 50);
    }

    @Test
    void pass_doesNothingWhileThePeriodIsUnset() {
        closedRun(WorkflowRunStatus.completed, daysAgo(400));

        assertThat(retention(null).expireDue()).isZero();
        verifyNoInteractions(deleter, publisher);
    }

    @Test
    void wiredService_bindsAnUnsetPeriodAsOff() {
        closedRun(WorkflowRunStatus.completed, daysAgo(400));

        assertThat(wiredRetention.expireDue()).isZero();
    }

    @Test
    void periodSetting_readsADayCountAsDays() {
        assertThat(ApplicationConversionService.getSharedInstance().convert("30d", Duration.class))
                .isEqualTo(Duration.ofDays(30));
    }

    @Test
    void aPeriodUnderADay_refusesToStart() {
        // A bare "30" binds as milliseconds.
        assertThatThrownBy(() -> retention(Duration.ofMillis(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("objectstore.artifact-retention");
    }

    @Test
    void pass_expiresEveryClosedStatusPastThePeriod_andAnnouncesEach() {
        UUID completed = closedRun(WorkflowRunStatus.completed, daysAgo(31));
        UUID failed = closedRun(WorkflowRunStatus.failed, daysAgo(45));
        UUID cancelled = closedRun(WorkflowRunStatus.cancelled, daysAgo(365));

        assertThat(retention(PERIOD).expireDue()).isEqualTo(3);

        for (UUID runId : new UUID[] {completed, failed, cancelled}) {
            verify(deleter).deleteAll(storageScope.treeOf(runId));
            verify(publisher).publishRunArtifactsExpired(runId);
            assertThat(expiredAt(runId)).isNotNull();
        }
    }

    @Test
    void pass_neverTouchesAnOpenRunOrOneStillInsideThePeriod() {
        UUID running = openRun(WorkflowRunStatus.running);
        UUID awaiting = openRun(WorkflowRunStatus.awaiting_human);
        UUID recent = closedRun(WorkflowRunStatus.completed, daysAgo(29));

        assertThat(retention(PERIOD).expireDue()).isZero();

        verifyNoInteractions(deleter, publisher);
        assertThat(expiredAt(running)).isNull();
        assertThat(expiredAt(awaiting)).isNull();
        assertThat(expiredAt(recent)).isNull();
    }

    @Test
    void pass_recordsNothingWhileTheTreeStillListsObjects() {
        UUID runId = closedRun(WorkflowRunStatus.completed, daysAgo(100));
        when(deleter.isEmpty(storageScope.treeOf(runId))).thenReturn(false);

        assertThat(retention(PERIOD).expireDue()).isZero();

        verify(deleter).deleteAll(storageScope.treeOf(runId));
        verify(publisher, never()).publishRunArtifactsExpired(runId);
        assertThat(expiredAt(runId)).isNull();
    }

    @Test
    void pass_leavesAFailedDeletionUnrecorded_andStillExpiresTheOthers() {
        UUID failing = closedRun(WorkflowRunStatus.completed, daysAgo(200));
        UUID healthy = closedRun(WorkflowRunStatus.completed, daysAgo(100));
        doThrow(new IllegalStateException("store refused")).when(deleter).deleteAll(storageScope.treeOf(failing));

        assertThat(retention(PERIOD).expireDue()).isEqualTo(1);

        assertThat(expiredAt(failing)).isNull();
        assertThat(expiredAt(healthy)).isNotNull();
        verify(publisher, never()).publishRunArtifactsExpired(failing);
    }

    @Test
    void secondPass_changesNothing() {
        UUID runId = closedRun(WorkflowRunStatus.completed, daysAgo(100));
        ArtifactRetentionService retention = retention(PERIOD);
        retention.expireDue();
        Instant first = expiredAt(runId);

        assertThat(retention.expireDue()).isZero();

        verify(deleter, times(1)).deleteAll(storageScope.treeOf(runId));
        verify(publisher, times(1)).publishRunArtifactsExpired(runId);
        assertThat(expiredAt(runId)).isEqualTo(first);
    }

    @Test
    void pass_skipsDesignRunsWhoseTreeAReaderStillNeeds() {
        StoryResponse story = makeStory("retention-design-skips");
        UUID uncaptured = designRun(story.id(), WorkflowRunStatus.completed, daysAgo(300));
        StorySpecFixtures.insertUncaptured(jdbc, story.id(), uncaptured);
        StoryResponse unrecordedStory = makeStory("retention-design-unrecorded");
        UUID unrecorded = designRun(unrecordedStory.id(), WorkflowRunStatus.completed, daysAgo(300));
        StoryResponse capturedStory = makeStory("retention-design-captured");
        UUID captured = designRun(capturedStory.id(), WorkflowRunStatus.completed, daysAgo(300));
        specService.recordApproved(capturedStory.id(), captured, "# Spec", "Review");
        StoryResponse failedStory = makeStory("retention-design-failed");
        UUID failed = designRun(failedStory.id(), WorkflowRunStatus.failed, daysAgo(300));

        assertThat(retention(PERIOD).expireDue()).isEqualTo(2);

        assertThat(expiredAt(uncaptured)).isNull();
        assertThat(expiredAt(unrecorded)).isNull();
        assertThat(expiredAt(captured)).isNotNull();
        assertThat(expiredAt(failed)).isNotNull();
    }

    @Test
    void expireNow_expiresAClosedRunInsideItsPeriod_evenWithRetentionOff() {
        UUID runId = closedRun(WorkflowRunStatus.completed, Instant.now());

        retention(null).expireNow(runId);

        verify(deleter).deleteAll(storageScope.treeOf(runId));
        verify(publisher).publishRunArtifactsExpired(runId);
        assertThat(expiredAt(runId)).isNotNull();
    }

    @Test
    void expireNow_refusesAnOpenRun_andARunAReaderStillNeeds() {
        UUID open = openRun(WorkflowRunStatus.awaiting_human);
        StoryResponse story = makeStory("retention-now-unrecorded");
        UUID unrecorded = designRun(story.id(), WorkflowRunStatus.completed, daysAgo(1));

        assertThatThrownBy(() -> retention(null).expireNow(open)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> retention(null).expireNow(unrecorded)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(deleter, publisher);
    }

    @Test
    void expireNow_onAnExpiredRun_isANoOp() {
        UUID runId = closedRun(WorkflowRunStatus.completed, daysAgo(1));
        ArtifactRetentionService retention = retention(null);
        retention.expireNow(runId);

        retention.expireNow(runId);

        verify(deleter, times(1)).deleteAll(storageScope.treeOf(runId));
    }

    @Test
    void aStaleWholeEntitySave_neverClearsTheRecordedExpiry() {
        UUID runId = closedRun(WorkflowRunStatus.completed, daysAgo(100));
        WorkflowRun stale = runRepo.findById(runId).orElseThrow();
        retention(PERIOD).expireDue();

        stale.setName("renamed after expiry");
        runRepo.saveAndFlush(stale);

        assertThat(expiredAt(runId)).isNotNull();
    }

    @Test
    void runApi_reportsTheExpiry() {
        UUID runId = closedRun(WorkflowRunStatus.completed, daysAgo(100));
        assertThat(runService.getRun(runId).artifactsExpiredAt()).isNull();

        retention(PERIOD).expireDue();

        assertThat(runService.getRun(runId).artifactsExpiredAt()).isEqualTo(expiredAt(runId));
    }

    private Instant expiredAt(UUID runId) {
        return jdbc.queryForObject("SELECT artifacts_expired_at FROM workflow_run WHERE id = ?", Instant.class, runId);
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    private UUID closedRun(WorkflowRunStatus status, Instant completedAt) {
        WorkflowRun run = run(GraphIds.FEATURE_DEVELOPMENT, status);
        run.setCompletedAt(completedAt);
        return runRepo.saveAndFlush(run).getId();
    }

    private UUID openRun(WorkflowRunStatus status) {
        return runRepo.saveAndFlush(run(GraphIds.FEATURE_DEVELOPMENT, status)).getId();
    }

    private UUID designRun(UUID storyId, WorkflowRunStatus status, Instant completedAt) {
        WorkflowRun run = run(GraphIds.STORY_DESIGN, status);
        run.setStoryId(storyId);
        run.setCompletedAt(completedAt);
        return runRepo.saveAndFlush(run).getId();
    }

    private WorkflowRun run(String graphId, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateRepo
                .findFirstByGraphIdOrderByVersionDesc(graphId)
                .orElseThrow()
                .getId());
        run.setStatus(status);
        return run;
    }

    private StoryResponse makeStory(String slug) {
        String url = "https://github.com/test/" + slug + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        GitRepo repo = gitRepoRepo.save(r);
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "D", null, repo.getId()), null);
        return storyService.create(epic.id(), new StoryRequest("Story", "D"));
    }
}
