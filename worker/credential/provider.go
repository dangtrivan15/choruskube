// Package credential is where a Worker gets the credentials its runs need: a GitHub token for a
// run, and the Claude token and registry login its agent pods launch with — supplied either by
// the Worker itself (local) or passed through from what the control plane attached (control-plane).
//
// ControlPlane asks the API server per run. Local reads the Worker's own mounted folder; an App
// token it mints is narrowed to the run's GitHub token scope, fetched from the API server, while
// a PAT is passed through unchanged.
package credential

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
)

// Token is a bearer credential for GitHub. A zero ExpiresAt means the lifetime is unknown, and a
// caller must not cache it.
type Token struct {
	Value     string
	ExpiresAt time.Time
}

// Registry is a container registry login for pulling the agent image.
type Registry struct {
	Host     string
	Username string
	Password string
}

// Agent is what an agent pod is launched with that does not come from its run.
type Agent struct {
	ClaudeOAuthToken string
	Registry         *Registry
}

// ErrNoClaudeToken wraps the error AgentCredentials returns when a Claude-running node has no
// Claude token to launch with, so the caller can fail the node non-retryably without parsing a
// message.
var ErrNoClaudeToken = errors.New("no Claude token for this AI node")

// Provider supplies a Worker's credentials.
type Provider interface {
	GitHubToken(ctx context.Context, runID uuid.UUID) (Token, error)

	// AgentCredentials returns what the agent pod launches with. attached is what the control
	// plane attached at prepare; a provider that holds its own credentials ignores it. With
	// runsClaude set and no Claude token to launch with, it returns an error wrapping
	// ErrNoClaudeToken that names where the token belongs.
	AgentCredentials(ctx context.Context, runID uuid.UUID, attached Agent, runsClaude bool) (Agent, error)
}
