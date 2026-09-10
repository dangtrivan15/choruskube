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
	// reattachClient returns a client able to complete/heartbeat by id for an execution this
	// Worker never launched (a previous Worker did, before a restart), together with the Temporal
	// namespace that client serves. By-id addressing needs only the namespace, so any client on it
	// works; it is unambiguous only while the Worker serves a single Temporal namespace, and errors
	// rather than guess when it serves several.
	reattachClient() (cl client.Client, namespace string, err error)
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

// resolve recovers the addressing to complete or heartbeat an execution by id: the Temporal client
// and the PendingCompletion identifying the activity on it. The launching Worker reads both from
// the cache it populated; a Worker that inherited the execution across a restart holds neither and
// rebuilds them from the run and execution ids instead (see the cache-miss branch).
func (c *activityCompleter) resolve(runID, executionID uuid.UUID) (temporalCompletions, activity.PendingCompletion, error) {
	if p, ok := c.pending.Get(executionID); ok {
		cl := c.clients.clientFor(p.Namespace, p.TaskQueue)
		if cl == nil {
			return nil, activity.PendingCompletion{}, fmt.Errorf(
				"no Temporal client serving namespace=%s taskQueue=%s for execution %s", p.Namespace, p.TaskQueue, executionID)
		}
		return cl, p, nil
	}

	// Cache miss: a previous Worker launched this execution and a restart emptied the cache it
	// held, while the agent pod it started keeps calling back to whichever Worker now answers the
	// Service. Rebuild the by-id addressing -- workflow id from the run id, activity id from the
	// execution id, both fixed in Temporal history at schedule time -- and relay through a served
	// client. Without this every in-flight node is stranded until its heartbeat timeout on any
	// Worker redeploy.
	if runID == uuid.Nil {
		return nil, activity.PendingCompletion{}, fmt.Errorf(
			"cannot reattach execution %s: callback carried no run id to rebuild its workflow id", executionID)
	}
	cl, namespace, err := c.clients.reattachClient()
	if err != nil {
		return nil, activity.PendingCompletion{}, fmt.Errorf("cannot reattach execution %s: %w", executionID, err)
	}
	return cl, activity.PendingCompletion{
		Namespace:  namespace,
		WorkflowID: activity.WorkflowID(runID),
		ActivityID: executionID.String(),
	}, nil
}

// Complete reports the agent's outcome to the Temporal activity waiting on it. "completed" and
// "rate_limited" report a successful result (so the workflow can advance or sleep-and-requeue);
// every other status fails the activity so the workflow's retry/error path runs.
func (c *activityCompleter) Complete(ctx context.Context, req callback.CompletionRequest) error {
	cl, p, err := c.resolve(req.RunID, req.NodeExecutionID)
	if err != nil {
		return err
	}

	var result interface{}
	var activityErr error
	switch req.Status {
	case "completed":
		result = activity.CallbackResult{
			Status:       req.Status,
			Result:       req.Result,
			ArtifactRefs: string(req.ArtifactRefs),
			ErrorMessage: req.ErrorMessage,
			SessionID:    req.SessionID,
		}
	case "rate_limited":
		result = activity.CallbackResult{
			Status:              "rate_limited",
			ResumeAt:            req.ResumeAt,
			SessionID:           req.SessionID,
			SessionArtifactPath: req.SessionArtifactPath,
		}
	default:
		activityErr = fmt.Errorf("agent reported status %q: %s", req.Status, req.ErrorMessage)
	}

	if err := cl.CompleteActivityByID(ctx, p.Namespace, p.WorkflowID, "", p.ActivityID, result, activityErr); err != nil {
		return fmt.Errorf("complete activity by id: %w", err)
	}
	c.pending.Remove(req.NodeExecutionID)
	return nil
}

// Fail fails the Temporal activity without a result, so the workflow's retry/error path runs.
// This is the handler's direct entry point for cases where the callback is rejected before it
// reaches the normal Complete path (e.g. empty-result rejection).
func (c *activityCompleter) Fail(ctx context.Context, runID, executionID uuid.UUID, reason error) error {
	cl, p, err := c.resolve(runID, executionID)
	if err != nil {
		return err
	}
	if err := cl.CompleteActivityByID(ctx, p.Namespace, p.WorkflowID, "", p.ActivityID, nil, reason); err != nil {
		return fmt.Errorf("fail activity by id: %w", err)
	}
	c.pending.Remove(executionID)
	return nil
}

// RecordHeartbeat proxies an agent's liveness ping to the Temporal activity it is running,
// keeping it from timing out. The pending entry is left in place either way -- a heartbeat that
// arrives after the activity's own completion is an expected race, not a reason to forget the
// addressing a retried or still-in-flight completion would need.
func (c *activityCompleter) RecordHeartbeat(ctx context.Context, runID, executionID uuid.UUID) error {
	cl, p, err := c.resolve(runID, executionID)
	if err != nil {
		return err
	}
	return cl.RecordActivityHeartbeatByID(ctx, p.Namespace, p.WorkflowID, "", p.ActivityID)
}
