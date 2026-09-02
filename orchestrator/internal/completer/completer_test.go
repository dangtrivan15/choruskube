package completer

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

// fakeCompletions records what would have reached Temporal. Guarded by mu: callbacks can
// legitimately race through namespaceFor concurrently, so every field access must too.
type fakeCompletions struct {
	mu         sync.Mutex
	namespace  string
	workflowID string
	activityID string
	calls      int
}

func (f *fakeCompletions) CompleteActivityByID(ctx context.Context, namespace, workflowID, runID, activityID string, result interface{}, err error) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.namespace, f.workflowID, f.activityID = namespace, workflowID, activityID
	f.calls++
	return nil
}

func (f *fakeCompletions) RecordActivityHeartbeatByID(ctx context.Context, namespace, workflowID, runID, activityID string, details ...interface{}) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.namespace, f.workflowID, f.activityID = namespace, workflowID, activityID
	f.calls++
	return nil
}

// fakeLookup's namespace and err are set once at construction and never mutated, so only
// calls needs its own synchronization against namespaceFor's concurrent callers.
type fakeLookup struct {
	namespace string
	calls     atomic.Int64
	err       error
}

func (f *fakeLookup) GetRunNamespace(ctx context.Context, runID uuid.UUID) (apiclient.RunNamespace, error) {
	f.calls.Add(1)
	return apiclient.RunNamespace{Namespace: f.namespace}, f.err
}

func TestCompleteActivityUsesTheRunsNamespace(t *testing.T) {
	fake := &fakeCompletions{}
	lookup := &fakeLookup{namespace: "tenant-ns"}
	runID := uuid.New()
	c := New(fake, lookup, "choruskube")
	execID := uuid.New()

	if err := c.CompleteActivity(context.Background(), execID, "choruskube-run-"+runID.String(), "ok", "{}", ""); err != nil {
		t.Fatalf("CompleteActivity = %v", err)
	}

	if fake.namespace != "tenant-ns" {
		t.Fatalf("namespace = %q, want the run's namespace", fake.namespace)
	}
	if fake.activityID != execID.String() {
		t.Fatalf("activityID = %q, want the node execution id", fake.activityID)
	}
}

func TestRecordHeartbeatUsesTheRunsNamespace(t *testing.T) {
	fake := &fakeCompletions{}
	lookup := &fakeLookup{namespace: "tenant-ns"}
	runID := uuid.New()
	c := New(fake, lookup, "choruskube")

	if err := c.RecordHeartbeat(context.Background(), uuid.New(), "choruskube-run-"+runID.String()); err != nil {
		t.Fatalf("RecordHeartbeat = %v", err)
	}

	if fake.namespace != "tenant-ns" {
		t.Fatalf("namespace = %q, want the run's namespace", fake.namespace)
	}
}

// A run's placement never changes, so a second callback for the same run must not re-ask.
func TestNamespaceIsCachedPerRun(t *testing.T) {
	lookup := &fakeLookup{namespace: "tenant-ns"}
	c := New(&fakeCompletions{}, lookup, "choruskube")
	workflowID := "choruskube-run-" + uuid.New().String()

	_ = c.RecordHeartbeat(context.Background(), uuid.New(), workflowID)
	_ = c.RecordHeartbeat(context.Background(), uuid.New(), workflowID)

	if got := lookup.calls.Load(); got != 1 {
		t.Fatalf("lookup calls = %d, want 1", got)
	}
}

// A lookup failure must not drop the completion: the configured namespace is where every
// single-namespace deployment's runs live, and a dropped completion hangs the run instead.
func TestLookupFailureFallsBackToTheConfiguredNamespace(t *testing.T) {
	fake := &fakeCompletions{}
	lookup := &fakeLookup{namespace: "tenant-ns", err: errors.New("api server down")}
	c := New(fake, lookup, "choruskube")

	_ = c.RecordHeartbeat(context.Background(), uuid.New(), "choruskube-run-"+uuid.New().String())

	if fake.namespace != "choruskube" {
		t.Fatalf("namespace = %q, want the configured fallback", fake.namespace)
	}
}

// A run predating the temporal_namespace column reports an empty namespace with no error
// (the api-server's documented behavior for that case), which must fall back the same as an
// outright lookup failure rather than address Temporal with an empty namespace string.
func TestEmptyNamespaceFallsBackToTheConfiguredNamespace(t *testing.T) {
	fake := &fakeCompletions{}
	lookup := &fakeLookup{namespace: ""}
	c := New(fake, lookup, "choruskube")

	_ = c.RecordHeartbeat(context.Background(), uuid.New(), "choruskube-run-"+uuid.New().String())

	if fake.namespace != "choruskube" {
		t.Fatalf("namespace = %q, want the configured fallback", fake.namespace)
	}
}

func TestUnparseableWorkflowIDFallsBackToTheConfiguredNamespace(t *testing.T) {
	fake := &fakeCompletions{}
	c := New(fake, &fakeLookup{namespace: "tenant-ns"}, "choruskube")

	_ = c.RecordHeartbeat(context.Background(), uuid.New(), "not-a-run-id")

	if fake.namespace != "choruskube" {
		t.Fatalf("namespace = %q, want the configured fallback", fake.namespace)
	}
}

// Callbacks for the same run can land on the completer from concurrent HTTP handlers, so the
// cache must not corrupt under -race even when many goroutines race to fill it.
func TestNamespaceForIsSafeUnderConcurrentCallbacks(t *testing.T) {
	c := New(&fakeCompletions{}, &fakeLookup{namespace: "tenant-ns"}, "choruskube")
	workflowID := "choruskube-run-" + uuid.New().String()

	var wg sync.WaitGroup
	for range 50 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c.namespaceFor(context.Background(), workflowID)
		}()
	}
	wg.Wait()
}
