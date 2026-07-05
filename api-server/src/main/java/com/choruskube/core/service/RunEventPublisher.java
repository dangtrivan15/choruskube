package com.choruskube.core.service;

import com.choruskube.core.dto.FeatureProposalEvent;
import com.choruskube.core.dto.RunEvent;
import com.choruskube.core.event.OrgScopedFeedPublisher;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class RunEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final OrgScopedFeedPublisher feedPublisher;

    public RunEventPublisher(SimpMessagingTemplate messagingTemplate, OrgScopedFeedPublisher feedPublisher) {
        this.messagingTemplate = messagingTemplate;
        this.feedPublisher = feedPublisher;
    }

    public void publishRunStatusChanged(UUID runId, String status) {
        RunEvent event = new RunEvent("run_status_changed", runId, null, status);
        messagingTemplate.convertAndSend("/topic/runs/" + runId, event);

        // Notify the pending-gates dashboard when a run is cancelled or fails
        if ("cancelled".equals(status) || "failed".equals(status)) {
            feedPublisher.pendingGatesChanged(runId, event);
        }

        // Notify the feature-proposals dashboard when a run reaches a terminal status,
        // so linked proposals reflect the latest workflow run state
        if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            FeatureProposalEvent bridgeEvent = new FeatureProposalEvent("run_status_changed", null, status);
            feedPublisher.featureProposalsChanged("workflow_run", runId, bridgeEvent);
        }
    }

    public void publishFeatureProposalChanged(UUID proposalId, String status) {
        FeatureProposalEvent event = new FeatureProposalEvent("proposal_changed", proposalId, status);
        feedPublisher.featureProposalsChanged("feature_proposal", proposalId, event);
    }

    public void publishNodeStatusChanged(UUID runId, UUID nodeExecutionId, String status) {
        RunEvent event = new RunEvent("node_status_changed", runId, nodeExecutionId, status);
        messagingTemplate.convertAndSend("/topic/runs/" + runId, event);

        // Notify the pending-gates dashboard on relevant status changes
        if ("awaiting_human".equals(status)
                || "live_chat".equals(status)
                || "completed".equals(status)
                || "failed".equals(status)
                || "signaled".equals(status)) {
            feedPublisher.pendingGatesChanged(runId, event);
        }
    }

    public void publishNodeLogsUpdated(UUID runId, UUID nodeExecutionId) {
        RunEvent event = new RunEvent("node_logs_updated", runId, nodeExecutionId, null);
        messagingTemplate.convertAndSend("/topic/runs/" + runId, event);
    }

    public void publishPullRequestCreated(UUID runId) {
        RunEvent event = new RunEvent("run_pull_request_created", runId, null, null);
        messagingTemplate.convertAndSend("/topic/runs/" + runId, event);
        feedPublisher.pendingGatesChanged(runId, event);
    }

    public void publishLiveChatStatusChanged(UUID runId, UUID nodeExecutionId, UUID sessionId, String status) {
        RunEvent event = new RunEvent("live_chat_status_changed", runId, nodeExecutionId, status);
        messagingTemplate.convertAndSend("/topic/runs/" + runId, event);
        feedPublisher.pendingGatesChanged(runId, event);
    }

    public void publishLiveChatMessage(UUID runId, UUID sessionId, String role, String content) {
        com.choruskube.core.dto.LiveChatMessageEvent event =
                new com.choruskube.core.dto.LiveChatMessageEvent("live_chat_message", sessionId, role, content);
        messagingTemplate.convertAndSend("/topic/live-chat/" + sessionId, event);
    }
}
