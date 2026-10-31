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

// NewControlPlane returns a Provider that asks the API server for each run's GitHub token.
// AgentCredentials passes through whatever the API server attached at prepare, rather than
// holding any credential of its own.
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

// AgentCredentials passes attached straight through: the API server is this Worker's credential
// source, so a Claude-running node with nothing attached has nowhere else to get a token from.
func (p *controlPlane) AgentCredentials(_ context.Context, runID uuid.UUID, attached Agent, runsClaude bool) (Agent, error) {
	if runsClaude && attached.ClaudeOAuthToken == "" {
		return Agent{}, fmt.Errorf("%w: the control plane attached none for run %s (CREDENTIAL_SOURCE=control-plane, "+
			"so the API server supplies this Worker's credentials)", ErrNoClaudeToken, runID)
	}
	if !runsClaude {
		attached.ClaudeOAuthToken = ""
	}
	return attached, nil
}
