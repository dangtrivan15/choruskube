package com.choruskube.core.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.RoadmapItemEvent;
import com.choruskube.core.dto.RunEvent;
import com.choruskube.core.event.OrgScopedFeedPublisher;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RunEventPublisherTest {

    private SimpMessagingTemplate messagingTemplate;
    private OrgScopedFeedPublisher feedPublisher;
    private RunEventPublisher publisher;

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID NODE_EXEC_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        feedPublisher = mock(OrgScopedFeedPublisher.class);
        publisher = new RunEventPublisher(messagingTemplate, feedPublisher);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void fireAfterCommit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
    }

    @Test
    void publishRunStatusChanged_cancelled_sendsToRunAndPendingGatesAndRoadmapItems() {
        publisher.publishRunStatusChanged(RUN_ID, "cancelled");

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        verify(feedPublisher).pendingGatesChanged(eq(RUN_ID), any(RunEvent.class));
        verify(feedPublisher).roadmapItemChanged(eq("workflow_run"), eq(RUN_ID), any(RoadmapItemEvent.class));
    }

    @Test
    void publishRunStatusChanged_completed_sendsToRunAndRoadmapItems_notPendingGates() {
        publisher.publishRunStatusChanged(RUN_ID, "completed");

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        // completed does NOT trigger pending-gates
        verify(feedPublisher, never()).pendingGatesChanged(any(), any());
        verify(feedPublisher).roadmapItemChanged(eq("workflow_run"), eq(RUN_ID), any(RoadmapItemEvent.class));
    }

    @Test
    void publishRunStatusChanged_running_sendsOnlyToRunTopic() {
        publisher.publishRunStatusChanged(RUN_ID, "running");

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        verify(feedPublisher, never()).pendingGatesChanged(any(), any());
        verify(feedPublisher, never()).roadmapItemChanged(any(), any(), any());
    }

    @Test
    void publishNodeStatusChanged_awaitingHuman_sendsToRunAndPendingGates() {
        publisher.publishNodeStatusChanged(RUN_ID, NODE_EXEC_ID, "awaiting_human");

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        verify(feedPublisher).pendingGatesChanged(eq(RUN_ID), any(RunEvent.class));
    }

    @Test
    void publishNodeStatusChanged_running_sendsOnlyToRunTopic() {
        publisher.publishNodeStatusChanged(RUN_ID, NODE_EXEC_ID, "running");

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        verify(feedPublisher, never()).pendingGatesChanged(any(), any());
    }

    @Test
    void publishRoadmapItemChanged_keyedByItemType() {
        publisher.publishRoadmapItemChanged("epic", TASK_ID, "backlog");

        verify(feedPublisher).roadmapItemChanged(eq("epic"), eq(TASK_ID), any(RoadmapItemEvent.class));
    }

    @Test
    void publishRoadmapItemChanged_task_keyedByTaskResource() {
        publisher.publishRoadmapItemChanged("task", TASK_ID, "in_progress");

        verify(feedPublisher).roadmapItemChanged(eq("task"), eq(TASK_ID), any(RoadmapItemEvent.class));
    }

    @Test
    void publishRoadmapItemChanged_story_keyedByStoryResource() {
        publisher.publishRoadmapItemChanged("story", TASK_ID, "backlog");

        verify(feedPublisher).roadmapItemChanged(eq("story"), eq(TASK_ID), any(RoadmapItemEvent.class));
    }

    @Test
    void publishDependencyChanged_keyedByBlockedItemType() {
        UUID edgeId = UUID.randomUUID();
        UUID blockingTaskId = UUID.randomUUID();
        UUID blockedTaskId = TASK_ID;
        DependencyEdgeResponse edge =
                new DependencyEdgeResponse(edgeId, "task", blockingTaskId, "task", blockedTaskId, null);

        publisher.publishDependencyChanged(edge, "created");

        // Scoping/routing key must be the BLOCKED item's own type/id, never a "dependency" type.
        verify(feedPublisher).roadmapItemChanged(eq("task"), eq(blockedTaskId), any(RoadmapItemEvent.class));
    }

    @Test
    void publishDependencyChanged_keyedByBlockedItemType_story() {
        UUID edgeId = UUID.randomUUID();
        UUID blockingTaskId = UUID.randomUUID();
        UUID blockedStoryId = UUID.randomUUID();
        DependencyEdgeResponse edge =
                new DependencyEdgeResponse(edgeId, "task", blockingTaskId, "story", blockedStoryId, null);

        publisher.publishDependencyChanged(edge, "deleted");

        verify(feedPublisher).roadmapItemChanged(eq("story"), eq(blockedStoryId), any(RoadmapItemEvent.class));
        verify(feedPublisher, never()).roadmapItemChanged(eq("dependency"), any(), any());
    }

    @Test
    void publishRoadmapItemChanged_defersBroadcastUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishRoadmapItemChanged("story", TASK_ID, "backlog");
        // A refetch on receipt would read pre-commit state; the broadcast must not go out yet.
        verify(feedPublisher, never()).roadmapItemChanged(any(), any(), any());

        fireAfterCommit();
        verify(feedPublisher).roadmapItemChanged(eq("story"), eq(TASK_ID), any(RoadmapItemEvent.class));
    }

    @Test
    void publishDependencyChanged_defersBroadcastUntilAfterCommit() {
        UUID blockedStoryId = UUID.randomUUID();
        DependencyEdgeResponse edge =
                new DependencyEdgeResponse(UUID.randomUUID(), "task", UUID.randomUUID(), "story", blockedStoryId, null);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishDependencyChanged(edge, "created");
        verify(feedPublisher, never()).roadmapItemChanged(any(), any(), any());

        fireAfterCommit();
        verify(feedPublisher).roadmapItemChanged(eq("story"), eq(blockedStoryId), any(RoadmapItemEvent.class));
    }

    @Test
    void publishPullRequestCreated_sendsToRunAndPendingGates() {
        publisher.publishPullRequestCreated(RUN_ID);

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        verify(feedPublisher).pendingGatesChanged(eq(RUN_ID), any(RunEvent.class));
    }

    @Test
    void publishNodeLogsUpdated_sendsOnlyToRunTopic() {
        publisher.publishNodeLogsUpdated(RUN_ID, NODE_EXEC_ID);

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        verifyNoMoreInteractions(messagingTemplate);
        verifyNoInteractions(feedPublisher);
    }
}
