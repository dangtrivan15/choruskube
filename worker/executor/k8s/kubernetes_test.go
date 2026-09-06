package k8s

import (
	"context"
	"fmt"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/fake"

	coreexec "github.com/dangtrivan15/choruskube/worker/executor"
)

func TestKubernetesExecutor_Execute_CreatesJobAndSecrets(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	namespace := "test-org-ns"

	// Create the namespace
	_, err := fakeClient.CoreV1().Namespaces().Create(context.Background(),
		&corev1.Namespace{ObjectMeta: metav1.ObjectMeta{Name: namespace}}, metav1.CreateOptions{})
	require.NoError(t, err)

	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:           namespace,
		AgentServiceAccount: "choruskube-agent",
	})

	params := coreexec.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "ghcr.io/test/agent:latest",
		JobSecret:       "test-secret-123",
		ConfigJSON:      map[string]any{"run_id": uuid.New().String()},
		CallbackURL:     "http://worker:9090/api/v1/callback",
		Identity: coreexec.ExecutionIdentity{
			ServiceAccount: "choruskube-agent",
		},
	}

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)
	assert.NotEmpty(t, result.PodName)
	assert.NotEmpty(t, result.JobSecretHash)

	// Verify Job was created
	jobs, err := fakeClient.BatchV1().Jobs(namespace).List(context.Background(), metav1.ListOptions{})
	require.NoError(t, err)
	assert.Len(t, jobs.Items, 1)
	assert.Equal(t, "choruskube-agent", jobs.Items[0].Labels["app"])

	// Verify Secret was created with JOB_SECRET
	secrets, err := fakeClient.CoreV1().Secrets(namespace).List(context.Background(), metav1.ListOptions{})
	require.NoError(t, err)
	found := false
	for _, s := range secrets.Items {
		if _, ok := s.Data["JOB_SECRET"]; ok {
			found = true
			assert.Equal(t, []byte("test-secret-123"), s.Data["JOB_SECRET"])
		}
	}
	assert.True(t, found, "job-secret Secret should exist")

	// Verify ConfigMap was created
	cms, err := fakeClient.CoreV1().ConfigMaps(namespace).List(context.Background(), metav1.ListOptions{})
	require.NoError(t, err)
	assert.NotEmpty(t, cms.Items)
}

func TestKubernetesExecutor_ResolveJobSecretHash(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	namespace := "test-org-ns"
	execID := uuid.New()
	secret := "resolve-test-secret"

	// Pre-create the Secret the executor would have created
	_, err := fakeClient.CoreV1().Secrets(namespace).Create(context.Background(), &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("job-secret-%s", execID.String()[:8]),
			Namespace: namespace,
			Labels:    map[string]string{"choruskube/exec-id": execID.String()},
		},
		Data: map[string][]byte{"JOB_SECRET": []byte(secret)},
	}, metav1.CreateOptions{})
	require.NoError(t, err)

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: namespace})

	hash, err := exec.ResolveJobSecretHash(context.Background(), execID)
	require.NoError(t, err)
	assert.Equal(t, coreexec.HashSecret(secret), hash)
}

// --- Additional coverage beyond the brief's own two tests ---

// testNamespace is the launch namespace the executor is bound to across these tests; the
// executor is single-namespace, so it is a Config field, not a per-call parameter.
const testNamespace = "test-org-ns"

func newTestParams() coreexec.ExecutionParams {
	return coreexec.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "ghcr.io/test/agent:latest",
		JobSecret:       "test-secret-123",
		ConfigJSON:      map[string]any{"run_id": uuid.New().String()},
		CallbackURL:     "http://worker:9090/api/v1/callback",
		Identity: coreexec.ExecutionIdentity{
			ServiceAccount: "choruskube-agent",
		},
	}
}

func TestKubernetesExecutor_Execute_ClaudeOAuthToken_InjectedForAiExecution(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})

	params := newTestParams()
	params.Credentials.ClaudeOAuthToken = "oauth-token-value"

	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	secretName := fmt.Sprintf("job-secret-%s", params.NodeExecutionID.String()[:8])
	secret, err := fakeClient.CoreV1().Secrets(testNamespace).Get(context.Background(), secretName, metav1.GetOptions{})
	require.NoError(t, err)
	assert.Equal(t, []byte("oauth-token-value"), secret.Data["CLAUDE_CODE_OAUTH_TOKEN"])
}

// testAgentResources is the default sizing a real deployment supplies via Config; tests that
// enable ResourceQuota pass it so pinAgentContainerResources has values to apply.
func testAgentResources() coreexec.AgentResources {
	return coreexec.AgentResources{CPURequest: "200m", MemoryRequest: "1Gi", CPULimit: "1", MemoryLimit: "3Gi"}
}

// The executor injects whatever credential it is handed and decides nothing from node type:
// present -> in the Secret; empty (the caller/prepare omits it for e.g. script nodes) -> absent.
func TestKubernetesExecutor_Execute_ClaudeOAuthToken_InjectedWhenPresentOnly(t *testing.T) {
	cases := []struct {
		name  string
		token string
		want  bool
	}{
		{"token present", "oauth-token-value", true},
		{"token empty", "", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fakeClient := fake.NewSimpleClientset()
			exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})

			params := newTestParams()
			params.Credentials.ClaudeOAuthToken = tc.token
			// executor_type is deliberately "script" to prove the executor does NOT gate the token
			// on node type -- only on whether a value was supplied.
			params.ConfigJSON = map[string]any{"executor_type": "script"}

			_, err := exec.Execute(context.Background(), params)
			require.NoError(t, err)

			secretName := fmt.Sprintf("job-secret-%s", params.NodeExecutionID.String()[:8])
			secret, err := fakeClient.CoreV1().Secrets(testNamespace).Get(context.Background(), secretName, metav1.GetOptions{})
			require.NoError(t, err)
			_, ok := secret.Data["CLAUDE_CODE_OAUTH_TOKEN"]
			assert.Equal(t, tc.want, ok)
		})
	}
}

func TestKubernetesExecutor_Execute_RegistryCredentials_CreatesPullSecretAndMounts(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})

	params := newTestParams()
	params.Credentials.Registry = &coreexec.RegistryCredentials{
		Host:     "registry.example.com",
		Username: "user",
		Password: "pass",
	}

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	execIDShort := params.NodeExecutionID.String()[:8]
	regcredName := "regcred-" + execIDShort

	regcred, err := fakeClient.CoreV1().Secrets(testNamespace).Get(context.Background(), regcredName, metav1.GetOptions{})
	require.NoError(t, err)
	assert.Equal(t, corev1.SecretTypeDockerConfigJson, regcred.Type)
	assert.Contains(t, string(regcred.Data[corev1.DockerConfigJsonKey]), "registry.example.com")

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	require.Len(t, job.Spec.Template.Spec.ImagePullSecrets, 1)
	assert.Equal(t, regcredName, job.Spec.Template.Spec.ImagePullSecrets[0].Name)
	assert.True(t, hasEnv(job.Spec.Template.Spec.Containers[0].Env, "DOCKER_CONFIG", "/etc/regcred"))

	// Owner reference set so the regcred Secret is GC'd with the Job.
	require.Len(t, regcred.OwnerReferences, 1)
	assert.Equal(t, job.Name, regcred.OwnerReferences[0].Name)
}

func TestKubernetesExecutor_Execute_ResourceQuotaEnabled_PinsConfigDefault(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:            testNamespace,
		AgentServiceAccount:  "choruskube-agent",
		ResourceQuotaEnabled: true,
		AgentResources:       testAgentResources(),
	})

	params := newTestParams()
	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	res := job.Spec.Template.Spec.Containers[0].Resources
	assert.Equal(t, "1", res.Limits.Cpu().String())
	assert.Equal(t, "3Gi", res.Limits.Memory().String())
	assert.Equal(t, "200m", res.Requests.Cpu().String())
	assert.Equal(t, "1Gi", res.Requests.Memory().String())
}

// A per-execution override wins over the Config default, field by field -- the caller sizes a
// node, the executor applies it verbatim.
func TestKubernetesExecutor_Execute_PerExecutionResourceOverride(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:            testNamespace,
		AgentServiceAccount:  "choruskube-agent",
		ResourceQuotaEnabled: true,
		AgentResources:       testAgentResources(),
	})

	params := newTestParams()
	params.AgentResources = &coreexec.AgentResources{CPULimit: "2", MemoryLimit: "6Gi"} // requests fall back to default

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	res := job.Spec.Template.Spec.Containers[0].Resources
	assert.Equal(t, "2", res.Limits.Cpu().String())
	assert.Equal(t, "6Gi", res.Limits.Memory().String())
	assert.Equal(t, "200m", res.Requests.Cpu().String(), "empty override field falls back to the Config default")
}

// ResourceQuota is on but neither the Config default nor an override supplies values: the pod
// would be rejected at admission, so the executor fails loudly at build time instead.
func TestKubernetesExecutor_Execute_ResourceQuotaEnabled_UnconfiguredErrors(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent", ResourceQuotaEnabled: true})

	_, err := exec.Execute(context.Background(), newTestParams())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "not configured")
}

func TestKubernetesExecutor_Execute_ResourceQuotaDisabled_LeavesResourcesUnset(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent", ResourceQuotaEnabled: false})

	params := newTestParams()
	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	res := job.Spec.Template.Spec.Containers[0].Resources
	assert.Empty(t, res.Limits)
	assert.Empty(t, res.Requests)
}

func TestKubernetesExecutor_Execute_OwnerReferencesLinkConfigMapAndSecretToJob(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
	params := newTestParams()

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	execIDShort := params.NodeExecutionID.String()[:8]
	cm, err := fakeClient.CoreV1().ConfigMaps(testNamespace).Get(context.Background(), "config-"+execIDShort, metav1.GetOptions{})
	require.NoError(t, err)
	require.Len(t, cm.OwnerReferences, 1)
	assert.Equal(t, "Job", cm.OwnerReferences[0].Kind)
	assert.Equal(t, result.PodName, cm.OwnerReferences[0].Name)

	secret, err := fakeClient.CoreV1().Secrets(testNamespace).Get(context.Background(), "job-secret-"+execIDShort, metav1.GetOptions{})
	require.NoError(t, err)
	require.Len(t, secret.OwnerReferences, 1)
	assert.Equal(t, result.PodName, secret.OwnerReferences[0].Name)
}

// setupDindTemplate creates the wrapper ConfigMap addDindSupport reads its DinD PodTemplate
// from, shared by every test exercising EnableDocker.
func setupDindTemplate(t *testing.T, fakeClient kubernetes.Interface, templateNamespace, templateName string) {
	t.Helper()

	templateYAML := `
apiVersion: v1
kind: PodTemplate
metadata:
  name: choruskube-agent-pod-template
template:
  spec:
    runtimeClassName: sysbox-runc
    hostUsers: false
    initContainers:
      - name: dind
        image: docker:29-dind
        env:
          - name: DOCKER_TLS_CERTDIR
            value: ""
        resources:
          requests:
            cpu: 500m
            memory: 512Mi
          limits:
            cpu: "1"
            memory: 1Gi
    containers:
      - name: agent
        image: placeholder
        env:
          - name: DOCKER_HOST
            value: tcp://localhost:2375
        volumeMounts:
          - name: docker-certs
            mountPath: /certs
    volumes:
      - name: docker-certs
        emptyDir: {}
`
	_, err := fakeClient.CoreV1().ConfigMaps(templateNamespace).Create(context.Background(), &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: templateName, Namespace: templateNamespace},
		Data:       map[string]string{"template.yaml": templateYAML},
	}, metav1.CreateOptions{})
	require.NoError(t, err)
}

func TestKubernetesExecutor_Execute_DinD_SplicesTemplate(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	templateNamespace := "choruskube"
	templateName := "choruskube-agent-pod-template"
	setupDindTemplate(t, fakeClient, templateNamespace, templateName)

	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:            testNamespace,
		AgentServiceAccount:  "choruskube-agent",
		AgentPodTemplateName: templateName,
		TemplateNamespace:    templateNamespace,
		ResourceQuotaEnabled: true,
		AgentResources:       testAgentResources(),
	})

	params := newTestParams()
	params.EnableDocker = true
	// A deployment-specific host template -- this package must inject exactly these values
	// verbatim, never derive its own from the namespace.
	params.RegistryMirror = &coreexec.RegistryMirror{
		Mirror:       "mirror.internal.test:5000",
		BuildCache:   "mirror.internal.test:5001",
		DepProxyBase: "http://mirror.internal.test:8081",
	}

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)

	podSpec := job.Spec.Template.Spec
	require.NotNil(t, podSpec.RuntimeClassName)
	assert.Equal(t, "sysbox-runc", *podSpec.RuntimeClassName)
	require.NotNil(t, podSpec.HostUsers)
	assert.False(t, *podSpec.HostUsers)

	require.Len(t, podSpec.InitContainers, 1)
	dind := podSpec.InitContainers[0]
	assert.Equal(t, "dind", dind.Name)
	assert.True(t, hasEnv(dind.Env, "REGISTRY_MIRROR", "mirror.internal.test:5000"))
	assert.True(t, hasEnv(dind.Env, "INSECURE_REGISTRIES", "mirror.internal.test:5000 mirror.internal.test:5001"))

	agent := podSpec.Containers[0]
	assert.True(t, hasEnv(agent.Env, "DOCKER_HOST", "tcp://localhost:2375"))
	assert.True(t, hasEnv(agent.Env, "BUILD_CACHE_REGISTRY", "mirror.internal.test:5001"))
	assert.True(t, hasEnv(agent.Env, "DEP_PROXY_BASE", "http://mirror.internal.test:8081"))
	found := false
	for _, vm := range agent.VolumeMounts {
		if vm.Name == "docker-certs" {
			found = true
		}
	}
	assert.True(t, found, "docker-certs volume mount should be spliced onto the agent container")

	foundVol := false
	for _, v := range podSpec.Volumes {
		if v.Name == "docker-certs" {
			foundVol = true
		}
	}
	assert.True(t, foundVol, "docker-certs volume should be spliced onto the pod spec")
}

// TestKubernetesExecutor_Execute_DinD_NoRegistryMirror_InjectsNoMirrorEnv guards against this
// package deriving its own registry-mirror host from the target namespace instead of reading it
// from params: a version of addDindSupport that computes such a host internally, ignoring
// params.RegistryMirror, reddens this test by injecting the env below regardless of the nil.
func TestKubernetesExecutor_Execute_DinD_NoRegistryMirror_InjectsNoMirrorEnv(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	templateNamespace := "choruskube"
	templateName := "choruskube-agent-pod-template"
	setupDindTemplate(t, fakeClient, templateNamespace, templateName)

	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:            testNamespace,
		AgentServiceAccount:  "choruskube-agent",
		AgentPodTemplateName: templateName,
		TemplateNamespace:    templateNamespace,
	})

	params := newTestParams()
	params.EnableDocker = true
	params.RegistryMirror = nil // the OSS default seam resolves no mirror

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)

	podSpec := job.Spec.Template.Spec
	require.Len(t, podSpec.InitContainers, 1)
	dind := podSpec.InitContainers[0]
	agent := podSpec.Containers[0]

	for _, name := range []string{"REGISTRY_MIRROR", "REGISTRY_MIRRORS", "INSECURE_REGISTRIES"} {
		assert.False(t, hasEnvName(dind.Env, name), "dind should carry no %s when no registry mirror was resolved", name)
	}
	for _, name := range []string{
		"REGISTRY_MIRROR", "BUILD_CACHE_REGISTRY", "BUILD_CACHE_PUSH",
		"DEP_PROXY_BASE", "GOPROXY", "GOSUMDB", "npm_config_registry",
	} {
		assert.False(t, hasEnvName(agent.Env, name), "agent should carry no %s when no registry mirror was resolved", name)
	}
}

func TestKubernetesExecutor_Execute_DinD_MissingTemplate_ReturnsError(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:            testNamespace,
		AgentServiceAccount:  "choruskube-agent",
		AgentPodTemplateName: "does-not-exist",
		TemplateNamespace:    "choruskube",
	})

	params := newTestParams()
	params.EnableDocker = true

	_, err := exec.Execute(context.Background(), params)
	require.Error(t, err)

	// The ConfigMap/Secret created before the DinD lookup failed must not be left behind.
	execIDShort := params.NodeExecutionID.String()[:8]
	_, cmErr := fakeClient.CoreV1().ConfigMaps(testNamespace).Get(context.Background(), "config-"+execIDShort, metav1.GetOptions{})
	assert.Error(t, cmErr, "configmap should have been cleaned up after the failed Execute")
	_, secretErr := fakeClient.CoreV1().Secrets(testNamespace).Get(context.Background(), "job-secret-"+execIDShort, metav1.GetOptions{})
	assert.Error(t, secretErr, "secret should have been cleaned up after the failed Execute")
}

// TestKubernetesExecutor_WithNamespace_LaunchesInCopyNamespaceAndSharesTemplateCache pins the
// two properties WithNamespace must have: the derived copy launches into (and would tear down
// within) its own namespace while the base keeps using Config.Namespace, and the two share ONE
// pod-template cache -- across both DinD launches the wrapper ConfigMap is fetched exactly once.
func TestKubernetesExecutor_WithNamespace_LaunchesInCopyNamespaceAndSharesTemplateCache(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	nsA := "ns-a"
	nsB := "ns-b"
	templateNamespace := "choruskube"
	templateName := "choruskube-agent-pod-template"
	setupDindTemplate(t, fakeClient, templateNamespace, templateName)

	base := NewKubernetesExecutor(fakeClient, Config{
		Namespace:            nsA,
		AgentServiceAccount:  "choruskube-agent",
		AgentPodTemplateName: templateName,
		TemplateNamespace:    templateNamespace,
	})
	other := base.WithNamespace(nsB)

	// The base launches into Config.Namespace (nsA); the derived copy launches into nsB. DinD is
	// enabled on both so each Execute goes through loadPodTemplate.
	pA := newTestParams()
	pA.EnableDocker = true
	rA, err := base.Execute(context.Background(), pA)
	require.NoError(t, err)

	pB := newTestParams()
	pB.EnableDocker = true
	rB, err := other.Execute(context.Background(), pB)
	require.NoError(t, err)

	// Each Job landed in its own namespace, and neither leaked into the other's.
	_, err = fakeClient.BatchV1().Jobs(nsA).Get(context.Background(), rA.PodName, metav1.GetOptions{})
	require.NoError(t, err, "base must create its Job in Config.Namespace")
	_, err = fakeClient.BatchV1().Jobs(nsB).Get(context.Background(), rB.PodName, metav1.GetOptions{})
	require.NoError(t, err, "WithNamespace copy must create its Job in the copy namespace")

	jobsA, err := fakeClient.BatchV1().Jobs(nsA).List(context.Background(), metav1.ListOptions{})
	require.NoError(t, err)
	assert.Len(t, jobsA.Items, 1, "the copy's Job must not appear in the base namespace")
	jobsB, err := fakeClient.BatchV1().Jobs(nsB).List(context.Background(), metav1.ListOptions{})
	require.NoError(t, err)
	assert.Len(t, jobsB.Items, 1, "the base's Job must not appear in the copy namespace")

	// The per-execution Secret follows its executor's namespace too.
	_, err = fakeClient.CoreV1().Secrets(nsB).Get(context.Background(), "job-secret-"+pB.NodeExecutionID.String()[:8], metav1.GetOptions{})
	require.NoError(t, err, "WithNamespace copy must create its Secret in the copy namespace")

	// One shared pod-template cache: across BOTH DinD launches the wrapper ConfigMap in the
	// template namespace is GET exactly once -- the copy reused the base instance's cached
	// template rather than re-fetching through its own (shared) cache.
	templateGets := 0
	for _, a := range fakeClient.Actions() {
		if a.GetVerb() == "get" && a.GetResource().Resource == "configmaps" && a.GetNamespace() == templateNamespace {
			templateGets++
		}
	}
	assert.Equal(t, 1, templateGets, "pod-template cache must be shared: exactly one template ConfigMap GET across both instances")
}

func TestKubernetesExecutor_Cleanup_DeletesJobAndChildren(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
	params := newTestParams()

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	err = exec.Cleanup(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)

	_, err = fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	assert.Error(t, err)

	execIDShort := params.NodeExecutionID.String()[:8]
	_, err = fakeClient.CoreV1().ConfigMaps(testNamespace).Get(context.Background(), "config-"+execIDShort, metav1.GetOptions{})
	assert.Error(t, err)
	_, err = fakeClient.CoreV1().Secrets(testNamespace).Get(context.Background(), "job-secret-"+execIDShort, metav1.GetOptions{})
	assert.Error(t, err)
}

func TestKubernetesExecutor_Cleanup_JobAlreadyGone_StillDeletesSecret(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
	params := newTestParams()

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	// Simulate the Job already having been reaped (ttlSecondsAfterFinished, or a race with
	// K8s GC) while its owner-ref'd children are still present -- the fake clientset does not
	// run a garbage collector, so deleting the Job here does not cascade-delete them, exactly
	// like a real cluster mid-way through GC.
	require.NoError(t, fakeClient.BatchV1().Jobs(testNamespace).Delete(context.Background(), result.PodName, metav1.DeleteOptions{}))

	err = exec.Cleanup(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)

	execIDShort := params.NodeExecutionID.String()[:8]
	_, err = fakeClient.CoreV1().Secrets(testNamespace).Get(context.Background(), "job-secret-"+execIDShort, metav1.GetOptions{})
	assert.Error(t, err, "job-secret Secret should have been deleted even though the Job was already gone")
	_, err = fakeClient.CoreV1().ConfigMaps(testNamespace).Get(context.Background(), "config-"+execIDShort, metav1.GetOptions{})
	assert.Error(t, err, "ConfigMap should have been deleted even though the Job was already gone")
}

func TestKubernetesExecutor_Cleanup_JobAndSecretGone_ConfigMapSurvives_StillDeletesConfigMap(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
	params := newTestParams()

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	execIDShort := params.NodeExecutionID.String()[:8]
	require.NoError(t, fakeClient.BatchV1().Jobs(testNamespace).Delete(context.Background(), result.PodName, metav1.DeleteOptions{}))
	require.NoError(t, fakeClient.CoreV1().Secrets(testNamespace).Delete(context.Background(), "job-secret-"+execIDShort, metav1.DeleteOptions{}))

	err = exec.Cleanup(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)

	_, err = fakeClient.CoreV1().ConfigMaps(testNamespace).Get(context.Background(), "config-"+execIDShort, metav1.GetOptions{})
	assert.Error(t, err, "ConfigMap should have been deleted via the ConfigMap-label fallback")
}

func TestKubernetesExecutor_Cleanup_NoJobFound_NoOps(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace})

	err := exec.Cleanup(context.Background(), uuid.New())
	assert.NoError(t, err)
}

func TestKubernetesExecutor_Terminate_PatchesActiveDeadline(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
	params := newTestParams()

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	err = exec.Terminate(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	require.NotNil(t, job.Spec.ActiveDeadlineSeconds)
	assert.Equal(t, int64(1), *job.Spec.ActiveDeadlineSeconds)
}

func TestKubernetesExecutor_Terminate_NoJobFound_NoOps(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace})

	err := exec.Terminate(context.Background(), uuid.New())
	assert.NoError(t, err)
}

func TestKubernetesExecutor_GetLogs_NoJobFound(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace})

	logs, err := exec.GetLogs(context.Background(), uuid.New(), 100)
	require.NoError(t, err)
	assert.Equal(t, "(no pod found)", logs)
}

func TestKubernetesExecutor_GetLogs_NoPodForJob(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
	params := newTestParams()

	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	// The fake clientset does not run a Job controller, so no Pod is ever created for the Job.
	logs, err := exec.GetLogs(context.Background(), params.NodeExecutionID, 100)
	require.NoError(t, err)
	assert.Equal(t, "(no pod found)", logs)
}

func TestKubernetesExecutor_ResolveJobSecretHash_NotFound(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace})

	_, err := exec.ResolveJobSecretHash(context.Background(), uuid.New())
	assert.Error(t, err)
}

func TestKubernetesExecutor_HealthCheck(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{})

	err := exec.HealthCheck(context.Background())
	assert.NoError(t, err)
}

// TestKubernetesExecutor_Teardown_IsNamespacedGetByName_NoClusterWideList is the anti-vacuity
// guard for this task: every teardown/recovery call must reach its resources by name within the
// executor's configured namespace, never via a cluster-wide (all-namespaces) LIST. It inspects
// the fake clientset's recorded actions and fails if any carries an empty namespace, which is
// exactly what an all-namespaces list looks like on the wire.
func TestKubernetesExecutor_Teardown_IsNamespacedGetByName_NoClusterWideList(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
	params := newTestParams()

	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	// Only teardown/recovery calls are under test, so drop Execute's own create actions.
	fakeClient.ClearActions()

	_, err = exec.ResolveJobSecretHash(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	_, err = exec.GetLogs(context.Background(), params.NodeExecutionID, 100)
	require.NoError(t, err)
	require.NoError(t, exec.Cleanup(context.Background(), params.NodeExecutionID))

	sawSecretGet := false
	sawJobGet := false
	for _, a := range fakeClient.Actions() {
		// An empty namespace on any verb is the all-namespaces signature this task must eliminate.
		assert.NotEmpty(t, a.GetNamespace(),
			"action %s on %s must be namespace-scoped, not cluster-wide", a.GetVerb(), a.GetResource().Resource)
		assert.Equal(t, testNamespace, a.GetNamespace(),
			"action %s on %s targeted the wrong namespace", a.GetVerb(), a.GetResource().Resource)
		// A cluster-wide read of secrets/jobs/configmaps (list on all namespaces) is exactly the
		// read-all-secrets grant this task removes -- assert those resources are never listed.
		if a.GetVerb() == "list" {
			res := a.GetResource().Resource
			assert.NotContains(t, []string{"secrets", "jobs", "configmaps"}, res,
				"%s must be fetched by name, never listed", res)
		}
		if a.GetVerb() == "get" && a.GetResource().Resource == "secrets" {
			sawSecretGet = true
		}
		if a.GetVerb() == "get" && a.GetResource().Resource == "jobs" {
			sawJobGet = true
		}
	}
	assert.True(t, sawSecretGet, "ResolveJobSecretHash should GET the job-secret Secret by name")
	assert.True(t, sawJobGet, "GetLogs should GET the Job by name")
}

func TestBuildDockerConfigJSON(t *testing.T) {
	reg := &coreexec.RegistryCredentials{Host: "registry.example.com", Username: "user", Password: "pass"}
	data, err := buildDockerConfigJSON(reg)
	require.NoError(t, err)
	assert.Contains(t, string(data), "registry.example.com")
	assert.Contains(t, string(data), "dXNlcjpwYXNz") // base64("user:pass")
}

func hasEnv(env []corev1.EnvVar, name, value string) bool {
	for _, e := range env {
		if e.Name == name && e.Value == value {
			return true
		}
	}
	return false
}

func hasEnvName(env []corev1.EnvVar, name string) bool {
	for _, e := range env {
		if e.Name == name {
			return true
		}
	}
	return false
}
