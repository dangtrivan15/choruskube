package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.dto.BlockingChainResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage of {@link DefaultBlockingChainService} in isolation (Mockito, no Spring context,
 * no database) — mirrors {@link DefaultTaskServiceTest}'s use of TestContainers for the *service*
 * layer proper, but this class has no repository of its own beyond {@link
 * WorkItemDependencyRepository}, so a plain Mockito unit test (like {@link
 * GraphTemplateServiceTest}) is the right shape here.
 */
@ExtendWith(MockitoExtension.class)
class DefaultBlockingChainServiceTest {

    @Mock
    private StoryService storyService;

    @Mock
    private TaskService taskService;

    @Mock
    private WorkItemDependencyRepository dependencyRepo;

    private DefaultBlockingChainService service;

    @BeforeEach
    void setUp() {
        service = new DefaultBlockingChainService(storyService, taskService, dependencyRepo);
    }

    private static TaskResponse task(UUID id, String title, String status) {
        return new TaskResponse(
                id,
                UUID.randomUUID(),
                title,
                "desc",
                status,
                null,
                List.of(),
                null,
                null,
                null,
                List.of(),
                0,
                Instant.now(),
                Instant.now(),
                "medium");
    }

    private static WorkItemDependency edge(UUID blockingId, UUID blockedId) {
        WorkItemDependency dep = new WorkItemDependency();
        dep.setBlockingItemType(BlockableItemType.task);
        dep.setBlockingItemId(blockingId);
        dep.setBlockedItemType(BlockableItemType.task);
        dep.setBlockedItemId(blockedId);
        return dep;
    }

    @Test
    void getChain_directBlocker_resolvesTitleAndStatusThroughTaskServiceNotRepository() {
        UUID root = UUID.randomUUID();
        UUID blockerA = UUID.randomUUID();
        when(taskService.get(root)).thenReturn(task(root, "Root", "backlog"));
        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(root), Set.of(root)))
                .thenReturn(List.of(edge(blockerA, root)));
        when(taskService.get(blockerA)).thenReturn(task(blockerA, "Blocker A", "backlog"));
        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(blockerA), Set.of(blockerA)))
                .thenReturn(List.of());

        BlockingChainResponse response = service.getChain(BlockableItemType.task, root);

        assertThat(response.itemId()).isEqualTo(root);
        assertThat(response.title()).isEqualTo("Root");
        assertThat(response.blockedBy()).hasSize(1);
        assertThat(response.blockedBy().get(0).itemId()).isEqualTo(blockerA);
        assertThat(response.blockedBy().get(0).title()).isEqualTo("Blocker A");
        verify(taskService).get(root);
        verify(taskService).get(blockerA);
    }

    @Test
    void getChain_unknownRoot_notFoundPropagates() {
        UUID root = UUID.randomUUID();
        when(taskService.get(root)).thenThrow(new NotFoundException("Task not found: " + root));

        assertThatThrownBy(() -> service.getChain(BlockableItemType.task, root)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(dependencyRepo);
    }

    @Test
    void getChain_notFoundOnNonRootBlocker_isSwallowedAndOmittedFromChain() {
        // root <- blockerA <- blockerB, where blockerB has since been deleted (dangling edge, no
        // DB-level FK) — the walk must still resolve, just without blockerB.
        UUID root = UUID.randomUUID();
        UUID blockerA = UUID.randomUUID();
        UUID blockerB = UUID.randomUUID();
        when(taskService.get(root)).thenReturn(task(root, "Root", "backlog"));
        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(root), Set.of(root)))
                .thenReturn(List.of(edge(blockerA, root)));
        when(taskService.get(blockerA)).thenReturn(task(blockerA, "Blocker A", "backlog"));
        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(blockerA), Set.of(blockerA)))
                .thenReturn(List.of(edge(blockerB, blockerA)));
        when(taskService.get(blockerB)).thenThrow(new NotFoundException("Task not found: " + blockerB));

        BlockingChainResponse response = service.getChain(BlockableItemType.task, root);

        assertThat(response.blockedBy()).hasSize(1);
        assertThat(response.blockedBy().get(0).itemId()).isEqualTo(blockerA);
        assertThat(response.blockedBy().get(0).blockedBy()).isEmpty();
    }

    @Test
    void getChain_forbiddenOnRoot_propagates() {
        UUID root = UUID.randomUUID();
        when(taskService.get(root)).thenThrow(new ForbiddenException("outside org"));

        assertThatThrownBy(() -> service.getChain(BlockableItemType.task, root)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getChain_itemRootBlocksRatherThanIsBlockedBy_neverAppearsInChain() {
        // findByBlockingItemIdInOrBlockedItemIdIn is bidirectional by design:
        // it also returns rows where `root` is the *blockingItemId* — i.e. items root itself
        // blocks, not items blocking root. Those rows must be discarded rather than followed, or
        // the walk would present something root blocks as though it were a blocker of root.
        UUID root = UUID.randomUUID();
        UUID itemRootBlocks = UUID.randomUUID();
        when(taskService.get(root)).thenReturn(task(root, "Root", "backlog"));
        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(root), Set.of(root)))
                .thenReturn(List.of(edge(root, itemRootBlocks)));

        BlockingChainResponse response = service.getChain(BlockableItemType.task, root);

        assertThat(response.blockedBy()).isEmpty();
        verify(taskService, never()).get(itemRootBlocks);
    }

    @Test
    void getChain_forbiddenOnNonRootBlocker_propagates() {
        UUID root = UUID.randomUUID();
        UUID blockerA = UUID.randomUUID();
        when(taskService.get(root)).thenReturn(task(root, "Root", "backlog"));
        when(dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(Set.of(root), Set.of(root)))
                .thenReturn(List.of(edge(blockerA, root)));
        when(taskService.get(blockerA)).thenThrow(new ForbiddenException("outside org"));

        assertThatThrownBy(() -> service.getChain(BlockableItemType.task, root)).isInstanceOf(ForbiddenException.class);
    }
}
