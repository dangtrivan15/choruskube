package com.choruskube.core.service;

import com.choruskube.core.dto.AutopilotStatusResponse;
import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.RoadmapItemEvent;
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

        // Notify the roadmap dashboard when a run reaches a terminal status,
        // so linked Tasks reflect the latest workflow run state
        if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            RoadmapItemEvent bridgeEvent = new RoadmapItemEvent("run_status_changed", null, status);
            feedPublisher.roadmapItemChanged("workflow_run", runId, bridgeEvent);
        }
    }

    /**
     * Publishes a roadmap-item change for an Epic, Story, or Task. {@code itemType} is one of
     * "epic"/"story"/"task" and doubles as the resource-type key the feed is scoped
     * by; the payload's own {@code itemType} field is {@code itemType + "_changed"}.
     */
    public void publishRoadmapItemChanged(String itemType, UUID itemId, String status) {
        RoadmapItemEvent event = new RoadmapItemEvent(itemType + "_changed", itemId, status);
        feedPublisher.roadmapItemChanged(itemType, itemId, event);
    }

    /**
     * Publishes a "blocking" dependency-edge change (create or delete; {@code status} is e.g.
     * {@code "created"}/{@code "deleted"}, mirroring the literal {@code "deleted"} status {@link
     * #publishRoadmapItemChanged} is passed on Epic/Story/Task delete). The payload's own {@code
     * itemType} is {@code "dependency_changed"}, but — unlike {@link #publishRoadmapItemChanged},
     * which uses its own {@code itemType} argument as both the payload field and the scoping key —
     * a dependency edge has no resource type of its own to scope by. The scoping/routing key is
     * therefore the BLOCKED item's existing type/id: a caller must already be authorized to read
     * the blocked item to see that it just became (un)blocked.
     */
    public void publishDependencyChanged(DependencyEdgeResponse edge, String status) {
        RoadmapItemEvent event = new RoadmapItemEvent("dependency_changed", edge.id(), status);
        feedPublisher.roadmapItemChanged(edge.blockedItemType(), edge.blockedItemId(), event);
    }

    /**
     * Publishes the Autopilot's new status. Delegates to the org-scoping seam rather than calling
     * {@code convertAndSend} directly — publishing straight to {@code /topic/autopilot} would work
     * in single-tenant core while broadcasting one org's Autopilot state to every subscriber
     * downstream, and the UI subscribes to the resolved destination.
     *
     * <p>The whole status is the payload, not a bare signal: this is published from inside the
     * tick's transaction, so a subscriber that responded by refetching could read the state the
     * tick has not committed yet.
     */
    public void publishAutopilotChanged(UUID autopilotId, AutopilotStatusResponse status) {
        feedPublisher.autopilotChanged(autopilotId, status);
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
