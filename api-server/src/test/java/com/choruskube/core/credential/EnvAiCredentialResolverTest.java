package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.executor.WorkloadAgentCredentials;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvAiCredentialResolverTest {

    @Test
    void attachesTheConfiguredTokenToAClaudeNode() {
        var resolver = new EnvAiCredentialResolver("sk-ant-env-token");
        WorkloadAgentCredentials result = resolver.resolve(UUID.randomUUID(), true);
        assertThat(result.claudeOAuthToken()).isEqualTo("sk-ant-env-token");
        assertThat(result.registryCredentials()).isNull();
    }

    @Test
    void attachesNothingWhenNoTokenIsConfigured() {
        var resolver = new EnvAiCredentialResolver("");
        assertThatCode(() -> resolver.resolve(UUID.randomUUID(), true)).doesNotThrowAnyException();
        assertThat(resolver.resolve(UUID.randomUUID(), true)).isEqualTo(WorkloadAgentCredentials.NONE);
    }

    @Test
    void attachesNoTokenToANodeThatDoesNotRunClaude() {
        var resolver = new EnvAiCredentialResolver("sk-ant-env-token");
        assertThat(resolver.resolve(UUID.randomUUID(), false)).isEqualTo(WorkloadAgentCredentials.NONE);
    }

    @Test
    void neverAttachesARegistryLogin() {
        var resolver = new EnvAiCredentialResolver("sk-ant-env-token");
        assertThat(resolver.resolve(UUID.randomUUID(), true).registryCredentials())
                .isNull();
    }
}
