package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class StompAuthInterceptorTest {

    private StompAuthStrategy strategy;
    private StompAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        strategy = mock(StompAuthStrategy.class);
        interceptor = new StompAuthInterceptor(strategy);
    }

    @Test
    void connectFrameDelegatesToStrategy() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(new HashMap<>());
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
        verify(strategy).authenticate(argThat(a -> StompCommand.CONNECT.equals(a.getCommand())));
    }

    @Test
    void subscribeFramePassesThroughWithoutCallingStrategy() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setDestination("/topic/runs/123");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
        verifyNoInteractions(strategy);
    }

    @Test
    void nullAccessorPassesThroughWithoutCallingStrategy() {
        // A plain message without STOMP headers
        Message<?> message = MessageBuilder.withPayload(new byte[0]).build();

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
        verifyNoInteractions(strategy);
    }
}
