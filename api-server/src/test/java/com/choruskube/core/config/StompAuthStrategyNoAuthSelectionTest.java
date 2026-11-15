package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.SharedContextTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies that {@link NoAuthStompAuthStrategy} is the active {@link StompAuthStrategy}
 * when {@code auth.enabled} is false (the default in the test profile).
 */
public class StompAuthStrategyNoAuthSelectionTest extends SharedContextTest {

    @Autowired
    private StompAuthStrategy strategy;

    @Test
    void noAuthStompAuthStrategyIsSelectedWhenAuthDisabled() {
        assertThat(strategy).isInstanceOf(NoAuthStompAuthStrategy.class);
    }
}
