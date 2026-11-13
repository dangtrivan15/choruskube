package worker

import (
	"time"

	"github.com/dangtrivan15/choruskube/worker/anthropic"
	credprov "github.com/dangtrivan15/choruskube/worker/credential"
	"github.com/dangtrivan15/choruskube/worker/github"
	"github.com/dangtrivan15/choruskube/worker/registry"
)

// CredentialPlane is where this Worker's credentials come from. It is the seam a binary embedding
// this module uses to supply its own credential source rather than the local one: the open-source
// binary always passes LocalCredentialPlane; another binary built on this module may construct its
// own CredentialPlane around a Provider of its own.
type CredentialPlane struct {
	// Source is the wire value reported at registration, so the API server can tell providers
	// apart (e.g. refusing a declared source it cannot serve).
	Source string
	// New builds this plane's Provider. Called once, after the API-server client this Worker
	// will use for the rest of its life exists, so a plane that itself calls the API server (for
	// a run's GitHub token scope, as the local plane does) can bind to it.
	New func(PlaneDeps) credprov.Provider
}

// PlaneDeps is what Run hands a CredentialPlane's New to build its Provider.
type PlaneDeps struct {
	// API is the Worker's own API-server client, already authenticated with the credential this
	// process registered with.
	API credprov.ScopeClient
	// Credential returns the live (rotating) credential this process presents to the API server.
	// A plane whose Provider makes its own calls elsewhere, outside API, reads this rather than
	// capturing a value that a later renewal would make stale.
	Credential func() string
}

const (
	defaultCredentialsDir  = "/etc/choruskube/credentials"
	defaultAnthropicAPIURL = "https://api.anthropic.com"
)

// localPlaneDefaults fills each blank argument, leaving an explicitly set one untouched.
func localPlaneDefaults(credentialsDir, gitHubAPIURL, anthropicAPIURL string) (string, string, string) {
	if credentialsDir == "" {
		credentialsDir = defaultCredentialsDir
	}
	if gitHubAPIURL == "" {
		gitHubAPIURL = defaultGitHubAPIURL
	}
	if anthropicAPIURL == "" {
		anthropicAPIURL = defaultAnthropicAPIURL
	}
	return credentialsDir, gitHubAPIURL, anthropicAPIURL
}

// LocalCredentialPlane builds the plane that reads this Worker's own mounted credentials folder.
// It is what the open-source binary always passes: this Worker holds its own GitHub, Claude and
// registry credentials, falling back to the environment for anything the folder does not hold.
func LocalCredentialPlane(credentialsDir, gitHubAPIURL, anthropicAPIURL string) CredentialPlane {
	credentialsDir, gitHubAPIURL, anthropicAPIURL = localPlaneDefaults(credentialsDir, gitHubAPIURL, anthropicAPIURL)
	return CredentialPlane{
		Source: "local",
		New: func(deps PlaneDeps) credprov.Provider {
			ghClient := github.New(gitHubAPIURL)
			return credprov.NewLocal(credentialsDir, ghClient, deps.API, time.Now, ghClient, anthropic.New(anthropicAPIURL), registry.Check)
		},
	}
}
