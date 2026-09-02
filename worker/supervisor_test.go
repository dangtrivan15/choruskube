package worker

import (
	"context"
	"testing"
)

// serve dials Temporal, which a unit test cannot do — but the guard that makes it safe to call
// on every renewal tick runs before the dial, so idempotency is testable on its own.
func TestSupervisorServeIsIdempotentPerFleet(t *testing.T) {
	f := Fleet{Namespace: "ns", TaskQueue: "q", Token: "tok"}
	sup := &fleetSupervisor{served: map[string]func(){fleetKey(f): func() {}}}

	if err := sup.serve(f); err != nil {
		t.Fatalf("serve on an already-served fleet = %v, want nil (no re-dial)", err)
	}
	if got := sup.count(); got != 1 {
		t.Fatalf("served count = %d, want 1: a second serve must not add a duplicate Worker", got)
	}
}

func TestSupervisorStopAllClearsTheServedSet(t *testing.T) {
	stopped := 0
	sup := &fleetSupervisor{served: map[string]func(){
		"a": func() { stopped++ },
		"b": func() { stopped++ },
	}}

	sup.stopAll()

	if stopped != 2 {
		t.Fatalf("stopped %d workers, want 2", stopped)
	}
	if got := sup.count(); got != 0 {
		t.Fatalf("served count after stopAll = %d, want 0", got)
	}
}

// The reason renewOnce returns the roster at all: without it the loop has nothing to serve, and a
// Fleet created after startup is never polled — its runs sit unclaimed until they time out.
func TestRenewOnceReturnsTheRosterSoNewFleetsCanBeServed(t *testing.T) {
	existing := Fleet{Namespace: "ns", TaskQueue: "q-old", Token: "old"}
	arrived := Fleet{Namespace: "ns", TaskQueue: "q-new", Token: "new"}
	tokens := newTokenCache([]Fleet{existing})

	reg, err := renewOnce(context.Background(), fixedProvider{reg: Registration{Fleets: []Fleet{existing, arrived}}}, tokens, newCredentialCache("held"))
	if err != nil {
		t.Fatalf("renewOnce = %v", err)
	}
	if len(reg.Fleets) != 2 {
		t.Fatalf("renewOnce returned %d fleets, want 2 — the loop cannot serve what it cannot see", len(reg.Fleets))
	}
	var found bool
	for _, f := range reg.Fleets {
		if fleetKey(f) == fleetKey(arrived) {
			found = true
		}
	}
	if !found {
		t.Fatal("the newly appeared fleet is missing from the returned roster")
	}
}
