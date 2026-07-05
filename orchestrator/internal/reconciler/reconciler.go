package reconciler

import (
	"context"
	"log"
	"time"

	"github.com/google/uuid"
)

// APIClient is the subset of the API client used by the reconciler.
type APIClient interface {
	GetWorkflowRunStatus(ctx context.Context, runID uuid.UUID) (string, error)
	TerminateWorkload(ctx context.Context, executionID uuid.UUID) error
	ListWorkloads(ctx context.Context) ([]WorkloadInfo, error)
}

// WorkloadInfo describes a running workload (matches the API server response).
type WorkloadInfo struct {
	NodeExecutionID uuid.UUID
	RunID           uuid.UUID
	ExecutionHandle string
}

// Config holds reconciler configuration.
type Config struct {
	Interval         time.Duration // How often to run reconciliation (default: 5min)
	TerminalStatuses map[string]bool
}

// DefaultConfig returns the default reconciler configuration.
func DefaultConfig() Config {
	return Config{
		Interval: 5 * time.Minute,
		TerminalStatuses: map[string]bool{
			"completed": true,
			"failed":    true,
			"cancelled": true,
		},
	}
}

// Reconciler periodically scans for orphaned workloads and terminates them.
// A workload is considered orphaned if its parent workflow run is in a terminal state
// (completed/failed/cancelled). Terminated Jobs are preserved for debugging —
// TTLSecondsAfterFinished (24h) on the Job spec handles final cleanup.
type Reconciler struct {
	apiClient APIClient
	config    Config
}

// New creates a new Reconciler.
func New(apiClient APIClient, cfg Config) *Reconciler {
	return &Reconciler{
		apiClient: apiClient,
		config:    cfg,
	}
}

// Start begins the reconciliation loop. It performs an immediate scan on startup,
// then runs periodically at the configured interval. It stops when ctx is cancelled.
func (r *Reconciler) Start(ctx context.Context) {
	// Immediate startup scan to catch orphans from downtime
	log.Println("reconciler: running startup scan")
	r.reconcileOnce(ctx)

	ticker := time.NewTicker(r.config.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			log.Println("reconciler: shutting down")
			return
		case <-ticker.C:
			r.reconcileOnce(ctx)
		}
	}
}

// reconcileOnce performs a single reconciliation pass.
func (r *Reconciler) reconcileOnce(ctx context.Context) {
	workloads, err := r.apiClient.ListWorkloads(ctx)
	if err != nil {
		log.Printf("reconciler: failed to list workloads: %v", err)
		return
	}

	if len(workloads) == 0 {
		return
	}

	log.Printf("reconciler: found %d agent workloads", len(workloads))

	// Cache run status lookups to avoid redundant API calls
	runStatusCache := make(map[string]string)

	for _, wl := range workloads {
		runIDStr := wl.RunID.String()
		if wl.RunID == uuid.Nil {
			continue // no run ID — skip
		}

		// Look up run status (with caching)
		status, ok := runStatusCache[runIDStr]
		if !ok {
			status, err = r.apiClient.GetWorkflowRunStatus(ctx, wl.RunID)
			if err != nil {
				// API server unavailable or run not found — degrade gracefully.
				log.Printf("reconciler: failed to get status for run %s: %v", runIDStr, err)
				// Mark as unknown so we don't keep retrying this tick
				runStatusCache[runIDStr] = "unknown"
				continue
			}
			runStatusCache[runIDStr] = status
		}

		// If the run is in a terminal state, terminate the execution.
		if r.config.TerminalStatuses[status] {
			log.Printf("reconciler: terminating orphaned workload %s (run %s status: %s)",
				wl.ExecutionHandle, runIDStr, status)
			if err := r.apiClient.TerminateWorkload(ctx, wl.NodeExecutionID); err != nil {
				log.Printf("reconciler: failed to terminate orphaned workload %s: %v", wl.ExecutionHandle, err)
			}
		}
	}
}
