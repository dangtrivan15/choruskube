// Package namespaces keeps one Temporal workflow worker running per namespace this deployment
// may place a run in.
package namespaces

import (
	"context"
	"fmt"
	"log"
	"sync"
	"time"

	"go.temporal.io/sdk/client"
	tworker "go.temporal.io/sdk/worker"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/workflow"
)

// RefreshInterval bounds how long a namespace created after this process started waits before
// anything serves workflow tasks in it. A run placed there does not fail while nobody polls —
// it hangs until its activity times out, so the wait is invisible until then.
const RefreshInterval = 60 * time.Second

// Roster answers which namespaces this deployment may place a run in.
type Roster interface {
	ListNamespaces(ctx context.Context) ([]string, error)
}

// Supervisor owns the workflow workers this process runs, one per namespace.
type Supervisor struct {
	mu      sync.Mutex
	address string
	acts    *activity.Activities
	served  map[string]func()
}

func NewSupervisor(address string, acts *activity.Activities) *Supervisor {
	return &Supervisor{address: address, acts: acts, served: map[string]func(){}}
}

// Serve starts a workflow worker for ns unless one is already running. Idempotent, so the
// refresh loop may call it for every namespace on every tick.
func (s *Supervisor) Serve(ns string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.served[ns]; ok {
		return nil
	}
	// No credentials: this process reaches Temporal on the internal frontend, where no
	// authorizer runs. Serving N namespaces therefore costs N connections and zero tokens.
	c, err := client.Dial(client.Options{HostPort: s.address, Namespace: ns})
	if err != nil {
		return fmt.Errorf("dial temporal for namespace %s: %w", ns, err)
	}
	w := tworker.New(c, workflow.TaskQueue, tworker.Options{})
	w.RegisterWorkflow(workflow.DAGExecutorWorkflow)
	w.RegisterActivity(s.acts)
	if err := w.Start(); err != nil {
		c.Close()
		return fmt.Errorf("start workflow worker for namespace %s: %w", ns, err)
	}
	log.Printf("serving namespace: %s (queue %s)", ns, workflow.TaskQueue)
	s.served[ns] = func() { w.Stop(); c.Close() }
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

// refreshOnce adds workers for namespaces that have appeared. It never removes one: a namespace
// that leaves the roster can still hold a running workflow, and an idle poller is cheaper than
// abandoning it.
func refreshOnce(ctx context.Context, sup *Supervisor, roster Roster) {
	list, err := roster.ListNamespaces(ctx)
	if err != nil {
		log.Printf("namespace roster refresh failed, will retry next interval: %v", err)
		return
	}
	for _, ns := range list {
		if err := sup.Serve(ns); err != nil {
			log.Printf("could not start serving namespace %s: %v", ns, err)
		}
	}
}

// Run refreshes the roster until ctx is cancelled.
func Run(ctx context.Context, sup *Supervisor, roster Roster, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			refreshOnce(ctx, sup, roster)
		}
	}
}
