package worker

import (
	"bytes"
	"context"
	"errors"
	"log"
	"math"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestTokenCacheGetSetIsolatesKeys(t *testing.T) {
	fleets := []Fleet{
		{Namespace: "ns-a", TaskQueue: "q-a", Token: "tok-a"},
		{Namespace: "ns-b", TaskQueue: "q-b", Token: "tok-b"},
	}
	tc := newTokenCache(fleets)

	if got := tc.get(fleetKey(fleets[0])); got != "tok-a" {
		t.Fatalf("get(a) = %q, want tok-a", got)
	}
	if got := tc.get(fleetKey(fleets[1])); got != "tok-b" {
		t.Fatalf("get(b) = %q, want tok-b", got)
	}

	tc.set(fleetKey(fleets[0]), "tok-a-renewed")
	if got := tc.get(fleetKey(fleets[0])); got != "tok-a-renewed" {
		t.Fatalf("get(a) after set = %q, want tok-a-renewed", got)
	}
	// Setting one Fleet's token must not disturb another's.
	if got := tc.get(fleetKey(fleets[1])); got != "tok-b" {
		t.Fatalf("get(b) after unrelated set = %q, want tok-b unchanged", got)
	}
}

func TestTokenCacheGetUnknownKeyReturnsEmpty(t *testing.T) {
	tc := newTokenCache(nil)
	if got := tc.get("missing"); got != "" {
		t.Fatalf("get(missing) = %q, want empty", got)
	}
}

// TestTokenCacheConcurrentAccess exercises the exact contention shape production has: one
// writer (the renewal goroutine) and many readers (a credential callback invoked per
// Temporal request), all touching the same key. Run with -race; without it this test proves
// nothing about the mutex being correct. Iteration-bounded rather than wall-clock-bounded,
// and each goroutine yields via runtime.Gosched, so it cannot busy-spin a constrained runner.
func TestTokenCacheConcurrentAccess(t *testing.T) {
	tc := newTokenCache([]Fleet{{Namespace: "ns", TaskQueue: "q", Token: "initial"}})
	key := fleetKey(Fleet{Namespace: "ns", TaskQueue: "q"})

	const iterations = 500
	var wg sync.WaitGroup

	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < iterations; i++ {
			tc.set(key, "tok")
			runtime.Gosched()
		}
	}()

	for r := 0; r < 8; r++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < iterations; i++ {
				_ = tc.get(key)
				runtime.Gosched()
			}
		}()
	}

	wg.Wait()
}

func TestRenewalIntervalHalfOfSmallestExpiry(t *testing.T) {
	fleets := []Fleet{
		{ExpiresInSeconds: 3600},
		{ExpiresInSeconds: 1800},
		{ExpiresInSeconds: 7200},
	}
	want := 900 * time.Second // half of the smallest, 1800
	if got := renewalInterval(fleets); got != want {
		t.Fatalf("renewalInterval = %v, want %v", got, want)
	}
}

func TestRenewalIntervalIgnoresNonPositiveExpiry(t *testing.T) {
	fleets := []Fleet{
		{ExpiresInSeconds: 0},
		{ExpiresInSeconds: -1},
		{ExpiresInSeconds: 600},
	}
	want := 300 * time.Second
	if got := renewalInterval(fleets); got != want {
		t.Fatalf("renewalInterval = %v, want %v", got, want)
	}
}

func TestRenewalIntervalFallsBackWhenNoExpirySupplied(t *testing.T) {
	fleets := []Fleet{{ExpiresInSeconds: 0}, {}}
	if got := renewalInterval(fleets); got != defaultRenewalInterval {
		t.Fatalf("renewalInterval = %v, want the default %v", got, defaultRenewalInterval)
	}
	// Also the empty-list case: a provider that briefly returns nothing must not divide by zero.
	if got := renewalInterval(nil); got != defaultRenewalInterval {
		t.Fatalf("renewalInterval(nil) = %v, want the default %v", got, defaultRenewalInterval)
	}
}

// TestRenewalIntervalFloorsTinyExpiry guards the exact panic the review found: dividing 1 by
// 2 in integer arithmetic yields 0, and time.NewTicker(0) panics — taking down every Fleet
// this process serves, not just the one with the short-lived token.
func TestRenewalIntervalFloorsTinyExpiry(t *testing.T) {
	for _, secs := range []int64{1, 2} {
		got := renewalInterval([]Fleet{{ExpiresInSeconds: secs}})
		if got != renewalMinInterval {
			t.Fatalf("renewalInterval(%ds) = %v, want the floor %v", secs, got, renewalMinInterval)
		}
		if got <= 0 {
			t.Fatalf("renewalInterval(%ds) = %v, would panic time.NewTicker", secs, got)
		}
	}
}

// TestRenewalIntervalCapsVeryLargeExpiry guards the overflow half of the same panic: a
// lifetime whose half, converted to nanoseconds, exceeds int64 wraps negative.
func TestRenewalIntervalCapsVeryLargeExpiry(t *testing.T) {
	got := renewalInterval([]Fleet{{ExpiresInSeconds: 40_000_000_000}}) // ~2e10s half-life
	if got != renewalMaxInterval {
		t.Fatalf("renewalInterval(4e10s) = %v, want the cap %v", got, renewalMaxInterval)
	}
	if got <= 0 {
		t.Fatalf("renewalInterval(4e10s) = %v, would panic time.NewTicker", got)
	}
}

// TestRenewalIntervalCapsMaxInt64Expiry is the most extreme input the field can ever hold —
// proof the clamp holds at the boundary, not just at one large sample value.
func TestRenewalIntervalCapsMaxInt64Expiry(t *testing.T) {
	got := renewalInterval([]Fleet{{ExpiresInSeconds: math.MaxInt64}})
	if got != renewalMaxInterval {
		t.Fatalf("renewalInterval(MaxInt64) = %v, want the cap %v", got, renewalMaxInterval)
	}
	if got <= 0 {
		t.Fatalf("renewalInterval(MaxInt64) = %v, would panic time.NewTicker", got)
	}
}

func TestRenewOnceUpdatesCachedTokenForKnownFleet(t *testing.T) {
	f := Fleet{Namespace: "ns", TaskQueue: "q", Token: "old"}
	tokens := newTokenCache([]Fleet{f})

	renewed := Fleet{Namespace: "ns", TaskQueue: "q", Token: "new"}
	_, err := renewOnce(context.Background(), fixedProvider{fleets: []Fleet{renewed}}, tokens)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got := tokens.get(fleetKey(f)); got != "new" {
		t.Fatalf("token after renewOnce = %q, want new", got)
	}
}

func TestRenewOnceSurfacesProviderError(t *testing.T) {
	boom := errors.New("boom")
	tokens := newTokenCache([]Fleet{{Namespace: "ns", TaskQueue: "q", Token: "old"}})

	_, err := renewOnce(context.Background(), errProvider{boom}, tokens)
	if !errors.Is(err, boom) {
		t.Fatalf("want boom, got %v", err)
	}
	// A failed renewal must leave the previous token in place — it is still valid for the
	// remaining half of its life, so clobbering it here would make things worse, not better.
	if got := tokens.get(fleetKey(Fleet{Namespace: "ns", TaskQueue: "q"})); got != "old" {
		t.Fatalf("token after failed renewOnce = %q, want old preserved", got)
	}
}

// TestRenewOnceSkipsEmptyToken guards the inverse of the error-path property above: a
// renewal response with a blank token must not overwrite a still-valid cached one, or the
// callback would start failing every Temporal request until a later renewal succeeds.
func TestRenewOnceSkipsEmptyToken(t *testing.T) {
	f := Fleet{Namespace: "ns", TaskQueue: "q", Token: "old"}
	tokens := newTokenCache([]Fleet{f})

	blank := Fleet{Namespace: "ns", TaskQueue: "q", Token: ""}
	if _, err := renewOnce(context.Background(), fixedProvider{fleets: []Fleet{blank}}, tokens); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got := tokens.get(fleetKey(f)); got != "old" {
		t.Fatalf("token after empty-token renewal = %q, want old preserved", got)
	}
}

// TestRenewOnceAddsANewlySeenFleetKey documents the actual behavior: a Fleet key renewOnce
// has never cached before is added, not rejected — the cache is not restricted to the
// roster Run started with.
func TestRenewOnceAddsANewlySeenFleetKey(t *testing.T) {
	tokens := newTokenCache([]Fleet{{Namespace: "ns", TaskQueue: "q", Token: "old"}})

	other := Fleet{Namespace: "other-ns", TaskQueue: "other-q", Token: "other-tok"}
	if _, err := renewOnce(context.Background(), fixedProvider{fleets: []Fleet{other}}, tokens); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got := tokens.get(fleetKey(other)); got != "other-tok" {
		t.Fatalf("token for newly-seen fleet = %q, want other-tok", got)
	}
	// An unrelated renewal response must not disturb the originally-served fleet.
	if got := tokens.get(fleetKey(Fleet{Namespace: "ns", TaskQueue: "q"})); got != "old" {
		t.Fatalf("token for served fleet = %q, want old unchanged", got)
	}
}

// TestRenewOnceLogsWhenAServedFleetGoesMissing guards visibility: a renewal that silently
// stops covering a Fleet must not look identical to one that renewed it, or the token quietly
// ages toward expiry with no signal until requests start failing.
func TestRenewOnceLogsWhenAServedFleetGoesMissing(t *testing.T) {
	served := Fleet{Namespace: "ns", TaskQueue: "q", Token: "old"}
	tokens := newTokenCache([]Fleet{served})

	var buf bytes.Buffer
	orig := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(orig)

	other := Fleet{Namespace: "other-ns", TaskQueue: "other-q", Token: "other-tok"}
	if _, err := renewOnce(context.Background(), fixedProvider{fleets: []Fleet{other}}, tokens); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if got := tokens.get(fleetKey(served)); got != "old" {
		t.Fatalf("token for dropped-out fleet = %q, want old preserved", got)
	}
	if !strings.Contains(buf.String(), fleetKey(served)) {
		t.Fatalf("want a log line naming the dropped-out fleet %q, got %q", fleetKey(served), buf.String())
	}
}

// TestCredentialReadsCurrentCacheValueAcrossARenewal is the missing link the review called
// out: it proves the exact callback Run hands to the Temporal SDK, not just tokenCache and
// renewOnce in isolation, observes a renewal — with no real Temporal connection involved.
func TestCredentialReadsCurrentCacheValueAcrossARenewal(t *testing.T) {
	f := Fleet{Namespace: "ns", TaskQueue: "q", Token: "first"}
	tokens := newTokenCache([]Fleet{f})
	key := fleetKey(f)
	cb := credential(tokens, key)

	got, err := cb(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != "first" {
		t.Fatalf("first read = %q, want first", got)
	}

	renewed := Fleet{Namespace: "ns", TaskQueue: "q", Token: "second"}
	if _, err := renewOnce(context.Background(), fixedProvider{fleets: []Fleet{renewed}}, tokens); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	got, err = cb(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != "second" {
		t.Fatalf("read after renewal = %q, want second", got)
	}
}

// TestCredentialErrorsOnEmptyCachedToken is the callback's other branch: a key the cache was
// never populated under must fail loudly rather than hand Temporal an empty API key.
func TestCredentialErrorsOnEmptyCachedToken(t *testing.T) {
	tokens := newTokenCache(nil)
	cb := credential(tokens, "ns|q")

	_, err := cb(context.Background())
	if err == nil {
		t.Fatal("want an error for an uncached key, got nil")
	}
}
