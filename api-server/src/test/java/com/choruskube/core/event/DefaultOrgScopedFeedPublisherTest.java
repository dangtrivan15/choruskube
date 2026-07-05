package com.choruskube.core.event;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class DefaultOrgScopedFeedPublisherTest {

    private SimpMessagingTemplate messagingTemplate;
    private DefaultOrgScopedFeedPublisher publisher;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        publisher = new DefaultOrgScopedFeedPublisher(messagingTemplate);
    }

    @Test
    void pendingGatesChanged_publishesToOrgFreeTopic() {
        Object payload = new Object();
        publisher.pendingGatesChanged(UUID.randomUUID(), payload);

        verify(messagingTemplate).convertAndSend("/topic/pending-gates", payload);
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void featureProposalsChanged_publishesToOrgFreeTopic_ignoringResource() {
        Object payload = new Object();
        publisher.featureProposalsChanged("feature_proposal", UUID.randomUUID(), payload);

        verify(messagingTemplate).convertAndSend("/topic/feature-proposals", payload);
        verifyNoMoreInteractions(messagingTemplate);
    }
}
