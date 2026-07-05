package com.choruskube.core.config;

import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

/**
 * Strategy for authenticating a STOMP CONNECT frame and populating the
 * resulting session attributes ({@link StompAuthInterceptor#SESSION_ATTR_ORG_ID},
 * {@link StompAuthInterceptor#SESSION_ATTR_USER_ID}).
 *
 * <p>Selected at startup by Spring {@code @ConditionalOnProperty} keyed on
 * {@code auth.enabled}:
 * <ul>
 *   <li>the auth-enabled STOMP auth strategy when {@code auth.enabled=true}</li>
 *   <li>{@link NoAuthStompAuthStrategy} when {@code auth.enabled} is unset or false (default)</li>
 * </ul>
 *
 * <p>On success, impls write the resolved org id and user id to
 * {@link StompHeaderAccessor#getSessionAttributes()} (no-op if the map is unexpectedly
 * null — which is unreachable for a properly-initialized CONNECT frame in Spring's STOMP
 * messaging stack). On any auth failure they detect, impls throw
 * {@link MessageDeliveryException} (which STOMP surfaces as a CONNECT failure).
 */
public interface StompAuthStrategy {

    /**
     * Authenticate the CONNECT frame and populate session attributes.
     *
     * <p>Writes the resolved org id and user id to
     * {@link StompHeaderAccessor#getSessionAttributes()} on success (no-op if the
     * map is unexpectedly null — unreachable for a properly-initialized CONNECT frame).
     *
     * @param accessor the STOMP header accessor for the CONNECT frame
     * @throws MessageDeliveryException if authentication fails
     */
    void authenticate(StompHeaderAccessor accessor);
}
