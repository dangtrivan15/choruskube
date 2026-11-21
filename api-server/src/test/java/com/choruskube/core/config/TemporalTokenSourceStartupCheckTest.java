package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.credential.ControlPlaneTemporalTokens;
import com.choruskube.core.credential.TemporalBearerToken;
import com.choruskube.core.credential.TemporalTokenSupplier;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/** Context tests for {@link TemporalTokenSourceStartupCheck}, covering every row of its table. */
class TemporalTokenSourceStartupCheckTest {

    private static final String DISTINCTIVE_HASH = "deadbeef".repeat(8);

    @Configuration
    static class NoSourceConfig {
        @Bean
        ControlPlaneTemporalTokens tokens() {
            return new ControlPlaneTemporalTokens(
                    Optional.empty(), Clock.systemUTC(), Executors.newSingleThreadExecutor());
        }
    }

    @Configuration
    static class WithSourceConfig {
        @Bean
        ControlPlaneTemporalTokens tokens() {
            TemporalTokenSupplier supplier = ns ->
                    Optional.of(new TemporalBearerToken("tok", Instant.now().plusSeconds(600)));
            return new ControlPlaneTemporalTokens(
                    Optional.of(supplier), Clock.systemUTC(), Executors.newSingleThreadExecutor());
        }
    }

    private static ApplicationContextRunner withProperties(boolean source, String mode, String hash) {
        Map<String, Object> properties = new HashMap<>();
        if (mode != null) {
            properties.put("internal.auth.mode", mode);
        }
        if (hash != null) {
            properties.put("internal.auth.orchestrator-secret-hash", hash);
        }
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        source ? WithSourceConfig.class : NoSourceConfig.class, TemporalTokenSourceStartupCheck.class)
                .withInitializer(context -> context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource("props", properties)));
    }

    @Test
    void noSource_bootsEvenWithWarnModeAndBlankHash() {
        withProperties(false, "warn", "").run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"enforce", "ENFORCE"})
    void sourcePresent_enforceModeAnyCase_withSecret_boots(String mode) {
        withProperties(true, mode, DISTINCTIVE_HASH)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void sourcePresent_warnMode_fails_namingOnlyTheMode() {
        withProperties(true, "warn", DISTINCTIVE_HASH).run(context -> {
            String message = failureMessage(context);
            assertThat(message).contains("INTERNAL_AUTH_MODE");
            assertThat(message).doesNotContain("ORCHESTRATOR_SECRET_HASH");
        });
    }

    @Test
    void sourcePresent_blankHash_fails_namingOnlyTheHash() {
        withProperties(true, "enforce", "").run(context -> {
            String message = failureMessage(context);
            assertThat(message).contains("ORCHESTRATOR_SECRET_HASH");
            assertThat(message).doesNotContain("INTERNAL_AUTH_MODE");
        });
    }

    @Test
    void sourcePresent_missingHash_fails_namingOnlyTheHash() {
        withProperties(true, "enforce", null).run(context -> {
            String message = failureMessage(context);
            assertThat(message).contains("ORCHESTRATOR_SECRET_HASH");
            assertThat(message).doesNotContain("INTERNAL_AUTH_MODE");
        });
    }

    @Test
    void failureMessage_neverEchoesTheHashValue() {
        withProperties(true, "warn", DISTINCTIVE_HASH)
                .run(context -> assertThat(failureMessage(context)).doesNotContain(DISTINCTIVE_HASH));
    }

    private static String failureMessage(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable root = context.getStartupFailure();
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class);
        return root.getMessage();
    }
}
