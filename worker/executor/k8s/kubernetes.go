// Package k8s implements executor.Executor by launching agent workloads as Kubernetes Jobs.
// It is generic and tenant-agnostic: an instance is bound to one namespace (Config.Namespace) and
// launches with whatever service account and credentials its ExecutionParams carry, resolving no
// organization, namespace, or credential itself (WithNamespace derives a per-org copy).
// See docs/decisions/2026-09-05---01-worker-owns-tenant-agnostic-executor.md.
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
	// labelApp is a stable convention so operators can recognize this package's Job/Pods.
	labelAppKey = "app"
	labelApp    = "choruskube-agent"
	// exec-id/run-id are for operators to read, not lookup keys: teardown addresses resources by
	// deterministic name, needing only namespaced delete rights, never a cluster-scoped label list.
	labelExecID = "choruskube/exec-id"
	labelRunID  = "choruskube/run-id"

	configMapPrefix = "config-"
	jobSecretPrefix = "job-secret-"
	regcredPrefix   = "regcred-"
	jobPrefix       = "agent-"

	dindInitContainerName = "dind"
	agentContainerName    = "agent"

	// logLimitBytes keeps GetLogs' return under the callback payload's upstream size ceiling.
	logLimitBytes = 64 * 1024

	// Hard lower bound: must exceed the orchestrator's node heartbeat timeout (capped at 15m in
	// dag_executor.go), or a crashed agent's Pod is GC'd before post-timeout FetchPodLogs runs and
	// the crash logs vanish. Upper bound is node ephemeral disk: this also reaps successful Pods.
	ttlSecondsAfterFinished = int32(3600)

	podTemplateDataKey = "template.yaml"
)

// Config configures a KubernetesExecutor.
type Config struct {
	// Namespace scopes every client call this instance makes -- the Job, ConfigMap, and Secret(s)
	// it creates, and the resources its teardown addresses by name. WithNamespace copies with only
	// this field changed.
	Namespace string

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

	// AgentResources is the default agent-container CPU/memory, overridable per-execution via
	// ExecutionParams.AgentResources. All fields empty runs the agent as BestEffort. The dind
	// sidecar keeps the template's resources; this package never overrides them.
	AgentResources coreexec.AgentResources
}

// podTemplateStore is the DinD PodTemplate cache and its lock, shared by pointer across
// KubernetesExecutor copies so they coordinate one cache.
type podTemplateStore struct {
	mu    sync.Mutex
	cache map[string]*corev1.PodTemplate
}

// KubernetesExecutor implements executor.Executor by launching agent workloads as Kubernetes
// Jobs in its configured namespace (Config.Namespace).
type KubernetesExecutor struct {
	client kubernetes.Interface
	config Config

	// templates is shared by pointer across WithNamespace copies so per-org instances coordinate
	// one DinD PodTemplate cache.
	templates *podTemplateStore
}

// NewKubernetesExecutor returns a KubernetesExecutor that issues Kubernetes API calls through
// client.
func NewKubernetesExecutor(client kubernetes.Interface, cfg Config) *KubernetesExecutor {
	return &KubernetesExecutor{
		client:    client,
		config:    cfg,
		templates: &podTemplateStore{cache: map[string]*corev1.PodTemplate{}},
	}
}

var _ coreexec.Executor = (*KubernetesExecutor)(nil)

// WithNamespace returns a copy of k that launches into and tears down within ns. The copy shares
// k's client and pod-template cache (pointer); only config.Namespace differs. A multi-tenant
// deployment uses it to obtain a per-org executor.
func (k *KubernetesExecutor) WithNamespace(ns string) *KubernetesExecutor {
	cp := *k
	cp.config.Namespace = ns
	return &cp
}

// Execute launches params as a new Kubernetes Job in k.config.Namespace: a ConfigMap for
// config.json, a Secret for JOB_SECRET (and the Claude OAuth token when present), an optional
// registry pull Secret, then the Job, owner-ref'ing the ConfigMap/Secret(s) to it for GC.
func (k *KubernetesExecutor) Execute(ctx context.Context, params coreexec.ExecutionParams) (coreexec.ExecutionResult, error) {
	ns := k.config.Namespace
	execIDShort := params.NodeExecutionID.String()[:8]

	configBytes, err := json.MarshalIndent(params.ConfigJSON, "", "  ")
	if err != nil {
		return coreexec.ExecutionResult{}, fmt.Errorf("marshal config.json: %w", err)
	}

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

	// Create fails on a retry that finds its own prior partial output, and no caller-side handle
	// can remove it later, so each resource created below is unwound here on any later failure.
	success := false
	defer func() {
		if !success {
			_ = k.client.CoreV1().ConfigMaps(ns).Delete(context.WithoutCancel(ctx), cmName, metav1.DeleteOptions{})
		}
	}()

	// The caller resolves the credential; prepare omits the token for nodes that don't invoke
	// `claude`, and an empty value simply stays out of the Secret. This package reads no node type.
	secretData := map[string][]byte{"JOB_SECRET": []byte(params.JobSecret)}
	if params.Credentials.ClaudeOAuthToken != "" {
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

	// Ephemeral per-execution pull secret: kubelet reads it via imagePullSecrets and the same
	// payload mounts as the in-pod Docker client config (DOCKER_CONFIG). Owner-ref'd to the Job
	// so it's GC'd with it -- the org's registry credential row stays the only durable copy.
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

	job := k.buildJob(jobName, ns, serviceAccount, secretName, cmName, regcredName, params)

	if err := k.pinAgentContainerResources(job, params.AgentResources); err != nil {
		return coreexec.ExecutionResult{}, err
	}

	if params.EnableDocker {
		if err := k.addDindSupport(ctx, job, params.DindImage); err != nil {
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

	// Owner references so the ConfigMap and Secret(s) are GC'd with the Job. Best-effort: a failed
	// Update here doesn't fail the launch -- Cleanup's explicit per-name deletes still reach them.
	ownerRef := metav1.OwnerReference{
		APIVersion: "batch/v1",
		Kind:       "Job",
		Name:       createdJob.Name,
		UID:        createdJob.UID,
	}
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

// buildJob assembles the inline Job spec: the "agent" container, base volumes, and -- when a
// registry credential is present -- the regcred volume/mounts and DOCKER_CONFIG env. DinD and
// resource-pinning are spliced on afterward by addDindSupport/pinAgentContainerResources.
func (k *KubernetesExecutor) buildJob(
	jobName, ns, serviceAccount, secretName, cmName, regcredName string,
	params coreexec.ExecutionParams,
) *batchv1.Job {
	envFrom := []corev1.EnvFromSource{
		{SecretRef: &corev1.SecretEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: secretName}}},
	}

	// readOnlyRootFilesystem is intentionally false: the agent's tools (git, gh, claude, gradle)
	// write under $HOME on the image-baked rootfs, which RORFS would mount EROFS. sysbox-runc,
	// runAsNonRoot/runAsUser=1000, dropped capabilities, and restartPolicy=Never compensate.
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

	// The caller supplies every extra env var; this package injects none of its own beyond the
	// Secret's JOB_SECRET/token and DOCKER_CONFIG (below).
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
		// $DOCKER_CONFIG must be a writable directory: `docker buildx` creates its builder-state
		// subdir there at bootstrap and a read-only secret mount breaks it ("read-only file
		// system"). So a writable emptyDir holds /etc/regcred and only config.json is overlaid RO.
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

// Cleanup removes all Kubernetes resources for executionID in k.config.Namespace: the Job
// (foreground propagation, so Pods go before the Job object), its ConfigMap, and Secret(s). Each
// is addressed by deterministic name, not a cluster-wide label search, so this needs only
// namespaced delete rights, never a cluster-scoped grant. Idempotent: a missing resource is fine.
func (k *KubernetesExecutor) Cleanup(ctx context.Context, executionID uuid.UUID) error {
	namespace := k.config.Namespace
	execIDShort := executionID.String()[:8]

	propagation := metav1.DeletePropagationForeground
	if err := k.client.BatchV1().Jobs(namespace).Delete(ctx, jobPrefix+execIDShort, metav1.DeleteOptions{PropagationPolicy: &propagation}); err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("delete job: %w", err)
	}
	if err := k.client.CoreV1().ConfigMaps(namespace).Delete(ctx, configMapPrefix+execIDShort, metav1.DeleteOptions{}); err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("delete configmap: %w", err)
	}
	if err := k.client.CoreV1().Secrets(namespace).Delete(ctx, jobSecretPrefix+execIDShort, metav1.DeleteOptions{}); err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("delete secret: %w", err)
	}
	if err := k.client.CoreV1().Secrets(namespace).Delete(ctx, regcredPrefix+execIDShort, metav1.DeleteOptions{}); err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("delete regcred secret: %w", err)
	}
	return nil
}

// Terminate patches executionID's Job with activeDeadlineSeconds=1 so the Job controller stops it
// almost immediately, rather than deleting it -- Cleanup removes the Job and children separately.
// Idempotent: an already-gone Job is not an error.
func (k *KubernetesExecutor) Terminate(ctx context.Context, executionID uuid.UUID) error {
	namespace := k.config.Namespace
	jobName := jobPrefix + executionID.String()[:8]
	patch := []byte(`{"spec":{"activeDeadlineSeconds":1}}`)
	if _, err := k.client.BatchV1().Jobs(namespace).Patch(ctx, jobName, types.MergePatchType, patch, metav1.PatchOptions{}); err != nil {
		if apierrors.IsNotFound(err) {
			return nil
		}
		return fmt.Errorf("terminate job: %w", err)
	}
	return nil
}

// GetLogs returns up to the last tailLines of executionID's agent container, capped at 64KB. The
// Pod is found by the built-in job-name label -- a namespaced LIST, never cluster-wide. Errors
// (pod not scheduled or already reaped) come back as text: log retrieval is best-effort.
func (k *KubernetesExecutor) GetLogs(ctx context.Context, executionID uuid.UUID, tailLines int) (string, error) {
	namespace := k.config.Namespace
	jobName := jobPrefix + executionID.String()[:8]
	if _, err := k.client.BatchV1().Jobs(namespace).Get(ctx, jobName, metav1.GetOptions{}); err != nil {
		if apierrors.IsNotFound(err) {
			return "(no pod found)", nil
		}
		return "", fmt.Errorf("get job: %w", err)
	}

	pods, err := k.client.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{LabelSelector: "job-name=" + jobName})
	if err != nil {
		return "", fmt.Errorf("list pods: %w", err)
	}
	if len(pods.Items) == 0 {
		return "(no pod found)", nil
	}
	podName := pods.Items[0].Name

	tail := int64(tailLines)
	stream, err := k.client.CoreV1().Pods(namespace).GetLogs(podName, &corev1.PodLogOptions{
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

// ResolveJobSecretHash reads JOB_SECRET back from executionID's Secret and returns its SHA-256
// hash, recovering the hash cache after a Worker restart. runID is ignored: the namespace is
// fixed (a multi-tenant overlay uses runID to pick the namespace before delegating here).
func (k *KubernetesExecutor) ResolveJobSecretHash(ctx context.Context, _, executionID uuid.UUID) (string, error) {
	namespace := k.config.Namespace
	secretName := jobSecretPrefix + executionID.String()[:8]
	secret, err := k.client.CoreV1().Secrets(namespace).Get(ctx, secretName, metav1.GetOptions{})
	if err != nil {
		if apierrors.IsNotFound(err) {
			return "", fmt.Errorf("no job-secret found for execution %s", executionID)
		}
		return "", fmt.Errorf("get secret: %w", err)
	}
	raw, ok := secret.Data["JOB_SECRET"]
	if !ok {
		return "", fmt.Errorf("secret %s/%s missing JOB_SECRET", namespace, secretName)
	}
	return coreexec.HashSecret(string(raw)), nil
}

// HealthCheck verifies the Kubernetes API server is reachable via version discovery -- a call
// that needs no RBAC and no namespace, so the probe never depends on a cluster-scoped grant.
func (k *KubernetesExecutor) HealthCheck(ctx context.Context) error {
	_, err := k.client.Discovery().ServerVersion()
	return err
}

func execLabels(params coreexec.ExecutionParams) map[string]string {
	return map[string]string{
		labelAppKey: labelApp,
		labelExecID: params.NodeExecutionID.String(),
	}
}

// --- Resource sizing ---

// pinAgentContainerResources pins explicit CPU/memory on the "agent" container. Values come from
// the per-execution override when set, else the deployment default (Config.AgentResources); this
// package chooses no sizing and knows no node type. The namespace ships no LimitRange to supply
// defaults, so all four (cpu/memory x requests/limits) must resolve, or none (BestEffort).
func (k *KubernetesExecutor) pinAgentContainerResources(job *batchv1.Job, override *coreexec.AgentResources) error {
	def := k.config.AgentResources
	cpuRequest := resolveResource(override, func(r coreexec.AgentResources) string { return r.CPURequest }, def.CPURequest)
	memoryRequest := resolveResource(override, func(r coreexec.AgentResources) string { return r.MemoryRequest }, def.MemoryRequest)
	cpuLimit := resolveResource(override, func(r coreexec.AgentResources) string { return r.CPULimit }, def.CPULimit)
	memoryLimit := resolveResource(override, func(r coreexec.AgentResources) string { return r.MemoryLimit }, def.MemoryLimit)
	// Nothing configured: run BestEffort rather than failing.
	if cpuRequest == "" && memoryRequest == "" && cpuLimit == "" && memoryLimit == "" {
		return nil
	}
	// Partial config is a mistake, not an intent — fail loudly rather than ship a half-sized pod.
	for name, val := range map[string]string{
		"cpu request": cpuRequest, "memory request": memoryRequest, "cpu limit": cpuLimit, "memory limit": memoryLimit,
	} {
		if val == "" {
			return fmt.Errorf("agent %s not configured (set the deployment default or a per-execution override)", name)
		}
	}

	containers := job.Spec.Template.Spec.Containers
	for i := range containers {
		if containers[i].Name != agentContainerName {
			continue
		}
		containers[i].Resources = corev1.ResourceRequirements{
			Requests: corev1.ResourceList{
				corev1.ResourceCPU:    resource.MustParse(cpuRequest),
				corev1.ResourceMemory: resource.MustParse(memoryRequest),
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

// resolveResource returns the override's field when set and non-empty, else the deployment default.
func resolveResource(override *coreexec.AgentResources, field func(coreexec.AgentResources) string, def string) string {
	if override != nil {
		if v := field(*override); v != "" {
			return v
		}
	}
	return def
}

// --- DinD support ---

// addDindSupport splices the operator-supplied PodTemplate (loadPodTemplate) onto the inline Job:
// pod-level runtimeClassName/hostUsers, the "dind" init container (deep-copied so the cached
// template is never mutated), and the template agent's env/volumeMounts/volumes. dindImageOverride
// replaces the template's dind image when non-empty.
func (k *KubernetesExecutor) addDindSupport(ctx context.Context, job *batchv1.Job, dindImageOverride string) error {
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
	if dindImageOverride != "" {
		dind.Image = dindImageOverride
	}
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
	inlineAgent.VolumeMounts = append(inlineAgent.VolumeMounts, templateAgent.VolumeMounts...)

	podSpec.Volumes = append(podSpec.Volumes, templateSpec.Volumes...)
	return nil
}

// ValidatePodTemplate loads and parses the DinD PodTemplate once (also warming the cache),
// erroring if its wrapper ConfigMap is missing or malformed. Call it at startup so a missing-
// template misconfiguration fails fast rather than at the first DinD launch.
func (k *KubernetesExecutor) ValidatePodTemplate(ctx context.Context) error {
	_, err := k.loadPodTemplate(ctx)
	return err
}

// loadPodTemplate fetches the DinD PodTemplate from its wrapper ConfigMap
// (Config.AgentPodTemplateName in Config.TemplateNamespace, "template.yaml" key), caching it
// across repeat DinD launches.
func (k *KubernetesExecutor) loadPodTemplate(ctx context.Context) (*corev1.PodTemplate, error) {
	name := k.config.AgentPodTemplateName

	k.templates.mu.Lock()
	if cached, ok := k.templates.cache[name]; ok {
		k.templates.mu.Unlock()
		return cached, nil
	}
	k.templates.mu.Unlock()

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

	k.templates.mu.Lock()
	k.templates.cache[name] = &tmpl
	k.templates.mu.Unlock()
	return &tmpl, nil
}

// --- Misc helpers ---

// buildDockerConfigJSON renders reg as a Docker CLI config.json ("auths" keyed by registry host)
// -- the kubernetes.io/dockerconfigjson shape, so it serves both as the image-pull Secret payload
// and, via DOCKER_CONFIG, the in-pod docker client's credentials.
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
