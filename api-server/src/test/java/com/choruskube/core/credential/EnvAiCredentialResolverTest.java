package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvAiCredentialResolverTest {
    @Test
    void returnsEnvToken_ignoringRunId() {
        var resolver = new EnvAiCredentialResolver("sk-ant-env-token");
        assertThat(resolver.resolveOauthToken(UUID.randomUUID())).isEqualTo("sk-ant-env-token");
    }

    @Test
    void throwsWhenEnvTokenBlank() {
        var resolver = new EnvAiCredentialResolver("");
        assertThatThrownBy(() -> resolver.resolveOauthToken(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }
}
