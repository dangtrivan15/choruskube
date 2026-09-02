package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os/signal"
	"syscall"

	"github.com/google/uuid"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/worker"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/callback"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/completer"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/config"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/objectstore"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/prompt"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/reconciler"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/workflow"
)

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	cfg := config.Load()

	// --- API Client (replaces direct Postgres connection) ---
	var apiClient *apiclient.Client
	if cfg.OrchestratorSecret != "" {
		apiClient = apiclient.NewAuthenticatedClient(cfg.APIServerURL, cfg.OrchestratorSecret)
		log.Printf("API client configured with auth: %s", cfg.APIServerURL)
	} else {
		apiClient = apiclient.NewClient(cfg.APIServerURL)
		log.Printf("API client configured (no auth): %s", cfg.APIServerURL)
	}

	// --- Temporal ---
	temporalClient, err := client.Dial(client.Options{
		HostPort:  cfg.Temporal.Address,
		Namespace: cfg.Temporal.Namespace,
	})
	if err != nil {
		log.Fatalf("Failed to connect to Temporal: %v", err)
	}
	defer temporalClient.Close()
	log.Println("Connected to Temporal")

	// --- Executor is now in the API server ---
	// The orchestrator delegates workload creation/management to the API server via
	// /internal/workloads endpoints. No local executor is needed.
	log.Println("Executor delegated to API server via /internal/workloads endpoints")

	// --- Object storage client (for run log) ---
	objectStoreClient, err := objectstore.NewClient(
		cfg.ObjectStore.Endpoint, cfg.ObjectStore.Bucket,
		cfg.ObjectStore.AccessKey, cfg.ObjectStore.SecretKey,
	)
	if err != nil {
		log.Fatalf("Failed to create object storage client: %v", err)
	}
	log.Printf("Object store client configured: %s / %s", cfg.ObjectStore.Endpoint, cfg.ObjectStore.Bucket)

	// --- Activities ---
	resolver := prompt.NewResolver()
	activities := activity.NewActivities(apiClient, resolver, cfg, objectStoreClient)

	// --- Temporal Worker ---
	w := worker.New(temporalClient, workflow.TaskQueue, worker.Options{})
	w.RegisterWorkflow(workflow.DAGExecutorWorkflow)
	w.RegisterActivity(activities)

	go func() {
		if err := w.Run(worker.InterruptCh()); err != nil {
			log.Fatalf("Temporal worker failed: %v", err)
		}
	}()
	log.Println("Temporal worker started")

	// --- Activity Completer (wraps Temporal client for async completion) ---
	activityCompleter := completer.New(temporalClient, cfg.Temporal.Namespace)

	// --- Callback & Heartbeat HTTP Server ---
	callbackHandler := callback.NewHandler(apiClient, activityCompleter)
	heartbeatHandler := callback.NewHeartbeatHandler(apiClient, activityCompleter)
	mux := http.NewServeMux()
	mux.Handle("/api/v1/callback", callbackHandler)
	mux.Handle("/api/v1/heartbeat", heartbeatHandler)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		// Health check delegates to the API server's workload health endpoint
		if err := apiClient.WorkloadHealth(r.Context()); err != nil {
			// Fall through to healthy if the API server workload health fails
			// (executor may not be configured, but orchestrator itself is healthy)
			log.Printf("Workload health check warning: %v", err)
		}
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{"status":"healthy"}`)
	})

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", cfg.Callback.Port),
		Handler: mux,
	}

	go func() {
		log.Printf("Callback server listening on :%d", cfg.Callback.Port)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("Callback server failed: %v", err)
		}
	}()

	// --- Orphaned Resource Reconciler ---
	rec := reconciler.New(&reconcilerAPIAdapter{client: apiClient}, reconciler.DefaultConfig())
	go rec.Start(ctx)
	log.Println("Orphaned resource reconciler started")

	fmt.Println("Orchestrator ready")
	<-ctx.Done()

	log.Println("Shutting down")
	server.Shutdown(context.Background())
	w.Stop()
}

// --- Adapters ---

// reconcilerAPIAdapter adapts *apiclient.Client to the reconciler.APIClient interface.
type reconcilerAPIAdapter struct {
	client *apiclient.Client
}

func (a *reconcilerAPIAdapter) GetWorkflowRunStatus(ctx context.Context, runID uuid.UUID) (string, error) {
	return a.client.GetWorkflowRunStatus(ctx, runID)
}

func (a *reconcilerAPIAdapter) TerminateWorkload(ctx context.Context, executionID uuid.UUID) error {
	return a.client.TerminateWorkload(ctx, executionID)
}

func (a *reconcilerAPIAdapter) ListWorkloads(ctx context.Context) ([]reconciler.WorkloadInfo, error) {
	workloads, err := a.client.ListWorkloads(ctx)
	if err != nil {
		return nil, err
	}
	result := make([]reconciler.WorkloadInfo, len(workloads))
	for i, wl := range workloads {
		result[i] = reconciler.WorkloadInfo{
			NodeExecutionID: wl.NodeExecutionID,
			RunID:           wl.RunID,
			ExecutionHandle: wl.ExecutionHandle,
		}
	}
	return result, nil
}
