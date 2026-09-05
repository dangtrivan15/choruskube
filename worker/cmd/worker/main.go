// Command worker is the default entrypoint for a ChorusKube Worker process: it reads its
// configuration from the environment, resolves which Fleet it serves, and runs agent-step
// activities until terminated.
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strconv"
	"syscall"

	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"

	"github.com/dangtrivan15/choruskube/worker"
	"github.com/dangtrivan15/choruskube/worker/executor"
	"github.com/dangtrivan15/choruskube/worker/executor/docker"
	"github.com/dangtrivan15/choruskube/worker/executor/k8s"
)

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	cfg := worker.Config{
		TemporalAddress: os.Getenv("TEMPORAL_ADDRESS"),
		APIServerURL:    os.Getenv("API_SERVER_URL"),
		CallbackURL:     os.Getenv("CALLBACK_URL"),
		// Opt-in, so a deployment against a TLS Temporal cannot lose TLS by omission.
		TemporalTLSDisabled: os.Getenv("TEMPORAL_TLS_DISABLED") == "true",
	}

	// This binary always runs workloads itself -- it never delegates to the API server's legacy
	// creation path -- so an Executor it cannot construct is fatal.
	executorType, err := executorTypeOf(os.Getenv("EXECUTOR_TYPE"))
	if err != nil {
		log.Fatalf("invalid EXECUTOR_TYPE: %v", err)
	}
	switch executorType {
	case "k8s":
		// In-cluster config: this path always runs as a pod inside the target cluster, never on
		// a developer workstation -- there is no kubeconfig fallback.
		restCfg, err := rest.InClusterConfig()
		if err != nil {
			log.Fatalf("in-cluster kubernetes config: %v", err)
		}
		clientset, err := kubernetes.NewForConfig(restCfg)
		if err != nil {
			log.Fatalf("create kubernetes client: %v", err)
		}
		cfg.Executor = k8s.NewKubernetesExecutor(clientset, k8s.Config{
			AgentServiceAccount:  envOrDefault("K8S_AGENT_SERVICE_ACCOUNT", "choruskube-agent"),
			AgentPodTemplateName: envOrDefault("K8S_AGENT_POD_TEMPLATE_NAME", "choruskube-agent-pod-template"),
			TemplateNamespace:    envOrDefault("K8S_TEMPLATE_NAMESPACE", "choruskube"),
			ResourceQuotaEnabled: os.Getenv("K8S_RESOURCE_QUOTA_ENABLED") != "false",
			// Default agent-container sizing, overridable per execution by the api-server. The
			// executor pins no numbers of its own; these env defaults are the deployment's.
			AgentResources: executor.AgentResources{
				CPURequest:    envOrDefault("K8S_AGENT_CPU_REQUEST", "200m"),
				MemoryRequest: envOrDefault("K8S_AGENT_MEMORY_REQUEST", "1Gi"),
				CPULimit:      envOrDefault("K8S_AGENT_CPU_LIMIT", "1"),
				MemoryLimit:   envOrDefault("K8S_AGENT_MEMORY_LIMIT", "3Gi"),
			},
		})
	case "docker":
		dockerStagingDir := os.Getenv("DOCKER_STAGING_DIR")
		if dockerStagingDir == "" {
			// A named, stable path rather than the package's own blank-means-OS-temp-dir default:
			// in Docker-out-of-Docker deployments this directory is bind-mounted into the host
			// daemon at an identical path, which an ephemeral per-process temp dir cannot be.
			dockerStagingDir = "/tmp/choruskube-agent-staging"
		}
		dockerExec, err := docker.New(docker.Config{
			Host:       os.Getenv("DOCKER_HOST"),
			Network:    os.Getenv("DOCKER_NETWORK"),
			StagingDir: dockerStagingDir,
		})
		if err != nil {
			log.Fatalf("create docker executor: %v", err)
		}
		cfg.Executor = dockerExec
	}

	if p := os.Getenv("WORKER_CALLBACK_PORT"); p != "" {
		port, err := strconv.Atoi(p)
		if err != nil {
			log.Fatalf("invalid WORKER_CALLBACK_PORT %q: %v", p, err)
		}
		cfg.CallbackPort = port
	}

	capabilities, err := worker.ParseCapabilitiesEnv(os.Getenv("WORKER_CAPABILITIES"))
	if err != nil {
		log.Fatalf("invalid WORKER_CAPABILITIES: %v", err)
	}

	// Resolved here, not in Config: which Fleet this process serves is the one setting with two
	// legitimate answers, and Config.Validate can only see that a Provider was supplied.
	// LookupEnv, not Getenv: an exported-but-empty FLEET_TOKEN means "I meant to register and my
	// credential did not arrive", which must fail rather than fall through to the static path.
	fleetToken, fleetTokenSet := os.LookupEnv("FLEET_TOKEN")
	provider, err := worker.FleetSource{
		APIServerURL:  cfg.APIServerURL,
		FleetToken:    fleetToken,
		FleetTokenSet: fleetTokenSet,
		Namespace:     os.Getenv("TEMPORAL_NAMESPACE"),
		TaskQueue:     os.Getenv("TEMPORAL_TASK_QUEUE"),
		// Only the static path needs this: the registration path is handed a credential, or
		// falls back to the Fleet token it registered with.
		InternalToken: os.Getenv("WORKER_INTERNAL_TOKEN"),
		Capabilities:  capabilities,
	}.Provider(nil)
	if err != nil {
		log.Fatalf("%v: set FLEET_TOKEN to register with the API server, "+
			"or TEMPORAL_NAMESPACE, TEMPORAL_TASK_QUEUE and WORKER_INTERNAL_TOKEN to serve one "+
			"Fleet without registering", err)
	}
	cfg.Provider = provider

	// Fail on a missing required setting here, before Run ever reaches the network: a bad
	// config would otherwise surface first as an opaque registration HTTP error.
	if err := cfg.Validate(); err != nil {
		log.Fatalf("invalid worker config: %v", err)
	}

	if err := worker.Run(ctx, cfg); err != nil {
		log.Fatalf("worker exited: %v", err)
	}
}

// executorTypeOf validates EXECUTOR_TYPE, defaulting an unset value to "docker" rather than
// silently accepting any string -- a typo must fail startup, not fall back to the wrong executor.
func executorTypeOf(raw string) (string, error) {
	switch raw {
	case "":
		return "docker", nil
	case "docker", "k8s":
		return raw, nil
	default:
		return "", fmt.Errorf("unknown EXECUTOR_TYPE %q: must be \"docker\" or \"k8s\"", raw)
	}
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
