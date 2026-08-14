package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.MilestoneRequest;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Covers Decision 4 of the "Group Epics under a named Milestone / Release" feature: {@code
 * EpicService#assignMilestone} — assign/clear, the same-project guard, exemption from the "no
 * edit once started" guard {@code update()} enforces, and the response/event contract.
 */
@Transactional
public class EpicMilestoneAssignmentTest extends BaseTest {

    @Autowired
    private EpicService epicService;

    @Autowired
    private MilestoneService milestoneService;

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

    @Test
    void assignMilestone_setsMilestoneRef() {
        GitRepo r = makeRepo("https://github.com/test/assign-set.git");
        EpicResponse epic = epicService.create(new EpicRequest("T", "D", null, r.getId()), null);
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("Q3 Launch", null, r.getId(), null));

        EpicResponse updated = epicService.assignMilestone(epic.id(), milestone.id());

        assertThat(updated.milestone()).isNotNull();
        assertThat(updated.milestone().id()).isEqualTo(milestone.id());
        assertThat(updated.milestone().name()).isEqualTo("Q3 Launch");

        EpicResponse refetched = epicService.get(epic.id());
        assertThat(refetched.milestone().id()).isEqualTo(milestone.id());
    }

    @Test
    void assignMilestone_withNull_clearsMilestone() {
        GitRepo r = makeRepo("https://github.com/test/assign-clear.git");
        EpicResponse epic = epicService.create(new EpicRequest("T", "D", null, r.getId()), null);
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("Q3 Launch", null, r.getId(), null));
        epicService.assignMilestone(epic.id(), milestone.id());

        EpicResponse cleared = epicService.assignMilestone(epic.id(), null);

        assertThat(cleared.milestone()).isNull();
        EpicResponse refetched = epicService.get(epic.id());
        assertThat(refetched.milestone()).isNull();
    }

    @Test
    void assignMilestone_crossProjectMismatch_throwsBadRequest() {
        GitRepo epicRepo = makeRepo("https://github.com/test/assign-cross-epic.git");
        GitRepo otherRepo = makeRepo("https://github.com/test/assign-cross-milestone.git");
        EpicResponse epic = epicService.create(new EpicRequest("T", "D", null, epicRepo.getId()), null);
        MilestoneResponse foreignMilestone =
                milestoneService.create(new MilestoneRequest("Other Project Milestone", null, otherRepo.getId(), null));

        assertThatThrownBy(() -> epicService.assignMilestone(epic.id(), foreignMilestone.id()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void assignMilestone_unknownMilestoneId_throwsNotFound() {
        GitRepo r = makeRepo("https://github.com/test/assign-unknown.git");
        EpicResponse epic = epicService.create(new EpicRequest("T", "D", null, r.getId()), null);

        assertThatThrownBy(() -> epicService.assignMilestone(epic.id(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void assignMilestone_unknownEpicId_throwsNotFound() {
        assertThatThrownBy(() -> epicService.assignMilestone(UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void assignMilestone_withStartedDescendantTask_succeeds() {
        // Proves the "no edit once started" guard the full PUT edit path enforces does NOT apply
        // to Milestone assignment — mirrors updateStage/updatePriority/updateTargetDate.
        GitRepo r = makeRepo("https://github.com/test/assign-started-task.git");
        EpicResponse epic = epicService.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        Task t = taskRepo.findById(task.id()).orElseThrow();
        t.setStatus(WorkItemStatus.in_progress);
        taskRepo.saveAndFlush(t);
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("Q3 Launch", null, r.getId(), null));

        EpicResponse updated = epicService.assignMilestone(epic.id(), milestone.id());

        assertThat(updated.milestone().id()).isEqualTo(milestone.id());
    }

    @Test
    void assignMilestone_publishesRoadmapItemChangedEvent() {
        GitRepo r = makeRepo("https://github.com/test/assign-event.git");
        EpicResponse epic = epicService.create(new EpicRequest("T", "D", null, r.getId()), null);
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("Q3 Launch", null, r.getId(), null));
        org.mockito.Mockito.clearInvocations(runEventPublisher);

        epicService.assignMilestone(epic.id(), milestone.id());

        verify(runEventPublisher).publishRoadmapItemChanged(eq("epic"), eq(epic.id()), any());
    }

    @Test
    void assignMilestone_writesAuditEntry() {
        GitRepo r = makeRepo("https://github.com/test/assign-audit.git");
        EpicResponse epic = epicService.create(new EpicRequest("T", "D", null, r.getId()), null);
        MilestoneResponse milestone = milestoneService.create(new MilestoneRequest("Q3 Launch", null, r.getId(), null));

        epicService.assignMilestone(epic.id(), milestone.id());

        verify(auditSink).record(eq(AuditSink.EPIC_MILESTONE_UPDATED), eq("epic"), eq(epic.id()), any());
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }
}
