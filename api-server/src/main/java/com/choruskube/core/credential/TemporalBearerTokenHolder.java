package com.choruskube.core.credential;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A pull-fed, namespace-keyed holder of control-plane Temporal bearer tokens, backed by a {@link
 * TemporalTokenSupplier}. A cached token is served as-is until half its lifetime has passed, then
 * renewed via a single background refresh per namespace so a caller holding a still-valid token
 * never waits on the supplier. A namespace with no valid token waits on (and dedups into) that
 * namespace's single in-flight supplier call.
 *
 * <p>It is not a {@code @Component} — the bean that constructs one, and the no-op supplier a
 * deployment with nothing to offer wires in, belong to whichever deployment has a token source.
 */
public class TemporalBearerTokenHolder {

    private static final Logger log = LoggerFactory.getLogger(TemporalBearerTokenHolder.class);

    /** How long a failed attempt blocks a new background refresh from starting for the same namespace. */
    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);

    private final TemporalTokenSupplier supplier;
    private final Clock clock;
    private final Executor executor;
    private final Map<String, NamespaceState> namespaces = new ConcurrentHashMap<>();

    public TemporalBearerTokenHolder(TemporalTokenSupplier supplier, Clock clock, Executor executor) {
        this.supplier = supplier;
        this.clock = clock;
        this.executor = executor;
    }

    /**
     * Returns namespace's current token, refreshing as needed.
     *
     * <ul>
     *   <li>a valid cached token before its renew point completes immediately with that token;
     *   <li>a valid cached token at or past its renew point also completes immediately with that
     *       token, and starts at most one background refresh for the namespace;
     *   <li>no valid cached token starts or joins the namespace's single in-flight supplier call,
     *       and the returned future completes once it resolves.
     * </ul>
     */
    public CompletableFuture<Optional<TemporalBearerToken>> lookup(String namespace) {
        NamespaceState state = namespaces.computeIfAbsent(namespace, ns -> new NamespaceState());
        Instant now = clock.instant();
        Cached cached = state.cached;

        if (cached != null && cached.token().expiresAt().isAfter(now)) {
            if (now.isBefore(cached.renewAt())) {
                return CompletableFuture.completedFuture(Optional.of(cached.token()));
            }
            maybeStartBackgroundRefresh(namespace, state, now);
            return CompletableFuture.completedFuture(Optional.of(cached.token()));
        }

        return startOrJoin(namespace, state);
    }

    /**
     * Blocks on {@link #lookup}. Returns {@code "Bearer <token>"}, or empty when the deployment
     * presents no credential for namespace.
     *
     * @throws TemporalTokenUnavailableException if no valid token could be obtained
     */
    public Optional<String> authorizationHeader(String namespace) {
        Optional<TemporalBearerToken> result;
        try {
            result = lookup(namespace).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TemporalTokenUnavailableException unavailable) {
                throw unavailable;
            }
            throw e;
        }
        return result.map(token -> "Bearer " + token.value());
    }

    /** Lists namespaces and expiries only. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TemporalBearerTokenHolder{");
        boolean first = true;
        for (Map.Entry<String, NamespaceState> entry : namespaces.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            Cached cached = entry.getValue().cached;
            sb.append(entry.getKey()).append(": ");
            sb.append(cached != null ? "expires=" + cached.token().expiresAt() : "none");
        }
        return sb.append("}").toString();
    }

    private void maybeStartBackgroundRefresh(String namespace, NamespaceState state, Instant now) {
        synchronized (state) {
            if (state.inFlight != null && !state.inFlight.isDone()) {
                return;
            }
            if (state.lastFailureAt != null
                    && state.lastFailureAt.plus(FAILURE_BACKOFF).isAfter(now)) {
                return;
            }
            state.inFlight = supplyAsync(namespace, state);
        }
    }

    private CompletableFuture<Optional<TemporalBearerToken>> startOrJoin(String namespace, NamespaceState state) {
        synchronized (state) {
            if (state.inFlight != null && !state.inFlight.isDone()) {
                return state.inFlight;
            }
            CompletableFuture<Optional<TemporalBearerToken>> future = supplyAsync(namespace, state);
            state.inFlight = future;
            return future;
        }
    }

    private CompletableFuture<Optional<TemporalBearerToken>> supplyAsync(String namespace, NamespaceState state) {
        CompletableFuture<Optional<TemporalBearerToken>> future = new CompletableFuture<>();
        executor.execute(() -> runSupplier(namespace, state, future));
        return future;
    }

    private void runSupplier(
            String namespace, NamespaceState state, CompletableFuture<Optional<TemporalBearerToken>> future) {
        Optional<TemporalBearerToken> result;
        try {
            result = supplier.supply(namespace);
        } catch (Exception e) {
            onFailure(namespace, state);
            future.completeExceptionally(new TemporalTokenUnavailableException(namespace, e));
            return;
        }
        if (result == null) {
            onFailure(namespace, state);
            future.completeExceptionally(new TemporalTokenUnavailableException(namespace, null));
            return;
        }
        if (result.isEmpty()) {
            future.complete(Optional.empty());
            return;
        }
        TemporalBearerToken token = result.get();
        Instant now = clock.instant();
        if (!token.expiresAt().isAfter(now)) {
            onFailure(namespace, state);
            future.completeExceptionally(new TemporalTokenUnavailableException(namespace, null));
            return;
        }
        onSuccess(namespace, state, token, now);
        future.complete(Optional.of(token));
    }

    private void onFailure(String namespace, NamespaceState state) {
        state.lastFailureAt = clock.instant();
        Cached cached = state.cached;
        if (cached != null) {
            log.warn(
                    "Temporal token refresh failed for namespace '{}'; held token expires {}",
                    namespace,
                    cached.token().expiresAt());
        } else {
            log.warn("Temporal token refresh failed for namespace '{}'; none held", namespace);
        }
    }

    private void onSuccess(String namespace, NamespaceState state, TemporalBearerToken token, Instant now) {
        Cached previous = state.cached;
        boolean wasValid = previous != null && previous.token().expiresAt().isAfter(now);
        Duration halfLife = Duration.between(now, token.expiresAt()).dividedBy(2);
        state.cached = new Cached(token, now, now.plus(halfLife));
        state.lastFailureAt = null;
        if (!wasValid) {
            log.info("Temporal token obtained for namespace '{}'; expires {}", namespace, token.expiresAt());
        }
    }

    /** obtainedAt pairs with token so the renew point can be recomputed from its own lifetime. */
    private record Cached(TemporalBearerToken token, Instant obtainedAt, Instant renewAt) {}

    /** cached and lastFailureAt are read without the lock for lookup's fast path; inFlight is CAS'd under it. */
    private static final class NamespaceState {
        private volatile Cached cached;
        private volatile Instant lastFailureAt;
        private CompletableFuture<Optional<TemporalBearerToken>> inFlight;
    }
}
