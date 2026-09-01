package worker

import (
	"context"
	"errors"
	"fmt"
	"log"
	"sync"
	"time"

	"go.temporal.io/sdk/client"
	tworker "go.temporal.io/sdk/worker"

	"github.com/dangtrivan15/choruskube/worker/activity"
	"github.com/dangtrivan15/choruskube/worker/workload"
)

// resolveAddress picks where to reach Temporal for one Fleet.
func resolveAddress(f Fleet, cfg Config) string {
	if f.Endpoint != "" {
		return f.Endpoint
	}
	return cfg.TemporalAddress
}

// clientOptions builds the Temporal dial options for one Fleet. It exists as its own
// function so the TLS decision is observable in a test: the SDK auto-enables TLS whenever
// API-key credentials are present, so TLSDisabled is the only thing standing between a
// plaintext Temporal and a connection that fails its handshake.
func clientOptions(f Fleet, cfg Config, tokens *tokenCache, key string) client.Options {
	return client.Options{
		HostPort:          resolveAddress(f, cfg),
		Namespace:         f.Namespace,
		Credentials:       client.NewAPIKeyDynamicCredentials(credential(tokens, key)),
		ConnectionOptions: client.ConnectionOptions{TLSDisabled: cfg.TemporalTLSDisabled},
	}
}

// fleetKey identifies a Fleet across successive FleetProvider.Fleets calls, so a renewed
// token can be matched back to the Worker it belongs to. Namespace+TaskQueue is what makes
// a Fleet addressable in the first place, so the pair is already a stable identity.
func fleetKey(f Fleet) string {
	return f.Namespace + "|" + f.TaskQueue
}

// tokenCache holds the current Temporal credential for each Fleet this process serves. The
// renewal goroutine writes it and every Temporal SDK request reads it via a credential
// callback running on its own goroutine — mu is what makes that safe.
type tokenCache struct {
	mu     sync.RWMutex
	tokens map[string]string
}

func newTokenCache(fleets []Fleet) *tokenCache {
	tc := &tokenCache{tokens: make(map[string]string, len(fleets))}
	for _, f := range fleets {
		tc.tokens[fleetKey(f)] = f.Token
	}
	return tc
}

func (tc *tokenCache) get(key string) string {
	tc.mu.RLock()
	defer tc.mu.RUnlock()
	return tc.tokens[key]
}

func (tc *tokenCache) set(key, token string) {
	tc.mu.Lock()
	defer tc.mu.Unlock()
	tc.tokens[key] = token
}

// keys snapshots every Fleet key currently cached, so renewOnce can detect one dropping out
// of a later renewal response.
func (tc *tokenCache) keys() map[string]struct{} {
	tc.mu.RLock()
	defer tc.mu.RUnlock()
	out := make(map[string]struct{}, len(tc.tokens))
	for k := range tc.tokens {
		out[k] = struct{}{}
	}
	return out
}

// credential returns the Temporal API-key callback for one Fleet, reading whatever tokens
// currently holds under key. Extracted from Run so the cache-to-Temporal binding is testable
// without a real Temporal connection.
func credential(tokens *tokenCache, key string) func(context.Context) (string, error) {
	return func(context.Context) (string, error) {
		tok := tokens.get(key)
		if tok == "" {
			return "", fmt.Errorf("no cached token for fleet %s", key)
		}
		return tok, nil
	}
}

// defaultRenewalInterval applies when no served Fleet reports a positive ExpiresInSeconds.
// A FleetProvider that omits expiry still gets renewal attempts, just on a conservative,
// fixed cadence instead of one derived from a lifetime it never supplied.
const defaultRenewalInterval = 30 * time.Minute

// renewalMinInterval floors the computed cadence: a lifetime of 1-2s would otherwise divide
// down to 0, and time.NewTicker panics on a non-positive duration.
const renewalMinInterval = 30 * time.Second

// renewalMaxInterval caps the computed cadence: half a lifetime large enough to overflow
// int64 nanoseconds on conversion to time.Duration would wrap negative — the same panic.
const renewalMaxInterval = 24 * time.Hour

// rosterRefreshInterval bounds how long a Fleet created after this process started waits before
// anything polls its queue. Token lifetime governs the renewal cadence and is measured in hours,
// which is far too slow for that: a run placed on a brand-new Fleet is not claimed until the next
// tick, and it hangs rather than failing, so the wait is invisible until the activity times out.
// provider.Fleets returns the roster and the tokens in one call, so refreshing more often costs
// one extra request per interval and renews tokens early, which is harmless.
const rosterRefreshInterval = 60 * time.Second

// loopInterval is the cadence renewLoop actually runs at: whichever of the two bounds is shorter.
func loopInterval(fleets []Fleet) time.Duration {
	if r := renewalInterval(fleets); r < rosterRefreshInterval {
		return r
	}
	return rosterRefreshInterval
}

// renewalInterval is roughly half the shortest token lifetime among fleets, clamped to
// [renewalMinInterval, renewalMaxInterval] so no value of ExpiresInSeconds — including 1, or
// one large enough to overflow — can hand renewLoop a non-positive ticker duration.
func renewalInterval(fleets []Fleet) time.Duration {
	var minSeconds int64
	for _, f := range fleets {
		if f.ExpiresInSeconds <= 0 {
			continue
		}
		if minSeconds == 0 || f.ExpiresInSeconds < minSeconds {
			minSeconds = f.ExpiresInSeconds
		}
	}
	if minSeconds == 0 {
		return defaultRenewalInterval
	}

	// Clamping in seconds, before any multiplication by time.Second, is what keeps a huge
	// minSeconds from ever reaching an overflowing nanosecond multiplication.
	halfSeconds := minSeconds / 2
	switch {
	case halfSeconds <= int64(renewalMinInterval/time.Second):
		return renewalMinInterval
	case halfSeconds > int64(renewalMaxInterval/time.Second):
		return renewalMaxInterval
	default:
		return time.Duration(halfSeconds) * time.Second
	}
}

// renewOnce re-invokes provider.Fleets and swaps in each returned Fleet's token, skipping
// any that comes back empty so a blank renewal cannot replace a still-valid credential. A
// key new to this process is added, not rejected; one previously cached that the provider
// stops returning is logged rather than silently left to age toward expiry.
func renewOnce(ctx context.Context, provider FleetProvider, tokens *tokenCache) ([]Fleet, error) {
	fleets, err := provider.Fleets(ctx)
	if err != nil {
		return nil, err
	}

	before := tokens.keys()
	seen := make(map[string]struct{}, len(fleets))
	for _, f := range fleets {
		key := fleetKey(f)
		seen[key] = struct{}{}
		if f.Token == "" {
			log.Printf("fleet token renewal returned an empty token for %s; keeping the previous one", key)
			continue
		}
		tokens.set(key, f.Token)
	}
	for key := range before {
		if _, ok := seen[key]; !ok {
			log.Printf("fleet %s was absent from this renewal response; its cached token is not being refreshed", key)
		}
	}
	return fleets, nil
}

// fleetSupervisor owns the Temporal Workers this process is running, one per Fleet, keyed by
// fleetKey. It exists because the roster is not fixed at startup: an organization created after
// this process booted gets a Fleet, runs in that org are dispatched to that Fleet's task queue,
// and with nobody polling it those runs sit unclaimed until the activity times out. Serving is
// therefore driven by every renewal, not only by the initial list.
type fleetSupervisor struct {
	mu     sync.Mutex
	cfg    Config
	acts   *activity.Activities
	tokens *tokenCache
	served map[string]func()
}

// serve starts a Worker for f unless one is already running for it. Idempotent by fleetKey, so
// the renewal loop can call it for every Fleet on every tick.
func (s *fleetSupervisor) serve(f Fleet) error {
	key := fleetKey(f)
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.served[key]; ok {
		return nil
	}
	c, err := client.Dial(clientOptions(f, s.cfg, s.tokens, key))
	if err != nil {
		return fmt.Errorf("dial temporal for fleet %s: %w", f.TaskQueue, err)
	}
	// Activities only. This process never holds workflow code — DAG semantics are
	// replay-deterministic and stay on the side that deploys them — so polling for
	// workflow tasks would claim work it cannot execute.
	w := tworker.New(c, f.TaskQueue, tworker.Options{DisableWorkflowWorker: true})
	w.RegisterActivity(s.acts)
	if err := w.Start(); err != nil {
		c.Close()
		return fmt.Errorf("start worker for fleet %s: %w", f.TaskQueue, err)
	}
	log.Printf("serving fleet: namespace=%s taskQueue=%s", f.Namespace, f.TaskQueue)
	s.served[key] = func() { w.Stop(); c.Close() }
	return nil
}

func (s *fleetSupervisor) stopAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, stop := range s.served {
		stop()
	}
	s.served = map[string]func(){}
}

func (s *fleetSupervisor) count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.served)
}

// renewLoop calls renewOnce on a ticker until ctx is cancelled. A failed renewal is logged
// and retried on the next tick rather than tearing the Worker down: the token already cached
// is still valid for the remaining half of its life, so a transient failure here costs
// nothing until it has happened enough times to exhaust that slack.
func renewLoop(ctx context.Context, provider FleetProvider, tokens *tokenCache, interval time.Duration, sup *fleetSupervisor) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			fleets, err := renewOnce(ctx, provider, tokens)
			if err != nil {
				log.Printf("fleet token renewal failed, will retry next interval: %v", err)
				continue
			}
			// A Fleet can appear between ticks — a new organization is provisioned one, and
			// its runs dispatch to a queue nobody polls until we start serving it. Failing to
			// start one Fleet must not stop the others, so this logs and moves on.
			if sup == nil {
				continue
			}
			for _, f := range fleets {
				if err := sup.serve(f); err != nil {
					log.Printf("could not start serving fleet %s: %v", f.TaskQueue, err)
				}
			}
		}
	}
}

// Run serves every Fleet the provider returns until ctx is cancelled. One Temporal Worker
// is started per Fleet: a Worker polls exactly one task queue in one namespace, so serving
// N Fleets means N of them in this process.
func Run(ctx context.Context, cfg Config) error {
	if err := cfg.Validate(); err != nil {
		return err
	}
	fleets, err := cfg.Provider.Fleets(ctx)
	if err != nil {
		return fmt.Errorf("resolve fleets: %w", err)
	}
	if len(fleets) == 0 {
		return errors.New("no fleets to serve")
	}

	acts := activity.New(workload.NewClient(cfg.APIServerURL, cfg.InternalSecret, nil))
	// Both are required: ExecuteAINodeFromSnapshot rejects an empty value rather than
	// launching a pod that cannot report back. Passing nil for the HTTP client selects the
	// package's 30s-timeout default; http.DefaultClient has no timeout and must not be used.
	acts.CallbackURL = cfg.CallbackURL
	acts.APIServerURL = cfg.APIServerURL

	tokens := newTokenCache(fleets)

	sup := &fleetSupervisor{cfg: cfg, acts: acts, tokens: tokens, served: map[string]func(){}}
	defer sup.stopAll()

	// A Fleet that cannot be served at startup is fatal, because it means the configured
	// deployment is wrong (bad address, bad credential) rather than merely incomplete. The
	// renewal loop treats the same failure as retryable, since by then the process is already
	// serving work and one unreachable Fleet must not take the others down.
	for _, f := range fleets {
		if err := sup.serve(f); err != nil {
			return err
		}
	}

	// Renew before tokens expire. Without this the Worker keeps polling with a lapsed
	// credential, and — because revocation is implemented by refusing to reissue — revoking
	// a Worker would have no effect at all. wg.Wait below guarantees this goroutine has
	// fully exited before Run returns, so it can never outlive the process that started it.
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		renewLoop(ctx, cfg.Provider, tokens, loopInterval(fleets), sup)
	}()

	<-ctx.Done()
	wg.Wait()
	return nil
}
