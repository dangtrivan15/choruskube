// Package worker runs ChorusKube agent steps as Temporal activities.
//
// A Worker serves one or more Fleets. A Fleet is an addressable place work runs: a
// Temporal namespace plus a task queue. Where that list comes from is the caller's
// choice — see FleetProvider — so a deployment can hold its Fleet in a config file or
// obtain it from a service without this package knowing which.
package worker

import (
	"context"
	"errors"
)

// Fleet is one addressable place this Worker serves.
type Fleet struct {
	Namespace string
	TaskQueue string
	// Token authenticates this Worker to Temporal for Namespace.
	Token string
	// WorkerID identifies this process to the issuing service. Informational.
	WorkerID string
	// Endpoint overrides Config.TemporalAddress for this Fleet when non-empty. A
	// deployment that fronts Temporal with a proxy sets it here rather than teaching
	// every Worker where Temporal itself lives.
	Endpoint string
	// ExpiresInSeconds is the lifetime of Token in seconds. Renewal logic uses it to
	// refresh before Token lapses.
	ExpiresInSeconds int64
}

// Registration is everything one call to a FleetProvider learns: which Fleets this process
// serves, and the credential it presents on the API server's application routes.
//
// InternalToken is a process fact, not a per-Fleet one -- one process makes one client's worth of
// application calls however many Fleets it serves -- so it lives here rather than on Fleet, where
// two disagreeing entries would have no resolution rule.
type Registration struct {
	Fleets []Fleet
	// InternalToken authenticates this process to the API server's /worker/** routes. Each
	// provider resolves its own: a registering provider takes the server's minted credential and
	// falls back to the token it registered with, a static one takes a configured value. Empty is
	// fatal at startup -- see Run -- because there is no cached credential to fall back on.
	InternalToken string
}

// FleetProvider supplies this process's registration. It is called at startup and on every
// renewal tick; a provider that talks to a service should apply its own timeout.
type FleetProvider interface {
	Fleets(ctx context.Context) (Registration, error)
}

// Config is everything Run needs.
type Config struct {
	TemporalAddress string
	APIServerURL    string
	// InternalSecret authenticates this Worker to the API server's internal endpoints.
	InternalSecret string
	// CallbackURL is passed through to activity.Activities.CallbackURL. Required because
	// ExecuteAINodeFromSnapshot rejects an empty value rather than launching a pod that
	// cannot report back.
	CallbackURL string
	// TemporalTLSDisabled turns off TLS on the Temporal connection. It defaults to false
	// because a Fleet credential is a bearer token: the SDK auto-enables TLS whenever API-key
	// credentials are set, and silently downgrading that would put the token on the wire in
	// clear. Set it only for a Temporal that genuinely serves plaintext gRPC, such as a local
	// stack or an in-cluster frontend with no TLS listener; a deployment that needs it and
	// omits it fails to connect rather than leaking.
	TemporalTLSDisabled bool
	Provider            FleetProvider
}

// Validate reports the first missing required field. Run calls it; callers building a
// Config from flags or env may call it earlier to fail before any connection is made.
func (c Config) Validate() error {
	switch {
	case c.TemporalAddress == "":
		return errors.New("TemporalAddress is required")
	case c.APIServerURL == "":
		return errors.New("APIServerURL is required")
	case c.CallbackURL == "":
		return errors.New("CallbackURL is required")
	case c.Provider == nil:
		return errors.New("Provider is required")
	}
	return nil
}
