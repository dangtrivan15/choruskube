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
func renewOnce(ctx context.Context, provider FleetProvider, tokens *tokenCache) error {
	fleets, err := provider.Fleets(ctx)
	if err != nil {
		return err
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
	return nil
}

// renewLoop calls renewOnce on a ticker until ctx is cancelled. A failed renewal is logged
// and retried on the next tick rather than tearing the Worker down: the token already cached
// is still valid for the remaining half of its life, so a transient failure here costs
// nothing until it has happened enough times to exhaust that slack.
func renewLoop(ctx context.Context, provider FleetProvider, tokens *tokenCache, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := renewOnce(ctx, provider, tokens); err != nil {
				log.Printf("fleet token renewal failed, will retry next interval: %v", err)
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

	var stops []func()
	defer func() {
		for _, stop := range stops {
			stop()
		}
	}()

	for _, f := range fleets {
		key := fleetKey(f)
		c, err := client.Dial(client.Options{
			HostPort:    resolveAddress(f, cfg),
			Namespace:   f.Namespace,
			Credentials: client.NewAPIKeyDynamicCredentials(credential(tokens, key)),
		})
		if err != nil {
			return fmt.Errorf("dial temporal for fleet %s: %w", f.TaskQueue, err)
		}
		// Activities only. This process never holds workflow code — DAG semantics are
		// replay-deterministic and stay on the side that deploys them — so polling for
		// workflow tasks would claim work it cannot execute.
		w := tworker.New(c, f.TaskQueue, tworker.Options{DisableWorkflowWorker: true})
		w.RegisterActivity(acts)
		if err := w.Start(); err != nil {
			c.Close()
			return fmt.Errorf("start worker for fleet %s: %w", f.TaskQueue, err)
		}
		log.Printf("serving fleet: namespace=%s taskQueue=%s", f.Namespace, f.TaskQueue)
		stops = append(stops, func() { w.Stop(); c.Close() })
	}

	// Renew before tokens expire. Without this the Worker keeps polling with a lapsed
	// credential, and — because revocation is implemented by refusing to reissue — revoking
	// a Worker would have no effect at all. wg.Wait below guarantees this goroutine has
	// fully exited before Run returns, so it can never outlive the process that started it.
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		renewLoop(ctx, cfg.Provider, tokens, renewalInterval(fleets))
	}()

	<-ctx.Done()
	wg.Wait()
	return nil
}
