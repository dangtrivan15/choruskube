package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class WorkItemDependencyServiceTest extends BaseTest {

    @Autowired
    private WorkItemDependencyService service;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkItemDependencyRepository dependencyRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private AuthorizationService authService;

    @Test
    void create_selfLoop_throwsBadRequest() {
        TaskResponse task = makeTask("https://github.com/test/dep-self-loop.git");

        assertThatThrownBy(() -> service.create(new CreateDependencyRequest("task", task.id(), "task", task.id())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_duplicateEdge_throwsBadRequest() {
        TaskResponse a = makeTask("https://github.com/test/dep-dup-a.git");
        TaskResponse b = makeTask("https://github.com/test/dep-dup-b.git");
        service.create(new CreateDependencyRequest("task", a.id(), "task", b.id()));

        assertThatThrownBy(() -> service.create(new CreateDependencyRequest("task", a.id(), "task", b.id())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_malformedItemType_throwsBadRequest() {
        TaskResponse a = makeTask("https://github.com/test/dep-malformed.git");

        assertThatThrownBy(
                        () -> service.create(new CreateDependencyRequest("bogus", UUID.randomUUID(), "task", a.id())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_nonexistentBlockingItem_throwsNotFound() {
        TaskResponse blocked = makeTask("https://github.com/test/dep-missing-blocking.git");

        assertThatThrownBy(() ->
                        service.create(new CreateDependencyRequest("task", UUID.randomUUID(), "task", blocked.id())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_nonexistentBlockedItem_throwsNotFound() {
        TaskResponse blocking = makeTask("https://github.com/test/dep-missing-blocked.git");

        assertThatThrownBy(() ->
                        service.create(new CreateDependencyRequest("task", blocking.id(), "task", UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_validEdge_persistsAndInvokesPublisher() {
        TaskResponse blocking = makeTask("https://github.com/test/dep-valid-a.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-valid-b.git");

        DependencyEdgeResponse edge =
                service.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        assertThat(edge.blockingItemId()).isEqualTo(blocking.id());
        assertThat(edge.blockedItemId()).isEqualTo(blocked.id());
        assertThat(dependencyRepo.findById(edge.id())).isPresent();
        verify(runEventPublisher).publishDependencyChanged(any(DependencyEdgeResponse.class), eq("created"));
    }

    @Test
    void delete_removesRowAndInvokesPublisher() {
        TaskResponse blocking = makeTask("https://github.com/test/dep-delete-a.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-delete-b.git");
        DependencyEdgeResponse edge =
                service.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        service.delete(edge.id());

        assertThat(dependencyRepo.findById(edge.id())).isEmpty();
        verify(runEventPublisher).publishDependencyChanged(any(DependencyEdgeResponse.class), eq("deleted"));
    }

    @Test
    void delete_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.delete(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_whenBlockingItemFailsOrgCheck_doesNotPersistOrPublish() {
        TaskResponse blocking = makeTask("https://github.com/test/dep-create-org-check-blocking.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-create-org-check-blocked.git");

        // Simulate an org mismatch specifically on the blocking item's checkOrgAccess call — a
        // regression that dropped this first call (e.g. reordering to check only the blocked item,
        // or collapsing both into a single assertSameOrg(blocking, blocked) call) would let this
        // pass since assertSameOrg never consults the mocked authService.checkOrgAccess at all.
        doThrow(new ForbiddenException("org mismatch")).when(authService).checkOrgAccess(eq("task"), eq(blocking.id()));

        assertThatThrownBy(
                        () -> service.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id())))
                .isInstanceOf(ForbiddenException.class);

        assertThat(dependencyRepo.findByBlockingItemTypeAndBlockingItemIdAndBlockedItemTypeAndBlockedItemId(
                        BlockableItemType.task, blocking.id(), BlockableItemType.task, blocked.id()))
                .isEmpty();
        verify(runEventPublisher, never()).publishDependencyChanged(any(), eq("created"));
    }

    @Test
    void create_whenBlockedItemFailsOrgCheck_doesNotPersistOrPublish() {
        TaskResponse blocking = makeTask("https://github.com/test/dep-create-org-check-blocking-2.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-create-org-check-blocked-2.git");

        // Mirror of the above, but for the second checkOrgAccess call — proves create() doesn't
        // short-circuit after the blocking item's check and skip the blocked item's.
        doThrow(new ForbiddenException("org mismatch")).when(authService).checkOrgAccess(eq("task"), eq(blocked.id()));

        assertThatThrownBy(
                        () -> service.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id())))
                .isInstanceOf(ForbiddenException.class);

        assertThat(dependencyRepo.findByBlockingItemTypeAndBlockingItemIdAndBlockedItemTypeAndBlockedItemId(
                        BlockableItemType.task, blocking.id(), BlockableItemType.task, blocked.id()))
                .isEmpty();
        verify(runEventPublisher, never()).publishDependencyChanged(any(), eq("created"));
    }

    @Test
    void delete_whenBlockingItemFailsOrgCheck_doesNotDeleteOrPublish() {
        TaskResponse blocking = makeTask("https://github.com/test/dep-delete-org-check-blocking.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-delete-org-check-blocked.git");
        DependencyEdgeResponse edge =
                service.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        // Mirror of delete_whenBlockedItemFailsOrgCheck_doesNotDeleteOrPublish, but for the
        // blocking item's own check failing instead of the blocked item's — the existing test
        // alone wouldn't catch a regression that dropped the blocking-item checkOrgAccess call.
        doThrow(new ForbiddenException("org mismatch")).when(authService).checkOrgAccess(eq("task"), eq(blocking.id()));

        assertThatThrownBy(() -> service.delete(edge.id())).isInstanceOf(ForbiddenException.class);

        assertThat(dependencyRepo.findById(edge.id())).isPresent();
        verify(runEventPublisher, never()).publishDependencyChanged(any(), eq("deleted"));
    }

    @Test
    void delete_whenBlockedItemFailsOrgCheck_doesNotDeleteOrPublish() {
        TaskResponse blocking = makeTask("https://github.com/test/dep-org-check-a.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-org-check-b.git");
        DependencyEdgeResponse edge =
                service.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        // Simulate an org mismatch specifically on the blocked item's own checkOrgAccess call —
        // the delete path must reject even though the blocking item's check would pass.
        doThrow(new ForbiddenException("org mismatch")).when(authService).checkOrgAccess(eq("task"), eq(blocked.id()));

        assertThatThrownBy(() -> service.delete(edge.id())).isInstanceOf(ForbiddenException.class);

        assertThat(dependencyRepo.findById(edge.id())).isPresent();
        verify(runEventPublisher, never()).publishDependencyChanged(any(), eq("deleted"));
    }

    @Test
    void create_epicBlocksEpic_isAccepted() {
        EpicResponse blocking = makeEpic("https://github.com/test/dep-epic-blocking.git");
        EpicResponse blocked = makeEpic("https://github.com/test/dep-epic-blocked.git");

        DependencyEdgeResponse edge =
                service.create(new CreateDependencyRequest("epic", blocking.id(), "epic", blocked.id()));

        assertThat(edge.blockingItemType()).isEqualTo("epic");
        assertThat(edge.blockedItemType()).isEqualTo("epic");
        assertThat(dependencyRepo.findById(edge.id())).isPresent();
    }

    @Test
    void create_unknownEpic_throwsNotFound() {
        TaskResponse blocked = makeTask("https://github.com/test/dep-epic-missing.git");

        assertThatThrownBy(() ->
                        service.create(new CreateDependencyRequest("epic", UUID.randomUUID(), "task", blocked.id())))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("epic not found");
    }

    private EpicResponse makeEpic(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }

    private TaskResponse makeTask(String url) {
        EpicResponse epic = makeEpic(url);
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
        return taskService.create(story.id(), new TaskRequest("Task", "Task desc"));
    }
}
