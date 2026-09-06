package worker

import (
	"context"
	"testing"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/worker/executor"
)

// nopExecutor satisfies executor.Executor with do-nothing methods, so a test can embed it and
// override only the capability it exercises.
type nopExecutor struct{}

func (nopExecutor) Execute(context.Context, executor.ExecutionParams) (executor.ExecutionResult, error) {
	return executor.ExecutionResult{}, nil
}
func (nopExecutor) Cleanup(context.Context, uuid.UUID) error                        { return nil }
func (nopExecutor) Terminate(context.Context, uuid.UUID) error                      { return nil }
func (nopExecutor) GetLogs(context.Context, uuid.UUID, int) (string, error)         { return "", nil }
func (nopExecutor) ResolveJobSecretHash(context.Context, uuid.UUID) (string, error) { return "", nil }
func (nopExecutor) HealthCheck(context.Context) error                               { return nil }

// credConsumerExecutor records the getter it is handed, so a test can prove wireExecutorCredential
// both invoked SetAPIServerCredential and passed a getter that reads the live (rotating) value.
type credConsumerExecutor struct {
	nopExecutor
	got func() string
}

func (c *credConsumerExecutor) SetAPIServerCredential(get func() string) { c.got = get }

func TestWireExecutorCredential_PassesLiveRotatingGetter(t *testing.T) {
	creds := newCredentialCache("first")
	exec := &credConsumerExecutor{}

	wireExecutorCredential(exec, creds.get)

	if exec.got == nil {
		t.Fatal("wireExecutorCredential did not call SetAPIServerCredential on a CredentialConsumer")
	}
	if v := exec.got(); v != "first" {
		t.Fatalf("getter = %q, want the current credential %q", v, "first")
	}
	// The getter must read the cache live: a credential rotated after wiring has to be visible, or
	// the executor's own API-server calls would keep presenting a revoked token.
	creds.set("second")
	if v := exec.got(); v != "second" {
		t.Fatalf("after rotation getter = %q, want %q -- getter captured a stale value", v, "second")
	}
}

func TestWireExecutorCredential_NoOpForNonConsumer(t *testing.T) {
	// A static single-namespace executor implements no CredentialConsumer; wiring must be a silent
	// no-op, not a panic.
	wireExecutorCredential(nopExecutor{}, func() string { return "x" })
}
