package completer

import (
	"context"
	"testing"

	"github.com/google/uuid"
)

// fakeCompletions records what would have reached Temporal.
type fakeCompletions struct {
	namespace  string
	workflowID string
	activityID string
	calls      int
}

func (f *fakeCompletions) CompleteActivityByID(ctx context.Context, namespace, workflowID, runID, activityID string, result interface{}, err error) error {
	f.namespace, f.workflowID, f.activityID = namespace, workflowID, activityID
	f.calls++
	return nil
}

func (f *fakeCompletions) RecordActivityHeartbeatByID(ctx context.Context, namespace, workflowID, runID, activityID string, details ...interface{}) error {
	f.namespace, f.workflowID, f.activityID = namespace, workflowID, activityID
	f.calls++
	return nil
}

func TestCompleteActivityAddressesTheConfiguredNamespace(t *testing.T) {
	fake := &fakeCompletions{}
	c := &Completer{completions: fake, defaultNamespace: "choruskube"}
	execID := uuid.New()

	if err := c.CompleteActivity(context.Background(), execID, "choruskube-run-x", "ok", "{}", ""); err != nil {
		t.Fatalf("CompleteActivity = %v", err)
	}

	if fake.namespace != "choruskube" {
		t.Fatalf("namespace = %q, want %q", fake.namespace, "choruskube")
	}
	if fake.activityID != execID.String() {
		t.Fatalf("activityID = %q, want the node execution id", fake.activityID)
	}
}

func TestRecordHeartbeatAddressesTheConfiguredNamespace(t *testing.T) {
	fake := &fakeCompletions{}
	c := &Completer{completions: fake, defaultNamespace: "choruskube"}

	if err := c.RecordHeartbeat(context.Background(), uuid.New(), "choruskube-run-x"); err != nil {
		t.Fatalf("RecordHeartbeat = %v", err)
	}

	if fake.namespace != "choruskube" {
		t.Fatalf("namespace = %q, want %q", fake.namespace, "choruskube")
	}
}
