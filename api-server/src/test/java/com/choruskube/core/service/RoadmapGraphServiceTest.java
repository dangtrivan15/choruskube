package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.RoadmapGraphSnapshot;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class RoadmapGraphServiceTest extends BaseTest {

    @Autowired
    private RoadmapGraphService graphService;

    @Autowired
    private WorkItemDependencyService dependencyService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @Test
    void getGraph_returnsAllStoriesAndTasksUnderEpic() {
        EpicResponse epic = makeEpic("https://github.com/test/graph-nodes.git");
        StoryResponse s1 = makeStory(epic.id(), "Story 1");
        StoryResponse s2 = makeStory(epic.id(), "Story 2");
        List<StoryResponse> stories = List.of(s1, s2);
        List<TaskResponse> tasks = stories.stream()
                .flatMap(s -> List.of(makeTask(s.id(), "T1"), makeTask(s.id(), "T2"), makeTask(s.id(), "T3")).stream())
                .toList();

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        assertThat(snapshot.epic().id()).isEqualTo(epic.id());
        assertThat(snapshot.stories()).extracting(StoryResponse::id).containsExactlyInAnyOrder(s1.id(), s2.id());
        assertThat(snapshot.tasks()).hasSize(6);
        assertThat(snapshot.tasks())
                .extracting(TaskResponse::id)
                .containsExactlyInAnyOrderElementsOf(
                        tasks.stream().map(TaskResponse::id).toList());
        assertThat(snapshot.dependencies()).isEmpty();
        assertThat(snapshot.externalBlockers()).isEmpty();
    }

    @Test
    void getGraph_intraEpicDependency_appearsInDependenciesNotExternalBlockers() {
        EpicResponse epic = makeEpic("https://github.com/test/graph-intra-dep.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse blocking = makeTask(story.id(), "Blocking");
        TaskResponse blocked = makeTask(story.id(), "Blocked");
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        assertThat(snapshot.dependencies()).hasSize(1);
        assertThat(snapshot.dependencies().get(0).blockingItemId()).isEqualTo(blocking.id());
        assertThat(snapshot.dependencies().get(0).blockedItemId()).isEqualTo(blocked.id());
        assertThat(snapshot.externalBlockers()).isEmpty();
    }

    @Test
    void getGraph_blockerInDifferentEpic_appearsInExternalBlockersNotDependencies() {
        EpicResponse epicA = makeEpic("https://github.com/test/graph-external-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/graph-external-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockingInB = makeTask(storyB.id(), "Blocking in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInB.id(), "task", blockedInA.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epicA.id());

        assertThat(snapshot.dependencies()).isEmpty();
        assertThat(snapshot.externalBlockers()).hasSize(1);
        var blocker = snapshot.externalBlockers().get(0);
        assertThat(blocker.itemType()).isEqualTo("task");
        assertThat(blocker.itemId()).isEqualTo(blockingInB.id());
        assertThat(blocker.title()).isEqualTo("Blocking in B");
        assertThat(blocker.epicId()).isEqualTo(epicB.id());
        assertThat(blocker.epicTitle()).isEqualTo(epicB.title());
    }

    @Test
    void getGraph_unknownEpic_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> graphService.getGraph(unknown))
                .isInstanceOf(com.choruskube.core.exception.NotFoundException.class);
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

    private TaskResponse makeTask(UUID storyId, String title) {
        return taskService.create(storyId, new TaskRequest(title, "Desc for " + title));
    }
}
