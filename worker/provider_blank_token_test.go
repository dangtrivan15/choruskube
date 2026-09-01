package worker

import (
	"errors"
	"testing"
)

// A token that is exported but empty means "I meant to register and my credential did not
// arrive". Selecting on emptiness alone cannot see that, and would start the Worker on whatever
// static coordinates happen to be configured — serving a queue it was never granted, with no
// registration record and nothing to revoke.
func TestBlankFleetTokenIsAnErrorNotAFallThrough(t *testing.T) {
	src := FleetSource{
		APIServerURL:  "http://api",
		FleetToken:    "",
		FleetTokenSet: true,
		Namespace:     "some-namespace",
		TaskQueue:     "some-queue",
	}

	got, err := src.Provider(nil)
	if !errors.Is(err, ErrBlankFleetToken) {
		t.Fatalf("Provider() err = %v, want ErrBlankFleetToken; provider = %#v", err, got)
	}
}

// With no token supplied at all, the static coordinates are a deliberate choice, not a fallback.
func TestAbsentFleetTokenSelectsStatic(t *testing.T) {
	src := FleetSource{Namespace: "ns", TaskQueue: "q"}

	p, err := src.Provider(nil)
	if err != nil {
		t.Fatalf("Provider() err = %v, want nil", err)
	}
	if _, ok := p.(*StaticFleetProvider); !ok {
		t.Fatalf("Provider() = %T, want *StaticFleetProvider", p)
	}
}
