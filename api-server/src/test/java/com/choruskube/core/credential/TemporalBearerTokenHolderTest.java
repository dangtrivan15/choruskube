package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TemporalBearerTokenHolderTest {

    private MutableClock clock;
    private ManualExecutor executor;
    private StubSupplier supplier;
    private TemporalBearerTokenHolder holder;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        executor = new ManualExecutor();
        supplier = new StubSupplier();
        holder = new TemporalBearerTokenHolder(supplier, clock, executor);
    }

    @Test
    void firstLookup_callsSupplierOnce_repeatedLookupsBeforeHalfLifeReuseCache() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(token));

        CompletableFuture<Optional<TemporalBearerToken>> first = holder.lookup("ns");
        executor.runOne();
        assertThat(first.join()).contains(token);
        assertThat(supplier.callCount()).isEqualTo(1);

        for (int i = 0; i < 5; i++) {
            assertThat(holder.lookup("ns").join()).contains(token);
        }
        assertThat(supplier.callCount()).isEqualTo(1);
    }

    @Test
    void atHalfLife_returnsCachedImmediately_exactlyOneBackgroundRefreshUnderConcurrentLookups() throws Exception {
        TemporalBearerToken firstToken = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(firstToken));
        CompletableFuture<Optional<TemporalBearerToken>> initial = holder.lookup("ns");
        executor.runOne();
        assertThat(initial.join()).contains(firstToken);
        assertThat(supplier.callCount()).isEqualTo(1);

        // Half of a 10-minute lifetime is 5 minutes past obtainedAt.
        clock.advance(Duration.ofMinutes(5));
        TemporalBearerToken secondToken = token("tok-b", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(secondToken));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Optional<TemporalBearerToken>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> holder.lookup("ns").join()));
            }
            for (Future<Optional<TemporalBearerToken>> f : futures) {
                // Every concurrent lookup at/after the renew point returns the still-valid cached
                // token immediately, without waiting on a refresh.
                assertThat(f.get(5, TimeUnit.SECONDS)).contains(firstToken);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(supplier.callCount()).isEqualTo(1);
        assertThat(executor.submittedCount()).isEqualTo(2); // exactly one background refresh queued

        executor.runOne();
        assertThat(supplier.callCount()).isEqualTo(2);
        assertThat(holder.lookup("ns").join()).contains(secondToken); // the new token is now served
    }

    @Test
    void failedRenewal_keepsServingLastValidToken_andBacksOffForThirtySeconds() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(token));
        CompletableFuture<Optional<TemporalBearerToken>> initial = holder.lookup("ns");
        executor.runOne();
        assertThat(initial.join()).contains(token);

        clock.advance(Duration.ofMinutes(5)); // at the renew point
        supplier.set(() -> {
            throw new IOException("supplier boom");
        });

        assertThat(holder.lookup("ns").join()).contains(token); // still served, non-blocking
        assertThat(executor.submittedCount()).isEqualTo(2);
        executor.runOne(); // runs the failing refresh

        assertThat(holder.lookup("ns").join()).contains(token); // failure keeps the last valid token

        clock.advance(Duration.ofSeconds(10)); // within 30s of the failure
        assertThat(holder.lookup("ns").join()).contains(token);
        assertThat(executor.submittedCount()).isEqualTo(2); // no new attempt started

        clock.advance(Duration.ofSeconds(25)); // past the 30s backoff
        supplier.set(() -> Optional.of(token));
        assertThat(holder.lookup("ns").join()).contains(token);
        assertThat(executor.submittedCount()).isEqualTo(3);
    }

    @Test
    void afterExpiry_withSupplierFailing_authorizationHeaderThrowsUnavailable() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofSeconds(10));
        supplier.set(() -> Optional.of(token));
        CompletableFuture<Optional<TemporalBearerToken>> initial = holder.lookup("ns");
        executor.runOne();
        assertThat(initial.join()).contains(token);

        clock.advance(Duration.ofSeconds(20)); // past expiry
        supplier.set(() -> {
            throw new IOException("supplier boom");
        });

        ExecutorService callerPool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> result = callerPool.submit(() -> holder.authorizationHeader("ns"));
            executor.runOne();
            Throwable thrown = catchThrowable(() -> result.get(5, TimeUnit.SECONDS));
            Throwable cause = thrown.getCause();
            assertThat(cause).isInstanceOf(TemporalTokenUnavailableException.class);
            assertThat(cause.getMessage()).doesNotContain("tok-a");
            assertThat(((TemporalTokenUnavailableException) cause).namespace()).isEqualTo("ns");
        } finally {
            callerPool.shutdownNow();
        }
    }

    @Test
    void concurrentLookupsForUncachedNamespace_shareOneSupplierCall() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(token));

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<CompletableFuture<Optional<TemporalBearerToken>>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> holder.lookup("ns")));
            }
            List<CompletableFuture<Optional<TemporalBearerToken>>> results = new ArrayList<>();
            for (Future<CompletableFuture<Optional<TemporalBearerToken>>> f : futures) {
                results.add(f.get(5, TimeUnit.SECONDS));
            }

            assertThat(executor.submittedCount()).isEqualTo(1);
            executor.runOne();
            for (CompletableFuture<Optional<TemporalBearerToken>> r : results) {
                assertThat(r.join()).contains(token);
            }
            assertThat(supplier.callCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void supplierReturningEmpty_givesEmptyAuthorizationHeader() throws Exception {
        supplier.set(Optional::empty);

        ExecutorService callerPool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> result = callerPool.submit(() -> holder.authorizationHeader("ns"));
            executor.runOne();
            assertThat(result.get(5, TimeUnit.SECONDS)).isEmpty();
        } finally {
            callerPool.shutdownNow();
        }
    }

    @Test
    void supplierThrowing_givesUnavailableNeverNone() throws Exception {
        supplier.set(() -> {
            throw new IOException("boom");
        });
        assertUnavailable();
    }

    @Test
    void supplierReturningNull_givesUnavailableNeverNone() throws Exception {
        supplier.set(() -> null);
        assertUnavailable();
    }

    @Test
    void supplierReturningAlreadyExpiredToken_givesUnavailableNeverNone() throws Exception {
        TemporalBearerToken expired =
                new TemporalBearerToken("tok-a", clock.instant().minusSeconds(1));
        supplier.set(() -> Optional.of(expired));
        assertUnavailable();
    }

    private void assertUnavailable() throws Exception {
        ExecutorService callerPool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> result = callerPool.submit(() -> holder.authorizationHeader("ns"));
            executor.runOne();
            Throwable thrown = catchThrowable(() -> result.get(5, TimeUnit.SECONDS));
            assertThat(thrown.getCause()).isInstanceOf(TemporalTokenUnavailableException.class);
        } finally {
            callerPool.shutdownNow();
        }
    }

    @Test
    void oneNamespaceFailure_leavesAnotherNamespacesTokenServed() throws Exception {
        TemporalBearerToken tokenA = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(tokenA));
        CompletableFuture<Optional<TemporalBearerToken>> a = holder.lookup("ns-a");
        executor.runOne();
        assertThat(a.join()).contains(tokenA);

        supplier.set(() -> {
            throw new IOException("boom");
        });
        ExecutorService callerPool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> result = callerPool.submit(() -> holder.authorizationHeader("ns-b"));
            executor.runOne();
            Throwable thrown = catchThrowable(() -> result.get(5, TimeUnit.SECONDS));
            assertThat(thrown.getCause()).isInstanceOf(TemporalTokenUnavailableException.class);
        } finally {
            callerPool.shutdownNow();
        }

        assertThat(holder.authorizationHeader("ns-a")).contains("Bearer tok-a");
    }

    @Test
    void authorizationHeader_formatIsBearerToken() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(token));
        CompletableFuture<Optional<TemporalBearerToken>> f = holder.lookup("ns");
        executor.runOne();
        f.join();

        assertThat(holder.authorizationHeader("ns")).contains("Bearer tok-a");
    }

    @Test
    void eachCallerGetsItsOwnFuture_oneCallersTimeoutNeverReadsAsNoneForAnother() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(token));

        CompletableFuture<Optional<TemporalBearerToken>> impatient = holder.lookup("ns");
        CompletableFuture<Optional<TemporalBearerToken>> patient = holder.lookup("ns");
        assertThat(impatient).isNotSameAs(patient);
        impatient.complete(Optional.empty()); // what completeOnTimeout(Optional.empty(), ...) does

        executor.runOne();
        assertThat(supplier.callCount()).isEqualTo(1);
        assertThat(patient.join()).contains(token);
        assertThat(holder.lookup("ns").join()).contains(token);
    }

    @Test
    void rejectedBackgroundRefresh_stillServesTheCachedToken() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(token));
        CompletableFuture<Optional<TemporalBearerToken>> initial = holder.lookup("ns");
        executor.runOne();
        initial.join();

        clock.advance(Duration.ofMinutes(5)); // at the renew point
        executor.rejecting = true;
        assertThat(holder.authorizationHeader("ns")).contains("Bearer tok-a");
    }

    @Test
    void rejectedFirstFetch_isUnavailable() {
        executor.rejecting = true;

        assertThatThrownBy(() -> holder.authorizationHeader("ns"))
                .isInstanceOf(TemporalTokenUnavailableException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void supplierThrowingAnError_isUnavailable_andTheNextLookupAsksAgain() throws Exception {
        supplier.set(() -> {
            throw new NoClassDefFoundError("missing issuer client");
        });
        CompletableFuture<Optional<TemporalBearerToken>> first = holder.lookup("ns");
        executor.runOne();
        assertThat(first).isCompletedExceptionally();
        assertThat(catchThrowable(first::join)).hasCauseInstanceOf(TemporalTokenUnavailableException.class);

        TemporalBearerToken token = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(token));
        CompletableFuture<Optional<TemporalBearerToken>> second = holder.lookup("ns");
        executor.runOne();
        assertThat(second.join()).contains(token);
    }

    @Test
    void refreshReturningEmpty_dropsTheCachedToken() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(token));
        CompletableFuture<Optional<TemporalBearerToken>> initial = holder.lookup("ns");
        executor.runOne();
        initial.join();

        clock.advance(Duration.ofMinutes(5)); // at the renew point
        supplier.set(Optional::empty);
        assertThat(holder.lookup("ns").join()).contains(token);
        executor.runOne(); // the background refresh answers "none"

        CompletableFuture<Optional<TemporalBearerToken>> after = holder.lookup("ns");
        executor.runOne();
        assertThat(after.join()).isEmpty();
    }

    @Test
    void afterExpiry_aFailedFetchReportsNoneHeld_andToStringDropsTheExpiredToken() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofSeconds(10));
        supplier.set(() -> Optional.of(token));
        CompletableFuture<Optional<TemporalBearerToken>> initial = holder.lookup("ns");
        executor.runOne();
        initial.join();

        clock.advance(Duration.ofSeconds(20)); // past expiry
        supplier.set(() -> {
            throw new IOException("boom");
        });
        List<ILoggingEvent> events = captureLogs(() -> {
            CompletableFuture<Optional<TemporalBearerToken>> f = holder.lookup("ns");
            executor.runOne();
            assertThat(f).isCompletedExceptionally();
        });

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("none held");
        });
        assertThat(holder.toString()).doesNotContain(token.expiresAt().toString());
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThatThrownBy(() -> new TemporalBearerTokenHolder(null, clock, executor))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemporalBearerTokenHolder(supplier, null, executor))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemporalBearerTokenHolder(supplier, clock, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void logs_oneInfoForFirstToken_warnPerFailedAttempt_noTokenInAnyLine() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofMinutes(10));
        List<ILoggingEvent> events = captureLogs(() -> {
            supplier.set(() -> Optional.of(token));
            CompletableFuture<Optional<TemporalBearerToken>> f = holder.lookup("ns");
            executor.runOne();
            f.join();

            // A supplier's own message may carry a token; no log line may repeat it.
            supplier.set(() -> {
                throw new IOException("issuer answered tok-a");
            });
            clock.advance(Duration.ofMinutes(5)); // at the renew point
            holder.lookup("ns").join(); // non-blocking; also queues a background refresh
            executor.runOne();
        });

        List<ILoggingEvent> infos =
                events.stream().filter(e -> e.getLevel() == Level.INFO).toList();
        List<ILoggingEvent> warns =
                events.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        // The rollout check reads the first-token line by namespace, so it carries the namespace.
        assertThat(infos).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("'ns'")
                .contains(token.expiresAt().toString()));
        assertThat(warns).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("'ns'")
                .contains(token.expiresAt().toString()));
        for (ILoggingEvent e : events) {
            assertThat(e.getFormattedMessage()).doesNotContain("tok-a");
            assertThat(e.getThrowableProxy()).isNull();
        }
    }

    @Test
    void toString_isTokenFree() throws Exception {
        TemporalBearerToken token = token("tok-a", Duration.ofMinutes(10));
        supplier.set(() -> Optional.of(token));
        CompletableFuture<Optional<TemporalBearerToken>> f = holder.lookup("ns");
        executor.runOne();
        f.join();

        String rendered = holder.toString();
        assertThat(rendered).doesNotContain("tok-a");
        assertThat(rendered).contains("ns");
    }

    private static List<ILoggingEvent> captureLogs(ThrowingRunnable body) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(TemporalBearerTokenHolder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private TemporalBearerToken token(String value, Duration lifetime) {
        return new TemporalBearerToken(value, clock.instant().plus(lifetime));
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

    /**
     * An {@link Executor} that only queues tasks; the test runs them explicitly with {@link
     * #runOne()}, so a supplier call's timing relative to concurrent lookups is fully controlled.
     */
    private static final class ManualExecutor implements Executor {
        private final BlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        private final AtomicInteger submitted = new AtomicInteger();
        volatile boolean rejecting;

        @Override
        public void execute(Runnable command) {
            if (rejecting) {
                throw new RejectedExecutionException("executor saturated");
            }
            submitted.incrementAndGet();
            queued.add(command);
        }

        void runOne() throws InterruptedException {
            Runnable r = queued.poll(5, TimeUnit.SECONDS);
            if (r == null) {
                throw new AssertionError("no task was submitted within the timeout");
            }
            r.run();
        }

        int submittedCount() {
            return submitted.get();
        }
    }

    /** A {@link TemporalTokenSupplier} whose behavior and call count the test controls directly. */
    private static final class StubSupplier implements TemporalTokenSupplier {
        private volatile Callable<Optional<TemporalBearerToken>> behavior = Optional::empty;
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<TemporalBearerToken> supply(String namespace) throws Exception {
            calls.incrementAndGet();
            return behavior.call();
        }

        void set(Callable<Optional<TemporalBearerToken>> behavior) {
            this.behavior = behavior;
        }

        int callCount() {
            return calls.get();
        }
    }
}
