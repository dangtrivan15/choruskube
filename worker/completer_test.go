package worker

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"go.temporal.io/sdk/client"

	"github.com/dangtrivan15/choruskube/worker/activity"
	"github.com/dangtrivan15/choruskube/worker/callback"
)

// fakePending is a minimal pendingLookup: a plain map plus a removed-set, so a test can assert
// both what was cached and whether Complete cleaned it up afterward.
type fakePending struct {
	entries map[uuid.UUID]activity.PendingCompletion
	removed map[uuid.UUID]bool
}

func newFakePending() *fakePending {
	return &fakePending{entries: map[uuid.UUID]activity.PendingCompletion{}, removed: map[uuid.UUID]bool{}}
}

func (f *fakePending) Get(id uuid.UUID) (activity.PendingCompletion, bool) {
	p, ok := f.entries[id]
	return p, ok
}

func (f *fakePending) Remove(id uuid.UUID) { f.removed[id] = true }

var _ pendingLookup = (*fakePending)(nil)

type completeCall struct {
	namespace, workflowID, activityID string
	result                            interface{}
	err                               error
}

type heartbeatCall struct {
	namespace, workflowID, activityID string
}

// fakeTemporalClient satisfies the full client.Client interface by embedding it (nil) and
// overriding only the two methods activityCompleter calls. Any other method panics if
// exercised, which fails a test loudly instead of masking an unexpected call with a quiet
// zero value.
type fakeTemporalClient struct {
	client.Client
	completeErr error
	calls       []completeCall
	heartbeats  []heartbeatCall
}

func (f *fakeTemporalClient) CompleteActivityByID(ctx context.Context, namespace, workflowID, runID, activityID string, result interface{}, err error) error {
	f.calls = append(f.calls, completeCall{namespace, workflowID, activityID, result, err})
	return f.completeErr
}

func (f *fakeTemporalClient) RecordActivityHeartbeatByID(ctx context.Context, namespace, workflowID, runID, activityID string, details ...interface{}) error {
	f.heartbeats = append(f.heartbeats, heartbeatCall{namespace, workflowID, activityID})
	return nil
}

var _ client.Client = (*fakeTemporalClient)(nil)

// singleClientResolver is a clientResolver serving exactly one Fleet -- enough for these tests
// without fleetSupervisor's real dial-and-map machinery.
type singleClientResolver struct {
	namespace, taskQueue string
	client               client.Client
}

func (r singleClientResolver) clientFor(namespace, taskQueue string) client.Client {
	if namespace != r.namespace || taskQueue != r.taskQueue {
		return nil
	}
	return r.client
}

var _ clientResolver = singleClientResolver{}

func TestActivityCompleter_Complete_CompletedStatus(t *testing.T) {
	execID := uuid.New()
	pending := newFakePending()
	pending.entries[execID] = activity.PendingCompletion{
		Namespace: "org-ns", TaskQueue: "org-q", WorkflowID: "choruskube-run-x", ActivityID: execID.String(),
	}
	fake := &fakeTemporalClient{}
	resolver := singleClientResolver{namespace: "org-ns", taskQueue: "org-q", client: fake}

	c := newActivityCompleter(pending, resolver)
	err := c.Complete(context.Background(), callback.CompletionRequest{
		NodeExecutionID: execID,
		Status:          "completed",
		Result:          "the result",
		ArtifactRefs:    []byte(`{"output":"path"}`),
	})
	assert.NoError(t, err)

	if assert.Len(t, fake.calls, 1) {
		call := fake.calls[0]
		assert.Equal(t, "org-ns", call.namespace)
		assert.Equal(t, "choruskube-run-x", call.workflowID)
		assert.Equal(t, execID.String(), call.activityID)
		assert.Nil(t, call.err)
		result, ok := call.result.(activity.CallbackResult)
		if assert.True(t, ok, "result must be an activity.CallbackResult") {
			assert.Equal(t, "the result", result.Result)
			assert.Equal(t, `{"output":"path"}`, result.ArtifactRefs)
		}
	}
	assert.True(t, pending.removed[execID], "a successful completion must clear the pending entry")
}

func TestActivityCompleter_Complete_FailedStatus_FailsTheActivity(t *testing.T) {
	execID := uuid.New()
	pending := newFakePending()
	pending.entries[execID] = activity.PendingCompletion{Namespace: "ns", TaskQueue: "q", WorkflowID: "wf", ActivityID: "a"}
	fake := &fakeTemporalClient{}
	resolver := singleClientResolver{namespace: "ns", taskQueue: "q", client: fake}

	c := newActivityCompleter(pending, resolver)
	err := c.Complete(context.Background(), callback.CompletionRequest{
		NodeExecutionID: execID,
		Status:          "failed",
		ErrorMessage:    "boom",
	})
	assert.NoError(t, err)

	if assert.Len(t, fake.calls, 1) {
		assert.Nil(t, fake.calls[0].result, "a failed status must not report a result")
		assert.Error(t, fake.calls[0].err)
	}
	assert.True(t, pending.removed[execID])
}

func TestActivityCompleter_Complete_NoPendingEntry_ReturnsErrorWithoutCallingTemporal(t *testing.T) {
	fake := &fakeTemporalClient{}
	c := newActivityCompleter(newFakePending(), singleClientResolver{client: fake})

	err := c.Complete(context.Background(), callback.CompletionRequest{NodeExecutionID: uuid.New(), Status: "completed"})
	assert.Error(t, err)
	assert.Empty(t, fake.calls)
}

func TestActivityCompleter_Complete_NoMatchingClient_ReturnsErrorAndKeepsPending(t *testing.T) {
	execID := uuid.New()
	pending := newFakePending()
	pending.entries[execID] = activity.PendingCompletion{Namespace: "ns", TaskQueue: "q"}
	// resolver serves a different namespace/taskQueue than the pending entry names.
	resolver := singleClientResolver{namespace: "other-ns", taskQueue: "other-q", client: &fakeTemporalClient{}}

	c := newActivityCompleter(pending, resolver)
	err := c.Complete(context.Background(), callback.CompletionRequest{NodeExecutionID: execID, Status: "completed"})
	assert.Error(t, err)
	assert.False(t, pending.removed[execID], "an unaddressable completion must not be dropped from the cache")
}

func TestActivityCompleter_Complete_TemporalError_KeepsPendingForRetry(t *testing.T) {
	execID := uuid.New()
	pending := newFakePending()
	pending.entries[execID] = activity.PendingCompletion{Namespace: "ns", TaskQueue: "q", WorkflowID: "wf", ActivityID: "a"}
	fake := &fakeTemporalClient{completeErr: errors.New("temporal unavailable")}
	c := newActivityCompleter(pending, singleClientResolver{namespace: "ns", taskQueue: "q", client: fake})

	err := c.Complete(context.Background(), callback.CompletionRequest{NodeExecutionID: execID, Status: "completed"})
	assert.Error(t, err)
	assert.False(t, pending.removed[execID], "a failed Temporal call must not be treated as delivered")
}

func TestActivityCompleter_RecordHeartbeat(t *testing.T) {
	execID := uuid.New()
	pending := newFakePending()
	pending.entries[execID] = activity.PendingCompletion{Namespace: "ns", TaskQueue: "q", WorkflowID: "wf", ActivityID: "a"}
	fake := &fakeTemporalClient{}
	c := newActivityCompleter(pending, singleClientResolver{namespace: "ns", taskQueue: "q", client: fake})

	err := c.RecordHeartbeat(context.Background(), execID)
	assert.NoError(t, err)
	if assert.Len(t, fake.heartbeats, 1) {
		assert.Equal(t, "wf", fake.heartbeats[0].workflowID)
		assert.Equal(t, "a", fake.heartbeats[0].activityID)
	}
	assert.False(t, pending.removed[execID], "a heartbeat must not clear the pending entry -- the activity has not completed")
}
