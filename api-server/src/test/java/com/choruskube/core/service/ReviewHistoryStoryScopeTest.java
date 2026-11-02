package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.StoryDesignGraph;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.ReviewHistoryResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ReviewHistoryStoryScopeTest extends BaseTest {

    private static final String STORY_LOOP = "story-spec";
    private static final String TASK_LOOP = "spec";

    @Autowired
    private RunService runService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reviewHistory_storyRun_spansStoryRuns() {
        StoryResponse story = makeStory("history-story");
        StoryResponse otherStory = makeStory("history-other-story");
        // Inserted out of order, and with iterations reversed, so neither insert order nor
        // iteration alone yields the expected run-by-run order.
        WorkflowRun second = storyRun(story.id());
        WorkflowRun first = storyRun(story.id());
        backdate(first, 2);
        backdate(second, 1);
        NodeExecution firstReview = execution(first, STORY_LOOP, 2);
        NodeExecution secondReview = execution(second, STORY_LOOP, 1);
        execution(storyRun(otherStory.id()), STORY_LOOP, 1);
        execution(second, "other-loop", 9);

        List<ReviewHistoryResponse> history = runService.getReviewHistoryInternal(second.getId(), STORY_LOOP);

        assertThat(history)
                .extracting(ReviewHistoryResponse::id)
                .containsExactly(firstReview.getId(), secondReview.getId());
    }

    @Test
    void reviewHistory_storyRun_excludesSoftDeletedRuns() {
        StoryResponse story = makeStory("history-story-deleted");
        WorkflowRun deleted = storyRun(story.id());
        backdate(deleted, 2);
        execution(deleted, STORY_LOOP, 1);
        jdbc.update("UPDATE workflow_run SET deleted_at = now() WHERE id = ?", deleted.getId());
        WorkflowRun current = storyRun(story.id());
        NodeExecution currentReview = execution(current, STORY_LOOP, 1);

        List<ReviewHistoryResponse> history = runService.getReviewHistoryInternal(current.getId(), STORY_LOOP);

        assertThat(history).extracting(ReviewHistoryResponse::id).containsExactly(currentReview.getId());
    }

    @Test
    void reviewHistory_taskRun_unchanged() {
        StoryResponse story = makeStory("history-task");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("Task", "D"));
        WorkflowRun earlier = taskRun(task.id());
        backdate(earlier, 2);
        execution(earlier, TASK_LOOP, 1);
        WorkflowRun run = taskRun(task.id());
        NodeExecution iteration2 = execution(run, TASK_LOOP, 2);
        NodeExecution iteration1 = execution(run, TASK_LOOP, 1);

        List<ReviewHistoryResponse> history = runService.getReviewHistoryInternal(run.getId(), TASK_LOOP);

        assertThat(history)
                .extracting(ReviewHistoryResponse::id)
                .containsExactly(iteration1.getId(), iteration2.getId());
    }

    // ---- helpers ----

    private WorkflowRun storyRun(UUID storyId) {
        WorkflowRun run = run(GraphIds.STORY_DESIGN);
        run.setStoryId(storyId);
        return runRepo.saveAndFlush(run);
    }

    private WorkflowRun taskRun(UUID taskId) {
        WorkflowRun run = run(GraphIds.FEATURE_DEVELOPMENT);
        run.setTaskId(taskId);
        return runRepo.saveAndFlush(run);
    }

    private WorkflowRun run(String graphId) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateRepo
                .findFirstByGraphIdOrderByVersionDesc(graphId)
                .orElseThrow()
                .getId());
        run.setStatus(WorkflowRunStatus.completed);
        return run;
    }

    /** {@code created_at} is not updatable through the entity. */
    private void backdate(WorkflowRun run, int hoursAgo) {
        jdbc.update(
                "UPDATE workflow_run SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(hoursAgo, ChronoUnit.HOURS)),
                run.getId());
    }

    private NodeExecution execution(WorkflowRun run, String loopGroup, int iteration) {
        UUID templateNodeId = templateNodeRepo.findByGraphTemplateId(run.getGraphTemplateId()).stream()
                .findFirst()
                .orElseThrow()
                .getId();
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNodeId);
        exec.setLabel(StoryDesignGraph.REVIEW);
        exec.setLoopGroup(loopGroup);
        exec.setStatus(NodeExecutionStatus.completed);
        exec.setIteration(iteration);
        exec.setCompletedAt(Instant.now());
        return execRepo.saveAndFlush(exec);
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
