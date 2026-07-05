package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifies the public default image refs baked into {@code application.properties}
 * resolve when the env overrides ({@code DEFAULT_AGENT_IMAGE} /
 * {@code CHORUSKUBE_REPO_AGENT_IMAGE}) are absent — the OSS
 * {@code docker compose up} first-run case.
 *
 * <p>Loads ONLY {@code application.properties} into a hermetic environment with
 * the OS/system property sources removed, so neither the test-profile override
 * in {@code application-test.properties} nor a developer's exported env var can
 * mask the defaults under test.
 */
class AgentImageDefaultsTest {

    private static StandardEnvironment hermeticEnvWithAppProperties() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        // Strip OS env + JVM system properties so the test is deterministic
        // regardless of what the CI/dev shell exports.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        List<PropertySource<?>> sources = new PropertiesPropertySourceLoader()
                .load("application", new ClassPathResource("application.properties"));
        sources.forEach(env.getPropertySources()::addLast);
        return env;
    }

    @Test
    void defaultAgentImageResolvesPublicDefaultWhenEnvUnset() throws Exception {
        StandardEnvironment env = hermeticEnvWithAppProperties();
        assertThat(env.resolveRequiredPlaceholders("${executor.default-agent-image}"))
                .isEqualTo("registry.choruskube.com/claude-code:latest");
    }

    @Test
    void repoAgentImageResolvesPublicDevDefaultWhenEnvUnset() throws Exception {
        StandardEnvironment env = hermeticEnvWithAppProperties();
        assertThat(env.resolveRequiredPlaceholders("${choruskube.repo.agent-image}"))
                .isEqualTo("registry.choruskube.com/choruskube-dev:latest");
    }
}
