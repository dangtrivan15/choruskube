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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

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
    void openDesignRun_ignoresTerminalAndRecordedRuns() {
        StoryResponse story = makeStory("spec-open-run");
        StoryResponse quiet = makeStory("spec-open-run-quiet");
        UUID recorded = designRun(story.id(), WorkflowRunStatus.completed);
        service.recordApproved(story.id(), recorded, "v1");
        designRun(quiet.id(), WorkflowRunStatus.failed);
        designRun(quiet.id(), WorkflowRunStatus.cancelled);
        UUID open = designRun(story.id(), WorkflowRunStatus.awaiting_human);

        assertThat(service.openDesignRun(story.id())).map(WorkflowRun::getId).contains(open);
        assertThat(service.openDesignRun(quiet.id())).isEmpty();
        assertThat(service.storiesWithOpenDesignRun(List.of(story.id(), quiet.id())))
                .containsExactly(story.id());
        assertThat(service.openDesignRunIds(List.of(story.id(), quiet.id())))
                .containsExactlyEntriesOf(Map.of(story.id(), open));
    }

    @Test
    void openDesignRun_completedButUnrecorded_countsUntilItsVersionIsRecorded() {
        StoryResponse story = makeStory("spec-open-unrecorded");
        UUID approved = designRun(story.id(), WorkflowRunStatus.completed);

        assertThat(service.openDesignRun(story.id())).map(WorkflowRun::getId).contains(approved);
        assertThat(service.storiesWithOpenDesignRun(List.of(story.id()))).containsExactly(story.id());
        StorySpecService.DesignState pending =
                service.designStates(List.of(story.id())).get(story.id());
        assertThat(pending.specVersion()).isNull();
        assertThat(pending.openDesignRunId()).isEqualTo(approved);
        assertThat(pending.underRevision()).isFalse();

        service.recordApproved(story.id(), approved, "v1");

        assertThat(service.openDesignRun(story.id())).isEmpty();
        assertThat(service.storiesWithOpenDesignRun(List.of(story.id()))).isEmpty();
        StorySpecService.DesignState recorded =
                service.designStates(List.of(story.id())).get(story.id());
        assertThat(recorded.specVersion()).isEqualTo(1);
        assertThat(recorded.openDesignRunId()).isNull();
        assertThat(recorded.underRevision()).isFalse();
    }

    @Test
    void openDesignRun_completedUnrecordedRevision_isUnderRevision() {
        StoryResponse story = makeStory("spec-open-unrecorded-revision");
        service.recordApproved(story.id(), designRun(story.id(), WorkflowRunStatus.completed), "v1");
        UUID revision = designRun(story.id(), WorkflowRunStatus.completed);

        StorySpecService.DesignState state =
                service.designStates(List.of(story.id())).get(story.id());

        assertThat(state.specVersion()).isEqualTo(1);
        assertThat(state.openDesignRunId()).isEqualTo(revision);
        assertThat(state.underRevision()).isTrue();
        assertThat(service.openDesignRunIds(List.of(story.id())))
                .containsExactlyEntriesOf(Map.of(story.id(), revision));
    }

    @Test
    void openDesignRun_softDeletedUnrecordedRun_doesNotCount() {
        StoryResponse story = makeStory("spec-open-soft-deleted");
        UUID abandoned = designRun(story.id(), WorkflowRunStatus.completed);
        WorkflowRun run = runRepo.findById(abandoned).orElseThrow();
        run.setDeletedAt(Instant.now());
        runRepo.saveAndFlush(run);

        assertThat(service.openDesignRun(story.id())).isEmpty();
        assertThat(service.storiesWithOpenDesignRun(List.of(story.id()))).isEmpty();
        assertThat(service.designStates(List.of(story.id())).get(story.id()).openDesignRunId())
                .isNull();
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

    @Test
    void latestDesignRuns_newestNonDeletedRunPerStory() {
        StoryResponse story = makeStory("spec-latest-runs");
        StoryResponse other = makeStory("spec-latest-runs-other");
        StoryResponse neverDesigned = makeStory("spec-latest-runs-none");
        UUID older = designRun(story.id(), WorkflowRunStatus.failed);
        UUID newer = designRun(story.id(), WorkflowRunStatus.cancelled);
        UUID newestButDeleted = designRun(story.id(), WorkflowRunStatus.running);
        UUID otherRun = designRun(other.id(), WorkflowRunStatus.failed);
        age(older, 3);
        age(newer, 2);
        age(otherRun, 1);
        WorkflowRun deleted = runRepo.findById(newestButDeleted).orElseThrow();
        deleted.setDeletedAt(Instant.now());
        runRepo.saveAndFlush(deleted);
        entityManager.clear();

        Map<UUID, WorkflowRun> latest = service.latestDesignRuns(List.of(story.id(), other.id(), neverDesigned.id()));

        assertThat(latest).containsOnlyKeys(story.id(), other.id());
        assertThat(latest.get(story.id()).getId()).isEqualTo(newer);
        assertThat(latest.get(other.id()).getId()).isEqualTo(otherRun);
        assertThat(service.latestDesignRuns(List.of())).isEmpty();
    }

    /** Backdates a run by whole hours, so "newest" does not hang on two inserts' clock ticks. */
    private void age(UUID runId, int hours) {
        jdbc.update(
                "UPDATE workflow_run SET created_at = now() - make_interval(hours => ?) WHERE id = ?", hours, runId);
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
