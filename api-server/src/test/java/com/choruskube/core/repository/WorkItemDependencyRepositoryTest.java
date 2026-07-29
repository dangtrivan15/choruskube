package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class WorkItemDependencyRepositoryTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private WorkItemDependencyRepository repo;

    @PersistenceContext
    private EntityManager entityManager;

    private WorkItemDependency buildEdge(
            BlockableItemType blockingType, UUID blockingId, BlockableItemType blockedType, UUID blockedId) {
        WorkItemDependency dep = new WorkItemDependency();
        dep.setBlockingItemType(blockingType);
        dep.setBlockingItemId(blockingId);
        dep.setBlockedItemType(blockedType);
        dep.setBlockedItemId(blockedId);
        return dep;
    }

    @Test
    void save_roundTripsAllFields() {
        UUID blockingId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();

        WorkItemDependency saved =
                repo.saveAndFlush(buildEdge(BlockableItemType.task, blockingId, BlockableItemType.task, blockedId));
        entityManager.clear();

        WorkItemDependency fetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(fetched.getBlockingItemType()).isEqualTo(BlockableItemType.task);
        assertThat(fetched.getBlockingItemId()).isEqualTo(blockingId);
        assertThat(fetched.getBlockedItemType()).isEqualTo(BlockableItemType.task);
        assertThat(fetched.getBlockedItemId()).isEqualTo(blockedId);
        assertThat(fetched.getCreatedAt()).isNotNull();
    }

    @Test
    void save_duplicateBlockingBlockedPair_violatesUniqueConstraint() {
        UUID blockingId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();
        repo.saveAndFlush(buildEdge(BlockableItemType.story, blockingId, BlockableItemType.task, blockedId));

        assertThatThrownBy(() -> repo.saveAndFlush(
                        buildEdge(BlockableItemType.story, blockingId, BlockableItemType.task, blockedId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByBlockingItemTypeAndBlockingItemIdAndBlockedItemTypeAndBlockedItemId_findsExactMatch() {
        UUID blockingId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();
        repo.saveAndFlush(buildEdge(BlockableItemType.story, blockingId, BlockableItemType.task, blockedId));

        var found = repo.findByBlockingItemTypeAndBlockingItemIdAndBlockedItemTypeAndBlockedItemId(
                BlockableItemType.story, blockingId, BlockableItemType.task, blockedId);

        assertThat(found).isPresent();
    }

    @Test
    void findByBlockingItemIdInOrBlockedItemIdIn_matchesEitherSide() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        WorkItemDependency edge = repo.saveAndFlush(buildEdge(BlockableItemType.task, a, BlockableItemType.task, b));

        var byBlocking = repo.findByBlockingItemIdInOrBlockedItemIdIn(java.util.List.of(a), java.util.List.of(a));
        var byBlocked = repo.findByBlockingItemIdInOrBlockedItemIdIn(java.util.List.of(b), java.util.List.of(b));
        var noMatch = repo.findByBlockingItemIdInOrBlockedItemIdIn(
                java.util.List.of(unrelated), java.util.List.of(unrelated));

        assertThat(byBlocking).extracting(WorkItemDependency::getId).containsExactly(edge.getId());
        assertThat(byBlocked).extracting(WorkItemDependency::getId).containsExactly(edge.getId());
        assertThat(noMatch).isEmpty();
    }

    @Test
    void findByBlockedItemTypeAndBlockedItemId_returnsOnlyEdgesBlockingThatItem() {
        UUID blockedTaskId = UUID.randomUUID();
        UUID blockedStoryId = UUID.randomUUID();
        WorkItemDependency edgeBlockingTask = repo.saveAndFlush(
                buildEdge(BlockableItemType.task, UUID.randomUUID(), BlockableItemType.task, blockedTaskId));
        repo.saveAndFlush(
                buildEdge(BlockableItemType.task, UUID.randomUUID(), BlockableItemType.story, blockedStoryId));

        var found = repo.findByBlockedItemTypeAndBlockedItemId(BlockableItemType.task, blockedTaskId);

        assertThat(found).extracting(WorkItemDependency::getId).containsExactly(edgeBlockingTask.getId());
    }

    @Test
    void findByBlockedItemTypeAndBlockedItemId_noIncomingEdges_returnsEmptyList() {
        var found = repo.findByBlockedItemTypeAndBlockedItemId(BlockableItemType.task, UUID.randomUUID());

        assertThat(found).isEmpty();
    }
}
