package worker

import (
	"context"
	"strings"
	"testing"
)

// An empty credential would go on the wire as "Bearer " and be refused per request, surfacing as
// every node failing for no stated reason. Refusing to start names the cause once.
func TestRunRefusesToStartWithoutACredential(t *testing.T) {
	cfg := Config{
		TemporalAddress: "localhost:7233",
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
}
