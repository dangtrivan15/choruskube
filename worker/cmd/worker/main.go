// Command worker is the default entrypoint for a ChorusKube Worker process: it reads its
// configuration from the environment, registers with its Fleet, and serves agent-step
// activities until terminated.
package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/dangtrivan15/choruskube/worker"
)

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	cfg := worker.Config{
		TemporalAddress: os.Getenv("TEMPORAL_ADDRESS"),
		APIServerURL:    os.Getenv("API_SERVER_URL"),
		InternalSecret:  os.Getenv("ORCHESTRATOR_SECRET"),
		CallbackURL:     os.Getenv("CALLBACK_URL"),
	}
	cfg.Provider = worker.NewTokenFleetProvider(cfg.APIServerURL, os.Getenv("FLEET_TOKEN"), nil)

	// Fail on a missing required setting here, before Run ever reaches the network: a bad
	// config would otherwise surface first as an opaque registration HTTP error.
	if err := cfg.Validate(); err != nil {
		log.Fatalf("invalid worker config: %v", err)
	}

	if err := worker.Run(ctx, cfg); err != nil {
		log.Fatalf("worker exited: %v", err)
	}
}
