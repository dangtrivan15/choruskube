package worker

import (
	"context"
	"testing"

	"go.temporal.io/sdk/client"
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

func TestSupervisorClientForReturnsTheDialedClient(t *testing.T) {
	f := Fleet{Namespace: "ns", TaskQueue: "q"}
	want := &fakeTemporalClient{}
	sup := &fleetSupervisor{clients: map[string]client.Client{fleetKey(f): want}}

	if got := sup.clientFor("ns", "q"); got != client.Client(want) {
		t.Fatalf("clientFor returned a different client than the one dialed for this fleet")
	}
}

func TestSupervisorClientForUnservedFleetReturnsNil(t *testing.T) {
	sup := &fleetSupervisor{clients: map[string]client.Client{}}
	if got := sup.clientFor("ns", "q"); got != nil {
		t.Fatalf("clientFor = %v, want nil for a fleet this process is not serving", got)
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

// reattachClient hands the completer a client for an execution this Worker never launched. By-id
// addressing needs only the Temporal namespace, so the sole served client is the answer.
func TestSupervisorReattachClientReturnsTheSoleNamespaceClient(t *testing.T) {
	want := &fakeTemporalClient{}
	sup := &fleetSupervisor{nsClients: map[string]client.Client{"org-ns": want}}

	cl, ns, err := sup.reattachClient()
	if err != nil {
		t.Fatalf("reattachClient = %v, want nil", err)
	}
	if cl != client.Client(want) || ns != "org-ns" {
		t.Fatalf("reattachClient = (%v, %q), want the sole served client and its namespace", cl, ns)
	}
}

func TestSupervisorReattachClientWithoutServedNamespaceErrors(t *testing.T) {
	sup := &fleetSupervisor{nsClients: map[string]client.Client{}}
	if _, _, err := sup.reattachClient(); err == nil {
		t.Fatal("reattachClient with no served namespace = nil error, want an error rather than a nil client")
	}
}

// With several Temporal namespaces the callback's ids do not say which one the run lives in, so
// reattach must refuse rather than complete an activity in the wrong namespace.
func TestSupervisorReattachClientWithMultipleNamespacesErrors(t *testing.T) {
	sup := &fleetSupervisor{nsClients: map[string]client.Client{
		"ns-a": &fakeTemporalClient{},
		"ns-b": &fakeTemporalClient{},
	}}
	if _, _, err := sup.reattachClient(); err == nil {
		t.Fatal("reattachClient with two served namespaces = nil error, want a refusal")
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
