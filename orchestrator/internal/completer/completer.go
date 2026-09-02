// Package completer completes and heartbeats agent-step activities from outside the Temporal
// worker, driven by the HTTP callbacks agent pods send.
package completer

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
)

// completions is the slice of client.Client this package uses. Narrow on purpose: the real
// client cannot be constructed without dialling Temporal, and everything here is addressing
// logic worth testing without one.
type completions interface {
	CompleteActivityByID(ctx context.Context, namespace, workflowID, runID, activityID string, result interface{}, err error) error
	RecordActivityHeartbeatByID(ctx context.Context, namespace, workflowID, runID, activityID string, details ...interface{}) error
}

// Completer implements callback.ActivityCompleter and callback.Heartbeater.
type Completer struct {
	completions      completions
	defaultNamespace string
}

func New(c completions, defaultNamespace string) *Completer {
	return &Completer{completions: c, defaultNamespace: defaultNamespace}
}

func (c *Completer) namespaceFor(ctx context.Context, workflowID string) string {
	return c.defaultNamespace
}

func (c *Completer) CompleteActivity(ctx context.Context, nodeExecID uuid.UUID, workflowID string, result, artifactRefs, errorMessage string) error {
	return c.completions.CompleteActivityByID(ctx, c.namespaceFor(ctx, workflowID), workflowID, "", nodeExecID.String(),
		activity.CallbackResult{
			Status:       "completed",
			Result:       result,
			ArtifactRefs: artifactRefs,
			ErrorMessage: errorMessage,
		}, nil)
}

func (c *Completer) FailActivity(ctx context.Context, nodeExecID uuid.UUID, workflowID string, reason error) error {
	return c.completions.CompleteActivityByID(
		ctx, c.namespaceFor(ctx, workflowID), workflowID, "", nodeExecID.String(), nil, reason)
}

func (c *Completer) CompleteActivityRateLimited(ctx context.Context, nodeExecID uuid.UUID, workflowID string, resumeAt time.Time, sessionID, sessionArtifactPath string) error {
	return c.completions.CompleteActivityByID(ctx, c.namespaceFor(ctx, workflowID), workflowID, "", nodeExecID.String(),
		activity.CallbackResult{
			Status:              "rate_limited",
			ResumeAt:            resumeAt,
			SessionID:           sessionID,
			SessionArtifactPath: sessionArtifactPath,
		}, nil)
}

func (c *Completer) RecordHeartbeat(ctx context.Context, nodeExecID uuid.UUID, workflowID string) error {
	return c.completions.RecordActivityHeartbeatByID(
		ctx, c.namespaceFor(ctx, workflowID), workflowID, "", nodeExecID.String())
}
