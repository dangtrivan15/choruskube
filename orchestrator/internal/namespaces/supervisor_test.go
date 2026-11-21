package namespaces

import (
	"bytes"
	"context"
	"errors"
	"log"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/temporaltoken"
)

// unreachableAddress refuses a TCP connection almost instantly on this host, so Serve fails fast
// with ErrUnreachable instead of hanging on a real dial.
const unreachableAddress = "127.0.0.1:1"

type fixedRoster struct {
	mu          sync.Mutex
	namespaces  []string
	credentials map[string]apiclient.NamespaceCredential
	err         error
	calls       int
}

func (f *fixedRoster) ListNamespaces(ctx context.Context) (apiclient.NamespaceRoster, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	if f.err != nil {
		return apiclient.NamespaceRoster{}, f.err
	}
	return apiclient.NamespaceRoster{Namespaces: f.namespaces, Credentials: f.credentials}, nil
}

func (f *fixedRoster) callCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

func (f *fixedRoster) setCredentials(c map[string]apiclient.NamespaceCredential) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.credentials = c
}

func captureLog(t *testing.T, fn func()) string {
	t.Helper()
	var buf bytes.Buffer
	prevOut := log.Writer()
	prevFlags := log.Flags()
	log.SetOutput(&buf)
	log.SetFlags(0)
	defer func() {
		log.SetOutput(prevOut)
		log.SetFlags(prevFlags)
	}()
	fn()
	return buf.String()
}

// Serve dials Temporal, which a unit test cannot do — but the guard that makes it safe to call
// on every tick runs before the dial, so idempotency is testable on its own.
func TestServeIsIdempotentPerNamespace(t *testing.T) {
	sup := &Supervisor{served: map[string]func(){"ns": func() {}}}

	if err := sup.Serve("ns"); err != nil {
		t.Fatalf("Serve on an already-served namespace = %v, want nil (no re-dial)", err)
	}
	if got := sup.Count(); got != 1 {
		t.Fatalf("served count = %d, want 1: a second Serve must not add a duplicate worker", got)
	}
}

func TestStopAllClearsTheServedSet(t *testing.T) {
	stopped := 0
	sup := &Supervisor{served: map[string]func(){
		"a": func() { stopped++ },
		"b": func() { stopped++ },
	}}

	sup.StopAll()

	if stopped != 2 {
		t.Fatalf("stopped %d workers, want 2", stopped)
	}
	if got := sup.Count(); got != 0 {
		t.Fatalf("served count after StopAll = %d, want 0", got)
	}
}

// A roster the orchestrator cannot fetch must not stop it serving what it already serves —
// otherwise an api-server blip takes every in-flight run's workflow offline.
func TestRefreshOnceSurvivesARosterFailure(t *testing.T) {
	sup := &Supervisor{served: map[string]func(){"ns": func() {}}}
	roster := &fixedRoster{err: errors.New("api server down")}

	err := refreshOnce(context.Background(), sup, roster, "ns")

	require.NoError(t, err)
	if got := sup.Count(); got != 1 {
		t.Fatalf("served count = %d, want the existing worker untouched", got)
	}
}

// A namespace that leaves the roster keeps its worker: a run started there is still running,
// and an idle poller costs one connection.
func TestRefreshOnceNeverStopsAWorker(t *testing.T) {
	sup := &Supervisor{address: unreachableAddress, tokens: temporaltoken.NewHolder(nil), served: map[string]func(){"gone": func() {}}}
	roster := &fixedRoster{namespaces: []string{}}

	err := refreshOnce(context.Background(), sup, roster, "configured-ns")

	require.NoError(t, err)
	if got := sup.Count(); got != 1 {
		t.Fatalf("served count = %d, want 1 — a departed namespace must keep its worker", got)
	}
}

func TestRunFetchesTheRosterBeforeTheFirstTick(t *testing.T) {
	sup := testSupervisorAt(unreachableAddress)
	roster := &fixedRoster{}
	ctx, cancel := context.WithCancel(context.Background())

	done := make(chan error, 1)
	go func() { done <- Run(ctx, sup, roster, "configured", time.Hour) }()

	deadline := time.Now().Add(2 * time.Second)
	for roster.callCount() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	assert.GreaterOrEqual(t, roster.callCount(), 1, "Run must fetch the roster immediately, not wait for the first tick")

	cancel()
	require.NoError(t, <-done)
}

// Every roster namespace not yet served, and the configured one, must be retried on each tick —
// observable here because an unreachable Temporal means Serve never succeeds, so the retry
// attempt is the only thing that can keep happening.
func TestRunRetriesEveryUnservedNamespaceIncludingConfiguredOnEachTick(t *testing.T) {
	sup := testSupervisorAt(unreachableAddress)
	roster := &fixedRoster{namespaces: []string{"ns-a"}}
	ctx, cancel := context.WithCancel(context.Background())

	out := captureLog(t, func() {
		done := make(chan error, 1)
		go func() { done <- Run(ctx, sup, roster, "ns-configured", 20*time.Millisecond) }()

		deadline := time.Now().Add(2 * time.Second)
		for roster.callCount() < 3 && time.Now().Before(deadline) {
			time.Sleep(time.Millisecond)
		}
		cancel()
		require.NoError(t, <-done)
	})

	assert.GreaterOrEqual(t, roster.callCount(), 3, "the roster must be refetched on later ticks, not just once")
	assert.GreaterOrEqual(t, strings.Count(out, "could not reach temporal for namespace ns-a,"), 3)
	assert.GreaterOrEqual(t, strings.Count(out, "could not reach temporal for namespace ns-configured,"), 3)
}

// The configured namespace never waits on the api-server, so a failed roster fetch still retries it.
func TestRefreshOnceRosterFailureStillRetriesTheConfiguredNamespace(t *testing.T) {
	sup := testSupervisorAt(unreachableAddress)
	roster := &fixedRoster{err: errors.New("api server down")}

	var err error
	out := captureLog(t, func() {
		err = refreshOnce(context.Background(), sup, roster, "ns-configured")
	})

	require.NoError(t, err)
	assert.Contains(t, out, "could not reach temporal for namespace ns-configured,")
}

func TestRefreshOnceLogsANamespaceNewlyMarkedNoneOnce(t *testing.T) {
	sup := testSupervisorAt(unreachableAddress)
	future := time.Now().Add(time.Hour)
	roster := &fixedRoster{
		namespaces:  []string{"ns-a"},
		credentials: map[string]apiclient.NamespaceCredential{"ns-a": {State: "none"}},
	}
	const line = "namespace ns-a: roster marks no credential"

	out := captureLog(t, func() {
		require.NoError(t, refreshOnce(context.Background(), sup, roster, "ns-a"))
		require.NoError(t, refreshOnce(context.Background(), sup, roster, "ns-a"))
	})
	assert.Equal(t, 1, strings.Count(out, line), "an unchanged none must not be logged every tick")

	roster.setCredentials(map[string]apiclient.NamespaceCredential{
		"ns-a": {State: "bearer", Token: "tok-a", ExpiresAt: future},
	})
	out = captureLog(t, func() {
		require.NoError(t, refreshOnce(context.Background(), sup, roster, "ns-a"))
		roster.setCredentials(map[string]apiclient.NamespaceCredential{"ns-a": {State: "none"}})
		require.NoError(t, refreshOnce(context.Background(), sup, roster, "ns-a"))
	})
	assert.Equal(t, 1, strings.Count(out, line), "a namespace returning to none is logged again")
	assert.NotContains(t, out, "tok-a")
}

func TestRefreshOnceMapsRosterStatesIntoTheHolder(t *testing.T) {
	sup := testSupervisorAt(unreachableAddress)
	future := time.Now().Add(time.Hour)
	roster := &fixedRoster{
		namespaces: []string{"bearer-ns", "none-ns", "missing-entry-ns", "unknown-ns", "empty-state-ns"},
		credentials: map[string]apiclient.NamespaceCredential{
			"bearer-ns":      {State: "bearer", Token: "tok-a", ExpiresAt: future},
			"none-ns":        {State: "none"},
			"unknown-ns":     {State: "something-else"},
			"empty-state-ns": {},
			// missing-entry-ns deliberately has no entry at all.
		},
	}

	require.NoError(t, refreshOnce(context.Background(), sup, roster, "bearer-ns"))

	assert.True(t, sup.tokens.Holds("bearer-ns"))
	val, err := sup.tokens.Credential("bearer-ns")(context.Background())
	require.NoError(t, err)
	assert.Equal(t, "tok-a", val)

	assert.False(t, sup.tokens.Holds("none-ns"))
	val, err = sup.tokens.Credential("none-ns")(context.Background())
	require.NoError(t, err)
	assert.Empty(t, val)

	assert.False(t, sup.tokens.Holds("missing-entry-ns"))
	val, err = sup.tokens.Credential("missing-entry-ns")(context.Background())
	require.NoError(t, err)
	assert.Empty(t, val, "a namespace missing from credentials must be treated as none")

	assert.False(t, sup.tokens.Holds("unknown-ns"))
	_, err = sup.tokens.Credential("unknown-ns")(context.Background())
	require.Error(t, err, "an unrecognized state must be treated as unavailable, not none")

	_, err = sup.tokens.Credential("empty-state-ns")(context.Background())
	require.Error(t, err, "an entry present with no state must be treated as unavailable, not none")
}

func TestRefreshOnceMissingCredentialsFieldMeansNoneForEveryNamespace(t *testing.T) {
	sup := testSupervisorAt(unreachableAddress)
	roster := &fixedRoster{namespaces: []string{"ns-a"}} // Credentials left nil, as an old api-server sends

	require.NoError(t, refreshOnce(context.Background(), sup, roster, "ns-a"))

	assert.False(t, sup.tokens.Holds("ns-a"))
	val, err := sup.tokens.Credential("ns-a")(context.Background())
	require.NoError(t, err)
	assert.Empty(t, val)
}

func TestRefreshOnceUnavailableEntryKeepsTheHeldToken(t *testing.T) {
	sup := testSupervisorAt(unreachableAddress)
	future := time.Now().Add(time.Hour)
	roster := &fixedRoster{
		namespaces:  []string{"ns-a"},
		credentials: map[string]apiclient.NamespaceCredential{"ns-a": {State: "bearer", Token: "tok-a", ExpiresAt: future}},
	}
	require.NoError(t, refreshOnce(context.Background(), sup, roster, "ns-a"))
	require.True(t, sup.tokens.Holds("ns-a"))

	roster.setCredentials(map[string]apiclient.NamespaceCredential{"ns-a": {State: "unavailable"}})
	require.NoError(t, refreshOnce(context.Background(), sup, roster, "ns-a"))

	assert.True(t, sup.tokens.Holds("ns-a"), "an unavailable push must keep the previously held token")
	val, err := sup.tokens.Credential("ns-a")(context.Background())
	require.NoError(t, err)
	assert.Equal(t, "tok-a", val)
}
