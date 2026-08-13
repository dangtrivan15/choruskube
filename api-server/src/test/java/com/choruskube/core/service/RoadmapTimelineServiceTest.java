package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.RoadmapTimelineResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TimelineEpicSummary;
import com.choruskube.core.dto.TimelineStorySummary;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class RoadmapTimelineServiceTest extends BaseTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Autowired
    private RoadmapTimelineService timelineService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private WorkItemDependencyService dependencyService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private AuditSink auditSink;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void stubClock() {
        // Every test in this class exercises DefaultRoadmapTimelineService's stalled() check, so
        // the mocked Clock needs a default "now" even for tests that don't care about staleness —
        // an unstubbed Clock bean returns null from instant(), which NPEs inside
        // Duration.between(updatedAt, null).
        when(clock.instant()).thenReturn(FIXED_NOW);
    }

    @Test
    void getTimeline_noEpics_returnsEmptyEpicsList() {
        // Single-tenant scope (NoOpScopeProvider) sees every Epic ever created by any other test
        // sharing this transaction's rollback boundary — but each test runs in its own rolled-back
        // @Transactional, so a fresh test method starts from a genuinely empty roadmap.
        RoadmapTimelineResponse response = timelineService.getTimeline();

        assertThat(response.epics()).isEmpty();
    }

    @Test
    void getTimeline_epicWithZeroStories_includedWithEmptyStoriesList() {
        EpicResponse epic = makeEpic("https://github.com/test/timeline-empty-epic.git");

        RoadmapTimelineResponse response = timelineService.getTimeline();

        TimelineEpicSummary summary = summaryFor(response, epic.id());
        assertThat(summary.stories()).isEmpty();
    }

    @Test
    void getTimeline_multipleEpicsAndStories_mapsFieldForFieldAndGroupsUnderCorrectEpic() {
        EpicResponse epicA = makeEpic("https://github.com/test/timeline-fields-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        EpicResponse epicB = makeEpic("https://github.com/test/timeline-fields-b.git");
        StoryResponse storyB1 = makeStory(epicB.id(), "Story B1");
        StoryResponse storyB2 = makeStory(epicB.id(), "Story B2");

        RoadmapTimelineResponse response = timelineService.getTimeline();

        TimelineEpicSummary summaryA = summaryFor(response, epicA.id());
        assertThat(summaryA.id()).isEqualTo(epicA.id());
        assertThat(summaryA.title()).isEqualTo(epicA.title());
        assertThat(summaryA.stage()).isEqualTo(epicA.stage());
        assertThat(summaryA.createdAt()).isEqualTo(epicA.createdAt());
        assertThat(summaryA.updatedAt()).isEqualTo(epicA.updatedAt());
        assertThat(summaryA.stories()).extracting(s -> s.id()).containsExactly(storyA.id());
        assertThat(summaryA.stories().get(0).epicId()).isEqualTo(epicA.id());
        assertThat(summaryA.stories().get(0).title()).isEqualTo(storyA.title());
        assertThat(summaryA.stories().get(0).stage()).isEqualTo(storyA.stage());
        assertThat(summaryA.stories().get(0).createdAt()).isEqualTo(storyA.createdAt());
        assertThat(summaryA.stories().get(0).updatedAt()).isEqualTo(storyA.updatedAt());

        TimelineEpicSummary summaryB = summaryFor(response, epicB.id());
        assertThat(summaryB.stories()).extracting(s -> s.id()).containsExactlyInAnyOrder(storyB1.id(), storyB2.id());
        assertThat(summaryB.stories()).allMatch(s -> s.epicId().equals(epicB.id()));
    }

    @Test
    void getTimeline_epicAndStoryCarryPriority() {
        EpicResponse epic = makeEpic("https://github.com/test/timeline-priority.git");
        epicService.updatePriority(epic.id(), com.choruskube.core.model.enums.Priority.high);
        StoryResponse story = makeStory(epic.id(), "Priority Story");
        storyService.updatePriority(story.id(), com.choruskube.core.model.enums.Priority.low);

        RoadmapTimelineResponse response = timelineService.getTimeline();

        TimelineEpicSummary summary = summaryFor(response, epic.id());
        assertThat(summary.priority()).isEqualTo("high");
        assertThat(summary.stories()).extracting(TimelineStorySummary::priority).containsExactly("low");
    }

    @Test
    void getTimeline_ordersEpicsAndTheirStoriesAscendingByCreatedAt() throws InterruptedException {
        // Deliberately created out of title order so a pass here can't be explained by DB default
        // (insertion/PK) order coinciding with the assertion — only the service's explicit
        // Sort.by(ASC, "createdAt") on the Epic query, plus its own in-memory sort of each Epic's
        // Story list, can make this pass.
        EpicResponse epicOld = makeEpic("https://github.com/test/timeline-order-old.git");
        Thread.sleep(5);
        EpicResponse epicNew = makeEpic("https://github.com/test/timeline-order-new.git");

        StoryResponse storyOld = makeStory(epicNew.id(), "Older Story");
        Thread.sleep(5);
        StoryResponse storyNew = makeStory(epicNew.id(), "Newer Story");

        RoadmapTimelineResponse response = timelineService.getTimeline();

        assertThat(response.epics()).extracting(TimelineEpicSummary::id).containsExactly(epicOld.id(), epicNew.id());
        TimelineEpicSummary newEpicSummary = summaryFor(response, epicNew.id());
        assertThat(newEpicSummary.stories()).extracting(s -> s.id()).containsExactly(storyOld.id(), storyNew.id());
    }

    @Test
    void getTimeline_storyBlockedByUnfinishedDependency_readinessIsBlocked() {
        EpicResponse epic = makeEpic("https://github.com/test/timeline-blocked.git");
        StoryResponse blocking = makeStory(epic.id(), "Blocking Story");
        StoryResponse blocked = makeStory(epic.id(), "Blocked Story");
        dependencyService.create(new CreateDependencyRequest("story", blocking.id(), "story", blocked.id()));

        RoadmapTimelineResponse response = timelineService.getTimeline();

        TimelineStorySummary blockedSummary = storySummaryFor(response, epic.id(), blocked.id());
        assertThat(blockedSummary.readiness()).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void getTimeline_readyStoryWithNoDependencies_readinessIsReadyAndNotStalled() {
        EpicResponse epic = makeEpic("https://github.com/test/timeline-ready.git");
        StoryResponse story = makeStory(epic.id(), "Ready Story");

        RoadmapTimelineResponse response = timelineService.getTimeline();

        TimelineStorySummary summary = storySummaryFor(response, epic.id(), story.id());
        assertThat(summary.readiness()).isEqualTo(Readiness.READY);
        assertThat(summary.stalled()).isFalse();
    }

    @Test
    void getTimeline_inProgressStoryUpdated15DaysAgo_isStalled() {
        EpicResponse epic = makeEpic("https://github.com/test/timeline-stalled-15.git");
        StoryResponse story = makeStory(epic.id(), "Stalled Story");
        storyService.updateStage(story.id(), WorkItemStatus.in_progress);
        backdateStory(story.id(), FIXED_NOW.minus(Duration.ofDays(15)));

        RoadmapTimelineResponse response = timelineService.getTimeline();

        TimelineStorySummary summary = storySummaryFor(response, epic.id(), story.id());
        assertThat(summary.stalled()).isTrue();
    }

    @Test
    void getTimeline_inProgressStoryUpdated13DaysAgo_isNotStalled() {
        EpicResponse epic = makeEpic("https://github.com/test/timeline-stalled-13.git");
        StoryResponse story = makeStory(epic.id(), "Fresh In-Progress Story");
        storyService.updateStage(story.id(), WorkItemStatus.in_progress);
        backdateStory(story.id(), FIXED_NOW.minus(Duration.ofDays(13)));

        RoadmapTimelineResponse response = timelineService.getTimeline();

        TimelineStorySummary summary = storySummaryFor(response, epic.id(), story.id());
        assertThat(summary.stalled()).isFalse();
    }

    @Test
    void getTimeline_backlogRolledOutAndDoneStoriesOld_neverStalled() {
        EpicResponse epic = makeEpic("https://github.com/test/timeline-stalled-immune.git");
        StoryResponse backlog = makeStory(epic.id(), "Backlog Story");
        StoryResponse rolledOut = makeStory(epic.id(), "Rolled Out Story");
        storyService.updateStage(rolledOut.id(), WorkItemStatus.rolled_out);
        Instant longAgo = FIXED_NOW.minus(Duration.ofDays(365));
        backdateStory(backlog.id(), longAgo);
        backdateStory(rolledOut.id(), longAgo);

        RoadmapTimelineResponse response = timelineService.getTimeline();

        assertThat(storySummaryFor(response, epic.id(), backlog.id()).stalled()).isFalse();
        assertThat(storySummaryFor(response, epic.id(), rolledOut.id()).stalled())
                .isFalse();
    }

    @Test
    void getTimeline_epicItselfInProgressAndOld_isStalled() {
        EpicResponse epic = makeEpic("https://github.com/test/timeline-epic-stalled.git");
        epicService.updateStage(epic.id(), WorkItemStatus.in_progress);
        backdateEpic(epic.id(), FIXED_NOW.minus(Duration.ofDays(15)));

        RoadmapTimelineResponse response = timelineService.getTimeline();

        TimelineEpicSummary summary = summaryFor(response, epic.id());
        assertThat(summary.stalled()).isTrue();
    }

    private static TimelineStorySummary storySummaryFor(RoadmapTimelineResponse response, UUID epicId, UUID storyId) {
        return summaryFor(response, epicId).stories().stream()
                .filter(s -> s.id().equals(storyId))
                .findFirst()
                .orElseThrow();
    }

    private void backdateStory(UUID storyId, Instant updatedAt) {
        // Flush first: the Story insert/update issued by storyService.* above is still only a
        // pending Hibernate action at this point, not yet applied to the DB — the raw JDBC UPDATE
        // below would silently affect zero rows without this, and the subsequent clear() would
        // then discard that still-pending insert outright (never reaching the DB at all).
        entityManager.flush();
        jdbc.update("UPDATE story SET updated_at = ? WHERE id = ?", Timestamp.from(updatedAt), storyId);
        entityManager.clear();
    }

    private void backdateEpic(UUID epicId, Instant updatedAt) {
        entityManager.flush();
        jdbc.update("UPDATE epic SET updated_at = ? WHERE id = ?", Timestamp.from(updatedAt), epicId);
        entityManager.clear();
    }

    private static TimelineEpicSummary summaryFor(RoadmapTimelineResponse response, UUID epicId) {
        return response.epics().stream()
                .filter(e -> e.id().equals(epicId))
                .findFirst()
                .orElseThrow();
    }

    private EpicResponse makeEpic(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }

    private StoryResponse makeStory(UUID epicId, String title) {
        return storyService.create(epicId, new StoryRequest(title, "Desc for " + title));
    }
}
