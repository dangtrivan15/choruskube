// Package docker implements executor.Executor by launching agent workloads as Docker
// containers, for the open, single-tenant self-hosted deployment. It is the single-tenant
// counterpart to github.com/dangtrivan15/choruskube/worker/executor/k8s: same contract, no
// per-org namespace isolation.
//
// Unlike Kubernetes, which reclaims a Job's workload itself once ActiveDeadlineSeconds or
// TTLSecondsAfterFinished fires, Docker has no such native bound. DockerExecutor's own janitor
// goroutine (New starts it, Close stops it) plays that role, reading the choruskube/deadline and
// choruskube/retention labels every object it creates carries back off the daemon -- see sweep.
package docker

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
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
	// Resource names mirror SingleTenantDockerExecutor.java so both launchers use one naming
	// convention on a shared host. Containers are found by label, so a divergent name is cosmetic.
	containerNamePrefix = "ck-agent-"
	labelAppKey         = "app"
	labelApp            = "choruskube-agent"
	labelRunID          = "choruskube/run-id"
	labelExecID         = "choruskube/exec-id"
	labelTmpDir         = "choruskube/tmp-dir"
	labelHasDind        = "choruskube/has-dind"
	// labelDind marks the DinD sidecar container itself, distinguishing it from the agent
	// container within a group -- both carry every other label above identically.
	labelDind = "choruskube/dind"
	// labelDeadline and labelRetention are the janitor's only input besides labelExecID: sweep
	// reads them back off whichever of a group's objects still exist, so every object this
	// executor creates for an execution must carry the same values.
	labelDeadline           = "choruskube/deadline"
	labelRetention          = "choruskube/retention"
	defaultDindImage        = "docker:29-dind"
	dindNamePrefix          = "ck-dind-"
	dindVolumePrefix        = "ck-dind-data-"
	workspaceVolumePrefix   = "ck-workspace-"
	workspaceMountPath      = "/workspace"
	defaultNetwork          = "choruskube"
	logLimitBytes           = 64 * 1024
	defaultDindReadyTimeout = 30 * time.Second
	// pullTimeout bounds a best-effort image pull: without it a registry that accepts the request
	// but stalls mid-transfer blocks Execute for as long as ctx allows.
	pullTimeout = 120 * time.Second
	// janitorInterval is how often sweep runs after its initial pass at startup.
	janitorInterval = time.Minute
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
	// DindImage is the image started as the DinD sidecar. Empty falls back to the stock
	// "docker:29-dind".
	DindImage string
	// Retention is how long a finished workload is kept before the janitor removes it, floored
	// per launch by executor.Bounds.Retention against that node's own heartbeat timeout.
	Retention time.Duration
}

// DockerExecutor implements executor.Executor by launching agent workloads as Docker
// containers on a single Docker daemon.
type DockerExecutor struct {
	client           *client.Client
	network          string
	stagingDir       string
	dindReadyTimeout time.Duration
	dindImage        string
	retention        time.Duration

	janitorStop chan struct{}
	janitorDone chan struct{}
}

// New returns a DockerExecutor connected to the Docker daemon described by cfg, with its
// janitor goroutine already running (see Close).
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
	dindImg := cfg.DindImage
	if dindImg == "" {
		dindImg = defaultDindImage
	}

	d := &DockerExecutor{
		client:           c,
		network:          network,
		stagingDir:       cfg.StagingDir,
		dindReadyTimeout: dindTimeout,
		dindImage:        dindImg,
		retention:        cfg.Retention,
		janitorStop:      make(chan struct{}),
		janitorDone:      make(chan struct{}),
	}
	go d.runJanitor()
	return d, nil
}

// Close stops this executor's janitor goroutine and waits for its current sweep, if any, to
// finish. Deliberately not part of executor.Executor: only an entrypoint that owns this
// executor's whole lifecycle, not the Worker's generic per-execution use of it, calls this.
func (d *DockerExecutor) Close() {
	close(d.janitorStop)
	<-d.janitorDone
}

var _ executor.Executor = (*DockerExecutor)(nil)

// Execute launches params as a new Docker container: it stages config.json to a per-execution
// directory reachable by the Docker daemon, optionally starts a DinD sidecar and a registry
// auth config, then creates and starts the agent container.
func (d *DockerExecutor) Execute(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
	// Refused before anything is created: with no deadline label to read, the janitor would
	// never reclaim this workload, and nothing short of a human would.
	if params.Bounds.ActivityTimeout <= 0 {
		return executor.ExecutionResult{}, fmt.Errorf("execute: activity timeout must be positive, got %s", params.Bounds.ActivityTimeout)
	}

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
				// Best-effort: Execute is already failing, so a second error here has no
				// caller left to report to.
				_ = d.cleanupDindResources(context.WithoutCancel(ctx), execIDShort)
			}
		}
	}()

	configPath := filepath.Join(tmpDir, "config.json")
	configBytes, err := json.MarshalIndent(params.ConfigJSON, "", "  ")
	if err != nil {
		return executor.ExecutionResult{}, fmt.Errorf("marshal config.json: %w", err)
	}
	// 0o644, not 0o600: the non-root agent bind-mounts this read-only, so an owner-only mode (the
	// worker writes it as root) fails the entrypoint's jq reads with EACCES, killing the agent
	// before callback. The 0o700 staging dir keeps it off other host users; only the mount exposes it.
	if err := os.WriteFile(configPath, configBytes, 0o644); err != nil {
		return executor.ExecutionResult{}, fmt.Errorf("write config.json: %w", err)
	}

	hash := executor.HashSecret(params.JobSecret)

	env := []string{"JOB_SECRET=" + params.JobSecret}
	if params.Credentials.ClaudeOAuthToken != "" {
		env = append(env, "CLAUDE_CODE_OAUTH_TOKEN="+params.Credentials.ClaudeOAuthToken)
	}
	for k, v := range params.Environment {
		env = append(env, k+"="+v)
	}

	mounts := []mount.Mount{
		{Type: mount.TypeBind, Source: configPath, Target: "/workspace/config.json", ReadOnly: true},
	}

	// Computed once, up front, so every object this launch creates -- the agent container, the
	// DinD sidecar and its two volumes -- carries the same bounds for the janitor to read back.
	deadlineAt := time.Now().Add(params.Bounds.Deadline())
	retention := params.Bounds.Retention(d.retention)
	labels := map[string]string{
		labelAppKey:    labelApp,
		labelRunID:     params.RunID.String(),
		labelExecID:    params.NodeExecutionID.String(),
		labelTmpDir:    tmpDir,
		labelDeadline:  strconv.FormatInt(deadlineAt.Unix(), 10),
		labelRetention: strconv.FormatInt(int64(retention/time.Second), 10),
	}
	if params.EnableDocker {
		labels[labelHasDind] = "true"
	}

	if params.EnableDocker {
		dindStarted = true
		dindContainerID, err := d.startDindSidecar(ctx, execIDShort, params.DindImage, labels)
		if err != nil {
			return executor.ExecutionResult{}, fmt.Errorf("start DinD sidecar: %w", err)
		}
		if err := d.waitForDindReady(ctx, dindContainerID); err != nil {
			return executor.ExecutionResult{}, fmt.Errorf("start DinD sidecar: %w", err)
		}
		env = append(env, "DOCKER_HOST=tcp://"+dindNamePrefix+execIDShort+":2375")
		// Docker fills an empty volume from the image's /workspace, owner included, which is what
		// lets the non-root agent write there. Pre-populating the volume or mounting it no-copy
		// leaves it root-owned, and every Docker-enabled agent fails to set up its workspace.
		mounts = append(mounts, mount.Mount{Type: mount.TypeVolume, Source: workspaceVolumePrefix + execIDShort, Target: workspaceMountPath})
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
		// params.Image (via pullAuth), and mounted as the container's DOCKER_CONFIG so the
		// agent's own docker commands against DinD authenticate to the same registry.
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

// Cleanup removes every Docker resource for executionID: the agent container, the DinD sidecar
// and its two volumes (addressed by execIDShort-derived name, not by reading them off the agent
// container), and the staging directory. It always attempts all four, whether or not the agent
// container is still there to find -- an interrupted launch or a prior partial cleanup must not
// strand the rest. Idempotent: a not-found error on any one is tolerated, and every removal is
// attempted independently, so a failure on one does not skip the others.
func (d *DockerExecutor) Cleanup(ctx context.Context, executionID uuid.UUID) error {
	execIDShort := executionID.String()[:8]

	var errs []error

	c, err := d.findContainer(ctx, executionID)
	if err != nil {
		errs = append(errs, fmt.Errorf("find container: %w", err))
	}

	if c != nil {
		if err := d.client.ContainerRemove(ctx, c.ID, container.RemoveOptions{Force: true, RemoveVolumes: true}); err != nil && !client.IsErrNotFound(err) {
			errs = append(errs, fmt.Errorf("remove container: %w", err))
		}
	}

	if err := d.cleanupDindResources(ctx, execIDShort); err != nil {
		errs = append(errs, fmt.Errorf("cleanup DinD resources: %w", err))
	}

	if c != nil && c.Labels[labelTmpDir] != "" {
		d.deleteTempDir(c.Labels[labelTmpDir])
	} else {
		// The container that would have named the staging directory is already gone, so fall
		// back to the one name this executor ever gives it: ck-config-<execIDShort>-<random>.
		d.deleteStagingDirsByExecID(execIDShort)
	}

	return errors.Join(errs...)
}

// Terminate stops executionID's container gracefully (SIGTERM, 30s grace before SIGKILL).
// Idempotent: an already-stopped or already-gone container is not an error.
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
// stderr interleaved), capped at 64KB.
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
// Docker host has no namespaces, so the runID is ignored.
func (d *DockerExecutor) ResolveJobSecretHash(ctx context.Context, _, executionID uuid.UUID) (string, error) {
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
// agent can reach it at ck-dind-<execIdShort>:2375. dindImageOverride, when non-empty, replaces
// d.dindImage for this one launch (a per-project custom image).
func (d *DockerExecutor) startDindSidecar(ctx context.Context, execIDShort string, dindImageOverride string, labels map[string]string) (string, error) {
	dindName := dindNamePrefix + execIDShort
	volName := dindVolumePrefix + execIDShort

	if _, err := d.client.VolumeCreate(ctx, volume.CreateOptions{Name: volName, Labels: labels}); err != nil {
		return "", fmt.Errorf("create DinD volume: %w", err)
	}
	if _, err := d.client.VolumeCreate(ctx, volume.CreateOptions{Name: workspaceVolumePrefix + execIDShort, Labels: labels}); err != nil {
		return "", fmt.Errorf("create workspace volume: %w", err)
	}

	img := dindImageOverride
	if img == "" {
		img = d.dindImage
	}

	// The Engine API's container-create does not auto-pull, so pull the sidecar image first
	// (best-effort, like the agent image) -- otherwise a fresh daemon 404s with "No such image".
	d.pullImageBestEffort(ctx, img, "")

	healthcheck := &container.HealthConfig{
		Test:        []string{"CMD", "docker", "version"},
		Interval:    1 * time.Second,
		Timeout:     3 * time.Second,
		Retries:     30,
		StartPeriod: 5 * time.Second,
	}

	sidecarLabels := make(map[string]string, len(labels)+1)
	for k, v := range labels {
		sidecarLabels[k] = v
	}
	sidecarLabels[labelDind] = "true"

	resp, err := d.client.ContainerCreate(ctx,
		&container.Config{
			Image:       img,
			Env:         []string{"DOCKER_TLS_CERTDIR="},
			Labels:      sidecarLabels,
			Healthcheck: healthcheck,
		},
		&container.HostConfig{
			Mounts: []mount.Mount{
				{Type: mount.TypeVolume, Source: volName, Target: "/var/lib/docker"},
				// dockerd resolves bind-mount sources on its own filesystem: without the agent's
				// workspace at the same path, every bind mount from the checkout comes up empty.
				{Type: mount.TypeVolume, Source: workspaceVolumePrefix + execIDShort, Target: workspaceMountPath},
			},
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

// cleanupDindResources force-removes the DinD sidecar container, its data volume and the
// workspace volume it shares with the agent for execIDShort. Idempotent: a not-found error on
// any is swallowed. The removals are attempted independently — a failure removing one must not
// skip the others, since each is the only handle a retry would have to that resource.
func (d *DockerExecutor) cleanupDindResources(ctx context.Context, execIDShort string) error {
	dindName := dindNamePrefix + execIDShort

	var errs []error
	if err := d.client.ContainerRemove(ctx, dindName, container.RemoveOptions{Force: true}); err != nil && !client.IsErrNotFound(err) {
		errs = append(errs, fmt.Errorf("remove DinD container: %w", err))
	}
	if err := d.client.VolumeRemove(ctx, dindVolumePrefix+execIDShort, true); err != nil && !client.IsErrNotFound(err) {
		errs = append(errs, fmt.Errorf("remove DinD volume: %w", err))
	}
	if err := d.client.VolumeRemove(ctx, workspaceVolumePrefix+execIDShort, true); err != nil && !client.IsErrNotFound(err) {
		errs = append(errs, fmt.Errorf("remove workspace volume: %w", err))
	}
	return errors.Join(errs...)
}

// createStagingDir creates the per-execution directory that holds config.json and any staged
// registry auth config. When d.stagingDir is set, it is created under that shared, host-mounted
// base so its absolute path is valid both here and on the host Docker daemon (required for
// sibling bind mounts in Docker-out-of-Docker mode). Blank falls back to the OS temp dir --
// correct for direct-on-host runs and tests.
func (d *DockerExecutor) createStagingDir(execIDShort string) (string, error) {
	base := d.stagingBase()
	if err := os.MkdirAll(base, 0o700); err != nil {
		return "", err
	}
	return os.MkdirTemp(base, "ck-config-"+execIDShort+"-")
}

// stagingBase is the effective directory under which this executor's own per-execution staging
// directories are created: cfg.StagingDir when set, otherwise the OS temp dir. The janitor
// compares a group's choruskube/tmp-dir label against this, not cfg.StagingDir directly, so a
// blank config still resolves to the same answer createStagingDir used to create it.
func (d *DockerExecutor) stagingBase() string {
	if d.stagingDir != "" {
		return d.stagingDir
	}
	return os.TempDir()
}

// deleteStagingDirsByExecID removes every staging directory this executor may have created for
// execIDShort under its own staging base -- used when no container label survives to name the
// directory exactly (the container is already gone).
func (d *DockerExecutor) deleteStagingDirsByExecID(execIDShort string) {
	matches, err := filepath.Glob(filepath.Join(d.stagingBase(), "ck-config-"+execIDShort+"-*"))
	if err != nil {
		return
	}
	for _, m := range matches {
		d.deleteTempDir(m)
	}
}

func (d *DockerExecutor) deleteTempDir(dir string) {
	if dir == "" {
		return
	}
	_ = os.RemoveAll(dir)
}

// pullImageBestEffort ensures imageName is available locally, pulling only when it is absent.
// Local-first keeps a self-hosted stack (locally built agent images) self-contained; the pull,
// when needed, is best-effort -- a failure is swallowed and Execute proceeds against what is present.
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
// registry host) -- the shape both this executor's image pull and the agent's own DOCKER_CONFIG use.
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

// --- Janitor ---

// runJanitor sweeps once immediately, then on janitorInterval, until Close closes
// d.janitorStop.
func (d *DockerExecutor) runJanitor() {
	defer close(d.janitorDone)

	d.sweep(context.Background(), time.Now())

	ticker := time.NewTicker(janitorInterval)
	defer ticker.Stop()
	for {
		select {
		case <-d.janitorStop:
			return
		case <-ticker.C:
			d.sweep(context.Background(), time.Now())
		}
	}
}

// janitorGroup is one execution's objects, as found on the daemon by label, regardless of
// whether this process launched them -- sweep rebuilds everything it needs from labels alone, so
// it stays correct across a Worker restart.
type janitorGroup struct {
	agent, sidecar *container.Summary
	volumes        []*volume.Volume

	tmpDir    string
	deadline  time.Time
	retention time.Duration
}

// noteLabels folds one object's labels into the group. Every object this executor creates for
// an execution carries identical tmp-dir/deadline/retention values, so last-write-wins is safe.
func (g *janitorGroup) noteLabels(labels map[string]string) {
	if tmpDir := labels[labelTmpDir]; tmpDir != "" {
		g.tmpDir = tmpDir
	}
	if raw := labels[labelDeadline]; raw != "" {
		if sec, err := strconv.ParseInt(raw, 10, 64); err == nil {
			g.deadline = time.Unix(sec, 0)
		}
	}
	if raw := labels[labelRetention]; raw != "" {
		if sec, err := strconv.ParseInt(raw, 10, 64); err == nil {
			g.retention = time.Duration(sec) * time.Second
		}
	}
}

// sweep finds every workload this executor's own labels describe on its daemon, groups them by
// execution id, and reclaims each group whose state and bounds call for it -- see the package
// doc. now is a parameter so tests control it; production calls always pass time.Now().
func (d *DockerExecutor) sweep(ctx context.Context, now time.Time) {
	f := filters.NewArgs()
	f.Add("label", labelAppKey+"="+labelApp)

	containers, err := d.client.ContainerList(ctx, container.ListOptions{All: true, Filters: f})
	if err != nil {
		slog.Error("janitor: list containers", "err", err)
		return
	}
	volumesResp, err := d.client.VolumeList(ctx, volume.ListOptions{Filters: f})
	if err != nil {
		slog.Error("janitor: list volumes", "err", err)
		return
	}

	groups := map[string]*janitorGroup{}
	groupFor := func(labels map[string]string) *janitorGroup {
		execID, ok := labels[labelExecID]
		// The list call above already filtered on the app label, but a group is only ever
		// built from an object that unambiguously carries both.
		if !ok || labels[labelAppKey] != labelApp {
			return nil
		}
		g := groups[execID]
		if g == nil {
			g = &janitorGroup{}
			groups[execID] = g
		}
		g.noteLabels(labels)
		return g
	}

	for i := range containers {
		c := &containers[i]
		g := groupFor(c.Labels)
		if g == nil {
			continue
		}
		if c.Labels[labelDind] == "true" {
			g.sidecar = c
		} else {
			g.agent = c
		}
	}
	for _, v := range volumesResp.Volumes {
		g := groupFor(v.Labels)
		if g == nil {
			continue
		}
		g.volumes = append(g.volumes, v)
	}

	base := d.stagingBase()
	for execID, g := range groups {
		if !isUnderDir(g.tmpDir, base) {
			continue
		}
		d.sweepGroup(ctx, execID, g, now)
	}
}

// isUnderDir reports whether path is base itself or a descendant of it. A group whose tmp-dir
// fails this check belongs to a different Worker sharing this daemon, and the janitor must never
// strand (or race) that Worker's own staging files.
func isUnderDir(path, base string) bool {
	if path == "" {
		return false
	}
	rel, err := filepath.Rel(base, path)
	if err != nil {
		return false
	}
	return rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)))
}

// sweepGroup applies the reclaim rules to one execution's group:
//   - the agent container has exited and is past its retention: Cleanup removes the lot;
//   - it has exited but is still within retention: only the sidecar, now useless, is removed;
//   - it is still running but past its deadline: it is stopped, then Cleanup removes the lot;
//   - there is no agent container and every remaining object is past its deadline: Cleanup
//     removes the lot -- a sidecar created ahead of its agent during a launch still in progress
//     is never taken, because that launch has not yet reached its own deadline;
//   - otherwise the group is left alone.
func (d *DockerExecutor) sweepGroup(ctx context.Context, execID string, g *janitorGroup, now time.Time) {
	execUUID, err := uuid.Parse(execID)
	if err != nil {
		slog.Error("janitor: exec id label is not a uuid", "execID", execID, "err", err)
		return
	}

	if g.agent == nil {
		if now.After(g.deadline) {
			if err := d.Cleanup(ctx, execUUID); err != nil {
				slog.Error("janitor: cleanup", "execID", execID, "err", err)
			}
		}
		return
	}

	if g.agent.State == container.StateRunning {
		if now.After(g.deadline) {
			timeout := 30
			if err := d.client.ContainerStop(ctx, g.agent.ID, container.StopOptions{Timeout: &timeout}); err != nil &&
				!client.IsErrNotFound(err) && !dockererrdefs.IsNotModified(err) {
				slog.Error("janitor: stop agent container past deadline", "execID", execID, "err", err)
				return
			}
			if err := d.Cleanup(ctx, execUUID); err != nil {
				slog.Error("janitor: cleanup", "execID", execID, "err", err)
			}
		}
		return
	}

	finishedAt, err := d.agentFinishedAt(ctx, g.agent.ID)
	if err != nil {
		slog.Error("janitor: inspect exited agent container", "execID", execID, "err", err)
		return
	}
	if !now.Before(finishedAt.Add(g.retention)) {
		if err := d.Cleanup(ctx, execUUID); err != nil {
			slog.Error("janitor: cleanup", "execID", execID, "err", err)
		}
		return
	}

	// Exited, but still within retention: the sidecar has no further use once its agent has
	// exited, but the agent container itself stays reachable (for its logs) until retention.
	if g.sidecar != nil {
		if err := d.client.ContainerRemove(ctx, g.sidecar.ID, container.RemoveOptions{Force: true, RemoveVolumes: true}); err != nil &&
			!client.IsErrNotFound(err) {
			slog.Error("janitor: remove sidecar of exited agent", "execID", execID, "err", err)
		}
	}
}

// agentFinishedAt inspects containerID (expected non-running) and returns when it finished.
func (d *DockerExecutor) agentFinishedAt(ctx context.Context, containerID string) (time.Time, error) {
	inspect, err := d.client.ContainerInspect(ctx, containerID)
	if err != nil {
		return time.Time{}, fmt.Errorf("inspect container: %w", err)
	}
	if inspect.State == nil || inspect.State.FinishedAt == "" {
		return time.Time{}, fmt.Errorf("container %s has no FinishedAt", containerID)
	}
	finishedAt, err := time.Parse(time.RFC3339Nano, inspect.State.FinishedAt)
	if err != nil {
		return time.Time{}, fmt.Errorf("parse FinishedAt %q: %w", inspect.State.FinishedAt, err)
	}
	return finishedAt, nil
}
