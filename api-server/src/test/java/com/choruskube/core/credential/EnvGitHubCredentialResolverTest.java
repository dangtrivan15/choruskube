package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.service.GitHubAppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvGitHubCredentialResolverTest {

    private static final String NO_APP = "";

    @Test
    void returnsThePat_whenOnlyAPatIsConfigured() {
        assertThat(resolver("ghp_configured").getTokenForRun(UUID.randomUUID())).isEqualTo("ghp_configured");
    }

    @Test
    void throwsWhenNothingIsConfigured() {
        assertThatThrownBy(() -> resolver("").getTokenForRun(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No GitHub credential configured");
    }

    private static EnvGitHubCredentialResolver resolver(String pat) {
        return new EnvGitHubCredentialResolver(
                pat, NO_APP, NO_APP, NO_APP, new GitHubAppService(new ObjectMapper(), "https://api.github.com"));
    }
}
