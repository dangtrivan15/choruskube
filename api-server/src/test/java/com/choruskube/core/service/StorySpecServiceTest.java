package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.StorySpecVersion;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class StorySpecServiceTest extends BaseTest {

    @Autowired
    private StorySpecService service;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Test
    void recordApproved_numbersVersionsPerStory() {
        StoryResponse story = makeStory("spec-numbering");
        StoryResponse other = makeStory("spec-numbering-other");

        StorySpecVersion v1 =
                service.recordApproved(story.id(), designRun(story.id(), WorkflowRunStatus.completed), "one");
        StorySpecVersion v2 =
                service.recordApproved(story.id(), designRun(story.id(), WorkflowRunStatus.completed), "two");
        StorySpecVersion otherV1 =
                service.recordApproved(other.id(), designRun(other.id(), WorkflowRunStatus.completed), "x");

        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(otherV1.getVersion()).isEqualTo(1);
        assertThat(service.current(story.id()))
                .map(StorySpecVersion::getContent)
                .contains("two");
        assertThat(service.versions(story.id()))
                .extracting(StorySpecVersion::getVersion)
                .containsExactly(1, 2);
        assertThat(service.version(story.id(), 1).getContent()).isEqualTo("one");
    }

    @Test
    void recordApproved_sameRunTwice_keepsTheFirstRow() {
        StoryResponse story = makeStory("spec-idempotent");
        UUID runId = designRun(story.id(), WorkflowRunStatus.completed);

        StorySpecVersion first = service.recordApproved(story.id(), runId, "content");
        StorySpecVersion again = service.recordApproved(story.id(), runId, "content");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(service.versions(story.id())).hasSize(1);
    }

    @Test
    void version_unknown_throwsNotFound() {
        StoryResponse story = makeStory("spec-unknown-version");

        assertThatThrownBy(() -> service.version(story.id(), 3)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void openDesignRun_countsOnlyActiveRunsOfThatStory() {
        StoryResponse story = makeStory("spec-open-run");
        StoryResponse quiet = makeStory("spec-open-run-quiet");
        designRun(story.id(), WorkflowRunStatus.completed);
        designRun(quiet.id(), WorkflowRunStatus.failed);
        UUID open = designRun(story.id(), WorkflowRunStatus.awaiting_human);

        assertThat(service.openDesignRun(story.id())).map(WorkflowRun::getId).contains(open);
        assertThat(service.openDesignRun(quiet.id())).isEmpty();
        assertThat(service.storiesWithOpenDesignRun(List.of(story.id(), quiet.id())))
                .containsExactly(story.id());
        assertThat(service.openDesignRunIds(List.of(story.id(), quiet.id())))
                .containsExactlyEntriesOf(Map.of(story.id(), open));
    }

    @Test
    void currentVersions_mapsOnlyStoriesWithASpec() {
        StoryResponse designed = makeStory("spec-current-designed");
        StoryResponse undesigned = makeStory("spec-current-undesigned");
        service.recordApproved(designed.id(), designRun(designed.id(), WorkflowRunStatus.completed), "a");
        service.recordApproved(designed.id(), designRun(designed.id(), WorkflowRunStatus.completed), "b");

        assertThat(service.currentVersions(List.of(designed.id(), undesigned.id())))
                .containsExactlyEntriesOf(Map.of(designed.id(), 2));
        assertThat(service.currentVersions(List.of())).isEmpty();
    }

    private UUID designRun(UUID storyId, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.STORY_DESIGN)
                .orElseThrow()
                .getId());
        run.setStatus(status);
        run.setStoryId(storyId);
        return runRepo.saveAndFlush(run).getId();
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
