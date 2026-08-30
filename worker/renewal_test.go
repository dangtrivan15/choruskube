package worker

import (
	"context"
	"errors"
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
// nothing about the mutex being correct.
func TestTokenCacheConcurrentAccess(t *testing.T) {
	tc := newTokenCache([]Fleet{{Namespace: "ns", TaskQueue: "q", Token: "initial"}})
	key := fleetKey(Fleet{Namespace: "ns", TaskQueue: "q"})

	var wg sync.WaitGroup
	stop := make(chan struct{})

	wg.Add(1)
	go func() {
		defer wg.Done()
		i := 0
		for {
			select {
			case <-stop:
				return
			default:
				i++
				tc.set(key, "tok")
			}
		}
	}()

	for r := 0; r < 8; r++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
					_ = tc.get(key)
				}
			}
		}()
	}

	time.Sleep(20 * time.Millisecond)
	close(stop)
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

func TestRenewOnceUpdatesCachedTokenForKnownFleet(t *testing.T) {
	f := Fleet{Namespace: "ns", TaskQueue: "q", Token: "old"}
	tokens := newTokenCache([]Fleet{f})

	renewed := Fleet{Namespace: "ns", TaskQueue: "q", Token: "new"}
	err := renewOnce(context.Background(), fixedProvider{fleets: []Fleet{renewed}}, tokens)
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

	err := renewOnce(context.Background(), errProvider{boom}, tokens)
	if !errors.Is(err, boom) {
		t.Fatalf("want boom, got %v", err)
	}
	// A failed renewal must leave the previous token in place — it is still valid for the
	// remaining half of its life, so clobbering it here would make things worse, not better.
	if got := tokens.get(fleetKey(Fleet{Namespace: "ns", TaskQueue: "q"})); got != "old" {
		t.Fatalf("token after failed renewOnce = %q, want old preserved", got)
	}
}

func TestRenewOnceLeavesUnknownFleetsUncached(t *testing.T) {
	tokens := newTokenCache([]Fleet{{Namespace: "ns", TaskQueue: "q", Token: "old"}})

	other := Fleet{Namespace: "other-ns", TaskQueue: "other-q", Token: "other-tok"}
	if err := renewOnce(context.Background(), fixedProvider{fleets: []Fleet{other}}, tokens); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// renewOnce sets whatever keys the provider returns; a key this process never served
	// simply sits unused in the map rather than being rejected. What matters is that the
	// Fleet this process *does* serve keeps its own token untouched.
	if got := tokens.get(fleetKey(Fleet{Namespace: "ns", TaskQueue: "q"})); got != "old" {
		t.Fatalf("token for served fleet = %q, want old unchanged", got)
	}
}
