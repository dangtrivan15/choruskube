package com.choruskube.core.config;

import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Component
public class StompSubscriptionInterceptor implements ChannelInterceptor {

    private final StompSubscriptionAuthorizer authorizer;

    public StompSubscriptionInterceptor(StompSubscriptionAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            throw new MessageDeliveryException("SUBSCRIBE without destination");
        }

        UUID sessionOrgId = getSessionAttr(accessor, StompAuthInterceptor.SESSION_ATTR_ORG_ID);
        if (sessionOrgId == null) {
            throw new MessageDeliveryException("No tenant context — authenticate first");
        }
        UUID sessionUserId = getSessionAttr(accessor, StompAuthInterceptor.SESSION_ATTR_USER_ID);

        if (!authorizer.canSubscribe(destination, sessionOrgId, sessionUserId)) {
            throw new MessageDeliveryException("Access denied: " + destination);
        }
        return message;
    }

    private UUID getSessionAttr(StompHeaderAccessor accessor, String key) {
        Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs == null) {
            return null;
        }
        Object value = sessionAttrs.get(key);
        return value instanceof UUID uuid ? uuid : null;
    }
}
