// Package credential is where a Worker gets the credentials its runs need: a GitHub token for a
// run, and optionally the Claude token and registry login its agent pods launch with.
//
// ControlPlane asks the API server per run. Local reads the Worker's own mounted folder.
package credential

import (
	"context"
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

// Provider supplies a Worker's credentials.
type Provider interface {
	GitHubToken(ctx context.Context, runID uuid.UUID) (Token, error)
	// ok=false: this provider supplies no agent credentials; use what prepare returned.
	AgentCredentials(ctx context.Context, runID uuid.UUID) (agent Agent, ok bool, err error)
}
