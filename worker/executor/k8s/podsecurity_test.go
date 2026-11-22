package k8s

import (
	"context"
	"sort"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/fake"
	"k8s.io/pod-security-admission/api"
	"k8s.io/pod-security-admission/policy"

	coreexec "github.com/dangtrivan15/choruskube/worker/executor"
)

// baselineCheckIDs returns policy.DefaultChecks()'s baseline-level IDs sorted the way
// policy/registry.go orders them internally. EvaluatePod's results carry no ID of their own,
// so this is what zips a []policy.CheckResult back to the check that produced each one; the
// sort order is confirmed against the real Evaluator in TestKubernetesExecutor_PodSecurity_Baseline,
// not assumed from the upstream source.
func baselineCheckIDs() []string {
	var ids []string
	for _, c := range policy.DefaultChecks() {
		if c.Level == api.LevelBaseline {
			ids = append(ids, string(c.ID))
		}
	}
	sort.Strings(ids)
	return ids
}

// evaluateBaseline runs podMeta/podSpec through the upstream baseline/latest policy, returning
// whether every check allowed it and, if not, the IDs of the checks that disallowed it.
func evaluateBaseline(t *testing.T, podMeta *metav1.ObjectMeta, podSpec *corev1.PodSpec) (allowed bool, disallowedIDs []string) {
	t.Helper()
	eval, err := policy.NewEvaluator(policy.DefaultChecks())
	require.NoError(t, err)
	ids := baselineCheckIDs()
	lv := api.LevelVersion{Level: api.LevelBaseline, Version: api.LatestVersion()}
	results := eval.EvaluatePod(lv, podMeta, podSpec)
	require.Len(t, results, len(ids), "EvaluatePod must return one result per baseline check for the zip above to line up")
	for i, r := range results {
		if !r.Allowed {
			disallowedIDs = append(disallowedIDs, ids[i])
		}
	}
	return len(disallowedIDs) == 0, disallowedIDs
}

// buildPodSpec runs Execute and reads back the Job it created, so every executor step that
// shapes the pod (buildJob, applyTemplateScheduling, addDindSupport) has actually run.
func buildPodSpec(t *testing.T, fakeClient kubernetes.Interface, cfg Config, params coreexec.ExecutionParams) (metav1.ObjectMeta, corev1.PodSpec) {
	t.Helper()
	exec := NewKubernetesExecutor(fakeClient, cfg)
	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)
	job, err := fakeClient.BatchV1().Jobs(cfg.Namespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
	require.NoError(t, err)
	return job.Spec.Template.ObjectMeta, job.Spec.Template.Spec
}

// positiveDindTemplateYAML is a DinD PodTemplate whose dind sidecar sets fields baseline never
// gates (runAsUser=0, runAsNonRoot=false, allowPrivilegeEscalation=true) but leaves every
// baseline-gated field alone: unprivileged, no added capabilities, no host port, confined
// seccomp/AppArmor (unset), and only an emptyDir volume. The negative fixtures below are this
// string plus exactly one more line, so a failing case is provably not vacuous.
const positiveDindTemplateYAML = `apiVersion: v1
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
        securityContext:
          runAsUser: 0
          runAsNonRoot: false
          allowPrivilegeEscalation: true
        resources:
          requests:
            cpu: 500m
            memory: 512Mi
    containers:
      - name: agent
        image: placeholder
    volumes:
      - name: docker-certs
        emptyDir: {}
`

func TestKubernetesExecutor_PodSecurity_Baseline(t *testing.T) {
	templateNamespace := "choruskube"
	templateName := "choruskube-agent-pod-template"

	t.Run("no docker: every check allowed", func(t *testing.T) {
		fakeClient := fake.NewSimpleClientset()
		podMeta, podSpec := buildPodSpec(t, fakeClient,
			Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"}, newTestParams())

		allowed, disallowed := evaluateBaseline(t, &podMeta, &podSpec)
		assert.True(t, allowed, "disallowed by: %v", disallowed)
	})

	t.Run("registry credential: every check allowed", func(t *testing.T) {
		fakeClient := fake.NewSimpleClientset()
		params := newTestParams()
		params.Credentials.Registry = &coreexec.RegistryCredentials{
			Host:     "registry.example.com",
			Username: "user",
			Password: "pass",
		}
		podMeta, podSpec := buildPodSpec(t, fakeClient,
			Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"}, params)

		allowed, disallowed := evaluateBaseline(t, &podMeta, &podSpec)
		assert.True(t, allowed, "disallowed by: %v", disallowed)
	})

	t.Run("docker, sysbox template: every check allowed", func(t *testing.T) {
		fakeClient := fake.NewSimpleClientset()
		createTemplateWrapper(t, fakeClient, templateNamespace, templateName, positiveDindTemplateYAML)

		params := newTestParams()
		params.EnableDocker = true
		podMeta, podSpec := buildPodSpec(t, fakeClient, Config{
			Namespace:            testNamespace,
			AgentServiceAccount:  "choruskube-agent",
			AgentPodTemplateName: templateName,
			TemplateNamespace:    templateNamespace,
			AgentResources:       testAgentResources(),
		}, params)

		allowed, disallowed := evaluateBaseline(t, &podMeta, &podSpec)
		assert.True(t, allowed, "disallowed by: %v", disallowed)

		// Prove the fixture isn't accidentally empty: the fields baseline doesn't gate are
		// genuinely set on the built Job, not merely absent from the template.
		require.Len(t, podSpec.InitContainers, 1)
		dind := podSpec.InitContainers[0]
		require.NotNil(t, dind.SecurityContext)
		require.NotNil(t, dind.SecurityContext.RunAsUser)
		assert.Equal(t, int64(0), *dind.SecurityContext.RunAsUser)
		require.NotNil(t, dind.SecurityContext.AllowPrivilegeEscalation)
		assert.True(t, *dind.SecurityContext.AllowPrivilegeEscalation)
	})

	t.Run("docker, privileged dind: disallowed by privileged", func(t *testing.T) {
		fakeClient := fake.NewSimpleClientset()
		privilegedYAML := strings.Replace(positiveDindTemplateYAML,
			"          allowPrivilegeEscalation: true\n",
			"          allowPrivilegeEscalation: true\n          privileged: true\n", 1)
		require.NotEqual(t, positiveDindTemplateYAML, privilegedYAML, "fixture must actually add the field under test")
		createTemplateWrapper(t, fakeClient, templateNamespace, templateName, privilegedYAML)

		params := newTestParams()
		params.EnableDocker = true
		podMeta, podSpec := buildPodSpec(t, fakeClient, Config{
			Namespace:            testNamespace,
			AgentServiceAccount:  "choruskube-agent",
			AgentPodTemplateName: templateName,
			TemplateNamespace:    templateNamespace,
			AgentResources:       testAgentResources(),
		}, params)

		allowed, disallowed := evaluateBaseline(t, &podMeta, &podSpec)
		assert.False(t, allowed)
		assert.Contains(t, disallowed, "privileged")
	})

	t.Run("docker, hostPath volume: disallowed by hostPathVolumes", func(t *testing.T) {
		fakeClient := fake.NewSimpleClientset()
		hostPathYAML := strings.Replace(positiveDindTemplateYAML,
			"    volumes:\n      - name: docker-certs\n        emptyDir: {}\n",
			"    volumes:\n      - name: docker-certs\n        emptyDir: {}\n"+
				"      - name: docker-storage\n        hostPath:\n          path: /var/lib/docker\n", 1)
		hostPathYAML = strings.Replace(hostPathYAML,
			"        resources:\n",
			"        volumeMounts:\n          - name: docker-storage\n            mountPath: /var/lib/docker\n        resources:\n", 1)
		require.NotEqual(t, positiveDindTemplateYAML, hostPathYAML, "fixture must actually add the volume under test")
		createTemplateWrapper(t, fakeClient, templateNamespace, templateName, hostPathYAML)

		params := newTestParams()
		params.EnableDocker = true
		podMeta, podSpec := buildPodSpec(t, fakeClient, Config{
			Namespace:            testNamespace,
			AgentServiceAccount:  "choruskube-agent",
			AgentPodTemplateName: templateName,
			TemplateNamespace:    templateNamespace,
			AgentResources:       testAgentResources(),
		}, params)

		allowed, disallowed := evaluateBaseline(t, &podMeta, &podSpec)
		assert.False(t, allowed)
		assert.Contains(t, disallowed, "hostPathVolumes")
	})
}
