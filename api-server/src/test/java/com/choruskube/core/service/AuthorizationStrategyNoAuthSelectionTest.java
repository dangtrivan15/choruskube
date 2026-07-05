package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that {@link AlwaysAllowAuthorizationStrategy} is the active
 * {@link AuthorizationStrategy} when {@code auth.enabled} is false (the default).
 */
@TestPropertySource(properties = "auth.enabled=false")
public class AuthorizationStrategyNoAuthSelectionTest extends BaseTest {

    @Autowired
    private AuthorizationStrategy strategy;

    @Test
    void alwaysAllowStrategyIsSelectedWhenAuthDisabled() {
        assertThat(strategy).isInstanceOf(AlwaysAllowAuthorizationStrategy.class);
    }
}
