package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.SharedContextTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies that {@link NoAuthConfigurer} is the active {@link AuthConfigurer}
 * when {@code auth.enabled} is false (the default in the test profile).
 */
public class AuthConfigurerNoAuthSelectionTest extends SharedContextTest {

    @Autowired
    private AuthConfigurer authConfigurer;

    @Test
    void noAuthConfigurerIsSelectedWhenAuthDisabled() {
        assertThat(authConfigurer).isInstanceOf(NoAuthConfigurer.class);
    }
}
