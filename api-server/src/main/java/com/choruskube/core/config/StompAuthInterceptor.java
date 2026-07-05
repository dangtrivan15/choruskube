package com.choruskube.core.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Validates the STOMP CONNECT frame and stores resolved tenant identity
 * ({@code orgId}, {@code userId}) in STOMP session attributes. Always registered.
 *
 * <p>The actual authentication logic lives in the injected {@link StompAuthStrategy}.
 * Spring selects the implementation at startup based on {@code auth.enabled}:
 * <ul>
 *   <li>the auth-enabled STOMP auth strategy when {@code auth.enabled=true}</li>
 *   <li>{@link NoAuthStompAuthStrategy} when {@code auth.enabled} is unset or false</li>
 * </ul>
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    public static final String SESSION_ATTR_ORG_ID = "orgId";
    public static final String SESSION_ATTR_USER_ID = "userId";

    private final StompAuthStrategy strategy;

    public StompAuthInterceptor(StompAuthStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }
        strategy.authenticate(accessor);
        return message;
    }
}
