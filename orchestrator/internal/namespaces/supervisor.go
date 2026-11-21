// Package namespaces keeps one Temporal workflow worker running per namespace this deployment
// may place a run in.
package namespaces

import (
	"context"
	"errors"
	"fmt"
	"log"
	"slices"
	"sync"
	"time"

	"go.temporal.io/sdk/client"
	tworker "go.temporal.io/sdk/worker"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/temporaltoken"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/workflow"
)

// RefreshInterval bounds how long a namespace created after this process started waits before
// anything serves workflow tasks in it. A run placed there does not fail while nobody polls —
// it hangs until its activity times out, so the wait is invisible until then.
const RefreshInterval = 60 * time.Second

// ErrUnreachable classifies a Serve failure as the dial failing: Temporal was never reached, or
// the namespace's credential callback had no valid token for the dial's first call. Distinct from
// ErrRefused so a caller can tell either from Temporal actively rejecting the namespace.
var ErrUnreachable = errors.New("temporal unreachable")

// ErrRefused classifies a Serve failure as Temporal being reached but refusing the namespace — a
// worker-start failure, which is how the SDK surfaces an authentication or authorization
// rejection at this version.
var ErrRefused = errors.New("temporal refused namespace")

// Roster answers which namespaces this deployment may place a run in, and how to authenticate to
// each one.
type Roster interface {
	ListNamespaces(ctx context.Context) (apiclient.NamespaceRoster, error)
}

// Supervisor owns the workflow workers this process runs, one per namespace.
type Supervisor struct {
	mu      sync.Mutex
	address string
	acts    *activity.Activities
	tokens  *temporaltoken.Holder
	served  map[string]func()
	// none is the latest roster's set of namespaces marked "none", replaced wholesale on every
	// successful fetch — never merged — so a namespace that starts requiring a token stops being
	// treated as one that doesn't.
	none map[string]bool
}

func NewSupervisor(address string, acts *activity.Activities, tokens *temporaltoken.Holder) *Supervisor {
	return &Supervisor{address: address, acts: acts, tokens: tokens, served: map[string]func(){}}
}

// Serve starts a workflow worker for ns unless one is already running. Idempotent, so the
// refresh loop may call it for every namespace on every tick. The connection always dials
// plaintext explicitly and reads its credential from the token holder on every call, so a token
// arriving or renewing later never reconnects and never flips the transport.
func (s *Supervisor) Serve(ns string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.served[ns]; ok {
		return nil
	}
	c, err := client.Dial(client.Options{
		HostPort:  s.address,
		Namespace: ns,
		ConnectionOptions: client.ConnectionOptions{
			TLSDisabled: true,
		},
		Credentials: client.NewAPIKeyDynamicCredentials(s.tokens.Credential(ns)),
	})
	if err != nil {
		return fmt.Errorf("dial temporal for namespace %s: %w: %w", ns, ErrUnreachable, err)
	}
	w := tworker.New(c, workflow.TaskQueue, tworker.Options{})
	w.RegisterWorkflow(workflow.DAGExecutorWorkflow)
	w.RegisterWorkflow(workflow.CloseTaskIssueWorkflow)
	w.RegisterActivity(s.acts)
	if err := w.Start(); err != nil {
		c.Close()
		return fmt.Errorf("start workflow worker for namespace %s: %w: %w", ns, ErrRefused, err)
	}
	log.Printf("serving namespace: %s (queue %s)", ns, workflow.TaskQueue)
	s.served[ns] = func() { w.Stop(); c.Close() }
	return nil
}

// ServeConfigured serves ns, the deployment's own configured namespace, at startup. It returns an
// error only when Temporal cannot be reached at all: a refusal is logged and ns left unserved,
// because at startup nothing is held yet and no roster has been seen — neither exit condition a
// later refusal is judged against applies here.
func (s *Supervisor) ServeConfigured(ns string) error {
	err := s.Serve(ns)
	if err == nil || errors.Is(err, ErrUnreachable) {
		return err
	}
	log.Printf("could not start serving the configured namespace %s yet, will retry: %v", ns, err)
	return nil
}

func (s *Supervisor) StopAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, stop := range s.served {
		stop()
	}
	s.served = map[string]func(){}
}

func (s *Supervisor) Count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.served)
}

// applyRoster pushes every roster namespace's state into the token holder and replaces the
// none-marked set. A namespace newly marked none is logged once, by name only, as the holder logs
// a namespace's first token.
func (s *Supervisor) applyRoster(r apiclient.NamespaceRoster) {
	none := map[string]bool{}
	for _, ns := range r.Namespaces {
		cred, present := r.Credentials[ns]
		state, isNone := credentialState(cred, present)
		s.tokens.Update(ns, state)
		if isNone {
			none[ns] = true
		}
	}

	s.mu.Lock()
	previous := s.none
	s.none = none
	s.mu.Unlock()

	for _, ns := range r.Namespaces {
		if none[ns] && !previous[ns] {
			log.Printf("namespace %s: roster marks no credential; calls carry none", ns)
		}
	}
}

func (s *Supervisor) isMarkedNone(ns string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.none[ns]
}

// credentialState turns one roster entry into the state pushed to the token holder. present is
// false for a namespace with no entry at all — including every namespace when Credentials itself
// is nil, which is how an api-server predating this field reads. isNone reports whether this
// entry means "no credential by design", for the exit-rule's none-marked check. An entry that is
// present but names no known state is unavailable: reading it as none would send calls uncredentialed.
func credentialState(cred apiclient.NamespaceCredential, present bool) (temporaltoken.State, bool) {
	switch {
	case !present || cred.State == "none":
		return temporaltoken.None(), true
	case cred.State == "bearer":
		return temporaltoken.Bearer(temporaltoken.NewToken(cred.Token, cred.ExpiresAt)), false
	default:
		return temporaltoken.Unavailable(), false
	}
}

// refreshOnce fetches the roster, pushes every namespace's token state into the holder, and
// retries Serve for every namespace not yet served — the roster's and configured alike. A failed
// fetch still retries the configured namespace, which never waits on the api-server. It never
// stops a namespace already being served: one that leaves the roster can still hold a running
// workflow, and an idle poller is cheaper than abandoning it.
//
// It returns a non-nil, fatal error only when Temporal refuses a namespace the orchestrator holds
// a token for, or one the latest roster marked "none" — both are misconfiguration Temporal should
// not have refused, so exiting keeps it loud. Every other failure (an unreachable Temporal, a
// roster fetch error, a refusal the token source simply hasn't caught up to) is logged and left
// for the next tick.
func refreshOnce(ctx context.Context, sup *Supervisor, roster Roster, configured string) error {
	var toServe []string
	rosterResult, err := roster.ListNamespaces(ctx)
	if err != nil {
		log.Printf("namespace roster refresh failed, will retry next interval: %v", err)
	} else {
		sup.applyRoster(rosterResult)
		toServe = append(toServe, rosterResult.Namespaces...)
	}
	if !slices.Contains(toServe, configured) {
		toServe = append(toServe, configured)
	}

	var fatal error
	for _, ns := range toServe {
		serveErr := sup.Serve(ns)
		if serveErr == nil {
			continue
		}
		if errors.Is(serveErr, ErrUnreachable) {
			log.Printf("could not reach temporal for namespace %s, will retry: %v", ns, serveErr)
			continue
		}
		if sup.tokens.Holds(ns) || sup.isMarkedNone(ns) {
			log.Printf("temporal refused namespace %s which should have been accepted: %v", ns, serveErr)
			if fatal == nil {
				fatal = serveErr
			}
			continue
		}
		log.Printf("could not start serving namespace %s yet, will retry: %v", ns, serveErr)
	}
	return fatal
}

// Run calls refreshOnce immediately — the roster is fetched before the first tick, not a minute
// in — then again on every tick, until ctx is cancelled or refreshOnce reports a fatal error.
func Run(ctx context.Context, sup *Supervisor, roster Roster, configured string, interval time.Duration) error {
	if err := refreshOnce(ctx, sup, roster, configured); err != nil {
		return err
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			if err := refreshOnce(ctx, sup, roster, configured); err != nil {
				return err
			}
		}
	}
}
