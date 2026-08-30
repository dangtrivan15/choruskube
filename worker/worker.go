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

// FleetProvider supplies the Fleets this process serves. It is called once at
// startup; a provider that talks to a service should apply its own timeout.
type FleetProvider interface {
	Fleets(ctx context.Context) ([]Fleet, error)
}

// Config is everything Run needs.
type Config struct {
	TemporalAddress string
	APIServerURL    string
	// InternalSecret authenticates this Worker to the API server's internal endpoints.
	InternalSecret string
	Provider       FleetProvider
}

// Validate reports the first missing required field. Run calls it; callers building a
// Config from flags or env may call it earlier to fail before any connection is made.
func (c Config) Validate() error {
	switch {
	case c.TemporalAddress == "":
		return errors.New("TemporalAddress is required")
	case c.APIServerURL == "":
		return errors.New("APIServerURL is required")
	case c.Provider == nil:
		return errors.New("Provider is required")
	}
	return nil
}
