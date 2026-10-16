package activity

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"

	"github.com/google/uuid"
	"go.temporal.io/sdk/temporal"

	"github.com/dangtrivan15/choruskube/worker/github"
)

// The api-server assigns this workflow id and the orchestrator copies the prefix verbatim; a rename
// on any side is a silent contract break, not a compile error, and every close then fails binding.
const taskIssueCloseWorkflowIDPrefix = "choruskube-task-issue-close-"

// The outcomes CloseTaskIssue answers with, in TaskIssueCloseResult.Outcome. The orchestrator
// copies these constants verbatim.
const (
	TaskIssueClosed  = "CLOSED"
	TaskIssueRefused = "REFUSED"
)

// The TaskIssueClose* types are copied verbatim by the orchestrator. Temporal's JSON converter
// makes these tags the wire contract, so a rename on one side blanks fields silently instead of
// failing.

// TaskIssueCloseParams is CloseTaskIssue's input. RunID keys the GitHub token; TaskID is bound to
// the workflow id.
type TaskIssueCloseParams struct {
	TaskID      uuid.UUID `json:"taskId"`
	RunID       uuid.UUID `json:"runId"`
	OwnerRepo   string    `json:"ownerRepo"`
	IssueNumber int       `json:"issueNumber"`
}

// TaskIssueCloseResult.Status is GitHub's status for a refusal, and 0 when closed.
type TaskIssueCloseResult struct {
	Outcome string `json:"outcome"`
	Status  int    `json:"status"`
}

// bindTaskIssueClose refuses a Task id the workflow's own id contradicts: the params travel through
// the workflow, but the activity must only ever close the issue of its own Task.
func bindTaskIssueClose(ctx context.Context, taskID uuid.UUID) error {
	workflowID := activityInfo(ctx).WorkflowExecution.ID
	if !strings.HasPrefix(workflowID, taskIssueCloseWorkflowIDPrefix) {
		return temporal.NewNonRetryableApplicationError("task issue close outside a task issue close workflow",
			"TaskIssueCloseBinding", fmt.Errorf("workflow id %q is not a task issue close's", workflowID))
	}
	bound, err := uuid.Parse(strings.TrimPrefix(workflowID, taskIssueCloseWorkflowIDPrefix))
	if err != nil {
		return temporal.NewNonRetryableApplicationError("task issue close outside a task issue close workflow",
			"TaskIssueCloseBinding", fmt.Errorf("workflow id %q carries no task id: %w", workflowID, err))
	}
	if bound != taskID {
		return temporal.NewNonRetryableApplicationError(
			fmt.Sprintf("task issue close for task %s requested by the workflow of task %s", taskID, bound),
			"TaskIssueCloseBinding", nil)
	}
	return nil
}

// CloseTaskIssue closes a completed Task's linked GitHub issue with the run's token. GitHub's 5xx,
// 408, 429 and transport errors fail the step for Temporal to retry; every other non-2xx status is
// a REFUSED outcome, never retried, since no retry changes GitHub's answer.
func (a *Activities) CloseTaskIssue(ctx context.Context, p TaskIssueCloseParams) (TaskIssueCloseResult, error) {
	if err := bindTaskIssueClose(ctx, p.TaskID); err != nil {
		return TaskIssueCloseResult{}, err
	}
	switch {
	case p.RunID == uuid.Nil:
		return TaskIssueCloseResult{}, temporal.NewNonRetryableApplicationError(
			"TaskIssueCloseParams without a run id, which the GitHub token is keyed by", "TaskIssueCloseParams", nil)
	case !github.ValidOwnerRepo(p.OwnerRepo):
		return TaskIssueCloseResult{}, temporal.NewNonRetryableApplicationError(
			fmt.Sprintf("TaskIssueCloseParams with %q, which is not an owner/repo slug", p.OwnerRepo),
			"TaskIssueCloseParams", nil)
	case p.IssueNumber <= 0:
		return TaskIssueCloseResult{}, temporal.NewNonRetryableApplicationError(
			fmt.Sprintf("TaskIssueCloseParams with issue number %d, which is not positive", p.IssueNumber),
			"TaskIssueCloseParams", nil)
	}
	if a.credentials == nil || a.gh == nil {
		return TaskIssueCloseResult{}, errors.New("this Worker has no GitHub credentials or client configured")
	}
	tok, err := a.credentials.GitHubToken(ctx, p.RunID)
	if err != nil {
		return TaskIssueCloseResult{}, fmt.Errorf("obtain GitHub token for run %s: %w", p.RunID, err)
	}
	if strings.TrimSpace(tok.Value) == "" {
		return TaskIssueCloseResult{}, errors.New("credential provider returned a blank GitHub token")
	}

	attrs := []any{"task_id", p.TaskID, "run_id", p.RunID, "repo", p.OwnerRepo, "issue", p.IssueNumber}
	err = a.gh.CloseIssue(ctx, tok.Value, p.OwnerRepo, p.IssueNumber)
	if err == nil {
		slog.Info("task issue close", append(attrs, "outcome", TaskIssueClosed)...)
		return TaskIssueCloseResult{Outcome: TaskIssueClosed, Status: 0}, nil
	}
	if cerr := ctx.Err(); cerr != nil {
		return TaskIssueCloseResult{}, cerr
	}
	var apiErr *github.APIError
	if !errors.As(err, &apiErr) || retryableIssueCloseStatus(apiErr.Status) {
		return TaskIssueCloseResult{}, fmt.Errorf("close issue %s#%d: %w", p.OwnerRepo, p.IssueNumber, err)
	}
	slog.Warn("task issue close", append(attrs, "outcome", TaskIssueRefused, "status", apiErr.Status)...)
	return TaskIssueCloseResult{Outcome: TaskIssueRefused, Status: apiErr.Status}, nil
}

func retryableIssueCloseStatus(status int) bool {
	return status >= 500 || status == http.StatusTooManyRequests || status == http.StatusRequestTimeout
}
