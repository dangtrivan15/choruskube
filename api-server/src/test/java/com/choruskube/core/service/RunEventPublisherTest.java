package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.FeatureProposalEvent;
import com.choruskube.core.dto.RunEvent;
import com.choruskube.core.event.OrgScopedFeedPublisher;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class RunEventPublisherTest {

    private SimpMessagingTemplate messagingTemplate;
    private OrgScopedFeedPublisher feedPublisher;
    private RunEventPublisher publisher;

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID NODE_EXEC_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID PROPOSAL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        feedPublisher = mock(OrgScopedFeedPublisher.class);
        publisher = new RunEventPublisher(messagingTemplate, feedPublisher);
    }

    @Test
    void publishRunStatusChanged_cancelled_sendsToRunAndPendingGatesAndFeatureProposals() {
        publisher.publishRunStatusChanged(RUN_ID, "cancelled");

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        verify(feedPublisher).pendingGatesChanged(eq(RUN_ID), any(RunEvent.class));
        verify(feedPublisher).featureProposalsChanged(eq("workflow_run"), eq(RUN_ID), any(FeatureProposalEvent.class));
    }

    @Test
    void publishRunStatusChanged_completed_sendsToRunAndFeatureProposals_notPendingGates() {
        publisher.publishRunStatusChanged(RUN_ID, "completed");

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        // completed does NOT trigger pending-gates
        verify(feedPublisher, never()).pendingGatesChanged(any(), any());
        verify(feedPublisher).featureProposalsChanged(eq("workflow_run"), eq(RUN_ID), any(FeatureProposalEvent.class));
    }

    @Test
    void publishRunStatusChanged_running_sendsOnlyToRunTopic() {
        publisher.publishRunStatusChanged(RUN_ID, "running");

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        verify(feedPublisher, never()).pendingGatesChanged(any(), any());
        verify(feedPublisher, never()).featureProposalsChanged(any(), any(), any());
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
    void publishFeatureProposalChanged_keyedByFeatureProposalResource() {
        publisher.publishFeatureProposalChanged(PROPOSAL_ID, "backlog");

        verify(feedPublisher)
                .featureProposalsChanged(eq("feature_proposal"), eq(PROPOSAL_ID), any(FeatureProposalEvent.class));
    }

    @Test
    void publishLiveChatStatusChanged_sendsToRunAndPendingGates() {
        publisher.publishLiveChatStatusChanged(RUN_ID, NODE_EXEC_ID, SESSION_ID, "active");

        verify(messagingTemplate).convertAndSend(eq("/topic/runs/" + RUN_ID), any(RunEvent.class));
        verify(feedPublisher).pendingGatesChanged(eq(RUN_ID), any(RunEvent.class));
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

    @Test
    void publishLiveChatMessage_sendsOnlyToLiveChatTopic() {
        publisher.publishLiveChatMessage(RUN_ID, SESSION_ID, "user", "hello");

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(messagingTemplate).convertAndSend(topicCaptor.capture(), any(Object.class));
        assertThat(topicCaptor.getValue()).isEqualTo("/topic/live-chat/" + SESSION_ID);
        verifyNoMoreInteractions(messagingTemplate);
        verifyNoInteractions(feedPublisher);
    }
}
