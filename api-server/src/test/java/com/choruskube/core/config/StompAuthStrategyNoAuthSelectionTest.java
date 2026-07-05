package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that {@link NoAuthStompAuthStrategy} is the active {@link StompAuthStrategy}
 * when {@code auth.enabled} is false (the default in the test profile).
 */
@TestPropertySource(properties = "auth.enabled=false")
public class StompAuthStrategyNoAuthSelectionTest extends BaseTest {

    @Autowired
    private StompAuthStrategy strategy;

    @Test
    void noAuthStompAuthStrategyIsSelectedWhenAuthDisabled() {
        assertThat(strategy).isInstanceOf(NoAuthStompAuthStrategy.class);
    }
}
