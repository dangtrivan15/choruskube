package activity

import (
	"context"
	"fmt"
	"strings"

	"github.com/google/uuid"
	temporalactivity "go.temporal.io/sdk/activity"
)

// The API server assigns this workflow id and it is fixed in history, so it is the one statement
// of which run an activity belongs to that the activity's own parameters cannot contradict.
const workflowIDPrefix = "choruskube-run-"

func runIDFromWorkflowID(workflowID string) (uuid.UUID, error) {
	if !strings.HasPrefix(workflowID, workflowIDPrefix) {
		return uuid.Nil, fmt.Errorf("workflow id %q is not a run's", workflowID)
	}
	id, err := uuid.Parse(strings.TrimPrefix(workflowID, workflowIDPrefix))
	if err != nil {
		return uuid.Nil, fmt.Errorf("workflow id %q carries no run id: %w", workflowID, err)
	}
	return id, nil
}

// activityInfo is temporalactivity.GetInfo behind a seam: GetInfo panics outside a real activity
// context, and the SDK's test harness cannot pin a chosen workflow id. Reassigned only from
// _test.go, and never from a test calling t.Parallel -- the swap is process-wide.
var activityInfo = temporalactivity.GetInfo

func runIDOf(ctx context.Context) (uuid.UUID, error) {
	return runIDFromWorkflowID(activityInfo(ctx).WorkflowExecution.ID)
}

// RunIDOf exposes runIDOf to other packages (the multi-tenant overlay resolves a run's org from
// the activity context the same way an activity here does).
func RunIDOf(ctx context.Context) (uuid.UUID, error) {
	return runIDOf(ctx)
}
