package com.choruskube.core.service;

import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class GitRepoEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public GitRepoEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publishOrgProvisioningStatus(UUID orgId, String status, String namespace) {
        messagingTemplate.convertAndSend(
                "/topic/organizations/" + orgId + "/provisioning-status",
                Map.of(
                        "orgId",
                        orgId.toString(),
                        "provisioningStatus",
                        status,
                        "namespace",
                        namespace != null ? namespace : ""));
    }
}
