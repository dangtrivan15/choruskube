package worker

import (
	"context"
	"fmt"

	"github.com/google/uuid"
	"go.temporal.io/sdk/client"

	"github.com/dangtrivan15/choruskube/worker/activity"
	"github.com/dangtrivan15/choruskube/worker/callback"
)

// temporalCompletions is the subset of client.Client this package needs to complete and
// heartbeat an activity Temporal already assigned an id to. Declared at the point of use so a
// test can inject a fake without dialling a real Temporal server; client.Client satisfies it
// unchanged.
type temporalCompletions interface {
	CompleteActivityByID(ctx context.Context, namespace, workflowID, runID, activityID string, result interface{}, err error) error
	RecordActivityHeartbeatByID(ctx context.Context, namespace, workflowID, runID, activityID string, details ...interface{}) error
}

var _ temporalCompletions = client.Client(nil)

// pendingLookup is the subset of *activity.PendingCache the completer needs, narrowed so a test
// can inject a fake without constructing Activities.
type pendingLookup interface {
	Get(executionID uuid.UUID) (activity.PendingCompletion, bool)
	Remove(executionID uuid.UUID)
}

var _ pendingLookup = (*activity.PendingCache)(nil)

// clientResolver looks up the Temporal client serving one Fleet by namespace and task queue.
// fleetSupervisor satisfies it; narrowed here so the completer's addressing logic is testable
// without dialling Temporal.
type clientResolver interface {
	clientFor(namespace, taskQueue string) client.Client
}

var _ clientResolver = (*fleetSupervisor)(nil)

// activityCompleter implements callback.ActivityCompleter and callback.Heartbeater by
// completing, or heartbeating, the Temporal activity ExecuteAINodeFromSnapshot is blocked in --
// addressed by the PendingCompletion this Worker cached when it launched the workload locally.
// The agent's completion and heartbeat requests carry only a NodeExecutionID, not enough on
// their own to name a workflow run or the per-Fleet Temporal connection it lives on.
type activityCompleter struct {
	pending pendingLookup
	clients clientResolver
}

func newActivityCompleter(pending pendingLookup, clients clientResolver) *activityCompleter {
	return &activityCompleter{pending: pending, clients: clients}
}

var (
	_ callback.ActivityCompleter = (*activityCompleter)(nil)
	_ callback.Heartbeater       = (*activityCompleter)(nil)
)

// resolve recovers the addressing a pending execution needs: the client dialed for the Fleet
// its activity is polled on, and the PendingCompletion identifying that activity on it.
func (c *activityCompleter) resolve(executionID uuid.UUID) (temporalCompletions, activity.PendingCompletion, error) {
	p, ok := c.pending.Get(executionID)
	if !ok {
		return nil, activity.PendingCompletion{}, fmt.Errorf("no pending Temporal completion cached for execution %s", executionID)
	}
	cl := c.clients.clientFor(p.Namespace, p.TaskQueue)
	if cl == nil {
		return nil, activity.PendingCompletion{}, fmt.Errorf(
			"no Temporal client serving namespace=%s taskQueue=%s for execution %s", p.Namespace, p.TaskQueue, executionID)
	}
	return cl, p, nil
}

// Complete reports the agent's outcome to the Temporal activity waiting on it. Only "completed"
// reports a successful result; every other status -- including the agent's own "failed" -- fails
// the activity so the workflow's retry/error path runs, mirroring the split the orchestrator's
// own completer makes between CompleteActivity and FailActivity.
func (c *activityCompleter) Complete(ctx context.Context, req callback.CompletionRequest) error {
	cl, p, err := c.resolve(req.NodeExecutionID)
	if err != nil {
		return err
	}

	var result interface{}
	var activityErr error
	if req.Status == "completed" {
		result = activity.CallbackResult{
			Status:       req.Status,
			Result:       req.Result,
			ArtifactRefs: string(req.ArtifactRefs),
			ErrorMessage: req.ErrorMessage,
			SessionID:    req.SessionID,
		}
	} else {
		activityErr = fmt.Errorf("agent reported status %q: %s", req.Status, req.ErrorMessage)
	}

	if err := cl.CompleteActivityByID(ctx, p.Namespace, p.WorkflowID, "", p.ActivityID, result, activityErr); err != nil {
		return fmt.Errorf("complete activity by id: %w", err)
	}
	c.pending.Remove(req.NodeExecutionID)
	return nil
}

// RecordHeartbeat proxies an agent's liveness ping to the Temporal activity it is running,
// keeping it from timing out. The pending entry is left in place either way -- a heartbeat that
// arrives after the activity's own completion is an expected race, not a reason to forget the
// addressing a retried or still-in-flight completion would need.
func (c *activityCompleter) RecordHeartbeat(ctx context.Context, executionID uuid.UUID) error {
	cl, p, err := c.resolve(executionID)
	if err != nil {
		return err
	}
	return cl.RecordActivityHeartbeatByID(ctx, p.Namespace, p.WorkflowID, "", p.ActivityID)
}
