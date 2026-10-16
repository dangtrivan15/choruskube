package workflow

import (
	"time"

	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/workflow"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
)

// taskIssueCloseWorkerOptions route the GitHub step to the Fleet's Worker. No schedule-to-start
// bound, so a Fleet with no live Worker leaves the close waiting rather than dropping it; the
// attempts count only runs a Worker actually started.
func taskIssueCloseWorkerOptions(workerTaskQueue string) workflow.ActivityOptions {
	return workflow.ActivityOptions{
		TaskQueue:           workerTaskQueue,
		StartToCloseTimeout: time.Minute,
		RetryPolicy: &temporal.RetryPolicy{
			InitialInterval:    30 * time.Second,
			BackoffCoefficient: 2,
			MaximumInterval:    30 * time.Minute,
			MaximumAttempts:    10,
		},
	}
}

// taskIssueCloseAPIOptions are for the record step the API server answers.
func taskIssueCloseAPIOptions() workflow.ActivityOptions {
	return workflow.ActivityOptions{
		StartToCloseTimeout: time.Minute,
		RetryPolicy: &temporal.RetryPolicy{
			InitialInterval:    5 * time.Second,
			BackoffCoefficient: 2,
			MaximumInterval:    5 * time.Minute,
			MaximumAttempts:    10,
		},
	}
}

// CloseTaskIssueWorkflow closes a completed Task's linked GitHub issue from the run's Worker, then
// records it closed on the API. A refusal from GitHub completes the workflow with nothing recorded.
func CloseTaskIssueWorkflow(ctx workflow.Context, p activity.CloseTaskIssueParams) error {
	logger := workflow.GetLogger(ctx)
	if p.WorkerTaskQueue == "" || p.IssueNumber <= 0 {
		return temporal.NewNonRetryableApplicationError(
			"close task issue needs a Worker task queue and a positive issue number", "TaskIssueCloseInput", nil)
	}

	var acts *activity.Activities
	var res activity.TaskIssueCloseResult
	if err := workflow.ExecuteActivity(
		workflow.WithActivityOptions(ctx, taskIssueCloseWorkerOptions(p.WorkerTaskQueue)),
		acts.CloseTaskIssue,
		activity.TaskIssueCloseParams{TaskID: p.TaskID, RunID: p.RunID, OwnerRepo: p.OwnerRepo, IssueNumber: p.IssueNumber},
	).Get(ctx, &res); err != nil {
		logger.Error("task issue close failed", "taskID", p.TaskID, "issue", p.IssueNumber, "error", err)
		return err
	}
	if res.Outcome != activity.TaskIssueClosed {
		logger.Warn("task issue close refused", "taskID", p.TaskID, "issue", p.IssueNumber,
			"outcome", res.Outcome, "status", res.Status)
		return nil
	}

	return workflow.ExecuteActivity(
		workflow.WithActivityOptions(ctx, taskIssueCloseAPIOptions()),
		acts.RecordTaskIssueClosed,
		activity.RecordTaskIssueClosedParams{TaskID: p.TaskID, IssueNumber: p.IssueNumber},
	).Get(ctx, nil)
}
