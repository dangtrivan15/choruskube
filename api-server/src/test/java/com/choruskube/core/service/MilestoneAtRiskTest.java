package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.MilestoneAtRiskItemsResponse;
import com.choruskube.core.dto.MilestoneRequest;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Covers the "at-risk" verdict/drill-down feature on top of Milestones: an Epic or Story is at
 * risk iff its {@code targetDate} is strictly before today (per the injected {@link Clock}) and
 * its {@code RollupCalculator#effectiveStatus} is not {@code done}; a Milestone itself is at risk
 * iff its own {@code targetDate} is overdue AND at least one of its Epics is incomplete.
 */
@Transactional
public class MilestoneAtRiskTest extends BaseTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-19T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    @Autowired
    private MilestoneService milestoneService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private TaskRepository taskRepo;

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
        when(clock.instant()).thenReturn(FIXED_NOW);
    }

    @Test
    void epicPastTargetDateAndIncomplete_milestoneIsAtRisk_andListedInDrillDown() {
        GitRepo r = makeRepo("https://github.com/test/atrisk-epic-yesterday.git");
        MilestoneResponse milestone =
                milestoneService.create(new MilestoneRequest("M", null, r.getId(), TODAY.minusDays(1)));
        EpicResponse epic = makeTaggedEpic(r.getId(), milestone.id());
        epicService.updateTargetDate(epic.id(), TODAY.minusDays(1));

        MilestoneResponse fetched = milestoneService.get(milestone.id());
        assertThat(fetched.atRisk()).isTrue();
        assertThat(fetched.atRiskItemCount()).isEqualTo(1);

        MilestoneAtRiskItemsResponse items = milestoneService.getAtRiskItems(milestone.id());
        assertThat(items.items()).hasSize(1);
        assertThat(items.items().get(0).id()).isEqualTo(epic.id());
        assertThat(items.items().get(0).tier()).isEqualTo("EPIC");
        assertThat(items.items().get(0).targetDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void epicTargetDateTomorrow_notAtRisk() {
        GitRepo r = makeRepo("https://github.com/test/atrisk-epic-tomorrow.git");
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("M", null, r.getId(), null));
        EpicResponse epic = makeTaggedEpic(r.getId(), milestone.id());
        epicService.updateTargetDate(epic.id(), TODAY.plusDays(1));

        MilestoneResponse fetched = milestoneService.get(milestone.id());
        assertThat(fetched.atRiskItemCount()).isZero();
        assertThat(fetched.atRisk()).isFalse();
        assertThat(milestoneService.getAtRiskItems(milestone.id()).items()).isEmpty();
    }

    @Test
    void epicTargetDateEqualsToday_notAtRisk_strictlyBeforeOnly() {
        GitRepo r = makeRepo("https://github.com/test/atrisk-epic-today.git");
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("M", null, r.getId(), null));
        EpicResponse epic = makeTaggedEpic(r.getId(), milestone.id());
        epicService.updateTargetDate(epic.id(), TODAY);

        assertThat(milestoneService.get(milestone.id()).atRiskItemCount()).isZero();
        assertThat(milestoneService.getAtRiskItems(milestone.id()).items()).isEmpty();
    }

    @Test
    void allTasksDoneEpicPastTargetDate_notAtRisk() {
        GitRepo r = makeRepo("https://github.com/test/atrisk-epic-done.git");
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("M", null, r.getId(), null));
        EpicResponse epic = makeTaggedEpic(r.getId(), milestone.id());
        epicService.updateTargetDate(epic.id(), TODAY.minusDays(5));
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        setTaskStatus(task.id(), WorkItemStatus.done);

        MilestoneResponse fetched = milestoneService.get(milestone.id());
        assertThat(fetched.atRiskItemCount()).isZero();
        assertThat(fetched.atRisk()).isFalse();
    }

    @Test
    void milestoneOverdueButAllEpicsComplete_notAtRisk() {
        GitRepo r = makeRepo("https://github.com/test/atrisk-milestone-epics-done.git");
        MilestoneResponse milestone =
                milestoneService.create(new MilestoneRequest("M", null, r.getId(), TODAY.minusDays(1)));
        EpicResponse epic = makeTaggedEpic(r.getId(), milestone.id());
        // The Milestone's own target date is overdue, but its only Epic is shipped (rolled_out ->
        // effectiveStatus "done"), so the Milestone-level verdict's "AND at least one incomplete
        // Epic" conjunct is false and the Milestone is NOT at risk even though its date has passed.
        // This isolates that second conjunct: every other atRisk-true/false case either has an
        // incomplete Epic or a non-overdue Milestone date, so a regression that dropped it (e.g.
        // atRisk = milestoneOverdue alone) would otherwise pass the whole suite.
        epicService.updateStage(epic.id(), WorkItemStatus.rolled_out);

        MilestoneResponse fetched = milestoneService.get(milestone.id());
        assertThat(fetched.atRisk()).isFalse();
        assertThat(fetched.atRiskItemCount()).isZero();
        assertThat(milestoneService.getAtRiskItems(milestone.id()).items()).isEmpty();
    }

    @Test
    void nullTargetDates_neverAtRisk() {
        GitRepo r = makeRepo("https://github.com/test/atrisk-null-dates.git");
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("M", null, r.getId(), null));
        makeTaggedEpic(r.getId(), milestone.id());

        MilestoneResponse fetched = milestoneService.get(milestone.id());
        assertThat(fetched.atRisk()).isFalse();
        assertThat(fetched.atRiskItemCount()).isZero();
    }

    @Test
    void getAtRiskItems_ordersByTargetDateAscending_thenEpicBeforeStory() {
        GitRepo r = makeRepo("https://github.com/test/atrisk-order.git");
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("M", null, r.getId(), null));
        EpicResponse epicLate = makeTaggedEpic(r.getId(), milestone.id());
        epicService.updateTargetDate(epicLate.id(), TODAY.minusDays(1));
        EpicResponse epicEarly = makeTaggedEpic(r.getId(), milestone.id());
        epicService.updateTargetDate(epicEarly.id(), TODAY.minusDays(10));
        StoryResponse storySameDateAsLateEpic = storyService.create(epicLate.id(), new StoryRequest("S", "D"));
        storyService.updateTargetDate(storySameDateAsLateEpic.id(), TODAY.minusDays(1));

        MilestoneAtRiskItemsResponse items = milestoneService.getAtRiskItems(milestone.id());

        assertThat(items.items())
                .extracting(i -> i.id())
                .containsExactly(epicEarly.id(), epicLate.id(), storySameDateAsLateEpic.id());
    }

    @Test
    void atRiskItemCount_matchesDrillDownListSize() {
        GitRepo r = makeRepo("https://github.com/test/atrisk-count-matches.git");
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("M", null, r.getId(), null));
        EpicResponse epic1 = makeTaggedEpic(r.getId(), milestone.id());
        epicService.updateTargetDate(epic1.id(), TODAY.minusDays(2));
        EpicResponse epic2 = makeTaggedEpic(r.getId(), milestone.id());
        epicService.updateTargetDate(epic2.id(), TODAY.minusDays(3));

        MilestoneResponse fetched = milestoneService.get(milestone.id());
        MilestoneAtRiskItemsResponse items = milestoneService.getAtRiskItems(milestone.id());

        assertThat(fetched.atRiskItemCount()).isEqualTo(items.items().size());
    }

    private EpicResponse makeTaggedEpic(UUID softwareProjectId, UUID milestoneId) {
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "D", null, softwareProjectId), null);
        epicService.assignMilestone(epic.id(), milestoneId);
        return epic;
    }

    private void setTaskStatus(UUID taskId, WorkItemStatus status) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(status);
        taskRepo.saveAndFlush(t);
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }
}
