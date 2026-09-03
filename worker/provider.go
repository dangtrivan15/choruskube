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
	fleet         Fleet
	internalToken string
}

// NewStaticFleetProvider builds a provider for one namespace and task queue. No Temporal token is
// carried: a Temporal that hands out no credential is the case this exists for, and Run omits
// credentials entirely rather than presenting an empty one. internalToken is separate and required
// -- this provider registers with nobody, so nothing can mint it one.
func NewStaticFleetProvider(namespace, taskQueue, internalToken string) *StaticFleetProvider {
	return &StaticFleetProvider{
		fleet:         Fleet{Namespace: namespace, TaskQueue: taskQueue},
		internalToken: internalToken,
	}
}

func (p *StaticFleetProvider) Fleets(context.Context) (Registration, error) {
	return Registration{Fleets: []Fleet{p.fleet}, InternalToken: p.internalToken}, nil
}

// ErrNoFleetConfigured reports a FleetSource that names neither way of learning a Fleet.
var ErrNoFleetConfigured = errors.New("no fleet configured: set FleetToken, or Namespace and TaskQueue")

// ErrBlankFleetToken reports a Fleet token that was supplied but is empty. It is distinct from
// ErrNoFleetConfigured because the two mean opposite things: one asks to register and failed to
// carry a credential, the other never asked. Treating them alike lets a Worker whose token
// renders empty fall through to locally configured coordinates and quietly serve a queue it was
// never granted -- registering with nobody, so there is no record of it and nothing to revoke.
var ErrBlankFleetToken = errors.New("FleetToken is present but empty")

// FleetSource is how a process is told which Fleet it serves. Exactly one of the two ways is
// selected, registration first: a Fleet token is a claim on a Fleet the server chooses, so it
// must win over locally configured coordinates that could name a different one.
type FleetSource struct {
	// APIServerURL and FleetToken select registration: the server answers with the namespace,
	// queue and credential, and can move or revoke this Worker later.
	APIServerURL string
	FleetToken   string
	// FleetTokenSet records that a token was supplied at all, which emptiness cannot express.
	// Selection reads this rather than FleetToken so that supplying a blank token is an error
	// rather than a silent switch to the static path.
	FleetTokenSet bool
	// Namespace and TaskQueue select StaticFleetProvider, serving one Fleet without registering.
	Namespace string
	TaskQueue string
	// InternalToken is the credential the static path presents on the API server's application
	// routes. The registration path ignores it: that one is handed a credential, or falls back to
	// the Fleet token it registered with.
	InternalToken string
	// Capabilities is what this Worker reports about its own infrastructure. Empty is the
	// honest default: a server that requires none accepts it, and one that requires some
	// refuses at registration rather than failing later as work it cannot execute.
	Capabilities map[string]string
}

// Provider returns the FleetProvider this source selects, or ErrNoFleetConfigured if it selects
// none. Passing a nil http.Client takes the registration path's own default timeout.
func (s FleetSource) Provider(hc *http.Client) (FleetProvider, error) {
	switch {
	case s.FleetTokenSet:
		if s.FleetToken == "" {
			return nil, ErrBlankFleetToken
		}
		return NewTokenFleetProvider(s.APIServerURL, s.FleetToken, s.Capabilities, hc), nil
	case s.Namespace != "" && s.TaskQueue != "":
		return NewStaticFleetProvider(s.Namespace, s.TaskQueue, s.InternalToken), nil
	default:
		return nil, ErrNoFleetConfigured
	}
}
