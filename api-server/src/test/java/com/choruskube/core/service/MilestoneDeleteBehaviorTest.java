package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.MilestoneRequest;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Behavioral test for Decision 2 of the "Group Epics under a named Milestone / Release" feature:
 * {@code epic.milestone_id} is {@code ON DELETE SET NULL}, so deleting a Milestone un-tags its
 * Epics rather than deleting them.
 */
@Transactional
public class MilestoneDeleteBehaviorTest extends BaseTest {

    @Autowired
    private MilestoneService milestoneService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicRepository epicRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private AuditSink auditSink;

    @Test
    void deleteMilestone_nullsTaggedEpicsMilestoneId_leavesEpicsIntact() {
        GitRepo r = makeRepo("https://github.com/test/milestone-delete-behavior.git");
        MilestoneResponse milestone =
                milestoneService.create(new MilestoneRequest("Deletable Milestone", null, r.getId(), null));
        EpicResponse epic = epicService.create(new EpicRequest("Tagged", "D", null, r.getId()), null);
        epicService.assignMilestone(epic.id(), milestone.id());

        milestoneService.delete(milestone.id());
        entityManager.flush();
        entityManager.clear();

        Epic reloaded = epicRepo.findById(epic.id()).orElseThrow();
        assertThat(reloaded.getMilestoneId()).isNull();
        assertThat(reloaded.getTitle()).isEqualTo("Tagged");

        EpicResponse fetched = epicService.get(epic.id());
        assertThat(fetched.milestone()).isNull();
    }

    @Test
    void deleteMilestone_withMultipleTaggedEpics_untagsAllOfThem() {
        GitRepo r = makeRepo("https://github.com/test/milestone-delete-multi.git");
        MilestoneResponse milestone =
                milestoneService.create(new MilestoneRequest("Multi-Tagged Milestone", null, r.getId(), null));
        EpicResponse e1 = epicService.create(new EpicRequest("E1", "D", null, r.getId()), null);
        EpicResponse e2 = epicService.create(new EpicRequest("E2", "D", null, r.getId()), null);
        epicService.assignMilestone(e1.id(), milestone.id());
        epicService.assignMilestone(e2.id(), milestone.id());

        milestoneService.delete(milestone.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(epicRepo.findById(e1.id()).orElseThrow().getMilestoneId()).isNull();
        assertThat(epicRepo.findById(e2.id()).orElseThrow().getMilestoneId()).isNull();
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }
}
