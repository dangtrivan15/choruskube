package com.choruskube.core.event;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class DefaultOrgScopedFeedPublisher implements OrgScopedFeedPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public DefaultOrgScopedFeedPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void pendingGatesChanged(UUID runId, Object payload) {
        messagingTemplate.convertAndSend("/topic/pending-gates", payload);
    }

    @Override
    public void roadmapItemChanged(String resourceType, UUID resourceId, Object payload) {
        messagingTemplate.convertAndSend("/topic/roadmap-items", payload);
    }
}
