package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.RoadmapTimelineResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TimelineEpicSummary;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class RoadmapTimelineServiceTest extends BaseTest {

    @Autowired
    private RoadmapTimelineService timelineService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private AuditSink auditSink;

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
