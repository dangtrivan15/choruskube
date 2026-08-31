package worker

import (
	"testing"
	"time"
)

// A Fleet created after startup is not polled until the next tick, and its runs hang rather than
// fail while it waits. The token lifetime is measured in hours, so it cannot be what bounds that.
func TestLoopIntervalIsBoundedByTheRosterRefresh(t *testing.T) {
	hourLong := []Fleet{{ExpiresInSeconds: 3600}}

	if got := renewalInterval(hourLong); got != 30*time.Minute {
		t.Fatalf("renewalInterval = %v, want 30m (half of a 1h token)", got)
	}
	if got := loopInterval(hourLong); got != rosterRefreshInterval {
		t.Fatalf("loopInterval = %v, want the roster bound %v: a new Fleet must not wait "+
			"half a token lifetime to be served", got, rosterRefreshInterval)
	}
}

// When a token is short-lived, renewal is the tighter constraint and must win, or the credential
// lapses while the loop is still waiting to refresh the roster.
func TestLoopIntervalYieldsToAShorterRenewalCadence(t *testing.T) {
	shortLived := []Fleet{{ExpiresInSeconds: 90}} // renewal wants 45s, tighter than the 60s roster bound

	got := loopInterval(shortLived)
	if got != renewalInterval(shortLived) {
		t.Fatalf("loopInterval = %v, want the renewal cadence %v", got, renewalInterval(shortLived))
	}
	if got > rosterRefreshInterval {
		t.Fatalf("loopInterval = %v, must never exceed the roster bound", got)
	}
}
