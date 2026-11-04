package worker

import (
	"bytes"
	"context"
	"log"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	credprov "github.com/dangtrivan15/choruskube/worker/credential"
)

// fakeHealthProvider is a credprov.Provider whose CheckHealth answers whatever a test sets,
// optionally blocking until ctx is done so a test can prove checkNow is bounded by its caller's
// deadline rather than hanging forever.
type fakeHealthProvider struct {
	results []credprov.HealthResult
	block   bool
	calls   int
}

func (f *fakeHealthProvider) GitHubToken(context.Context, uuid.UUID) (credprov.Token, error) {
	return credprov.Token{}, nil
}

func (f *fakeHealthProvider) AgentCredentials(context.Context, uuid.UUID, credprov.Agent, bool) (credprov.Agent, error) {
	return credprov.Agent{}, nil
}

func (f *fakeHealthProvider) CheckHealth(ctx context.Context) []credprov.HealthResult {
	f.calls++
	if f.block {
		<-ctx.Done()
	}
	return f.results
}

func TestHealthMonitorCheckNowFillsTheReport(t *testing.T) {
	now := time.Date(2026, 11, 4, 9, 0, 0, 0, time.UTC)
	provider := &fakeHealthProvider{results: []credprov.HealthResult{
		{Kind: credprov.KindGitHub, Status: credprov.StatusValid, CheckedAt: now},
		{Kind: credprov.KindClaude, Status: credprov.StatusNotHeld, CheckedAt: now},
		{Kind: credprov.KindRegistry, Status: credprov.StatusNotHeld, CheckedAt: now},
	}}
	m := newHealthMonitor(provider, time.Hour)

	m.checkNow(context.Background())

	got := m.latest()
	require.Len(t, got, 3)
}

func TestHealthMonitorCheckNowIsBoundedByItsCallersContext(t *testing.T) {
	provider := &fakeHealthProvider{block: true}
	m := newHealthMonitor(provider, time.Hour)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()

	done := make(chan struct{})
	go func() {
		m.checkNow(ctx)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("checkNow did not return once its context deadline passed")
	}
}

func TestHealthMonitorCheckNowNeverPanicsOnAnyResult(t *testing.T) {
	provider := &fakeHealthProvider{results: []credprov.HealthResult{
		{Kind: credprov.KindGitHub, Status: credprov.StatusUnreachable, Detail: "boom"},
	}}
	m := newHealthMonitor(provider, time.Hour)

	assert.NotPanics(t, func() { m.checkNow(context.Background()) })
}

func TestHealthMonitorLogsAStatusChangeOnce(t *testing.T) {
	var buf bytes.Buffer
	old := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(old)

	now := time.Now()
	provider := &fakeHealthProvider{results: []credprov.HealthResult{
		{Kind: credprov.KindGitHub, Status: credprov.StatusExpired, Detail: "token expired", CheckedAt: now},
	}}
	m := newHealthMonitor(provider, time.Hour)

	m.checkNow(context.Background())
	firstLen := buf.Len()
	require.Greater(t, firstLen, 0, "a change into a non-valid status must be logged")

	// Same status again: no new log line.
	m.checkNow(context.Background())
	assert.Equal(t, firstLen, buf.Len(), "an unchanged status must not be logged again")
}

func TestHealthMonitorLatestReturnsACopy(t *testing.T) {
	provider := &fakeHealthProvider{results: []credprov.HealthResult{
		{Kind: credprov.KindGitHub, Status: credprov.StatusValid},
	}}
	m := newHealthMonitor(provider, time.Hour)
	m.checkNow(context.Background())

	got := m.latest()
	got[0].Status = credprov.StatusExpired // mutate the caller's copy

	again := m.latest()
	assert.Equal(t, credprov.StatusValid, again[0].Status, "latest must return a fresh copy each time")
}

func TestHealthMonitorLatestIsEmptyBeforeAnyCheck(t *testing.T) {
	m := newHealthMonitor(&fakeHealthProvider{}, time.Hour)

	assert.Empty(t, m.latest())
}

func TestHealthMonitorRunStopsWhenContextIsCancelled(t *testing.T) {
	provider := &fakeHealthProvider{results: []credprov.HealthResult{{Kind: credprov.KindGitHub, Status: credprov.StatusValid}}}
	m := newHealthMonitor(provider, time.Millisecond)
	ctx, cancel := context.WithCancel(context.Background())

	done := make(chan struct{})
	go func() {
		m.run(ctx)
		close(done)
	}()
	time.Sleep(10 * time.Millisecond) // let it tick at least once
	cancel()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("run did not return after its context was cancelled")
	}
}
