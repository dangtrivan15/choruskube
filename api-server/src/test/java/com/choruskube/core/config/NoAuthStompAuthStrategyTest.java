package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "auth.enabled=false")
public class NoAuthStompAuthStrategyTest extends BaseTest {

    @Autowired
    private StompAuthStrategy strategy;

    @Test
    void connectWithoutAuthHeaderStampsSystemOrgAndUser() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Map<String, Object> sessionAttrs = new HashMap<>();
        accessor.setSessionAttributes(sessionAttrs);

        strategy.authenticate(accessor);

        assertThat(sessionAttrs.get(StompAuthInterceptor.SESSION_ATTR_ORG_ID)).isEqualTo(SingleTenant.ID);
        assertThat(sessionAttrs.get(StompAuthInterceptor.SESSION_ATTR_USER_ID)).isInstanceOf(UUID.class);
    }
}
