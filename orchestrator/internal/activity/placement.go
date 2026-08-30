package activity

import (
	"context"

	"github.com/google/uuid"
)

type CheckNodePlacementParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
}

type CheckNodePlacementResult struct {
	Allowed bool   `json:"allowed"`
	Reason  string `json:"reason"`
}

// CheckNodePlacement asks whether this node may be dispatched to the run's task queue.
// A transport failure is returned as an error, not as a permissive decision: the caller's
// retry policy is what decides how an unreachable check is treated, and answering
// "allowed" here would make an outage indistinguishable from an approval.
func (a *Activities) CheckNodePlacement(ctx context.Context, params CheckNodePlacementParams) (CheckNodePlacementResult, error) {
	d, err := a.client.CheckNodePlacement(ctx, params.RunID, params.NodeExecutionID)
	if err != nil {
		return CheckNodePlacementResult{}, err
	}
	return CheckNodePlacementResult{Allowed: d.Allowed, Reason: d.Reason}, nil
}
