package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.config.WorkerAuthFilter;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.GitHubTokenScopeException;
import com.choruskube.core.exception.GlobalExceptionHandler;
import com.choruskube.core.service.WorkerAuthorizer;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The broker route: registered only in multi-tenant mode, and refusing exactly as it always has. */
class WorkerGitHubTokenControllerTest {

    @Nested
    class Registration {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(WorkerAuthorizer.class, () -> mock(WorkerAuthorizer.class))
                .withBean(GitHubCredentialResolver.class, () -> mock(GitHubCredentialResolver.class))
                .withUserConfiguration(WorkerGitHubTokenController.class);

        @Test
        void absentWhenAuthEnabledIsUnset() {
            runner.run(context -> assertThat(context).doesNotHaveBean(WorkerGitHubTokenController.class));
        }

        @Test
        void absentWhenAuthEnabledIsFalse() {
            runner.withPropertyValues("auth.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(WorkerGitHubTokenController.class));
        }

        @Test
        void presentWhenAuthEnabledIsTrue() {
            runner.withPropertyValues("auth.enabled=true")
                    .run(context -> assertThat(context).hasSingleBean(WorkerGitHubTokenController.class));
        }
    }

    @Nested
    class Route {

        private final UUID runId = UUID.randomUUID();
        private WorkerAuthorizer authorizer;
        private GitHubCredentialResolver resolver;
        private MockMvc mvc;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            authorizer = mock(WorkerAuthorizer.class);
            resolver = mock(GitHubCredentialResolver.class);
            ObjectProvider<WorkerAuthorizer> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable(any())).thenReturn(authorizer);
            mvc = MockMvcBuilders.standaloneSetup(new WorkerGitHubTokenController(provider, "unused-token", resolver))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @Test
        void validCredential_returnsTheRunsToken() throws Exception {
            when(resolver.getTokenForRun(runId)).thenReturn("ghs_abc");

            mvc.perform(get("/worker/runs/{r}/github-token", runId)
                            .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").value("ghs_abc"));
        }

        /** An encoded path skips the filter, so the controller sees no credential attribute and must refuse. */
        @Test
        void missingCredential_isUnauthorized() throws Exception {
            mvc.perform(get("/worker/runs/{r}/github-token", runId)).andExpect(status().isUnauthorized());

            verifyNoInteractions(authorizer, resolver);
        }

        @Test
        void blankCredential_isUnauthorized() throws Exception {
            mvc.perform(get("/worker/runs/{r}/github-token", runId)
                            .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "  "))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(authorizer, resolver);
        }

        @Test
        void authorizerRefusal_isForbidden() throws Exception {
            doThrow(new ForbiddenException("Unknown or revoked worker credential"))
                    .when(authorizer)
                    .requireMayActOn(eq("ckw_wrong"), eq(runId));

            mvc.perform(get("/worker/runs/{r}/github-token", runId)
                            .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_wrong"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(resolver);
        }

        @Test
        void resolverFailure_isServiceUnavailable_withoutLeakingDetail() throws Exception {
            when(resolver.getTokenForRun(runId))
                    .thenThrow(new IllegalStateException("No GitHub credential configured"));

            mvc.perform(get("/worker/runs/{r}/github-token", runId)
                            .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").exists())
                    .andExpect(content().string(not(containsString("No GitHub credential configured"))));
        }

        /** A narrowed mint that finds no reachable repository still must not leak the repo name it tried. */
        @Test
        void resolverFailure_namingARepo_isServiceUnavailable_withoutLeakingTheName() throws Exception {
            when(resolver.getTokenForRun(runId))
                    .thenThrow(new GitHubTokenScopeException(
                            "No repository this installation can reach: dropped [acme/secret-repo]"));

            mvc.perform(get("/worker/runs/{r}/github-token", runId)
                            .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").exists())
                    .andExpect(content().string(not(containsString("acme/secret-repo"))));
        }
    }
}
