package activity

import (
	"context"
	"fmt"
	"strings"

	"github.com/google/uuid"
	"go.temporal.io/sdk/temporal"
)

// --- Task issue close ---

// taskIssueCloseWorkflowIDPrefix is the api-server's: it assigns the close workflow's id, and the
// Worker copies the prefix verbatim. A change on any one side makes every close fail its binding.
const taskIssueCloseWorkflowIDPrefix = "choruskube-task-issue-close-"

// taskIssueCloseBindingType is the application error type of an activity whose Task id its
// workflow id contradicts, the same type the Worker's binding answers with.
const taskIssueCloseBindingType = "TaskIssueCloseBinding"

func taskIDFromIssueCloseWorkflowID(workflowID string) (uuid.UUID, error) {
	if !strings.HasPrefix(workflowID, taskIssueCloseWorkflowIDPrefix) {
		return uuid.Nil, fmt.Errorf("workflow id %q is not a task issue close's", workflowID)
	}
	id, err := uuid.Parse(strings.TrimPrefix(workflowID, taskIssueCloseWorkflowIDPrefix))
	if err != nil {
		return uuid.Nil, fmt.Errorf("workflow id %q carries no task id: %w", workflowID, err)
	}
	return id, nil
}

// guardTaskIssueClose confines an activity to the Task whose close workflow scheduled it: the
// same hijack protection guardRun gives run activities.
func guardTaskIssueClose(ctx context.Context, claimed uuid.UUID) error {
	actual, err := taskIDFromIssueCloseWorkflowID(activityInfo(ctx).WorkflowExecution.ID)
	if err != nil {
		return temporal.NewNonRetryableApplicationError(err.Error(), taskIssueCloseBindingType, err)
	}
	if actual != claimed {
		return temporal.NewNonRetryableApplicationError(
			fmt.Sprintf("activity claims task %s but was scheduled by task %s", claimed, actual),
			taskIssueCloseBindingType, nil)
	}
	return nil
}

// The Worker's task-issue-close types, copied verbatim: the orchestrator and the Worker are
// separate Go modules, and Temporal's JSON converter makes these tags the wire contract, so a
// rename on one side blanks fields silently instead of failing.

// The outcomes the Worker answers with, in TaskIssueCloseResult.Outcome.
const (
	TaskIssueClosed  = "CLOSED"
	TaskIssueRefused = "REFUSED"
)

// TaskIssueCloseParams is the Worker step's input, dispatched to the Fleet's task queue.
type TaskIssueCloseParams struct {
	TaskID      uuid.UUID `json:"taskId"`
	RunID       uuid.UUID `json:"runId"`
	OwnerRepo   string    `json:"ownerRepo"`
	IssueNumber int       `json:"issueNumber"`
}

// TaskIssueCloseResult.Status is GitHub's answer to a refusal, and 0 when the issue closed.
type TaskIssueCloseResult struct {
	Outcome string `json:"outcome"`
	Status  int    `json:"status"`
}

// CloseTaskIssueParams is CloseTaskIssueWorkflow's input. The api-server's
// CloseTaskIssueWorkflowInput writes these keys, so a renamed tag blanks a field silently.
type CloseTaskIssueParams struct {
	TaskID          uuid.UUID `json:"taskId"`
	RunID           uuid.UUID `json:"runId"`
	OwnerRepo       string    `json:"ownerRepo"`
	IssueNumber     int       `json:"issueNumber"`
	WorkerTaskQueue string    `json:"workerTaskQueue"`
}

// CloseTaskIssue is dispatched to the Worker's task queue and never runs here. The method exists
// so the workflow can derive the activity type name from it.
func (a *Activities) CloseTaskIssue(_ context.Context, _ TaskIssueCloseParams) (TaskIssueCloseResult, error) {
	return TaskIssueCloseResult{}, fmt.Errorf("activity dispatched to Worker task queue, not the orchestrator")
}

type RecordTaskIssueClosedParams struct {
	TaskID      uuid.UUID `json:"taskId"`
	IssueNumber int       `json:"issueNumber"`
}

// RecordTaskIssueClosed marks the Task's issue linkage closed. Returns the client's error
// untouched: wrapping a rejection here would make the workflow retry it.
func (a *Activities) RecordTaskIssueClosed(ctx context.Context, p RecordTaskIssueClosedParams) error {
	if err := guardTaskIssueClose(ctx, p.TaskID); err != nil {
		return err
	}
	return a.client.MarkTaskIssueClosed(ctx, p.TaskID, p.IssueNumber)
}
