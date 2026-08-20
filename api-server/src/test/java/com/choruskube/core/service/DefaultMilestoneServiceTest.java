package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.MilestoneRequest;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.MilestoneUpdateRequest;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
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
import java.time.LocalDate;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class DefaultMilestoneServiceTest extends BaseTest {

    /** Test-only MappableCreated collector, mirroring MappableCreatedPublicationTest's pattern. */
    static class MappableEventCollector {
        private final List<MappableCreated> captured = new ArrayList<>();

        @EventListener
        public void on(MappableCreated event) {
            captured.add(event);
        }

        List<MappableCreated> getCaptured() {
            return captured;
        }

        void clear() {
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

    @Autowired
    private MilestoneService service;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private MappableEventCollector collector;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private AuditSink auditSink;

    @BeforeEach
    void clearEvents() {
        collector.clear();
    }

    @AfterEach
    void clearEventsAfter() {
        collector.clear();
    }

    @Test
    void create_returnsMilestoneWithZeroEpicCount() {
        GitRepo r = makeRepo("https://github.com/test/milestone-create.git");

        MilestoneResponse created = service.create(new MilestoneRequest("Q3 Launch", "Desc", r.getId(), null));

        assertThat(created.name()).isEqualTo("Q3 Launch");
        assertThat(created.softwareProjectId()).isEqualTo(r.getId());
        assertThat(created.epicCount()).isZero();
    }

    @Test
    void create_withNullSoftwareProjectId_throwsBadRequest() {
        assertThatThrownBy(() -> service.create(new MilestoneRequest("No project", null, null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_withUnknownSoftwareProjectId_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(new MilestoneRequest("Ghost", null, unknown, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_duplicateNameCaseInsensitive_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/milestone-dup.git");
        service.create(new MilestoneRequest("Q3 Launch", null, r.getId(), null));

        assertThatThrownBy(() -> service.create(new MilestoneRequest("q3 LAUNCH", null, r.getId(), null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_sameNameDifferentProject_succeeds() {
        GitRepo r1 = makeRepo("https://github.com/test/milestone-proj-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/milestone-proj-b.git");
        service.create(new MilestoneRequest("Q3 Launch", null, r1.getId(), null));

        MilestoneResponse other = service.create(new MilestoneRequest("Q3 Launch", null, r2.getId(), null));

        assertThat(other.softwareProjectId()).isEqualTo(r2.getId());
    }

    @Test
    void create_publishesMappableCreatedExactlyOnce() {
        GitRepo r = makeRepo("https://github.com/test/milestone-event.git");

        MilestoneResponse created = service.create(new MilestoneRequest("Event Milestone", null, r.getId(), null));

        List<MappableCreated> events = collector.getCaptured();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).resourceType()).isEqualTo("milestone");
        assertThat(events.get(0).resourceId()).isEqualTo(created.id());
    }

    @Test
    void create_writesAuditEntry() {
        GitRepo r = makeRepo("https://github.com/test/milestone-audit-create.git");

        MilestoneResponse created = service.create(new MilestoneRequest("Audited Milestone", null, r.getId(), null));

        verify(auditSink).record(eq(AuditSink.MILESTONE_CREATED), eq("milestone"), eq(created.id()), any());
    }

    @Test
    void get_returnsEpicCount_viaSingleMilestoneCount() {
        GitRepo r = makeRepo("https://github.com/test/milestone-get-count.git");
        MilestoneResponse milestone = service.create(new MilestoneRequest("Counted Milestone", null, r.getId(), null));
        var epic = epicService.create(new EpicRequest("Tagged", "D", null, r.getId()), null);
        epicService.assignMilestone(epic.id(), milestone.id());

        MilestoneResponse fetched = service.get(milestone.id());

        assertThat(fetched.epicCount()).isEqualTo(1);
    }

    @Test
    void get_notFound_throwsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void update_renamesAndWritesAudit() {
        GitRepo r = makeRepo("https://github.com/test/milestone-rename.git");
        MilestoneResponse created = service.create(new MilestoneRequest("Old Name", null, r.getId(), null));

        MilestoneResponse updated = service.update(
                created.id(), new MilestoneUpdateRequest("New Name", "New Desc", LocalDate.parse("2026-09-01")));

        assertThat(updated.name()).isEqualTo("New Name");
        assertThat(updated.description()).isEqualTo("New Desc");
        assertThat(updated.targetDate()).isEqualTo(LocalDate.parse("2026-09-01"));
        verify(auditSink).record(eq(AuditSink.MILESTONE_UPDATED), eq("milestone"), eq(created.id()), any());
    }

    @Test
    void update_renameToSameNameDifferentCase_succeeds() {
        GitRepo r = makeRepo("https://github.com/test/milestone-rename-samecase.git");
        MilestoneResponse created = service.create(new MilestoneRequest("Same Name", null, r.getId(), null));

        MilestoneResponse updated = service.update(created.id(), new MilestoneUpdateRequest("SAME NAME", null, null));

        assertThat(updated.name()).isEqualTo("SAME NAME");
    }

    @Test
    void update_renameToAnotherMilestonesName_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/milestone-rename-collision.git");
        service.create(new MilestoneRequest("Taken Name", null, r.getId(), null));
        MilestoneResponse toRename = service.create(new MilestoneRequest("Renamable", null, r.getId(), null));

        assertThatThrownBy(() -> service.update(toRename.id(), new MilestoneUpdateRequest("taken name", null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void delete_removesMilestone_andWritesAudit() {
        GitRepo r = makeRepo("https://github.com/test/milestone-delete.git");
        MilestoneResponse created = service.create(new MilestoneRequest("Deletable", null, r.getId(), null));

        service.delete(created.id());

        assertThatThrownBy(() -> service.get(created.id())).isInstanceOf(NotFoundException.class);
        verify(auditSink).record(eq(AuditSink.MILESTONE_DELETED), eq("milestone"), eq(created.id()), any());
    }

    // ── progress rollup ──

    @Test
    void get_computesProgress_fromMixedStatusTasksUnderTaggedEpic() {
        GitRepo r = makeRepo("https://github.com/test/milestone-progress-mixed.git");
        MilestoneResponse milestone = service.create(new MilestoneRequest("Progress Milestone", null, r.getId(), null));
        var epic = epicService.create(new EpicRequest("E", "D", null, r.getId()), null);
        epicService.assignMilestone(epic.id(), milestone.id());
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        var doneTask = taskService.create(story.id(), new TaskRequest("T1", "D"));
        var inProgressTask = taskService.create(story.id(), new TaskRequest("T2", "D"));
        taskService.create(story.id(), new TaskRequest("T3 (left in backlog)", "D"));
        setTaskStatus(doneTask.id(), WorkItemStatus.done);
        setTaskStatus(inProgressTask.id(), WorkItemStatus.in_progress);

        MilestoneResponse fetched = service.get(milestone.id());

        assertThat(fetched.epicCount()).isEqualTo(1);
        assertThat(fetched.progress().totalTasks()).isEqualTo(3);
        assertThat(fetched.progress().doneTasks()).isEqualTo(1);
        assertThat(fetched.progress().inProgressTasks()).isEqualTo(1);
        assertThat(fetched.progress().notStartedTasks()).isEqualTo(1);
        assertThat(fetched.progress().doneTasks()
                        + fetched.progress().inProgressTasks()
                        + fetched.progress().notStartedTasks())
                .isEqualTo(fetched.progress().totalTasks());
    }

    @Test
    void get_rolledOutEpicWithNoTasks_progressAllZero_andNotAtRisk() {
        GitRepo r = makeRepo("https://github.com/test/milestone-rolled-out-empty.git");
        MilestoneResponse milestone =
                service.create(new MilestoneRequest("Rolled Out Milestone", null, r.getId(), null));
        var epic = epicService.create(new EpicRequest("E", "D", null, r.getId()), null);
        epicService.assignMilestone(epic.id(), milestone.id());
        epicService.updateStage(epic.id(), WorkItemStatus.rolled_out);

        MilestoneResponse fetched = service.get(milestone.id());

        assertThat(fetched.progress().totalTasks()).isZero();
        assertThat(fetched.progress().doneTasks()).isZero();
        assertThat(fetched.progress().inProgressTasks()).isZero();
        assertThat(fetched.progress().notStartedTasks()).isZero();
        assertThat(fetched.atRisk()).isFalse();
    }

    private void setTaskStatus(UUID taskId, WorkItemStatus status) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(status);
        taskRepo.saveAndFlush(t);
    }

    // ── list(): batched epicCount via findByMilestoneIdIn (not per-Milestone countByMilestoneId) ──

    @Test
    void list_computesEpicCountPerMilestone_viaBatchedGrouping() {
        GitRepo r = makeRepo("https://github.com/test/milestone-list-batch.git");
        MilestoneResponse zeroEpics = service.create(new MilestoneRequest("Zero Epics", null, r.getId(), null));
        MilestoneResponse oneEpic = service.create(new MilestoneRequest("One Epic", null, r.getId(), null));
        MilestoneResponse twoEpics = service.create(new MilestoneRequest("Two Epics", null, r.getId(), null));

        var e1 = epicService.create(new EpicRequest("E1", "D", null, r.getId()), null);
        epicService.assignMilestone(e1.id(), oneEpic.id());
        var e2 = epicService.create(new EpicRequest("E2", "D", null, r.getId()), null);
        epicService.assignMilestone(e2.id(), twoEpics.id());
        var e3 = epicService.create(new EpicRequest("E3", "D", null, r.getId()), null);
        epicService.assignMilestone(e3.id(), twoEpics.id());

        Page<MilestoneResponse> page = service.list(r.getId(), PageRequest.of(0, 20));

        assertThat(epicCountOf(page, zeroEpics.id())).isZero();
        assertThat(epicCountOf(page, oneEpic.id())).isEqualTo(1);
        assertThat(epicCountOf(page, twoEpics.id())).isEqualTo(2);
    }

    @Test
    void list_filteredBySoftwareProjectId_returnsOnlyMatching() {
        GitRepo r1 = makeRepo("https://github.com/test/milestone-list-filter-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/milestone-list-filter-b.git");
        MilestoneResponse a = service.create(new MilestoneRequest("A", null, r1.getId(), null));
        MilestoneResponse b = service.create(new MilestoneRequest("B", null, r2.getId(), null));

        Page<MilestoneResponse> page = service.list(r1.getId(), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(MilestoneResponse::id).contains(a.id());
        assertThat(page.getContent()).extracting(MilestoneResponse::id).doesNotContain(b.id());
    }

    private long epicCountOf(Page<MilestoneResponse> page, UUID id) {
        return page.getContent().stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElseThrow()
                .epicCount();
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }
}
