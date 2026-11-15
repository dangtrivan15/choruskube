package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.SharedContextTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies that {@link AlwaysAllowAuthorizationStrategy} is the active
 * {@link AuthorizationStrategy} when {@code auth.enabled} is false (the default).
 */
public class AuthorizationStrategyNoAuthSelectionTest extends SharedContextTest {

    @Autowired
    private AuthorizationStrategy strategy;

    @Test
    void alwaysAllowStrategyIsSelectedWhenAuthDisabled() {
        assertThat(strategy).isInstanceOf(AlwaysAllowAuthorizationStrategy.class);
    }
}
