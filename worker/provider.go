package worker

import (
	"context"
	"errors"
	"net/http"
)

// StaticFleetProvider serves exactly one Fleet, taken straight from configuration.
//
// The deployment that wants this is the one where the answer is already known and constant:
// there is a single Temporal namespace and task queue, so asking a service which Fleet to
// serve would only ever return what the operator already configured. Nothing is registered,
// so nothing can be revoked either — stopping this Worker means stopping the process.
type StaticFleetProvider struct {
	fleet Fleet
}

// NewStaticFleetProvider builds a provider for one namespace and task queue. No token is
// carried: a Temporal that hands out no credential is the case this exists for, and Run omits
// credentials entirely rather than presenting an empty one.
func NewStaticFleetProvider(namespace, taskQueue string) *StaticFleetProvider {
	return &StaticFleetProvider{fleet: Fleet{Namespace: namespace, TaskQueue: taskQueue}}
}

func (p *StaticFleetProvider) Fleets(context.Context) ([]Fleet, error) {
	return []Fleet{p.fleet}, nil
}

// ErrNoFleetConfigured reports a FleetSource that names neither way of learning a Fleet.
var ErrNoFleetConfigured = errors.New("no fleet configured: set FleetToken, or Namespace and TaskQueue")

// FleetSource is how a process is told which Fleet it serves. Exactly one of the two ways is
// selected, registration first: a Fleet token is a claim on a Fleet the server chooses, so it
// must win over locally configured coordinates that could name a different one.
type FleetSource struct {
	// APIServerURL and FleetToken select registration: the server answers with the namespace,
	// queue and credential, and can move or revoke this Worker later.
	APIServerURL string
	FleetToken   string
	// Namespace and TaskQueue select StaticFleetProvider, serving one Fleet without registering.
	Namespace string
	TaskQueue string
}

// Provider returns the FleetProvider this source selects, or ErrNoFleetConfigured if it selects
// none. Passing a nil http.Client takes the registration path's own default timeout.
func (s FleetSource) Provider(hc *http.Client) (FleetProvider, error) {
	switch {
	case s.FleetToken != "":
		return NewTokenFleetProvider(s.APIServerURL, s.FleetToken, hc), nil
	case s.Namespace != "" && s.TaskQueue != "":
		return NewStaticFleetProvider(s.Namespace, s.TaskQueue), nil
	default:
		return nil, ErrNoFleetConfigured
	}
}
