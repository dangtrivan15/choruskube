package namespaces

import (
	"context"
	"errors"
	"testing"
)

type fixedRoster struct {
	namespaces []string
	err        error
	calls      int
}

func (f *fixedRoster) ListNamespaces(ctx context.Context) ([]string, error) {
	f.calls++
	return f.namespaces, f.err
}

// Serve dials Temporal, which a unit test cannot do — but the guard that makes it safe to call
// on every tick runs before the dial, so idempotency is testable on its own.
func TestServeIsIdempotentPerNamespace(t *testing.T) {
	sup := &Supervisor{served: map[string]func(){"ns": func() {}}}

	if err := sup.Serve("ns"); err != nil {
		t.Fatalf("Serve on an already-served namespace = %v, want nil (no re-dial)", err)
	}
	if got := sup.Count(); got != 1 {
		t.Fatalf("served count = %d, want 1: a second Serve must not add a duplicate worker", got)
	}
}

func TestStopAllClearsTheServedSet(t *testing.T) {
	stopped := 0
	sup := &Supervisor{served: map[string]func(){
		"a": func() { stopped++ },
		"b": func() { stopped++ },
	}}

	sup.StopAll()

	if stopped != 2 {
		t.Fatalf("stopped %d workers, want 2", stopped)
	}
	if got := sup.Count(); got != 0 {
		t.Fatalf("served count after StopAll = %d, want 0", got)
	}
}

// A roster the orchestrator cannot fetch must not stop it serving what it already serves —
// otherwise an api-server blip takes every in-flight run's workflow offline.
func TestRefreshOnceSurvivesARosterFailure(t *testing.T) {
	sup := &Supervisor{served: map[string]func(){"ns": func() {}}}
	roster := &fixedRoster{err: errors.New("api server down")}

	refreshOnce(context.Background(), sup, roster)

	if got := sup.Count(); got != 1 {
		t.Fatalf("served count = %d, want the existing worker untouched", got)
	}
}

// A namespace that leaves the roster keeps its worker: a run started there is still running,
// and an idle poller costs one connection.
func TestRefreshOnceNeverStopsAWorker(t *testing.T) {
	sup := &Supervisor{served: map[string]func(){"gone": func() {}}}
	roster := &fixedRoster{namespaces: []string{}}

	refreshOnce(context.Background(), sup, roster)

	if got := sup.Count(); got != 1 {
		t.Fatalf("served count = %d, want 1 — a departed namespace must keep its worker", got)
	}
}
