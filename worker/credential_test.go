package worker

import (
	"context"
	"net"
	"runtime"
	"strings"
	"sync"
	"testing"
)

// closedPort reserves a port and immediately releases it, so a dial there is refused rather than
// answered. Tests that must never reach Temporal need that guarantee: a plausible-looking address
// connects on any machine running the local stack, and a Run that gets past its guard then blocks
// on <-ctx.Done() instead of returning the error the test names.
func closedPort(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve a port: %v", err)
	}
	addr := l.Addr().String()
	if err := l.Close(); err != nil {
		t.Fatalf("release the reserved port: %v", err)
	}
	return addr
}

// An empty credential would go on the wire as "Bearer " and be refused per request, surfacing as
// every node failing for no stated reason. Refusing to start names the cause once.
func TestRunRefusesToStartWithoutACredential(t *testing.T) {
	cfg := Config{
		TemporalAddress: closedPort(t),
		APIServerURL:    "http://api",
		CallbackURL:     "http://cb",
		Provider:        fixedProvider{reg: Registration{Fleets: []Fleet{{Namespace: "ns", TaskQueue: "q"}}}},
	}

	err := Run(context.Background(), cfg)
	if err == nil || !strings.Contains(err.Error(), "credential") {
		t.Fatalf("err = %v, want an error naming the missing credential", err)
	}
}

func TestCredentialCacheSwapsUnderConcurrentReads(t *testing.T) {
	c := newCredentialCache("first")
	if c.get() != "first" {
		t.Fatalf("get() = %q", c.get())
	}
	c.set("second")
	if c.get() != "second" {
		t.Fatalf("after set, get() = %q", c.get())
	}
	// A renewal that returns no credential must not erase a working one.
	c.set("")
	if c.get() != "second" {
		t.Fatalf("a blank renewal erased the cached credential: %q", c.get())
	}

	t.Run("under concurrent readers", func(t *testing.T) {
		// One writer and many readers is the exact contention shape production has: the renewal
		// goroutine swaps the credential while every in-flight workload request reads it. Run with
		// -race; without it this subtest proves nothing about the mutex being there.
		c := newCredentialCache("first")

		const iterations = 500
		var wg sync.WaitGroup

		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < iterations; i++ {
				c.set("second")
				runtime.Gosched()
			}
		}()

		for r := 0; r < 8; r++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for i := 0; i < iterations; i++ {
					if got := c.get(); got != "first" && got != "second" {
						t.Errorf("read a value that was never set: %q", got)
					}
					runtime.Gosched()
				}
			}()
		}

		wg.Wait()
	})
}
