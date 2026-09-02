// Package completer completes and heartbeats agent-step activities from outside the Temporal
// worker, driven by the HTTP callbacks agent pods send.
package completer

import (
	"context"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

// completions is the slice of client.Client this package uses. Narrow on purpose: the real
// client cannot be constructed without dialling Temporal, and everything here is addressing
// logic worth testing without one.
type completions interface {
	CompleteActivityByID(ctx context.Context, namespace, workflowID, runID, activityID string, result interface{}, err error) error
	RecordActivityHeartbeatByID(ctx context.Context, namespace, workflowID, runID, activityID string, details ...interface{}) error
}

// PlacementLookup answers where a run's workflow lives.
type PlacementLookup interface {
	GetRunNamespace(ctx context.Context, runID uuid.UUID) (apiclient.RunNamespace, error)
}

// workflowIDPrefix is how the workflow package builds a run's Temporal workflow id; stripping
// it recovers the run id namespaceFor needs to look up.
const workflowIDPrefix = "choruskube-run-"

// Completer implements callback.ActivityCompleter and callback.Heartbeater.
type Completer struct {
	completions      completions
	lookup           PlacementLookup
	defaultNamespace string

	mu    sync.RWMutex
	byRun map[uuid.UUID]string
}

func New(c completions, lookup PlacementLookup, defaultNamespace string) *Completer {
	return &Completer{
		completions:      c,
		lookup:           lookup,
		defaultNamespace: defaultNamespace,
		byRun:            map[uuid.UUID]string{},
	}
}

// namespaceFor resolves the run's namespace from its workflow id, caching the answer for the
// life of the process. Every failure path returns the configured namespace rather than an
// error: a completion that never reaches Temporal hangs the run until its activity times out,
// which is strictly worse than addressing the namespace every single-namespace run lives in.
func (c *Completer) namespaceFor(ctx context.Context, workflowID string) string {
	runID, err := uuid.Parse(strings.TrimPrefix(workflowID, workflowIDPrefix))
	if err != nil {
		return c.defaultNamespace
	}

	c.mu.RLock()
	ns, ok := c.byRun[runID]
	c.mu.RUnlock()
	if ok {
		return ns
	}

	result, err := c.lookup.GetRunNamespace(ctx, runID)
	if err != nil || result.Namespace == "" {
		log.Printf("WARN: could not resolve namespace for run %s, using %s: %v", runID, c.defaultNamespace, err)
		return c.defaultNamespace
	}

	c.mu.Lock()
	c.byRun[runID] = result.Namespace
	c.mu.Unlock()
	return result.Namespace
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
