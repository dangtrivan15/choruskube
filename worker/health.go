package worker

import (
	"context"
	"log"
	"sync"
	"time"

	credprov "github.com/dangtrivan15/choruskube/worker/credential"
)

// healthCheckInterval is how often, after the startup check, a Worker holding its own credentials
// re-checks them without a run.
const healthCheckInterval = 5 * time.Minute

// healthMonitor keeps the latest credential-health report so registration's renewal loop — which
// has no run context of its own — always has one to send. checkNow is also what Run calls before
// the first registration, so that call carries a full report.
type healthMonitor struct {
	provider credprov.Provider
	interval time.Duration

	mu      sync.Mutex
	results map[credprov.Kind]credprov.HealthResult
}

func newHealthMonitor(p credprov.Provider, interval time.Duration) *healthMonitor {
	return &healthMonitor{provider: p, interval: interval, results: map[credprov.Kind]credprov.HealthResult{}}
}

// checkNow runs Provider.CheckHealth synchronously and folds every result into the latest report,
// logging any status change: Info for a change into or within VALID/NOT_HELD, Warn for a change
// into anything else. CheckHealth itself never returns an error (see credprov.Provider), so
// there is nothing here for a caller to propagate.
func (m *healthMonitor) checkNow(ctx context.Context) {
	for _, r := range m.provider.CheckHealth(ctx) {
		m.mu.Lock()
		prev, had := m.results[r.Kind]
		m.results[r.Kind] = r
		m.mu.Unlock()
		if had && prev.Status == r.Status {
			continue
		}
		if r.Status == credprov.StatusValid || r.Status == credprov.StatusNotHeld {
			log.Printf("credential health: %s is %s", r.Kind, r.Status)
		} else {
			log.Printf("credential health WARNING: %s is %s: %s", r.Kind, r.Status, r.Detail)
		}
	}
}

// run re-checks on a fixed interval until ctx is cancelled.
func (m *healthMonitor) run(ctx context.Context) {
	ticker := time.NewTicker(m.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			m.checkNow(ctx)
		}
	}
}

// latest is a copy of the last report, one entry per kind checked so far (empty before the first
// checkNow).
func (m *healthMonitor) latest() []credprov.HealthResult {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]credprov.HealthResult, 0, len(m.results))
	for _, r := range m.results {
		out = append(out, r)
	}
	return out
}
