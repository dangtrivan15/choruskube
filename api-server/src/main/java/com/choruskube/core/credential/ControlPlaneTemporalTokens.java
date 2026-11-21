package com.choruskube.core.credential;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The one holder over the control-plane token source seam, shared by the api-server's own
 * Temporal calls ({@link #authorizationHeader}) and the namespace roster the orchestrator fetches
 * ({@link #states}). With no {@link TemporalTokenSupplier} bean present, every namespace is
 * {@link TemporalTokenState.None}: today's behaviour, unchanged.
 */
@Component
public class ControlPlaneTemporalTokens {

    /** Null when no {@link TemporalTokenSupplier} bean is present — {@link #issuesTokens()} is then false. */
    private final TemporalBearerTokenHolder holder;

    public ControlPlaneTemporalTokens(
            Optional<TemporalTokenSupplier> supplier,
            Clock clock,
            @Qualifier("temporalTokenExecutor") Executor executor) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(executor, "executor");
        this.holder = supplier.map(s -> new TemporalBearerTokenHolder(s, clock, executor))
                .orElse(null);
    }

    /** Whether a deployment has wired a token source at all. */
    public boolean issuesTokens() {
        return holder != null;
    }

    /**
     * Blocks on a namespace's current token, same contract as {@link
     * TemporalBearerTokenHolder#authorizationHeader}.
     *
     * @param maxWait the longest this call waits on an uncached namespace before giving up as
     *     unavailable, or {@code null} to wait as long as the supplier takes
     * @return empty when there is no source, or the source says none for namespace
     * @throws TemporalTokenUnavailableException if no valid token could be obtained for namespace
     *     within maxWait
     */
    public Optional<String> authorizationHeader(String namespace, Duration maxWait) {
        if (holder == null) {
            return Optional.empty();
        }
        if (maxWait == null) {
            return holder.authorizationHeader(namespace);
        }
        CompletableFuture<Optional<TemporalBearerToken>> future = holder.lookup(namespace);
        Optional<TemporalBearerToken> result;
        try {
            result = future.get(Math.max(0, maxWait.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new TemporalTokenUnavailableException(namespace, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TemporalTokenUnavailableException unavailable) {
                throw unavailable;
            }
            throw new TemporalTokenUnavailableException(namespace, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TemporalTokenUnavailableException(namespace, e);
        }
        return result.map(token -> "Bearer " + token.value());
    }

    /**
     * Looks up every namespace concurrently and returns within bound. A namespace still pending,
     * or whose lookup failed, is reported {@link TemporalTokenState.Unavailable} — never {@link
     * TemporalTokenState.None} — so a hanging or failing source never silently drops a namespace's
     * credential requirement.
     */
    public Map<String, TemporalTokenState> states(Collection<String> namespaces, Duration bound) {
        Map<String, TemporalTokenState> result = new LinkedHashMap<>();
        if (holder == null) {
            for (String namespace : namespaces) {
                result.put(namespace, new TemporalTokenState.None());
            }
            return result;
        }

        Map<String, CompletableFuture<Optional<TemporalBearerToken>>> futures = new LinkedHashMap<>();
        for (String namespace : namespaces) {
            futures.put(namespace, holder.lookup(namespace));
        }
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(Math.max(0, bound.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // Individual futures are read below regardless: a still-pending or failed one becomes
            // Unavailable there.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (Map.Entry<String, CompletableFuture<Optional<TemporalBearerToken>>> entry : futures.entrySet()) {
            result.put(entry.getKey(), toState(entry.getValue()));
        }
        return result;
    }

    private static TemporalTokenState toState(CompletableFuture<Optional<TemporalBearerToken>> future) {
        if (!future.isDone()) {
            return new TemporalTokenState.Unavailable();
        }
        try {
            return future.getNow(Optional.empty())
                    .<TemporalTokenState>map(TemporalTokenState.Bearer::new)
                    .orElseGet(TemporalTokenState.None::new);
        } catch (CompletionException e) {
            return new TemporalTokenState.Unavailable();
        }
    }

    /** Lists namespaces and expiries only, via the underlying holder. */
    @Override
    public String toString() {
        return "ControlPlaneTemporalTokens{" + (holder == null ? "no source" : holder.toString()) + "}";
    }
}
