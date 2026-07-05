package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class StompSubscriptionInterceptorTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    // --- Delegation to the authorizer ---

    @Test
    void authorizerAllows_subscriptionPassesThrough() {
        StompSubscriptionAuthorizer authorizer = mock(StompSubscriptionAuthorizer.class);
        when(authorizer.canSubscribe(any(), any(), any())).thenReturn(true);
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(authorizer);

        Message<?> msg = buildSubscribeMessage("/topic/runs/" + RUN_ID, ORG_ID, USER_ID);
        Message<?> result = interceptor.preSend(msg, null);

        assertThat(result).isNotNull();
        verify(authorizer).canSubscribe("/topic/runs/" + RUN_ID, ORG_ID, USER_ID);
    }

    @Test
    void authorizerDenies_subscriptionRejected() {
        StompSubscriptionAuthorizer authorizer = mock(StompSubscriptionAuthorizer.class);
        when(authorizer.canSubscribe(any(), any(), any())).thenReturn(false);
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(authorizer);

        Message<?> msg = buildSubscribeMessage("/topic/runs/" + RUN_ID, ORG_ID, USER_ID);

        assertThatThrownBy(() -> interceptor.preSend(msg, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Access denied")
                .hasMessageContaining(RUN_ID.toString());
    }

    @Test
    void passesNullUserIdToAuthorizer() {
        StompSubscriptionAuthorizer authorizer = mock(StompSubscriptionAuthorizer.class);
        when(authorizer.canSubscribe(any(), any(), any())).thenReturn(true);
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(authorizer);

        Message<?> msg = buildSubscribeMessage("/topic/runs/" + RUN_ID, ORG_ID, null);
        interceptor.preSend(msg, null);

        verify(authorizer).canSubscribe(eq("/topic/runs/" + RUN_ID), eq(ORG_ID), eq(null));
    }

    // --- Mechanical guards (independent of the authorizer) ---

    @Test
    void subscribeWithoutDestination_rejected() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(allowAll());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put(StompAuthInterceptor.SESSION_ATTR_ORG_ID, ORG_ID);
        accessor.setSessionAttributes(sessionAttrs);
        // No destination set
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("SUBSCRIBE without destination");
    }

    @Test
    void subscribeWithoutSessionOrgId_rejected() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(allowAll());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setDestination("/topic/runs/" + RUN_ID);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("No tenant context");
    }

    @Test
    void subscribeWithoutSessionOrgId_authorizerNotConsulted() {
        StompSubscriptionAuthorizer authorizer = mock(StompSubscriptionAuthorizer.class);
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(authorizer);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setDestination("/topic/runs/" + RUN_ID);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, null)).isInstanceOf(MessageDeliveryException.class);
        verifyNoInteractions(authorizer);
    }

    @Test
    void nonSubscribeCommand_passesThrough() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(allowAll());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setDestination("/topic/runs/" + RUN_ID);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, null);
        assertThat(result).isSameAs(msg);
    }

    @Test
    void nullAccessor_passesThrough() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(allowAll());
        Message<?> msg = MessageBuilder.withPayload(new byte[0]).build();
        Message<?> result = interceptor.preSend(msg, null);
        assertThat(result).isSameAs(msg);
    }

    private StompSubscriptionAuthorizer allowAll() {
        return new AllowAllStompSubscriptionAuthorizer();
    }

    private Message<?> buildSubscribeMessage(String destination, UUID orgId, UUID userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        Map<String, Object> sessionAttrs = new HashMap<>();
        if (orgId != null) {
            sessionAttrs.put(StompAuthInterceptor.SESSION_ATTR_ORG_ID, orgId);
        }
        if (userId != null) {
            sessionAttrs.put(StompAuthInterceptor.SESSION_ATTR_USER_ID, userId);
        }
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
