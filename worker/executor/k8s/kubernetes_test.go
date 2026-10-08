package k8s

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/fake"
	"k8s.io/client-go/kubernetes/scheme"
	typedcorev1 "k8s.io/client-go/kubernetes/typed/core/v1"
	"k8s.io/client-go/rest"
	fakerest "k8s.io/client-go/rest/fake"

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

	hash, err := exec.ResolveJobSecretHash(context.Background(), uuid.Nil, execID)
	require.NoError(t, err)
	assert.Equal(t, coreexec.HashSecret(secret), hash)
}

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

// testAgentResources is the default sizing a real deployment supplies via Config.
func testAgentResources() coreexec.AgentResources {
	return coreexec.AgentResources{CPURequest: "200m", MemoryRequest: "1Gi", CPULimit: "1"}
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

// The finished-Job GC TTL must outlive the orchestrator's node heartbeat timeout (capped at 15m
// = 900s in dag_executor.go), or a crashed agent's Pod is reaped before post-timeout FetchPodLogs
// runs and the failure reaches operators as a bare heartbeat timeout. Guards against lowering it.
func TestKubernetesExecutor_Execute_JobTTLOutlivesHeartbeatTimeout(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:           testNamespace,
		AgentServiceAccount: "choruskube-agent",
	})

	result, err := exec.Execute(context.Background(), newTestParams())
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	require.NotNil(t, job.Spec.TTLSecondsAfterFinished)
	assert.Greater(t, *job.Spec.TTLSecondsAfterFinished, int32(900),
		"Job TTL must exceed the 15m heartbeat timeout so a crashed Pod survives to FetchPodLogs")
}

func TestKubernetesExecutor_Execute_PinsConfiguredAgentResources(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:           testNamespace,
		AgentServiceAccount: "choruskube-agent",
		AgentResources:      testAgentResources(),
	})

	params := newTestParams()
	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	res := job.Spec.Template.Spec.Containers[0].Resources
	assert.Equal(t, "1", res.Limits.Cpu().String())
	assert.Equal(t, "1434Mi", res.Limits.Memory().String(), "140% of the 1Gi default request")
	assert.Equal(t, "200m", res.Requests.Cpu().String())
	assert.Equal(t, "1Gi", res.Requests.Memory().String())
}

// A per-execution override wins over the Config default, field by field -- the caller sizes a
// node, the executor applies it verbatim.
func TestKubernetesExecutor_Execute_PerExecutionResourceOverride(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:           testNamespace,
		AgentServiceAccount: "choruskube-agent",
		AgentResources:      testAgentResources(),
	})

	params := newTestParams()
	params.AgentResources = &coreexec.AgentResources{CPULimit: "2"} // requests fall back to default

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	res := job.Spec.Template.Spec.Containers[0].Resources
	assert.Equal(t, "2", res.Limits.Cpu().String())
	assert.Equal(t, "200m", res.Requests.Cpu().String(), "empty override field falls back to the Config default")
	assert.Equal(t, "1434Mi", res.Limits.Memory().String(), "derived from the default request it fell back to")
}

// An exempt per-project memory request may exceed the deployment default, and its limit is 140% of
// itself either way: there is no deployment limit to fall back to or to stay above.
func TestKubernetesExecutor_Execute_ExemptMemoryRequest_DerivesLimit(t *testing.T) {
	for _, tc := range []struct{ request, wantLimit string }{
		{"512Mi", "717Mi"}, // below the 1Gi default; rounded up to a whole Mi
		{"1792Mi", "2509Mi"},
		{"8Gi", "11469Mi"}, // above the default
		{"5Gi", "7Gi"},     // an exact result is not rounded up a further Mi
		{"100Pi", "140Pi"}, // scaling a huge request does not overflow into a tiny limit
	} {
		t.Run(tc.request, func(t *testing.T) {
			fakeClient := fake.NewSimpleClientset()
			exec := NewKubernetesExecutor(fakeClient, Config{
				Namespace:           testNamespace,
				AgentServiceAccount: "choruskube-agent",
				AgentResources:      testAgentResources(),
			})

			params := newTestParams()
			params.AgentResources = &coreexec.AgentResources{MemoryRequest: tc.request}
			params.MemoryCeilingExempt = true

			result, err := exec.Execute(context.Background(), params)
			require.NoError(t, err)

			job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
			require.NoError(t, err)
			res := job.Spec.Template.Spec.Containers[0].Resources
			assert.Equal(t, tc.request, res.Requests.Memory().String())
			assert.Equal(t, tc.wantLimit, res.Limits.Memory().String())
		})
	}
}

// A capped (non-exempt) memory request may lower the deployment default but not raise it, and one
// above it fails before any object is created, naming the cause: on a shared data plane, the
// default is the most one project may reserve. With no default, a capped request is refused.
func TestKubernetesExecutor_Execute_CappedMemoryRequest_CeilingIsDeploymentDefault(t *testing.T) {
	for _, tc := range []struct {
		name, request, wantLimit, wantErr string
		defaults                          coreexec.AgentResources
	}{
		{"below the default", "768Mi", "1076Mi", "", testAgentResources()},
		{"at the default", "1Gi", "1434Mi", "", testAgentResources()},
		{"above the default", "1025Mi", "", "exceeds the deployment default 1Gi", testAgentResources()},
		{"no default request", "512Mi", "", "sets no default request", coreexec.AgentResources{CPURequest: "200m", CPULimit: "1"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			fakeClient := fake.NewSimpleClientset()
			exec := NewKubernetesExecutor(fakeClient, Config{
				Namespace:           testNamespace,
				AgentServiceAccount: "choruskube-agent",
				AgentResources:      tc.defaults,
			})

			params := newTestParams()
			params.AgentResources = &coreexec.AgentResources{MemoryRequest: tc.request}
			result, err := exec.Execute(context.Background(), params)
			if tc.wantErr != "" {
				require.Error(t, err)
				assert.Contains(t, err.Error(), tc.wantErr)
				jobs, listErr := fakeClient.BatchV1().Jobs(testNamespace).List(context.Background(), metav1.ListOptions{})
				require.NoError(t, listErr)
				assert.Empty(t, jobs.Items, "a refused request must not create a Job")
				return
			}
			require.NoError(t, err)
			job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
			require.NoError(t, err)
			res := job.Spec.Template.Spec.Containers[0].Resources
			assert.Equal(t, tc.request, res.Requests.Memory().String())
			assert.Equal(t, tc.wantLimit, res.Limits.Memory().String())
		})
	}
}

// An unparseable value must fail the launch with an error, never panic the Worker process.
func TestKubernetesExecutor_Execute_InvalidResourceQuantity_ErrorsWithoutPanic(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:           testNamespace,
		AgentServiceAccount: "choruskube-agent",
		AgentResources:      testAgentResources(),
	})

	params := newTestParams()
	params.AgentResources = &coreexec.AgentResources{MemoryRequest: "4GB"}

	require.NotPanics(t, func() {
		_, err := exec.Execute(context.Background(), params)
		require.Error(t, err)
	})
}

// Partial agent resources (some fields set, others empty) is a configuration mistake, not an
// intent to run BestEffort — the executor fails loudly at build time rather than ship a half-sized pod.
func TestKubernetesExecutor_Execute_PartialAgentResources_Errors(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:           testNamespace,
		AgentServiceAccount: "choruskube-agent",
		AgentResources:      coreexec.AgentResources{CPULimit: "1"}, // requests left empty
	})

	_, err := exec.Execute(context.Background(), newTestParams())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "not configured")
}

// No agent resources configured -> the agent container runs BestEffort (empty requests/limits)
// rather than failing.
func TestKubernetesExecutor_Execute_NoAgentResources_LeavesResourcesUnset(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})

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
	createTemplateWrapper(t, fakeClient, templateNamespace, templateName, templateYAML)
}

// createTemplateWrapper stores templateYAML under the wrapper ConfigMap's "template.yaml" key.
func createTemplateWrapper(t *testing.T, fakeClient kubernetes.Interface, templateNamespace, templateName, templateYAML string) {
	t.Helper()
	_, err := fakeClient.CoreV1().ConfigMaps(templateNamespace).Create(context.Background(), &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: templateName, Namespace: templateNamespace},
		Data:       map[string]string{"template.yaml": templateYAML},
	}, metav1.CreateOptions{})
	require.NoError(t, err)
}

// The Worker is the sole consumer of the agent PodTemplate, so a missing/malformed template is
// its misconfiguration to catch — at startup, via ValidatePodTemplate, not at the first launch.
func TestKubernetesExecutor_ValidatePodTemplate(t *testing.T) {
	templateNamespace := "choruskube"
	templateName := "choruskube-agent-pod-template"

	t.Run("nil when the template exists", func(t *testing.T) {
		fakeClient := fake.NewSimpleClientset()
		setupDindTemplate(t, fakeClient, templateNamespace, templateName)
		exec := NewKubernetesExecutor(fakeClient, Config{
			Namespace:            testNamespace,
			AgentPodTemplateName: templateName,
			TemplateNamespace:    templateNamespace,
		})
		require.NoError(t, exec.ValidatePodTemplate(context.Background()))
	})

	t.Run("errors when the dind container declares a memory limit", func(t *testing.T) {
		fakeClient := fake.NewSimpleClientset()
		setupDindTemplate(t, fakeClient, templateNamespace, templateName)
		cm, err := fakeClient.CoreV1().ConfigMaps(templateNamespace).Get(context.Background(), templateName, metav1.GetOptions{})
		require.NoError(t, err)
		cm.Data["template.yaml"] = strings.Replace(cm.Data["template.yaml"], `            cpu: "1"`+"\n", `            cpu: "1"`+"\n            memory: 1Gi\n", 1)
		_, err = fakeClient.CoreV1().ConfigMaps(templateNamespace).Update(context.Background(), cm, metav1.UpdateOptions{})
		require.NoError(t, err)
		exec := NewKubernetesExecutor(fakeClient, Config{
			Namespace:            testNamespace,
			AgentPodTemplateName: templateName,
			TemplateNamespace:    templateNamespace,
		})
		err = exec.ValidatePodTemplate(context.Background())
		require.Error(t, err)
		assert.Contains(t, err.Error(), "memory limit")
	})

	t.Run("errors when the template is absent", func(t *testing.T) {
		fakeClient := fake.NewSimpleClientset()
		exec := NewKubernetesExecutor(fakeClient, Config{
			Namespace:            testNamespace,
			AgentPodTemplateName: templateName,
			TemplateNamespace:    templateNamespace,
		})
		err := exec.ValidatePodTemplate(context.Background())
		require.Error(t, err)
		assert.Contains(t, err.Error(), "not found")
	})
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
		AgentResources:       testAgentResources(),
	})

	params := newTestParams()
	params.EnableDocker = true

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
	// The dind sidecar's resources come from the operator-supplied template, except its memory
	// limit, which follows its memory request.
	assert.Equal(t, "1", dind.Resources.Limits.Cpu().String())
	assert.Equal(t, "717Mi", dind.Resources.Limits.Memory().String())
	assert.Equal(t, "500m", dind.Resources.Requests.Cpu().String())
	assert.Equal(t, "512Mi", dind.Resources.Requests.Memory().String())

	agent := podSpec.Containers[0]
	assert.True(t, hasEnv(agent.Env, "DOCKER_HOST", "tcp://localhost:2375"))
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

// TestKubernetesExecutor_Execute_DinD_ImageOverride_SetsDindImage guards the per-project custom
// dind image: a non-empty params.DindImage must replace the template's init-container image
// rather than being ignored in favor of what the operator-supplied PodTemplate carries.
func TestKubernetesExecutor_Execute_DinD_ImageOverride_SetsDindImage(t *testing.T) {
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
	params.DindImage = "registry.example/custom-dind:v2"

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)

	require.Len(t, job.Spec.Template.Spec.InitContainers, 1)
	assert.Equal(t, "registry.example/custom-dind:v2", job.Spec.Template.Spec.InitContainers[0].Image)
}

// A per-project dind memory request replaces the sidecar's memory request and derives its memory
// limit; CPU stays the template's, and the cached template is untouched for the next launch.
func TestKubernetesExecutor_Execute_DinD_MemoryRequestOverride_LeavesTemplateIntact(t *testing.T) {
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

	sized := newTestParams()
	sized.EnableDocker = true
	sized.DindMemoryRequest = "768Mi"
	sized.MemoryCeilingExempt = true
	result, err := exec.Execute(context.Background(), sized)
	require.NoError(t, err)
	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	require.Len(t, job.Spec.Template.Spec.InitContainers, 1)
	dind := job.Spec.Template.Spec.InitContainers[0].Resources
	assert.Equal(t, "768Mi", dind.Requests.Memory().String())
	assert.Equal(t, "1076Mi", dind.Limits.Memory().String(), "140% of 768Mi")
	assert.Equal(t, "500m", dind.Requests.Cpu().String())

	unsized := newTestParams()
	unsized.EnableDocker = true
	result, err = exec.Execute(context.Background(), unsized)
	require.NoError(t, err)
	job, err = fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	unsizedDind := job.Spec.Template.Spec.InitContainers[0].Resources
	assert.Equal(t, "512Mi", unsizedDind.Requests.Memory().String(),
		"an earlier launch's override must not leak into the cached template")
	assert.Equal(t, "717Mi", unsizedDind.Limits.Memory().String(),
		"an earlier launch's derived limit must not leak into the cached template")
}

// The dind sidecar follows the agent's rule against the template's 512Mi request: its limit is 140%
// of whichever request applies; a capped request may not exceed the template's, and with no
// template request a capped one is refused. With no request at all the sidecar stays unlimited.
func TestKubernetesExecutor_Execute_DinD_MemoryRequestLimit(t *testing.T) {
	for _, tc := range []struct {
		name, request, wantRequest, wantLimit, wantErr string
		templateRequest, exempt                        bool
	}{
		{"exempt, below the template request", "256Mi", "256Mi", "359Mi", "", true, true},
		{"exempt, above the template request", "2Gi", "2Gi", "2868Mi", "", true, true},
		{"exempt, no template request", "2Gi", "2Gi", "2868Mi", "", false, true},
		{"capped, below the template request", "384Mi", "384Mi", "538Mi", "", true, false},
		{"capped, above the template request", "513Mi", "", "", "exceeds the deployment default 512Mi", true, false},
		{"capped, no template request", "256Mi", "", "", "sets no default request", false, false},
		{"no request anywhere", "", "", "", "", false, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			fakeClient := fake.NewSimpleClientset()
			templateNamespace := "choruskube"
			templateName := "choruskube-agent-pod-template"
			setupDindTemplate(t, fakeClient, templateNamespace, templateName)
			if !tc.templateRequest {
				cm, err := fakeClient.CoreV1().ConfigMaps(templateNamespace).Get(context.Background(), templateName, metav1.GetOptions{})
				require.NoError(t, err)
				cm.Data["template.yaml"] = strings.Replace(cm.Data["template.yaml"], "            memory: 512Mi\n", "", 1)
				_, err = fakeClient.CoreV1().ConfigMaps(templateNamespace).Update(context.Background(), cm, metav1.UpdateOptions{})
				require.NoError(t, err)
			}

			exec := NewKubernetesExecutor(fakeClient, Config{
				Namespace:            testNamespace,
				AgentServiceAccount:  "choruskube-agent",
				AgentPodTemplateName: templateName,
				TemplateNamespace:    templateNamespace,
			})

			params := newTestParams()
			params.EnableDocker = true
			params.DindMemoryRequest = tc.request
			params.MemoryCeilingExempt = tc.exempt

			result, err := exec.Execute(context.Background(), params)
			if tc.wantErr != "" {
				require.Error(t, err)
				assert.Contains(t, err.Error(), tc.wantErr)
				return
			}
			require.NoError(t, err)
			job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
			require.NoError(t, err)
			dind := job.Spec.Template.Spec.InitContainers[0].Resources
			limit, ok := dind.Limits[corev1.ResourceMemory]
			if tc.wantLimit == "" {
				assert.False(t, ok, "no memory limit expected, got %s", limit.String())
				_, hasRequest := dind.Requests[corev1.ResourceMemory]
				assert.False(t, hasRequest)
				return
			}
			assert.Equal(t, tc.wantRequest, dind.Requests.Memory().String())
			require.True(t, ok)
			assert.Equal(t, tc.wantLimit, limit.String())
		})
	}
}

// TestKubernetesExecutor_Execute_DinD_NoImageOverride_UsesTemplateImage guards the empty-override
// default: leaving params.DindImage unset must keep the operator-supplied PodTemplate's image
// rather than blanking it out.
func TestKubernetesExecutor_Execute_DinD_NoImageOverride_UsesTemplateImage(t *testing.T) {
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
	// params.DindImage left empty.

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)

	require.Len(t, job.Spec.Template.Spec.InitContainers, 1)
	assert.Equal(t, "docker:29-dind", job.Spec.Template.Spec.InitContainers[0].Image)
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

// TestKubernetesExecutor_WithNamespace_LaunchesInCopyNamespaceAndSharesTemplateCache pins the two
// properties WithNamespace must have: the derived copy launches into its own namespace while the
// base keeps Config.Namespace, and the two share ONE pod-template cache (one wrapper GET, not two).
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

// schedulingTemplateYAML is a complete agent PodTemplate -- usable by DinD launches too -- that sets
// every scheduling field applyTemplateScheduling copies. assertTemplateScheduling mirrors its values.
const schedulingTemplateYAML = `
apiVersion: v1
kind: PodTemplate
metadata:
  name: choruskube-agent-pod-template
template:
  spec:
    runtimeClassName: sysbox-runc
    nodeSelector:
      example.com/pool: agents
    affinity:
      nodeAffinity:
        requiredDuringSchedulingIgnoredDuringExecution:
          nodeSelectorTerms:
            - matchExpressions:
                - key: kubernetes.io/arch
                  operator: In
                  values: [amd64]
    tolerations:
      - key: example.com/dedicated
        operator: Equal
        value: agents
        effect: NoSchedule
      - key: node.kubernetes.io/unreachable
        operator: Exists
        effect: NoExecute
        tolerationSeconds: 300
    topologySpreadConstraints:
      - maxSkew: 1
        topologyKey: kubernetes.io/hostname
        whenUnsatisfiable: ScheduleAnyway
        labelSelector:
          matchLabels:
            app: choruskube-agent
    priorityClassName: agent-batch
    initContainers:
      - name: dind
        image: docker:29-dind
    containers:
      - name: agent
        image: placeholder
`

func assertTemplateScheduling(t *testing.T, podSpec corev1.PodSpec) {
	t.Helper()
	assert.Equal(t, map[string]string{"example.com/pool": "agents"}, podSpec.NodeSelector)

	require.NotNil(t, podSpec.Affinity)
	require.NotNil(t, podSpec.Affinity.NodeAffinity)
	required := podSpec.Affinity.NodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution
	require.NotNil(t, required)
	assert.Equal(t, []corev1.NodeSelectorTerm{{
		MatchExpressions: []corev1.NodeSelectorRequirement{
			{Key: "kubernetes.io/arch", Operator: corev1.NodeSelectorOpIn, Values: []string{"amd64"}},
		},
	}}, required.NodeSelectorTerms)

	assert.Equal(t, []corev1.Toleration{
		{Key: "example.com/dedicated", Operator: corev1.TolerationOpEqual, Value: "agents", Effect: corev1.TaintEffectNoSchedule},
		{Key: "node.kubernetes.io/unreachable", Operator: corev1.TolerationOpExists, Effect: corev1.TaintEffectNoExecute, TolerationSeconds: int64Ptr(300)},
	}, podSpec.Tolerations)

	assert.Equal(t, []corev1.TopologySpreadConstraint{{
		MaxSkew:           1,
		TopologyKey:       "kubernetes.io/hostname",
		WhenUnsatisfiable: corev1.ScheduleAnyway,
		LabelSelector:     &metav1.LabelSelector{MatchLabels: map[string]string{"app": "choruskube-agent"}},
	}}, podSpec.TopologySpreadConstraints)

	assert.Equal(t, "agent-batch", podSpec.PriorityClassName)
}

func assertNoScheduling(t *testing.T, podSpec corev1.PodSpec) {
	t.Helper()
	assert.Empty(t, podSpec.NodeSelector)
	assert.Nil(t, podSpec.Affinity)
	assert.Empty(t, podSpec.Tolerations)
	assert.Empty(t, podSpec.TopologySpreadConstraints)
	assert.Empty(t, podSpec.PriorityClassName)
}

// Scheduling decides which nodes may run an agent at all, so the template's scheduling fields reach
// every agent pod -- while its DinD parts (sidecar, runtimeClassName) stay on DinD launches only.
func TestKubernetesExecutor_Execute_TemplateScheduling_AppliesToEveryAgentPod(t *testing.T) {
	templateNamespace := "choruskube"
	templateName := "choruskube-agent-pod-template"

	for _, enableDocker := range []bool{false, true} {
		t.Run(fmt.Sprintf("enableDocker=%t", enableDocker), func(t *testing.T) {
			fakeClient := fake.NewSimpleClientset()
			createTemplateWrapper(t, fakeClient, templateNamespace, templateName, schedulingTemplateYAML)
			exec := NewKubernetesExecutor(fakeClient, Config{
				Namespace:            testNamespace,
				AgentServiceAccount:  "choruskube-agent",
				AgentPodTemplateName: templateName,
				TemplateNamespace:    templateNamespace,
			})

			params := newTestParams()
			params.EnableDocker = enableDocker
			result, err := exec.Execute(context.Background(), params)
			require.NoError(t, err)

			job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
			require.NoError(t, err)
			podSpec := job.Spec.Template.Spec
			assertTemplateScheduling(t, podSpec)

			if enableDocker {
				require.NotNil(t, podSpec.RuntimeClassName)
				assert.Len(t, podSpec.InitContainers, 1)
			} else {
				assert.Nil(t, podSpec.RuntimeClassName, "runtimeClassName is a DinD-only splice")
				assert.Empty(t, podSpec.InitContainers, "the dind sidecar is a DinD-only splice")
			}
		})
	}
}

func TestKubernetesExecutor_Execute_TemplateScheduling_LeavesPodUnsteeredWhenNothingToCopy(t *testing.T) {
	templateNamespace := "choruskube"
	templateName := "choruskube-agent-pod-template"

	t.Run("no template configured", func(t *testing.T) {
		fakeClient := fake.NewSimpleClientset()
		exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})

		result, err := exec.Execute(context.Background(), newTestParams())
		require.NoError(t, err)

		job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
		require.NoError(t, err)
		assertNoScheduling(t, job.Spec.Template.Spec)
		for _, a := range fakeClient.Actions() {
			assert.False(t, a.GetVerb() == "get" && a.GetResource().Resource == "configmaps",
				"with no template configured, a launch must not read one")
		}
	})

	t.Run("template sets no scheduling fields", func(t *testing.T) {
		fakeClient := fake.NewSimpleClientset()
		setupDindTemplate(t, fakeClient, templateNamespace, templateName)
		exec := NewKubernetesExecutor(fakeClient, Config{
			Namespace:            testNamespace,
			AgentServiceAccount:  "choruskube-agent",
			AgentPodTemplateName: templateName,
			TemplateNamespace:    templateNamespace,
		})

		result, err := exec.Execute(context.Background(), newTestParams())
		require.NoError(t, err)

		job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
		require.NoError(t, err)
		assertNoScheduling(t, job.Spec.Template.Spec)
	})
}

// A configured template that cannot be read fails every launch, not only DinD ones: launching
// unsteered would silently place the agent on nodes the operator meant to exclude.
func TestKubernetesExecutor_Execute_MissingTemplate_FailsNonDindLaunch(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:            testNamespace,
		AgentServiceAccount:  "choruskube-agent",
		AgentPodTemplateName: "does-not-exist",
		TemplateNamespace:    "choruskube",
	})

	params := newTestParams()
	_, err := exec.Execute(context.Background(), params)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "not found")

	jobs, err := fakeClient.BatchV1().Jobs(testNamespace).List(context.Background(), metav1.ListOptions{})
	require.NoError(t, err)
	assert.Empty(t, jobs.Items, "no Job may launch when the configured template is unreadable")
	execIDShort := params.NodeExecutionID.String()[:8]
	_, cmErr := fakeClient.CoreV1().ConfigMaps(testNamespace).Get(context.Background(), "config-"+execIDShort, metav1.GetOptions{})
	assert.Error(t, cmErr, "configmap should have been cleaned up after the failed Execute")
}

// Same rule as runtimeClassName/hostUsers: a field the template sets replaces the inline value
// whole (never merged); a field it leaves unset keeps the inline value.
func TestKubernetesExecutor_ApplyTemplateScheduling_TemplateReplacesSetFieldsKeepsUnset(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	templateNamespace := "choruskube"
	templateName := "choruskube-agent-pod-template"
	createTemplateWrapper(t, fakeClient, templateNamespace, templateName, `
apiVersion: v1
kind: PodTemplate
template:
  spec:
    nodeSelector:
      example.com/pool: agents
    tolerations:
      - key: example.com/dedicated
        operator: Exists
    containers:
      - name: agent
        image: placeholder
`)
	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:            testNamespace,
		AgentPodTemplateName: templateName,
		TemplateNamespace:    templateNamespace,
	})

	inlineAffinity := &corev1.Affinity{NodeAffinity: &corev1.NodeAffinity{
		RequiredDuringSchedulingIgnoredDuringExecution: &corev1.NodeSelector{NodeSelectorTerms: []corev1.NodeSelectorTerm{{
			MatchExpressions: []corev1.NodeSelectorRequirement{{Key: "example.com/inline", Operator: corev1.NodeSelectorOpExists}},
		}}},
	}}
	inlineSpread := []corev1.TopologySpreadConstraint{{
		MaxSkew: 2, TopologyKey: "topology.kubernetes.io/zone", WhenUnsatisfiable: corev1.DoNotSchedule,
	}}
	job := &batchv1.Job{Spec: batchv1.JobSpec{Template: corev1.PodTemplateSpec{Spec: corev1.PodSpec{
		NodeSelector:              map[string]string{"example.com/inline": "yes"},
		Tolerations:               []corev1.Toleration{{Key: "example.com/inline", Operator: corev1.TolerationOpExists}},
		Affinity:                  inlineAffinity,
		TopologySpreadConstraints: inlineSpread,
		PriorityClassName:         "inline-priority",
	}}}}

	require.NoError(t, exec.applyTemplateScheduling(context.Background(), job))
	podSpec := job.Spec.Template.Spec

	// Set by the template: replaced whole, with no inline entry merged in.
	assert.Equal(t, map[string]string{"example.com/pool": "agents"}, podSpec.NodeSelector)
	assert.Equal(t, []corev1.Toleration{{Key: "example.com/dedicated", Operator: corev1.TolerationOpExists}}, podSpec.Tolerations)
	// Unset in the template: the inline value survives.
	assert.Same(t, inlineAffinity, podSpec.Affinity)
	assert.Equal(t, inlineSpread, podSpec.TopologySpreadConstraints)
	assert.Equal(t, "inline-priority", podSpec.PriorityClassName)
}

// The template is cached for the life of the process, so a launch that edits its own pod in place
// must not reach the cache -- every later launch would inherit the edit.
func TestKubernetesExecutor_ApplyTemplateScheduling_DoesNotAliasCachedTemplate(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	templateNamespace := "choruskube"
	templateName := "choruskube-agent-pod-template"
	createTemplateWrapper(t, fakeClient, templateNamespace, templateName, schedulingTemplateYAML)
	exec := NewKubernetesExecutor(fakeClient, Config{
		Namespace:            testNamespace,
		AgentPodTemplateName: templateName,
		TemplateNamespace:    templateNamespace,
	})

	first := &batchv1.Job{}
	require.NoError(t, exec.applyTemplateScheduling(context.Background(), first))
	edited := &first.Spec.Template.Spec
	edited.NodeSelector["example.com/pool"] = "mutated"
	edited.Affinity.NodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution.NodeSelectorTerms[0].MatchExpressions[0].Values[0] = "mutated"
	edited.Tolerations[0].Value = "mutated"
	*edited.Tolerations[1].TolerationSeconds = 1
	edited.TopologySpreadConstraints[0].LabelSelector.MatchLabels["app"] = "mutated"
	edited.PriorityClassName = "mutated"

	second := &batchv1.Job{}
	require.NoError(t, exec.applyTemplateScheduling(context.Background(), second))
	assertTemplateScheduling(t, second.Spec.Template.Spec)
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

	// Simulate the Job already reaped (ttlSecondsAfterFinished, or a race with K8s GC) while its
	// owner-ref'd children remain -- the fake clientset runs no garbage collector, so deleting the
	// Job here does not cascade-delete them, like a real cluster mid-GC.
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

// fakeLogsBody is what the fake clientset's pod log stream always returns.
const fakeLogsBody = "fake logs"

// createAgentPod stands in for the Job controller the fake clientset does not run: it stores a Pod
// carrying the job-name label GetLogs selects on, with the given status.
func createAgentPod(t *testing.T, client kubernetes.Interface, execID uuid.UUID, status corev1.PodStatus) {
	t.Helper()
	jobName := jobPrefix + execID.String()[:8]
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      jobName + "-x7k2p",
			Namespace: testNamespace,
			Labels:    map[string]string{"job-name": jobName},
		},
		Status: status,
	}
	_, err := client.CoreV1().Pods(testNamespace).Create(context.Background(), pod, metav1.CreateOptions{})
	require.NoError(t, err)
}

func TestKubernetesExecutor_GetLogs_PodStatusSummary(t *testing.T) {
	tests := []struct {
		name   string
		status corev1.PodStatus
		want   string
	}{
		{
			name: "healthy running pod is unchanged",
			status: corev1.PodStatus{
				Phase: corev1.PodRunning,
				ContainerStatuses: []corev1.ContainerStatus{
					{Name: agentContainerName, State: corev1.ContainerState{Running: &corev1.ContainerStateRunning{}}},
					// Only the agent container's termination is reported.
					{Name: "sidecar", State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{Reason: "Error", ExitCode: 2}}},
				},
			},
			want: fakeLogsBody,
		},
		{
			name: "evicted pod surfaces pod reason and message",
			status: corev1.PodStatus{
				Phase:   corev1.PodFailed,
				Reason:  "Evicted",
				Message: "The node was low on resource: ephemeral-storage.",
			},
			want: "pod Evicted: The node was low on resource: ephemeral-storage.\n" + fakeLogsBody,
		},
		{
			name: "OOM-killed agent surfaces reason and exit code",
			status: corev1.PodStatus{
				Phase: corev1.PodFailed,
				ContainerStatuses: []corev1.ContainerStatus{{
					Name:  agentContainerName,
					State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{Reason: "OOMKilled", ExitCode: 137}},
				}},
			},
			want: "container agent OOMKilled (exit code 137)\n" + fakeLogsBody,
		},
		{
			name: "last termination is used when the agent has since restarted",
			status: corev1.PodStatus{
				Phase: corev1.PodRunning,
				ContainerStatuses: []corev1.ContainerStatus{{
					Name:  agentContainerName,
					State: corev1.ContainerState{Running: &corev1.ContainerStateRunning{}},
					LastTerminationState: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{
						Reason: "Error", ExitCode: 1, Message: "entrypoint failed",
					}},
				}},
			},
			want: "container agent Error (exit code 1): entrypoint failed\n" + fakeLogsBody,
		},
		{
			name: "pod and container facts each get their own line",
			status: corev1.PodStatus{
				Phase:   corev1.PodFailed,
				Reason:  "Evicted",
				Message: "The node was low on resource: memory.",
				ContainerStatuses: []corev1.ContainerStatus{{
					Name:  agentContainerName,
					State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{ExitCode: 137}},
				}},
			},
			want: "pod Evicted: The node was low on resource: memory.\n" +
				"container agent terminated (exit code 137)\n" + fakeLogsBody,
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			fakeClient := fake.NewSimpleClientset()
			exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
			params := newTestParams()
			_, err := exec.Execute(context.Background(), params)
			require.NoError(t, err)
			createAgentPod(t, fakeClient, params.NodeExecutionID, tc.status)

			logs, err := exec.GetLogs(context.Background(), params.NodeExecutionID, 100)
			require.NoError(t, err)
			assert.Equal(t, tc.want, logs)
		})
	}
}

// logStreamClient wraps a fake clientset so the pod log stream answers with respond instead of the
// fake's fixed body -- the fake cannot otherwise fail a log read or return empty logs.
type logStreamClient struct {
	kubernetes.Interface
	respond func() *http.Response
}

func (c logStreamClient) CoreV1() typedcorev1.CoreV1Interface {
	return logStreamCoreV1{CoreV1Interface: c.Interface.CoreV1(), respond: c.respond}
}

type logStreamCoreV1 struct {
	typedcorev1.CoreV1Interface
	respond func() *http.Response
}

func (c logStreamCoreV1) Pods(namespace string) typedcorev1.PodInterface {
	return logStreamPods{PodInterface: c.CoreV1Interface.Pods(namespace), respond: c.respond}
}

type logStreamPods struct {
	typedcorev1.PodInterface
	respond func() *http.Response
}

func (p logStreamPods) GetLogs(name string, _ *corev1.PodLogOptions) *rest.Request {
	client := &fakerest.RESTClient{
		Client: fakerest.CreateHTTPClient(func(*http.Request) (*http.Response, error) {
			return p.respond(), nil
		}),
		NegotiatedSerializer: scheme.Codecs.WithoutConversion(),
		GroupVersion:         corev1.SchemeGroupVersion,
		VersionedAPIPath:     "/api/v1/namespaces/" + testNamespace + "/pods/" + name + "/log",
	}
	return client.Request()
}

type failingReader struct{}

func (failingReader) Read([]byte) (int, error) { return 0, errors.New("connection reset") }

// TestKubernetesExecutor_GetLogs_EvictedPod_UnreadableLogs covers the case the summary exists for:
// an evicted Pod whose agent container is gone, so the log read itself fails or comes back empty.
func TestKubernetesExecutor_GetLogs_EvictedPod_UnreadableLogs(t *testing.T) {
	const summary = "pod Evicted: The node was low on resource: ephemeral-storage.\n"
	tests := []struct {
		name     string
		respond  func() *http.Response
		wantBody string
	}{
		{
			name: "log stream rejected",
			respond: func() *http.Response {
				return &http.Response{
					StatusCode: http.StatusBadRequest,
					Header:     http.Header{"Content-Type": []string{"application/json"}},
					Body: io.NopCloser(strings.NewReader(`{"kind":"Status","apiVersion":"v1","status":"Failure",` +
						`"message":"container \"agent\" in pod \"agent-0\" is terminated","reason":"BadRequest","code":400}`)),
				}
			},
			wantBody: `(failed to read logs: container "agent" in pod "agent-0" is terminated)`,
		},
		{
			name: "log body read fails",
			respond: func() *http.Response {
				return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(failingReader{})}
			},
			wantBody: "(failed to read logs: connection reset)",
		},
		{
			name: "log body empty",
			respond: func() *http.Response {
				return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(""))}
			},
			wantBody: "(no logs available)",
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			fakeClient := fake.NewSimpleClientset()
			exec := NewKubernetesExecutor(logStreamClient{Interface: fakeClient, respond: tc.respond},
				Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
			params := newTestParams()
			_, err := exec.Execute(context.Background(), params)
			require.NoError(t, err)
			createAgentPod(t, fakeClient, params.NodeExecutionID, corev1.PodStatus{
				Phase:   corev1.PodFailed,
				Reason:  "Evicted",
				Message: "The node was low on resource: ephemeral-storage.",
			})

			logs, err := exec.GetLogs(context.Background(), params.NodeExecutionID, 100)
			require.NoError(t, err)
			assert.Equal(t, summary+tc.wantBody, logs)
		})
	}
}

// TestKubernetesExecutor_GetLogs_CapsLogsNotSummary proves the 64KB cap trims the log body only, so
// an oversized log can never push the status summary out of the returned text.
func TestKubernetesExecutor_GetLogs_CapsLogsNotSummary(t *testing.T) {
	body := strings.Repeat("a", 10) + strings.Repeat("b", logLimitBytes)
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(logStreamClient{Interface: fakeClient, respond: func() *http.Response {
		return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(body))}
	}}, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
	params := newTestParams()
	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)
	createAgentPod(t, fakeClient, params.NodeExecutionID, corev1.PodStatus{
		Phase: corev1.PodFailed,
		ContainerStatuses: []corev1.ContainerStatus{{
			Name:  agentContainerName,
			State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{Reason: "OOMKilled", ExitCode: 137}},
		}},
	})

	logs, err := exec.GetLogs(context.Background(), params.NodeExecutionID, 100)
	require.NoError(t, err)
	const summary = "container agent OOMKilled (exit code 137)\n"
	require.True(t, strings.HasPrefix(logs, summary), "summary lost to the cap, got %.80q", logs)
	assert.True(t, logs[len(summary):] == strings.Repeat("b", logLimitBytes),
		"log body should be exactly the last %d bytes of the stream", logLimitBytes)
}

func TestKubernetesExecutor_ResolveJobSecretHash_NotFound(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace})

	_, err := exec.ResolveJobSecretHash(context.Background(), uuid.Nil, uuid.New())
	assert.Error(t, err)
}

func TestKubernetesExecutor_HealthCheck(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()
	exec := NewKubernetesExecutor(fakeClient, Config{})

	err := exec.HealthCheck(context.Background())
	assert.NoError(t, err)
}

// TestKubernetesExecutor_Teardown_IsNamespacedGetByName_NoClusterWideList guards the RBAC boundary:
// every teardown/recovery call must reach its resources by name within the executor's configured
// namespace, never via a cluster-wide (all-namespaces) LIST. It inspects the fake clientset's
// recorded actions and fails if any carries an empty namespace -- the all-namespaces signature.
func TestKubernetesExecutor_Teardown_IsNamespacedGetByName_NoClusterWideList(t *testing.T) {
	fakeClient := fake.NewSimpleClientset()

	exec := NewKubernetesExecutor(fakeClient, Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"})
	params := newTestParams()

	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	// Only teardown/recovery calls are under test, so drop Execute's own create actions.
	fakeClient.ClearActions()

	_, err = exec.ResolveJobSecretHash(context.Background(), uuid.Nil, params.NodeExecutionID)
	require.NoError(t, err)
	_, err = exec.GetLogs(context.Background(), params.NodeExecutionID, 100)
	require.NoError(t, err)
	require.NoError(t, exec.Cleanup(context.Background(), params.NodeExecutionID))

	sawSecretGet := false
	sawJobGet := false
	for _, a := range fakeClient.Actions() {
		// An empty namespace on any verb is the all-namespaces signature this guards against.
		assert.NotEmpty(t, a.GetNamespace(),
			"action %s on %s must be namespace-scoped, not cluster-wide", a.GetVerb(), a.GetResource().Resource)
		assert.Equal(t, testNamespace, a.GetNamespace(),
			"action %s on %s targeted the wrong namespace", a.GetVerb(), a.GetResource().Resource)
		// A cluster-wide list of secrets/jobs/configmaps is exactly the read-all-secrets grant this
		// boundary forbids -- assert those resources are never listed.
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
