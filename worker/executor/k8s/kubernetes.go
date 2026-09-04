// Package k8s implements executor.Executor by launching agent workloads as Kubernetes Jobs.
// It is generic and tenant-agnostic: it launches into whatever namespace, service account,
// and credentials its ExecutionParams carry, and resolves no organization, namespace, or
// credential itself. A multi-tenant deployment provisions those inputs elsewhere and passes
// them in; a single-tenant deployment has one fixed set of them.
package k8s

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"sync"

	"github.com/google/uuid"
	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes"
	"sigs.k8s.io/yaml"

	coreexec "github.com/dangtrivan15/choruskube/worker/executor"
)

const (
	// labelAppKey/labelApp mirror KubernetesWorkloadExecutor.java's "app"/LABEL_APP so
	// cluster operators reading Job/Pod labels recognize the same convention regardless of
	// which side (Java api-server, Go Worker) launched the workload.
	labelAppKey = "app"
	labelApp    = "choruskube-agent"
	// labelExecID/labelRunID mirror Java's "choruskube/exec-id"/"choruskube/run-id" keys.
	// This package's own resource lookups (which have no namespace to key off, per the
	// Executor interface) select by labelExecID, so it is load-bearing here too, not just
	// cosmetic parity with the Java side.
	labelExecID = "choruskube/exec-id"
	labelRunID  = "choruskube/run-id"

	configMapPrefix = "config-"
	jobSecretPrefix = "job-secret-"
	regcredPrefix   = "regcred-"
	jobPrefix       = "agent-"

	dindInitContainerName = "dind"
	agentContainerName    = "agent"

	// logLimitBytes caps GetLogs' return value, matching KubernetesWorkloadExecutor's
	// LOG_LIMIT_BYTES -- callback payloads have an upstream size ceiling this stays under.
	logLimitBytes = 64 * 1024

	// ttlSecondsAfterFinished is set by this package's own spec, not KubernetesWorkloadExecutor.java
	// (which uses 43200) -- see this package's kubernetes_test.go for the value under test.
	ttlSecondsAfterFinished = int32(300)

	podTemplateDataKey = "template.yaml"
)

// Config configures a KubernetesExecutor.
type Config struct {
	// AgentServiceAccount is the ServiceAccount agent pods run under when
	// params.Identity.ServiceAccount is empty.
	AgentServiceAccount string

	// AgentPodTemplateName names the wrapper ConfigMap (in TemplateNamespace) holding the
	// DinD PodTemplate spliced into a Job's pod when a launch sets EnableDocker. Required
	// only by launches that actually request Docker.
	AgentPodTemplateName string

	// TemplateNamespace is the namespace holding the AgentPodTemplateName wrapper
	// ConfigMap -- the api-server's own namespace, not an org namespace.
	TemplateNamespace string

	// ResourceQuotaEnabled toggles whether the agent (and, when DinD is enabled, the dind
	// init container) declares explicit cpu/memory requests/limits. Must match the org
	// namespace provisioner's own resource-quota flag: a namespace ResourceQuota that
	// tracks cpu/memory rejects any pod that omits resources, and pinning resources
	// without a quota is unnecessary overhead.
	ResourceQuotaEnabled bool
}

// KubernetesExecutor implements executor.Executor by launching agent workloads as Kubernetes
// Jobs in the org namespace named by each execution's ExecutionIdentity.
type KubernetesExecutor struct {
	client kubernetes.Interface
	config Config

	// podTemplateMu/podTemplateCache cache the DinD PodTemplate by name so a burst of
	// DinD-enabled launches does not re-fetch and re-parse the same wrapper ConfigMap on
	// every Execute call -- mirrors TemplateRegistry's cache on the Java side.
	podTemplateMu    sync.Mutex
	podTemplateCache map[string]*corev1.PodTemplate
}

// NewKubernetesExecutor returns a KubernetesExecutor that issues Kubernetes API calls through
// client.
func NewKubernetesExecutor(client kubernetes.Interface, cfg Config) *KubernetesExecutor {
	return &KubernetesExecutor{
		client:           client,
		config:           cfg,
		podTemplateCache: make(map[string]*corev1.PodTemplate),
	}
}

var _ coreexec.Executor = (*KubernetesExecutor)(nil)

// Execute launches params as a new Kubernetes Job in params.Identity.Namespace: it creates a
// ConfigMap for config.json, a Secret for JOB_SECRET (and, when present, the Claude OAuth
// token), an optional registry pull Secret, then the Job itself, and finally owner-refs the
// ConfigMap/Secret(s) to the Job so they are garbage-collected together.
func (k *KubernetesExecutor) Execute(ctx context.Context, params coreexec.ExecutionParams) (coreexec.ExecutionResult, error) {
	ns := params.Identity.Namespace
	execIDShort := params.NodeExecutionID.String()[:8]

	configBytes, err := json.MarshalIndent(params.ConfigJSON, "", "  ")
	if err != nil {
		return coreexec.ExecutionResult{}, fmt.Errorf("marshal config.json: %w", err)
	}

	testNodeExecution := isScriptExecution(params)
	hash := coreexec.HashSecret(params.JobSecret)

	cmName := configMapPrefix + execIDShort
	secretName := jobSecretPrefix + execIDShort
	regcredName := regcredPrefix + execIDShort
	jobName := jobPrefix + execIDShort

	labels := execLabels(params)

	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: cmName, Namespace: ns, Labels: labels},
		Data:       map[string]string{"config.json": string(configBytes)},
	}
	createdCM, err := k.client.CoreV1().ConfigMaps(ns).Create(ctx, cm, metav1.CreateOptions{})
	if err != nil {
		return coreexec.ExecutionResult{}, fmt.Errorf("create configmap: %w", err)
	}

	// Unlike Java's createOrReplace, Create fails outright on a retry that finds its own
	// prior partial output still present, so anything created before a later failure must be
	// unwound here -- there is no caller-side handle to find and remove it afterward.
	success := false
	defer func() {
		if !success {
			_ = k.client.CoreV1().ConfigMaps(ns).Delete(context.WithoutCancel(ctx), cmName, metav1.DeleteOptions{})
		}
	}()

	// Script nodes don't invoke `claude` and don't need the token -- keeping it out of their
	// Secret narrows the credential's blast radius.
	secretData := map[string][]byte{"JOB_SECRET": []byte(params.JobSecret)}
	if params.Credentials.ClaudeOAuthToken != "" && !testNodeExecution {
		secretData["CLAUDE_CODE_OAUTH_TOKEN"] = []byte(params.Credentials.ClaudeOAuthToken)
	}
	secret := &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns, Labels: labels},
		Data:       secretData,
	}
	createdSecret, err := k.client.CoreV1().Secrets(ns).Create(ctx, secret, metav1.CreateOptions{})
	if err != nil {
		return coreexec.ExecutionResult{}, fmt.Errorf("create secret: %w", err)
	}
	defer func() {
		if !success {
			_ = k.client.CoreV1().Secrets(ns).Delete(context.WithoutCancel(ctx), secretName, metav1.DeleteOptions{})
		}
	}()

	// Ephemeral per-execution image pull secret: kubelet reads it via imagePullSecrets, and
	// the same payload is mounted as the in-pod Docker client config (DOCKER_CONFIG). Owner-
	// ref'd to the Job below, so it is GC'd with it -- the org's registry credential row
	// stays the only durable copy.
	reg := params.Credentials.Registry
	var createdRegcred *corev1.Secret
	if reg != nil {
		dockerConfigJSON, err := buildDockerConfigJSON(reg)
		if err != nil {
			return coreexec.ExecutionResult{}, fmt.Errorf("marshal dockerconfigjson: %w", err)
		}
		regcred := &corev1.Secret{
			ObjectMeta: metav1.ObjectMeta{Name: regcredName, Namespace: ns, Labels: labels},
			Type:       corev1.SecretTypeDockerConfigJson,
			Data:       map[string][]byte{corev1.DockerConfigJsonKey: dockerConfigJSON},
		}
		createdRegcred, err = k.client.CoreV1().Secrets(ns).Create(ctx, regcred, metav1.CreateOptions{})
		if err != nil {
			return coreexec.ExecutionResult{}, fmt.Errorf("create regcred secret: %w", err)
		}
		defer func() {
			if !success {
				_ = k.client.CoreV1().Secrets(ns).Delete(context.WithoutCancel(ctx), regcredName, metav1.DeleteOptions{})
			}
		}()
	}

	serviceAccount := params.Identity.ServiceAccount
	if serviceAccount == "" {
		serviceAccount = k.config.AgentServiceAccount
	}

	job := k.buildJob(jobName, ns, serviceAccount, secretName, cmName, regcredName, params, testNodeExecution)

	if err := k.pinAgentContainerResources(job, testNodeExecution); err != nil {
		return coreexec.ExecutionResult{}, err
	}

	if params.EnableDocker {
		if err := k.addDindSupport(ctx, job, ns); err != nil {
			return coreexec.ExecutionResult{}, fmt.Errorf("add dind support: %w", err)
		}
	}

	createdJob, err := k.client.BatchV1().Jobs(ns).Create(ctx, job, metav1.CreateOptions{})
	if err != nil {
		if !apierrors.IsAlreadyExists(err) {
			return coreexec.ExecutionResult{}, fmt.Errorf("create job: %w", err)
		}
		// Already exists -- an activity retry after a transient failure that actually
		// landed the Job. Fetch it so owner-refs below still run.
		createdJob, err = k.client.BatchV1().Jobs(ns).Get(ctx, jobName, metav1.GetOptions{})
		if err != nil {
			return coreexec.ExecutionResult{}, fmt.Errorf("get existing job: %w", err)
		}
	}

	// Owner references so ConfigMap and Secret(s) are garbage-collected with the Job.
	// Best-effort, matching Java: a failure here does not fail the launch -- the resources
	// still work for the run, they simply survive Cleanup's Job-foreground-delete cascade
	// and rely on Cleanup's explicit per-name deletes instead.
	ownerRef := metav1.OwnerReference{
		APIVersion: "batch/v1",
		Kind:       "Job",
		Name:       createdJob.Name,
		UID:        createdJob.UID,
	}
	// Best-effort: Cleanup's explicit per-name deletes still reach these resources even if an
	// owner-ref Update here fails, so a failure is not propagated as an Execute error.
	createdCM.OwnerReferences = append(createdCM.OwnerReferences, ownerRef)
	_, _ = k.client.CoreV1().ConfigMaps(ns).Update(ctx, createdCM, metav1.UpdateOptions{})

	createdSecret.OwnerReferences = append(createdSecret.OwnerReferences, ownerRef)
	_, _ = k.client.CoreV1().Secrets(ns).Update(ctx, createdSecret, metav1.UpdateOptions{})

	if createdRegcred != nil {
		createdRegcred.OwnerReferences = append(createdRegcred.OwnerReferences, ownerRef)
		_, _ = k.client.CoreV1().Secrets(ns).Update(ctx, createdRegcred, metav1.UpdateOptions{})
	}

	success = true
	return coreexec.ExecutionResult{PodName: jobName, JobSecretHash: hash}, nil
}

// buildJob assembles the inline Job spec: the "agent" container, its base volumes, and --
// when a registry credential is present -- the regcred volume/mounts and DOCKER_CONFIG env.
// DinD and resource-pinning are spliced on afterward by addDindSupport/pinAgentContainerResources,
// mirroring the Java source's post-build mutation of the same Job object.
func (k *KubernetesExecutor) buildJob(
	jobName, ns, serviceAccount, secretName, cmName, regcredName string,
	params coreexec.ExecutionParams,
	testNodeExecution bool,
) *batchv1.Job {
	envFrom := []corev1.EnvFromSource{
		{SecretRef: &corev1.SecretEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: secretName}}},
	}

	// readOnlyRootFilesystem is intentionally false. The agent's tools (git, gh, claude CLI,
	// gradle) write runtime state under $HOME on the image-baked rootfs; RORFS would mount it
	// EROFS and every such write would fail. sysbox-runc, runAsNonRoot+runAsUser=1000, dropped
	// capabilities, and restartPolicy=Never are the compensating controls.
	agentContainer := corev1.Container{
		Name:            agentContainerName,
		Image:           params.Image,
		Command:         params.Command,
		ImagePullPolicy: corev1.PullAlways,
		SecurityContext: &corev1.SecurityContext{
			RunAsUser:                int64Ptr(1000),
			RunAsNonRoot:             boolPtr(true),
			AllowPrivilegeEscalation: boolPtr(false),
			ReadOnlyRootFilesystem:   boolPtr(false),
			Capabilities:             &corev1.Capabilities{Drop: []corev1.Capability{"ALL"}},
		},
		EnvFrom: envFrom,
		VolumeMounts: []corev1.VolumeMount{
			{Name: "workspace", MountPath: "/workspace"},
			{Name: "config", MountPath: "/workspace/config.json", SubPath: "config.json"},
			{Name: "tmp", MountPath: "/tmp"},
		},
	}

	if testNodeExecution {
		// Test-node ("script" executor_type) dogfood executions run a single subprocess
		// tree with no shard fan-out available to it -- only in-stack Playwright worker
		// parallelism -- so without this the suite silently falls back to serial.
		agentContainer.Env = append(agentContainer.Env, corev1.EnvVar{Name: "E2E_WORKERS", Value: "3"})
	}
	for envName, envValue := range params.Environment {
		agentContainer.Env = append(agentContainer.Env, corev1.EnvVar{Name: envName, Value: envValue})
	}

	volumes := []corev1.Volume{
		{Name: "workspace", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
		{
			Name: "config",
			VolumeSource: corev1.VolumeSource{
				ConfigMap: &corev1.ConfigMapVolumeSource{LocalObjectReference: corev1.LocalObjectReference{Name: cmName}},
			},
		},
		{Name: "tmp", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
	}

	var imagePullSecrets []corev1.LocalObjectReference
	if params.Credentials.Registry != nil {
		// $DOCKER_CONFIG must be a writable directory: `docker buildx` creates its
		// builder-state subdir at bootstrap, and a read-only secret mount there breaks it
		// with "read-only file system". So a writable emptyDir is mounted at /etc/regcred
		// and only config.json is overlaid (read-only) from the secret via subPath.
		volumes = append(volumes,
			corev1.Volume{Name: "docker-config", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
			corev1.Volume{
				Name: "regcred",
				VolumeSource: corev1.VolumeSource{
					Secret: &corev1.SecretVolumeSource{
						SecretName: regcredName,
						Items:      []corev1.KeyToPath{{Key: corev1.DockerConfigJsonKey, Path: "config.json"}},
					},
				},
			},
		)
		agentContainer.VolumeMounts = append(agentContainer.VolumeMounts,
			corev1.VolumeMount{Name: "docker-config", MountPath: "/etc/regcred"},
			corev1.VolumeMount{Name: "regcred", MountPath: "/etc/regcred/config.json", SubPath: "config.json", ReadOnly: true},
		)
		agentContainer.Env = append(agentContainer.Env, corev1.EnvVar{Name: "DOCKER_CONFIG", Value: "/etc/regcred"})
		imagePullSecrets = []corev1.LocalObjectReference{{Name: regcredName}}
	}

	return &batchv1.Job{
		ObjectMeta: metav1.ObjectMeta{
			Name:      jobName,
			Namespace: ns,
			Labels: map[string]string{
				labelAppKey: labelApp,
				labelRunID:  params.RunID.String(),
				labelExecID: params.NodeExecutionID.String(),
			},
		},
		Spec: batchv1.JobSpec{
			BackoffLimit:            int32Ptr(0),
			TTLSecondsAfterFinished: int32Ptr(ttlSecondsAfterFinished),
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{
					Labels: map[string]string{labelAppKey: labelApp, labelRunID: params.RunID.String()},
				},
				Spec: corev1.PodSpec{
					ServiceAccountName:           serviceAccount,
					ImagePullSecrets:             imagePullSecrets,
					AutomountServiceAccountToken: boolPtr(false),
					RestartPolicy:                corev1.RestartPolicyNever,
					Containers:                   []corev1.Container{agentContainer},
					Volumes:                      volumes,
				},
			},
		},
	}
}

// Cleanup removes all Kubernetes resources for executionID: the Job (foreground propagation,
// so K8s removes Pods before the Job object disappears), and its ConfigMap and Secret(s) by
// name. The namespace is resolved from the Job when it still exists; once it's gone
// (ttlSecondsAfterFinished, or a race with K8s GC) the Job's own label lookup can no longer
// find it, so Cleanup falls back to the exec-id label on the job-secret Secret, then the
// ConfigMap, to keep resolving a namespace instead of orphaning Secrets that hold JOB_SECRET,
// the Claude OAuth token, and the registry password. Idempotent: a missing Job or child
// resource is not an error, and an executionID with no exec-id-labeled resource left anywhere
// (already fully reaped, or Execute never got that far) makes this a no-op.
func (k *KubernetesExecutor) Cleanup(ctx context.Context, executionID uuid.UUID) error {
	job, err := k.findJobByExecID(ctx, executionID)
	if err != nil {
		return fmt.Errorf("find job: %w", err)
	}

	ns := ""
	switch {
	case job != nil:
		ns = job.Namespace
	default:
		secret, err := k.findJobSecretByExecID(ctx, executionID)
		if err != nil {
			return fmt.Errorf("find secret: %w", err)
		}
		if secret != nil {
			ns = secret.Namespace
		} else if cm, err := k.findConfigMapByExecID(ctx, executionID); err != nil {
			return fmt.Errorf("find configmap: %w", err)
		} else if cm != nil {
			ns = cm.Namespace
		}
	}
	if ns == "" {
		return nil
	}

	if job != nil {
		propagation := metav1.DeletePropagationForeground
		if err := k.client.BatchV1().Jobs(ns).Delete(ctx, job.Name, metav1.DeleteOptions{PropagationPolicy: &propagation}); err != nil && !apierrors.IsNotFound(err) {
			return fmt.Errorf("delete job: %w", err)
		}
	}

	execIDShort := executionID.String()[:8]
	if err := k.client.CoreV1().ConfigMaps(ns).Delete(ctx, configMapPrefix+execIDShort, metav1.DeleteOptions{}); err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("delete configmap: %w", err)
	}
	if err := k.client.CoreV1().Secrets(ns).Delete(ctx, jobSecretPrefix+execIDShort, metav1.DeleteOptions{}); err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("delete secret: %w", err)
	}
	if err := k.client.CoreV1().Secrets(ns).Delete(ctx, regcredPrefix+execIDShort, metav1.DeleteOptions{}); err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("delete regcred secret: %w", err)
	}
	return nil
}

// Terminate patches executionID's Job with a 1-second activeDeadlineSeconds so the Job
// controller stops it almost immediately, rather than deleting it outright -- Cleanup (called
// separately, later) is what removes the Job and its child resources. Idempotent: an
// already-gone Job is not an error.
func (k *KubernetesExecutor) Terminate(ctx context.Context, executionID uuid.UUID) error {
	job, err := k.findJobByExecID(ctx, executionID)
	if err != nil {
		return fmt.Errorf("find job: %w", err)
	}
	if job == nil {
		return nil
	}

	patch := []byte(`{"spec":{"activeDeadlineSeconds":1}}`)
	if _, err := k.client.BatchV1().Jobs(job.Namespace).Patch(ctx, job.Name, types.MergePatchType, patch, metav1.PatchOptions{}); err != nil {
		if apierrors.IsNotFound(err) {
			return nil
		}
		return fmt.Errorf("terminate job: %w", err)
	}
	return nil
}

// GetLogs returns up to the last tailLines lines of executionID's agent container output,
// capped at 64KB. Errors from a not-yet-scheduled or already-reaped pod are reported back as
// text rather than as an error -- log retrieval is best-effort diagnostic output, not a
// correctness-affecting call.
func (k *KubernetesExecutor) GetLogs(ctx context.Context, executionID uuid.UUID, tailLines int) (string, error) {
	job, err := k.findJobByExecID(ctx, executionID)
	if err != nil {
		return "", fmt.Errorf("find job: %w", err)
	}
	if job == nil {
		return "(no pod found)", nil
	}

	pods, err := k.client.CoreV1().Pods(job.Namespace).List(ctx, metav1.ListOptions{LabelSelector: "job-name=" + job.Name})
	if err != nil {
		return "", fmt.Errorf("list pods: %w", err)
	}
	if len(pods.Items) == 0 {
		return "(no pod found)", nil
	}
	podName := pods.Items[0].Name

	tail := int64(tailLines)
	stream, err := k.client.CoreV1().Pods(job.Namespace).GetLogs(podName, &corev1.PodLogOptions{
		Container: agentContainerName,
		TailLines: &tail,
	}).Stream(ctx)
	if err != nil {
		return fmt.Sprintf("(failed to read logs: %s)", err), nil
	}
	defer stream.Close()

	data, err := io.ReadAll(stream)
	if err != nil {
		return fmt.Sprintf("(failed to read logs: %s)", err), nil
	}
	if len(data) == 0 {
		return "(no logs available)", nil
	}
	if len(data) > logLimitBytes {
		data = data[len(data)-logLimitBytes:]
	}
	return string(data), nil
}

// ResolveJobSecretHash reads JOB_SECRET back from executionID's job-secret Secret (found by the
// choruskube/exec-id label, cluster-wide -- the interface passes no namespace) and returns its
// SHA-256 hash. Used to recover the hash cache after a Worker restart.
func (k *KubernetesExecutor) ResolveJobSecretHash(ctx context.Context, executionID uuid.UUID) (string, error) {
	secret, err := k.findJobSecretByExecID(ctx, executionID)
	if err != nil {
		return "", fmt.Errorf("find secret: %w", err)
	}
	if secret == nil {
		return "", fmt.Errorf("no job-secret found for execution %s", executionID)
	}
	raw, ok := secret.Data["JOB_SECRET"]
	if !ok {
		return "", fmt.Errorf("secret %s/%s missing JOB_SECRET", secret.Namespace, secret.Name)
	}
	return coreexec.HashSecret(string(raw)), nil
}

// HealthCheck verifies the Kubernetes API server is reachable, using the same cluster-scoped
// Job list call this package's own resource lookups rely on -- a healthy probe proves those
// lookups will work too.
func (k *KubernetesExecutor) HealthCheck(ctx context.Context) error {
	_, err := k.client.BatchV1().Jobs(metav1.NamespaceAll).List(ctx, metav1.ListOptions{LabelSelector: labelAppKey + "=" + labelApp})
	return err
}

// --- Lookup helpers ---
//
// The Executor interface's teardown methods (Cleanup, Terminate, GetLogs,
// ResolveJobSecretHash) take only an executionID, no namespace -- unlike the Java source, this
// package has no ownership-resolver/DB path back to the org namespace. Every resource this
// package creates therefore carries the choruskube/exec-id label, and teardown looks resources
// up by that label across all namespaces instead. Cleanup in particular chains these lookups
// (Job, then Secret, then ConfigMap) because the Job -- Cleanup's primary namespace source --
// is the first of the four to disappear once ttlSecondsAfterFinished elapses.

func (k *KubernetesExecutor) findJobByExecID(ctx context.Context, execID uuid.UUID) (*batchv1.Job, error) {
	list, err := k.client.BatchV1().Jobs(metav1.NamespaceAll).List(ctx, metav1.ListOptions{
		LabelSelector: labelExecID + "=" + execID.String(),
	})
	if err != nil {
		return nil, err
	}
	if len(list.Items) == 0 {
		return nil, nil
	}
	return &list.Items[0], nil
}

// findJobSecretByExecID disambiguates by name prefix because the job-secret and regcred
// Secrets for one execution carry the same choruskube/exec-id label.
func (k *KubernetesExecutor) findJobSecretByExecID(ctx context.Context, execID uuid.UUID) (*corev1.Secret, error) {
	list, err := k.client.CoreV1().Secrets(metav1.NamespaceAll).List(ctx, metav1.ListOptions{
		LabelSelector: labelExecID + "=" + execID.String(),
	})
	if err != nil {
		return nil, err
	}
	for i := range list.Items {
		if strings.HasPrefix(list.Items[i].Name, jobSecretPrefix) {
			return &list.Items[i], nil
		}
	}
	return nil, nil
}

// findConfigMapByExecID is Cleanup's second fallback (after findJobSecretByExecID) for
// resolving a namespace once the Job is already gone.
func (k *KubernetesExecutor) findConfigMapByExecID(ctx context.Context, execID uuid.UUID) (*corev1.ConfigMap, error) {
	list, err := k.client.CoreV1().ConfigMaps(metav1.NamespaceAll).List(ctx, metav1.ListOptions{
		LabelSelector: labelExecID + "=" + execID.String(),
	})
	if err != nil {
		return nil, err
	}
	if len(list.Items) == 0 {
		return nil, nil
	}
	return &list.Items[0], nil
}

func execLabels(params coreexec.ExecutionParams) map[string]string {
	return map[string]string{
		labelAppKey: labelApp,
		labelExecID: params.NodeExecutionID.String(),
	}
}

// --- Resource sizing ---

// pinAgentContainerResources pins explicit CPU/memory on the "agent" container. The namespace
// ships no LimitRange, so every container must set its own resources or ResourceQuota rejects
// admission. testNodeExecution raises the limit so the extra Playwright workers E2E_WORKERS
// enables have real headroom instead of contending on one pinned core.
func (k *KubernetesExecutor) pinAgentContainerResources(job *batchv1.Job, testNodeExecution bool) error {
	if !k.config.ResourceQuotaEnabled {
		return nil
	}
	cpuLimit := "1"
	memoryLimit := "3Gi"
	if testNodeExecution {
		cpuLimit = "2"
		memoryLimit = "6Gi"
	}

	containers := job.Spec.Template.Spec.Containers
	for i := range containers {
		if containers[i].Name != agentContainerName {
			continue
		}
		containers[i].Resources = corev1.ResourceRequirements{
			Requests: corev1.ResourceList{
				corev1.ResourceCPU:    resource.MustParse("200m"),
				corev1.ResourceMemory: resource.MustParse("1Gi"),
			},
			Limits: corev1.ResourceList{
				corev1.ResourceCPU:    resource.MustParse(cpuLimit),
				corev1.ResourceMemory: resource.MustParse(memoryLimit),
			},
		}
		return nil
	}
	return fmt.Errorf("agent container not found in job %s", job.Name)
}

// --- DinD support ---

// addDindSupport augments the inline-built Job with DinD bits sourced from the operator-
// supplied PodTemplate (see loadPodTemplate): pod-level runtimeClassName/hostUsers, the "dind"
// init container (deep-copied so the cached template is never mutated) with per-exec
// REGISTRY_MIRROR(S)/INSECURE_REGISTRIES env, the template "agent" container's env and
// volumeMounts appended to the inline agent, and the template's pod-level volumes appended to
// the pod. Every registry/cache endpoint is deterministic per-namespace K8s service DNS,
// derived from targetNamespace -- this executor has no DB-backed registry config to plumb
// through ExecutionParams.
func (k *KubernetesExecutor) addDindSupport(ctx context.Context, job *batchv1.Job, targetNamespace string) error {
	tmpl, err := k.loadPodTemplate(ctx)
	if err != nil {
		return err
	}
	templateSpec := tmpl.Template.Spec
	podSpec := &job.Spec.Template.Spec

	if templateSpec.RuntimeClassName != nil {
		podSpec.RuntimeClassName = templateSpec.RuntimeClassName
	}
	if templateSpec.HostUsers != nil {
		podSpec.HostUsers = templateSpec.HostUsers
	}

	var templateDind *corev1.Container
	for i := range templateSpec.InitContainers {
		if templateSpec.InitContainers[i].Name == dindInitContainerName {
			templateDind = &templateSpec.InitContainers[i]
			break
		}
	}
	if templateDind == nil {
		return fmt.Errorf("PodTemplate %q missing required init container %q", k.config.AgentPodTemplateName, dindInitContainerName)
	}
	dind := templateDind.DeepCopy()
	if !k.config.ResourceQuotaEnabled {
		// Kubelet schedules against node capacity rather than admission requirements when
		// no ResourceQuota is present.
		dind.Resources = corev1.ResourceRequirements{}
	}

	// REGISTRY_MIRROR is the wildcard-mirror dispatch path read by the dind script and the
	// agent entrypoint; REGISTRY_MIRRORS/INSECURE_REGISTRIES are the legacy fallback the dind
	// script uses when no mirror host is set.
	nexusHost := fmt.Sprintf("nexus.%s.svc.cluster.local:5000", targetNamespace)
	nexusCacheHost := fmt.Sprintf("nexus.%s.svc.cluster.local:5001", targetNamespace)
	dind.Env = append(dind.Env,
		corev1.EnvVar{Name: "REGISTRY_MIRROR", Value: nexusHost},
		corev1.EnvVar{Name: "REGISTRY_MIRRORS", Value: "http://" + nexusHost},
		corev1.EnvVar{Name: "INSECURE_REGISTRIES", Value: nexusHost + " " + nexusCacheHost},
	)
	podSpec.InitContainers = append(podSpec.InitContainers, *dind)

	var templateAgent *corev1.Container
	for i := range templateSpec.Containers {
		if templateSpec.Containers[i].Name == agentContainerName {
			templateAgent = &templateSpec.Containers[i]
			break
		}
	}
	if templateAgent == nil {
		return fmt.Errorf("PodTemplate %q missing required container %q", k.config.AgentPodTemplateName, agentContainerName)
	}

	if len(podSpec.Containers) == 0 || podSpec.Containers[0].Name != agentContainerName {
		return fmt.Errorf("expected first container to be %q", agentContainerName)
	}
	inlineAgent := &podSpec.Containers[0]
	inlineAgent.Env = append(inlineAgent.Env, templateAgent.Env...)

	// BUILD_CACHE_REGISTRY/BUILD_CACHE_PUSH self-warm the per-org build cache; DEP_PROXY_BASE
	// and friends route Gradle/Go/npm downloads through the per-org dependency proxy.
	nexusHTTPBase := fmt.Sprintf("http://nexus.%s.svc.cluster.local:8081", targetNamespace)
	inlineAgent.Env = append(inlineAgent.Env,
		corev1.EnvVar{Name: "BUILD_CACHE_REGISTRY", Value: nexusCacheHost},
		corev1.EnvVar{Name: "BUILD_CACHE_PUSH", Value: "1"},
		corev1.EnvVar{Name: "REGISTRY_MIRROR", Value: nexusHost},
		corev1.EnvVar{Name: "DEP_PROXY_BASE", Value: nexusHTTPBase},
		corev1.EnvVar{Name: "GOPROXY", Value: nexusHTTPBase + "/repository/go-proxy/,direct"},
		corev1.EnvVar{Name: "GOSUMDB", Value: "off"},
		corev1.EnvVar{Name: "npm_config_registry", Value: nexusHTTPBase + "/repository/npm-proxy/"},
	)
	inlineAgent.VolumeMounts = append(inlineAgent.VolumeMounts, templateAgent.VolumeMounts...)

	podSpec.Volumes = append(podSpec.Volumes, templateSpec.Volumes...)
	return nil
}

// loadPodTemplate fetches the DinD PodTemplate from its wrapper ConfigMap (the operator-
// supplied ConfigMap named Config.AgentPodTemplateName in Config.TemplateNamespace, whose
// "template.yaml" key holds the serialized PodTemplate), caching it so repeat DinD launches
// don't re-fetch and re-parse it.
func (k *KubernetesExecutor) loadPodTemplate(ctx context.Context) (*corev1.PodTemplate, error) {
	name := k.config.AgentPodTemplateName

	k.podTemplateMu.Lock()
	if cached, ok := k.podTemplateCache[name]; ok {
		k.podTemplateMu.Unlock()
		return cached, nil
	}
	k.podTemplateMu.Unlock()

	wrapper, err := k.client.CoreV1().ConfigMaps(k.config.TemplateNamespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		if apierrors.IsNotFound(err) {
			return nil, fmt.Errorf("required PodTemplate wrapper ConfigMap %q not found in namespace %q", name, k.config.TemplateNamespace)
		}
		return nil, fmt.Errorf("get pod template wrapper configmap: %w", err)
	}
	raw, ok := wrapper.Data[podTemplateDataKey]
	if !ok || strings.TrimSpace(raw) == "" {
		return nil, fmt.Errorf("wrapper ConfigMap %q missing required %q data key", name, podTemplateDataKey)
	}
	var tmpl corev1.PodTemplate
	if err := yaml.Unmarshal([]byte(raw), &tmpl); err != nil {
		return nil, fmt.Errorf("unmarshal PodTemplate from wrapper ConfigMap %q: %w", name, err)
	}

	k.podTemplateMu.Lock()
	k.podTemplateCache[name] = &tmpl
	k.podTemplateMu.Unlock()
	return &tmpl, nil
}

// --- Misc helpers ---

// isScriptExecution reports whether params is a Test-node ("script" executor_type) execution --
// mirrors KubernetesWorkloadExecutor.isScriptExecution and the Docker executor's identical
// helper.
func isScriptExecution(params coreexec.ExecutionParams) bool {
	if params.ConfigJSON == nil {
		return false
	}
	raw, ok := params.ConfigJSON["executor_type"]
	if !ok {
		return false
	}
	return strings.EqualFold(fmt.Sprintf("%v", raw), "script")
}

// buildDockerConfigJSON renders reg as a Docker CLI config.json ("auths" map keyed by registry
// host) -- the same document shape a kubernetes.io/dockerconfigjson Secret carries, so it
// serves both as the image-pull Secret payload and, mounted via DOCKER_CONFIG, the in-pod
// docker client's own credentials.
func buildDockerConfigJSON(reg *coreexec.RegistryCredentials) ([]byte, error) {
	doc := map[string]any{
		"auths": map[string]any{
			reg.Host: map[string]string{
				"username": reg.Username,
				"password": reg.Password,
				"auth":     base64.StdEncoding.EncodeToString([]byte(reg.Username + ":" + reg.Password)),
			},
		},
	}
	return json.Marshal(doc)
}

func boolPtr(b bool) *bool    { return &b }
func int32Ptr(i int32) *int32 { return &i }
func int64Ptr(i int64) *int64 { return &i }
