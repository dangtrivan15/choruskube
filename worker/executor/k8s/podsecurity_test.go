package k8s

import (
	"context"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"
	"k8s.io/pod-security-admission/api"
	"k8s.io/pod-security-admission/policy"

	coreexec "github.com/dangtrivan15/choruskube/worker/executor"
)

// baselineViolations returns the ForbiddenReason of every upstream baseline check, at the latest
// policy version, that disallows the pod: the same reasons a namespace enforcing baseline names
// when it refuses one. Empty means the pod is admitted.
func baselineViolations(t *testing.T, podMeta *metav1.ObjectMeta, podSpec *corev1.PodSpec) []string {
	t.Helper()
	eval, err := policy.NewEvaluator(policy.DefaultChecks())
	require.NoError(t, err)
	lv := api.LevelVersion{Level: api.LevelBaseline, Version: api.LatestVersion()}
	var reasons []string
	for _, r := range eval.EvaluatePod(lv, podMeta, podSpec) {
		if !r.Allowed {
			reasons = append(reasons, r.ForbiddenReason)
		}
	}
	return reasons
}

// baselineDindTemplateYAML is a DinD PodTemplate whose dind sidecar sets the fields baseline does
// not gate (root, privilege escalation allowed) and none it does: unprivileged, no added
// capability, no host port, seccomp/AppArmor left unset, and only emptyDir volumes.
const baselineDindTemplateYAML = `apiVersion: v1
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

// mustReplace replaces the first old in s, failing the test when s has none, so a negative
// fixture can never silently equal the positive one it is derived from.
func mustReplace(t *testing.T, s, old, replacement string) string {
	t.Helper()
	require.Contains(t, s, old)
	return strings.Replace(s, old, replacement, 1)
}

// Every pod shape Execute builds must be admitted by a namespace enforcing Pod Security baseline,
// and a template that breaks baseline must be refused, so the check is not vacuous.
func TestKubernetesExecutor_PodSecurity_Baseline(t *testing.T) {
	const templateNamespace = "choruskube"
	const templateName = "choruskube-agent-pod-template"

	privilegedDind := mustReplace(t, baselineDindTemplateYAML,
		"          allowPrivilegeEscalation: true\n",
		"          allowPrivilegeEscalation: true\n          privileged: true\n")
	hostPathVolume := mustReplace(t, baselineDindTemplateYAML,
		"        emptyDir: {}\n",
		"        emptyDir: {}\n      - name: docker-data\n        hostPath:\n          path: /var/lib/docker\n")

	tests := []struct {
		name         string
		template     string // the DinD PodTemplate; empty runs the pod without Docker
		withRegistry bool
		want         []string // baseline ForbiddenReasons; empty means admitted
	}{
		{name: "no docker"},
		{name: "registry credential", withRegistry: true},
		{name: "docker, sysbox template", template: baselineDindTemplateYAML},
		{name: "docker, privileged dind", template: privilegedDind, want: []string{"privileged"}},
		{name: "docker, hostPath volume", template: hostPathVolume, want: []string{"hostPath volumes"}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			fakeClient := fake.NewSimpleClientset()
			cfg := Config{Namespace: testNamespace, AgentServiceAccount: "choruskube-agent"}
			params := newTestParams()
			if tc.withRegistry {
				params.Credentials.Registry = &coreexec.RegistryCredentials{
					Host:     "registry.example.com",
					Username: "user",
					Password: "pass",
				}
			}
			if tc.template != "" {
				createTemplateWrapper(t, fakeClient, templateNamespace, templateName, tc.template)
				params.EnableDocker = true
				cfg.AgentPodTemplateName = templateName
				cfg.TemplateNamespace = templateNamespace
				cfg.AgentResources = testAgentResources()
			}

			result, err := NewKubernetesExecutor(fakeClient, cfg).Execute(context.Background(), params)
			require.NoError(t, err)
			job, err := fakeClient.BatchV1().Jobs(testNamespace).Get(context.Background(), result.PodName, metav1.GetOptions{})
			require.NoError(t, err)
			pod := job.Spec.Template

			if tc.template != "" {
				// The dind sidecar reached the built pod with the root and escalation settings
				// baseline must tolerate, so an admitted Docker pod really carries them.
				require.Len(t, pod.Spec.InitContainers, 1)
				sc := pod.Spec.InitContainers[0].SecurityContext
				require.NotNil(t, sc)
				require.NotNil(t, sc.RunAsUser)
				assert.Equal(t, int64(0), *sc.RunAsUser)
				require.NotNil(t, sc.AllowPrivilegeEscalation)
				assert.True(t, *sc.AllowPrivilegeEscalation)
			}

			assert.ElementsMatch(t, tc.want, baselineViolations(t, &pod.ObjectMeta, &pod.Spec))
		})
	}
}
