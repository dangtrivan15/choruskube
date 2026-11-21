package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ControlPlaneTemporalTokensTest {

    private MutableClock clock;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void noSupplier_issuesTokensFalse_headerEmpty_statesAllNone() {
        ControlPlaneTemporalTokens tokens = new ControlPlaneTemporalTokens(Optional.empty(), clock, executor);

        assertThat(tokens.issuesTokens()).isFalse();
        assertThat(tokens.authorizationHeader("ns", Duration.ofSeconds(1))).isEmpty();

        Map<String, TemporalTokenState> states = tokens.states(List.of("a", "b"), Duration.ofSeconds(1));
        assertThat(states).containsEntry("a", new TemporalTokenState.None());
        assertThat(states).containsEntry("b", new TemporalTokenState.None());
    }

    @Test
    void supplier_bearerToken_yieldsBearerWithExpiry() {
        Instant expiry = clock.instant().plusSeconds(600);
        TemporalTokenSupplier supplier = ns -> Optional.of(new TemporalBearerToken("tok-" + ns, expiry));
        ControlPlaneTemporalTokens tokens = new ControlPlaneTemporalTokens(Optional.of(supplier), clock, executor);

        Map<String, TemporalTokenState> states = tokens.states(List.of("ns"), Duration.ofSeconds(2));
        assertThat(states.get("ns")).isInstanceOf(TemporalTokenState.Bearer.class);
        assertThat(((TemporalTokenState.Bearer) states.get("ns")).token().expiresAt())
                .isEqualTo(expiry);
        assertThat(tokens.authorizationHeader("ns", Duration.ofSeconds(2))).contains("Bearer tok-ns");
    }

    @Test
    void supplier_emptyResult_yieldsNone() {
        TemporalTokenSupplier supplier = ns -> Optional.empty();
        ControlPlaneTemporalTokens tokens = new ControlPlaneTemporalTokens(Optional.of(supplier), clock, executor);

        Map<String, TemporalTokenState> states = tokens.states(List.of("ns"), Duration.ofSeconds(2));
        assertThat(states.get("ns")).isEqualTo(new TemporalTokenState.None());
        assertThat(tokens.authorizationHeader("ns", Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void supplier_throws_yieldsUnavailableNeverNone() {
        TemporalTokenSupplier supplier = ns -> {
            throw new IOException("boom");
        };
        ControlPlaneTemporalTokens tokens = new ControlPlaneTemporalTokens(Optional.of(supplier), clock, executor);

        Map<String, TemporalTokenState> states = tokens.states(List.of("ns"), Duration.ofSeconds(2));
        assertThat(states.get("ns")).isEqualTo(new TemporalTokenState.Unavailable());
        assertThatThrownBy(() -> tokens.authorizationHeader("ns", Duration.ofSeconds(2)))
                .isInstanceOf(TemporalTokenUnavailableException.class);
    }

    @Test
    void oneNamespaceFailure_leavesOthersAndANewNamespaceWithTokens() {
        Instant expiry = clock.instant().plusSeconds(600);
        TemporalTokenSupplier supplier = ns -> {
            if (ns.equals("bad")) {
                throw new IOException("boom");
            }
            return Optional.of(new TemporalBearerToken("tok-" + ns, expiry));
        };
        ControlPlaneTemporalTokens tokens = new ControlPlaneTemporalTokens(Optional.of(supplier), clock, executor);

        Map<String, TemporalTokenState> states = tokens.states(List.of("good", "bad", "new"), Duration.ofSeconds(2));
        assertThat(states.get("bad")).isEqualTo(new TemporalTokenState.Unavailable());
        assertThat(states.get("good")).isInstanceOf(TemporalTokenState.Bearer.class);
        assertThat(states.get("new")).isInstanceOf(TemporalTokenState.Bearer.class);
    }

    @Test
    void hangingSupplier_statesReturnsWithinBound_cachedNamespaceStillCarriesItsToken() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean hang = new AtomicBoolean(false);
        Instant expiry = clock.instant().plusSeconds(600);
        TemporalTokenSupplier supplier = ns -> {
            if (hang.get()) {
                release.await(10, TimeUnit.SECONDS);
                throw new IOException("too slow");
            }
            return Optional.of(new TemporalBearerToken("tok-" + ns, expiry));
        };
        ControlPlaneTemporalTokens tokens = new ControlPlaneTemporalTokens(Optional.of(supplier), clock, executor);
        tokens.states(List.of("cached"), Duration.ofSeconds(2)); // warms the cache

        hang.set(true);
        long start = System.nanoTime();
        Map<String, TemporalTokenState> states = tokens.states(List.of("cached", "slow"), Duration.ofMillis(300));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000);
        assertThat(states.get("cached")).isInstanceOf(TemporalTokenState.Bearer.class);
        assertThat(states.get("slow")).isEqualTo(new TemporalTokenState.Unavailable());
        release.countDown();
    }

    @Test
    void authorizationHeader_hangingSupplier_givesUpAsUnavailableOnceMaxWaitPasses() {
        CountDownLatch release = new CountDownLatch(1);
        TemporalTokenSupplier supplier = ns -> {
            release.await(10, TimeUnit.SECONDS);
            return Optional.empty();
        };
        ControlPlaneTemporalTokens tokens = new ControlPlaneTemporalTokens(Optional.of(supplier), clock, executor);
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> tokens.authorizationHeader("ns", Duration.ofMillis(200)))
                    .isInstanceOf(TemporalTokenUnavailableException.class);
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2000);
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedRenewal_keepsLastValidToken_authorizationHeaderThrowsAfterExpiry() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(false);
        TemporalBearerToken token =
                new TemporalBearerToken("tok-a", clock.instant().plusSeconds(10));
        TemporalTokenSupplier supplier = ns -> {
            if (fail.get()) {
                throw new IOException("boom");
            }
            return Optional.of(token);
        };
        ControlPlaneTemporalTokens tokens = new ControlPlaneTemporalTokens(Optional.of(supplier), clock, executor);
        assertThat(tokens.authorizationHeader("ns", Duration.ofSeconds(2))).contains("Bearer tok-a");

        clock.advance(Duration.ofSeconds(6)); // past half of the 10s lifetime; token still valid
        fail.set(true);
        assertThat(tokens.authorizationHeader("ns", Duration.ofSeconds(2))).contains("Bearer tok-a");

        Thread.sleep(200); // lets the background refresh this call triggered run and fail
        clock.advance(Duration.ofSeconds(10)); // now past the token's own expiry
        assertThatThrownBy(() -> tokens.authorizationHeader("ns", Duration.ofSeconds(2)))
                .isInstanceOf(TemporalTokenUnavailableException.class);
    }

    @Test
    void logsAndToString_neverContainTheTokenValue(CapturedOutput output) {
        TemporalBearerToken token =
                new TemporalBearerToken("super-secret-token", clock.instant().plusSeconds(600));
        TemporalTokenSupplier supplier = ns -> Optional.of(token);
        ControlPlaneTemporalTokens tokens = new ControlPlaneTemporalTokens(Optional.of(supplier), clock, executor);

        tokens.states(List.of("ns"), Duration.ofSeconds(2));

        assertThat(output.getOut()).doesNotContain("super-secret-token");
        assertThat(tokens.toString()).doesNotContain("super-secret-token");
    }

    /** A {@link Clock} the test advances by hand, so renewal and expiry are deterministic. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }
}
