// Package docker implements executor.Executor by launching agent workloads as Docker
// containers, for the open, single-tenant self-hosted deployment. It is the single-tenant
// counterpart to github.com/dangtrivan15/choruskube/worker/executor/k8s: same contract, no
// per-org namespace isolation.
package docker

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/distribution/reference"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/filters"
	"github.com/docker/docker/api/types/image"
	"github.com/docker/docker/api/types/mount"
	"github.com/docker/docker/api/types/registry"
	"github.com/docker/docker/api/types/volume"
	"github.com/docker/docker/client"
	dockererrdefs "github.com/docker/docker/errdefs"
	"github.com/docker/docker/pkg/stdcopy"
	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/worker/executor"
)

const (
	// containerNamePrefix and the constants below mirror SingleTenantDockerExecutor.java's
	// resource naming so that anyone reading Docker resources on a host running both a legacy
	// api-server-launched agent and a Worker-launched one recognizes the same conventions.
	containerNamePrefix     = "ck-agent-"
	labelAppKey             = "app"
	labelApp                = "choruskube-agent"
	labelRunID              = "choruskube/run-id"
	labelExecID             = "choruskube/exec-id"
	labelTmpDir             = "choruskube/tmp-dir"
	labelHasDind            = "choruskube/has-dind"
	dindImage               = "docker:29-dind"
	dindNamePrefix          = "ck-dind-"
	dindVolumePrefix        = "ck-dind-data-"
	defaultNetwork          = "choruskube"
	logLimitBytes           = 64 * 1024
	defaultDindReadyTimeout = 30 * time.Second
	// pullTimeout bounds a best-effort image pull, matching SingleTenantDockerExecutor's
	// awaitCompletion(120, SECONDS) -- without it, a registry that accepts the pull request but
	// stalls mid-transfer would block Execute for as long as ctx allows, turning a "best-effort"
	// pull into an unbounded one.
	pullTimeout = 120 * time.Second
)

// Config configures a DockerExecutor.
type Config struct {
	// Host is the Docker daemon socket (e.g. "unix:///var/run/docker.sock"). Empty uses the
	// client library's own environment-based default.
	Host string
	// Network is the Docker network agent containers and DinD sidecars attach to. Empty falls
	// back to "choruskube".
	Network string
	// StagingDir is the base directory under which per-execution config/credential staging
	// dirs are created. Blank falls back to the OS temp dir.
	//
	// In Docker-out-of-Docker deployments the Worker itself runs in a container but talks to
	// the host daemon over a mounted socket, so a bind source path must resolve to the same
	// bytes on the host as it does here — this dir must be mounted at an identical path on
	// both sides.
	StagingDir string
	// DindReadyTimeout bounds how long Execute waits for a DinD sidecar's healthcheck to pass
	// before failing the execution. Zero defaults to 30s.
	DindReadyTimeout time.Duration
}

// DockerExecutor implements executor.Executor by launching agent workloads as Docker
// containers on a single Docker daemon.
type DockerExecutor struct {
	client           *client.Client
	network          string
	stagingDir       string
	dindReadyTimeout time.Duration
}

// New returns a DockerExecutor connected to the Docker daemon described by cfg.
func New(cfg Config) (*DockerExecutor, error) {
	opts := []client.Opt{client.FromEnv, client.WithAPIVersionNegotiation()}
	if cfg.Host != "" {
		opts = append(opts, client.WithHost(cfg.Host))
	}
	c, err := client.NewClientWithOpts(opts...)
	if err != nil {
		return nil, fmt.Errorf("docker client: %w", err)
	}

	network := cfg.Network
	if network == "" {
		network = defaultNetwork
	}
	dindTimeout := cfg.DindReadyTimeout
	if dindTimeout == 0 {
		dindTimeout = defaultDindReadyTimeout
	}

	return &DockerExecutor{
		client:           c,
		network:          network,
		stagingDir:       cfg.StagingDir,
		dindReadyTimeout: dindTimeout,
	}, nil
}

var _ executor.Executor = (*DockerExecutor)(nil)

// Execute launches params as a new Docker container: it stages config.json to a per-execution
// directory reachable by the Docker daemon, optionally starts a DinD sidecar and a registry
// auth config, then creates and starts the agent container.
func (d *DockerExecutor) Execute(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
	execIDShort := params.NodeExecutionID.String()[:8]
	containerName := containerNamePrefix + execIDShort

	tmpDir, err := d.createStagingDir(execIDShort)
	if err != nil {
		return executor.ExecutionResult{}, fmt.Errorf("create staging dir: %w", err)
	}
	// Any failure past this point must not leave the staging dir or a DinD sidecar behind —
	// the caller has no other handle to find and remove them (the container itself, whose
	// label would otherwise point back at tmpDir, was never created).
	dindStarted := false
	success := false
	defer func() {
		if !success {
			d.deleteTempDir(tmpDir)
			if dindStarted {
				// Best-effort: Execute is already failing for its own reason below: a
				// second error from unwinding the DinD sidecar has no caller left to
				// report to here.
				_ = d.cleanupDindResources(context.WithoutCancel(ctx), execIDShort)
			}
		}
	}()

	configPath := filepath.Join(tmpDir, "config.json")
	configBytes, err := json.MarshalIndent(params.ConfigJSON, "", "  ")
	if err != nil {
		return executor.ExecutionResult{}, fmt.Errorf("marshal config.json: %w", err)
	}
	// 0o644, not 0o600: the agent image drops to a non-root user, and this file is bind-mounted
	// read-only into it — an owner-only mode (the worker writes it as root) makes the entrypoint's
	// jq reads fail with EACCES, killing the agent before it can call back. The per-execution
	// staging dir is 0o700, so this stays unreadable to other host users; only the mount exposes it.
	if err := os.WriteFile(configPath, configBytes, 0o644); err != nil {
		return executor.ExecutionResult{}, fmt.Errorf("write config.json: %w", err)
	}

	hash := executor.HashSecret(params.JobSecret)

	env := []string{"JOB_SECRET=" + params.JobSecret}
	if params.Credentials.ClaudeOAuthToken != "" {
		env = append(env, "CLAUDE_CODE_OAUTH_TOKEN="+params.Credentials.ClaudeOAuthToken)
	}
	// The caller supplies every extra env var via Environment -- this package injects no env of
	// its own beyond JOB_SECRET/token above and (below) the registry wiring.
	for k, v := range params.Environment {
		env = append(env, k+"="+v)
	}

	mounts := []mount.Mount{
		{Type: mount.TypeBind, Source: configPath, Target: "/workspace/config.json", ReadOnly: true},
	}

	if params.EnableDocker {
		dindStarted = true
		dindContainerID, err := d.startDindSidecar(ctx, execIDShort)
		if err != nil {
			return executor.ExecutionResult{}, fmt.Errorf("start DinD sidecar: %w", err)
		}
		if err := d.waitForDindReady(ctx, dindContainerID); err != nil {
			return executor.ExecutionResult{}, fmt.Errorf("start DinD sidecar: %w", err)
		}
		env = append(env, "DOCKER_HOST=tcp://"+dindNamePrefix+execIDShort+":2375")
	}

	var pullAuth string
	if reg := params.Credentials.Registry; reg != nil {
		authJSON, err := buildRegistryAuthConfigJSON(reg)
		if err != nil {
			return executor.ExecutionResult{}, fmt.Errorf("build registry auth config: %w", err)
		}
		regcredPath := filepath.Join(tmpDir, "docker-config.json")
		// 0o644 for the same reason as config.json: the non-root agent reads this via its bind
		// mount, so an owner-only mode makes its docker pulls fail. The 0o700 staging dir is the
		// isolation boundary that keeps these registry credentials off other host users.
		if err := os.WriteFile(regcredPath, authJSON, 0o644); err != nil {
			return executor.ExecutionResult{}, fmt.Errorf("stage registry auth config: %w", err)
		}
		// Same payload serves two purposes: authenticating this executor's own pull of
		// params.Image below (via pullAuth), and being mounted into the container as its
		// Docker client config so the agent's own docker commands against DOCKER_HOST (DinD)
		// can pull/push against the same registry.
		mounts = append(mounts, mount.Mount{
			Type:     mount.TypeBind,
			Source:   regcredPath,
			Target:   "/etc/regcred/config.json",
			ReadOnly: true,
		})
		env = append(env, "DOCKER_CONFIG=/etc/regcred")

		pullAuth, err = registryAuthHeader(reg, params.Image)
		if err != nil {
			return executor.ExecutionResult{}, fmt.Errorf("encode registry auth header: %w", err)
		}
	}

	labels := map[string]string{
		labelAppKey: labelApp,
		labelRunID:  params.RunID.String(),
		labelExecID: params.NodeExecutionID.String(),
		labelTmpDir: tmpDir,
	}
	if params.EnableDocker {
		labels[labelHasDind] = "true"
	}

	d.pullImageBestEffort(ctx, params.Image, pullAuth)

	containerCfg := &container.Config{
		Image:  params.Image,
		Cmd:    params.Command,
		Env:    env,
		Labels: labels,
	}
	hostCfg := &container.HostConfig{
		Mounts:      mounts,
		NetworkMode: container.NetworkMode(d.network),
	}

	resp, err := d.client.ContainerCreate(ctx, containerCfg, hostCfg, nil, nil, containerName)
	if err != nil {
		return executor.ExecutionResult{}, fmt.Errorf("create container: %w", err)
	}
	if err := d.client.ContainerStart(ctx, resp.ID, container.StartOptions{}); err != nil {
		return executor.ExecutionResult{}, fmt.Errorf("start container: %w", err)
	}

	success = true
	return executor.ExecutionResult{PodName: containerName, JobSecretHash: hash}, nil
}

// Cleanup removes all Docker resources for executionID: the agent container, its staging
// directory, and — if this execution had DinD enabled — the DinD sidecar and its data volume.
// It is idempotent: a missing container is not an error, since the caller may retry after a
// partial cleanup or call Cleanup for an execution that never created any resources. A single
// Docker host has no namespaces, and containers are found by exec-id label.
func (d *DockerExecutor) Cleanup(ctx context.Context, executionID uuid.UUID) error {
	c, err := d.findContainer(ctx, executionID)
	if err != nil {
		return fmt.Errorf("find container: %w", err)
	}
	if c == nil {
		return nil
	}

	if tmpDir := c.Labels[labelTmpDir]; tmpDir != "" {
		d.deleteTempDir(tmpDir)
	}

	if err := d.client.ContainerRemove(ctx, c.ID, container.RemoveOptions{Force: true, RemoveVolumes: true}); err != nil {
		return fmt.Errorf("remove container: %w", err)
	}

	if c.Labels[labelHasDind] == "true" {
		execIDShort := executionID.String()[:8]
		if err := d.cleanupDindResources(ctx, execIDShort); err != nil {
			return fmt.Errorf("cleanup DinD resources: %w", err)
		}
	}
	return nil
}

// Terminate stops executionID's container gracefully (SIGTERM, 30s grace before SIGKILL).
// Idempotent: an already-stopped or already-gone container is not an error. A single Docker host
// has no namespaces.
func (d *DockerExecutor) Terminate(ctx context.Context, executionID uuid.UUID) error {
	c, err := d.findContainer(ctx, executionID)
	if err != nil {
		return fmt.Errorf("find container: %w", err)
	}
	if c == nil {
		return nil
	}

	timeout := 30
	if err := d.client.ContainerStop(ctx, c.ID, container.StopOptions{Timeout: &timeout}); err != nil {
		if client.IsErrNotFound(err) || dockererrdefs.IsNotModified(err) {
			return nil
		}
		return fmt.Errorf("stop container: %w", err)
	}
	return nil
}

// GetLogs returns up to the last tailLines lines of executionID's container output (stdout and
// stderr interleaved), capped at 64KB. A single Docker host has no namespaces.
func (d *DockerExecutor) GetLogs(ctx context.Context, executionID uuid.UUID, tailLines int) (string, error) {
	c, err := d.findContainer(ctx, executionID)
	if err != nil {
		return "", fmt.Errorf("find container: %w", err)
	}
	if c == nil {
		return "(no container found)", nil
	}

	reader, err := d.client.ContainerLogs(ctx, c.ID, container.LogsOptions{
		ShowStdout: true,
		ShowStderr: true,
		Tail:       strconv.Itoa(tailLines),
	})
	if err != nil {
		return "", fmt.Errorf("get logs: %w", err)
	}
	defer reader.Close()

	// Container was created without a TTY, so stdout/stderr arrive multiplexed with an 8-byte
	// frame header each; StdCopy demultiplexes into plain text instead of leaking those header
	// bytes into the output.
	var out strings.Builder
	if _, err := stdcopy.StdCopy(&out, &out, reader); err != nil && err != io.EOF {
		return "", fmt.Errorf("read logs: %w", err)
	}

	data := out.String()
	if len(data) > logLimitBytes {
		data = data[len(data)-logLimitBytes:]
	}
	return data, nil
}

// ResolveJobSecretHash reads JOB_SECRET back from executionID's container environment and
// returns its SHA-256 hash. Used to recover the hash cache after a Worker restart. A single
// Docker host has no namespaces.
func (d *DockerExecutor) ResolveJobSecretHash(ctx context.Context, executionID uuid.UUID) (string, error) {
	c, err := d.findContainer(ctx, executionID)
	if err != nil {
		return "", fmt.Errorf("find container: %w", err)
	}
	if c == nil {
		return "", fmt.Errorf("no container found for execution %s", executionID)
	}

	inspect, err := d.client.ContainerInspect(ctx, c.ID)
	if err != nil {
		return "", fmt.Errorf("inspect container: %w", err)
	}
	if inspect.Config == nil {
		return "", fmt.Errorf("container %s has no config", c.ID)
	}
	for _, envVar := range inspect.Config.Env {
		if secret, ok := strings.CutPrefix(envVar, "JOB_SECRET="); ok {
			return executor.HashSecret(secret), nil
		}
	}
	return "", fmt.Errorf("JOB_SECRET not found in container for execution %s", executionID)
}

// HealthCheck verifies the Docker daemon is reachable.
func (d *DockerExecutor) HealthCheck(ctx context.Context) error {
	_, err := d.client.Ping(ctx)
	return err
}

// --- Helpers ---

// findContainer looks up the container for executionID by label rather than by reconstructing
// its name, so lookups survive a Worker restart without any in-memory state.
func (d *DockerExecutor) findContainer(ctx context.Context, executionID uuid.UUID) (*container.Summary, error) {
	f := filters.NewArgs()
	f.Add("label", labelExecID+"="+executionID.String())
	containers, err := d.client.ContainerList(ctx, container.ListOptions{All: true, Filters: f})
	if err != nil {
		return nil, err
	}
	if len(containers) == 0 {
		return nil, nil
	}
	return &containers[0], nil
}

// startDindSidecar launches the Docker-in-Docker sidecar for a DinD-enabled execution and
// returns its container ID. The sidecar runs privileged (required for the inner daemon) with
// its own named volume for /var/lib/docker, on the same network as the agent container so the
// agent can reach it at ck-dind-<execIdShort>:2375.
func (d *DockerExecutor) startDindSidecar(ctx context.Context, execIDShort string) (string, error) {
	dindName := dindNamePrefix + execIDShort
	volName := dindVolumePrefix + execIDShort

	if _, err := d.client.VolumeCreate(ctx, volume.CreateOptions{Name: volName}); err != nil {
		return "", fmt.Errorf("create DinD volume: %w", err)
	}

	// The Engine API's container-create does not auto-pull, so pull the sidecar image first
	// (best-effort, like the agent image) -- otherwise a fresh daemon 404s with "No such image".
	d.pullImageBestEffort(ctx, dindImage, "")

	healthcheck := &container.HealthConfig{
		Test:        []string{"CMD", "docker", "version"},
		Interval:    1 * time.Second,
		Timeout:     3 * time.Second,
		Retries:     30,
		StartPeriod: 5 * time.Second,
	}

	resp, err := d.client.ContainerCreate(ctx,
		&container.Config{
			Image:       dindImage,
			Env:         []string{"DOCKER_TLS_CERTDIR="},
			Labels:      map[string]string{labelAppKey: labelApp, "choruskube/dind": "true"},
			Healthcheck: healthcheck,
		},
		&container.HostConfig{
			Mounts:      []mount.Mount{{Type: mount.TypeVolume, Source: volName, Target: "/var/lib/docker"}},
			NetworkMode: container.NetworkMode(d.network),
			Privileged:  true,
		},
		nil, nil, dindName)
	if err != nil {
		return "", fmt.Errorf("create DinD container: %w", err)
	}
	if err := d.client.ContainerStart(ctx, resp.ID, container.StartOptions{}); err != nil {
		return "", fmt.Errorf("start DinD container: %w", err)
	}
	return resp.ID, nil
}

// waitForDindReady polls containerID's health status until "healthy" or dindReadyTimeout
// elapses.
func (d *DockerExecutor) waitForDindReady(ctx context.Context, containerID string) error {
	deadline := time.Now().Add(d.dindReadyTimeout)
	for time.Now().Before(deadline) {
		inspect, err := d.client.ContainerInspect(ctx, containerID)
		if err == nil && inspect.State != nil && inspect.State.Health != nil &&
			inspect.State.Health.Status == container.Healthy {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(1 * time.Second):
		}
	}
	return fmt.Errorf("DinD sidecar did not become healthy within %s", d.dindReadyTimeout)
}

// cleanupDindResources force-removes the DinD sidecar container and its data volume for
// execIDShort. Idempotent: a not-found error on either is swallowed. The two removals are
// attempted independently — a failure removing the container must not skip the attempt to
// remove its volume, since each is the only handle a retry would have to that resource.
func (d *DockerExecutor) cleanupDindResources(ctx context.Context, execIDShort string) error {
	dindName := dindNamePrefix + execIDShort
	volName := dindVolumePrefix + execIDShort

	var errs []error
	if err := d.client.ContainerRemove(ctx, dindName, container.RemoveOptions{Force: true}); err != nil && !client.IsErrNotFound(err) {
		errs = append(errs, fmt.Errorf("remove DinD container: %w", err))
	}
	if err := d.client.VolumeRemove(ctx, volName, true); err != nil && !client.IsErrNotFound(err) {
		errs = append(errs, fmt.Errorf("remove DinD volume: %w", err))
	}
	return errors.Join(errs...)
}

// createStagingDir creates the per-execution directory that holds config.json and any staged
// registry auth config. When d.stagingDir is set, it is created under that shared, host-mounted
// base so its absolute path is valid both here and on the host Docker daemon (required for
// sibling bind mounts in Docker-out-of-Docker mode). Blank falls back to the OS temp dir --
// correct for direct-on-host runs and tests.
func (d *DockerExecutor) createStagingDir(execIDShort string) (string, error) {
	prefix := "ck-config-" + execIDShort + "-"
	if d.stagingDir != "" {
		if err := os.MkdirAll(d.stagingDir, 0o700); err != nil {
			return "", err
		}
		return os.MkdirTemp(d.stagingDir, prefix)
	}
	return os.MkdirTemp("", prefix)
}

func (d *DockerExecutor) deleteTempDir(dir string) {
	if dir == "" {
		return
	}
	_ = os.RemoveAll(dir)
}

// pullImageBestEffort ensures imageName is available locally, pulling only when it is absent.
// Local-first by design: when the image is already present we skip the registry round-trip
// entirely, which keeps a self-hosted stack (agent images built locally) fully self-contained
// and avoids a per-run pull that 404s on a host whose architecture the published image lacks.
// The pull, when needed, stays best-effort: a failure is swallowed and Execute proceeds against
// whatever is present, matching SingleTenantDockerExecutor.pullImageBestEffort.
func (d *DockerExecutor) pullImageBestEffort(ctx context.Context, imageName, encodedAuth string) {
	if _, _, err := d.client.ImageInspectWithRaw(ctx, imageName); err == nil {
		return
	}

	pullCtx, cancel := context.WithTimeout(ctx, pullTimeout)
	defer cancel()

	reader, err := d.client.ImagePull(pullCtx, imageName, image.PullOptions{RegistryAuth: encodedAuth})
	if err != nil {
		return
	}
	defer reader.Close()
	// The pull itself happens as this body is streamed, not at ImagePull's call above, so the
	// deadline has to cover this read too -- otherwise a registry that accepts the request but
	// stalls mid-transfer would hang here past pullTimeout.
	_, _ = io.Copy(io.Discard, reader)
}

// buildRegistryAuthConfigJSON renders reg as a Docker CLI config.json ("auths" map keyed by
// registry host), the same document shape kubectl's kubernetes.io/dockerconfigjson secret type
// carries -- so a private agent image and, once staged into the container as DOCKER_CONFIG, the
// agent's own docker pushes/pulls, authenticate the same way.
func buildRegistryAuthConfigJSON(reg *executor.RegistryCredentials) ([]byte, error) {
	doc := map[string]any{
		"auths": map[string]any{
			reg.Host: map[string]string{
				"username": reg.Username,
				"password": reg.Password,
				"auth":     b64UserPass(reg.Username, reg.Password),
			},
		},
	}
	return json.MarshalIndent(doc, "", "  ")
}

// b64UserPass returns the base64(username:password) form the Docker CLI config.json "auth"
// field expects.
func b64UserPass(username, password string) string {
	return base64.StdEncoding.EncodeToString([]byte(username + ":" + password))
}

// registryAuthHeader returns the base64-encoded X-Registry-Auth header value for pulling
// imageRef, or "" if reg's host does not match imageRef's registry -- credentials for one
// registry must never be offered to another.
func registryAuthHeader(reg *executor.RegistryCredentials, imageRef string) (string, error) {
	named, err := reference.ParseNormalizedNamed(imageRef)
	if err != nil {
		return "", fmt.Errorf("parse image reference %q: %w", imageRef, err)
	}
	if reference.Domain(named) != reg.Host {
		return "", nil
	}
	return registry.EncodeAuthConfig(registry.AuthConfig{
		Username:      reg.Username,
		Password:      reg.Password,
		ServerAddress: reg.Host,
	})
}
