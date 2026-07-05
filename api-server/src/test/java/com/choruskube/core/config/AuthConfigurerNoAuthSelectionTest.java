package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that {@link NoAuthConfigurer} is the active {@link AuthConfigurer}
 * when {@code auth.enabled} is false (the default in the test profile).
 */
@TestPropertySource(properties = "auth.enabled=false")
public class AuthConfigurerNoAuthSelectionTest extends BaseTest {

    @Autowired
    private AuthConfigurer authConfigurer;

    @Test
    void noAuthConfigurerIsSelectedWhenAuthDisabled() {
        assertThat(authConfigurer).isInstanceOf(NoAuthConfigurer.class);
    }
}
