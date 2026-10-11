package credential

import (
	"context"
	"errors"
	"fmt"

	"github.com/google/uuid"
)

// TokenClient fetches a run's GitHub token from the API server. *workload.Client satisfies it.
type TokenClient interface {
	GitHubToken(ctx context.Context, runID uuid.UUID) (string, error)
}

type controlPlane struct {
	client TokenClient
}

// NewControlPlane returns a Provider that asks the API server for each run's GitHub token. It
// supplies no agent credentials: workload prepare already returns them.
func NewControlPlane(c TokenClient) Provider {
	return &controlPlane{client: c}
}

func (p *controlPlane) GitHubToken(ctx context.Context, runID uuid.UUID) (Token, error) {
	value, err := p.client.GitHubToken(ctx, runID)
	if err != nil {
		return Token{}, fmt.Errorf("control plane GitHub token: %w", err)
	}
	if value == "" {
		return Token{}, errors.New("control plane returned an empty GitHub token")
	}
	return Token{Value: value}, nil
}

func (p *controlPlane) AgentCredentials(context.Context, uuid.UUID) (Agent, bool, error) {
	return Agent{}, false, nil
}
