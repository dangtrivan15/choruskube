package com.choruskube.core.executor.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class TemplateRegistryTest {
    KubernetesClient client;

    // -----------------------------------------------------------------------
    // Minimal inline YAML strings for each inner resource type
    // -----------------------------------------------------------------------

    private static final String POD_TEMPLATE_YAML = """
            apiVersion: v1
            kind: PodTemplate
            metadata:
              name: agent
            template:
              spec: {}
            """;

    private static final String DEPLOYMENT_YAML = """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: buildkit
            spec:
              selector:
                matchLabels:
                  app: buildkit
              template:
                spec: {}
            """;

    private static final String CONFIGMAP_YAML = """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: buildkit-config
            data:
              buildkitd.toml: |
                [registry."example.svc.cluster.local:5000"]
                  http = true
            """;

    // -----------------------------------------------------------------------
    // Helper: create a wrapper ConfigMap with data.template.yaml
    // -----------------------------------------------------------------------

    private void createWrapper(String name, String templateYaml) {
        client.configMaps()
                .inNamespace("choruskube")
                .resource(new ConfigMapBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .endMetadata()
                        .addToData("template.yaml", templateYaml)
                        .build())
                .create();
    }

    // -----------------------------------------------------------------------
    // Fetch tests — happy path
    // -----------------------------------------------------------------------

    @Test
    void fetchesPodTemplateFromCluster() {
        createWrapper("agent-tmpl", POD_TEMPLATE_YAML);

        TemplateRegistry registry = new TemplateRegistry(client, "choruskube");
        registry.requirePodTemplate("agent-tmpl");

        assertThat(registry.getPodTemplate("agent-tmpl")).isNotNull();
    }

    @Test
    void fetchesDeploymentFromCluster() {
        createWrapper("buildkit-tmpl", DEPLOYMENT_YAML);

        TemplateRegistry registry = new TemplateRegistry(client, "choruskube");
        registry.requireDeployment("buildkit-tmpl");

        assertThat(registry.getDeployment("buildkit-tmpl")).isNotNull();
    }

    @Test
    void fetchesConfigMapFromCluster() {
        createWrapper("buildkit-cm-tmpl", CONFIGMAP_YAML);

        TemplateRegistry registry = new TemplateRegistry(client, "choruskube");
        registry.requireConfigMap("buildkit-cm-tmpl");

        assertThat(registry.getConfigMap("buildkit-cm-tmpl")).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Hard-fail: wrapper ConfigMap absent
    // -----------------------------------------------------------------------

    @Test
    void hardFailsWhenRequiredXMissing() {
        TemplateRegistry registry = new TemplateRegistry(client, "choruskube");

        assertThatThrownBy(() -> registry.requirePodTemplate("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PodTemplate")
                .hasMessageContaining("missing")
                .hasMessageContaining("choruskube");

        assertThatThrownBy(() -> registry.requireDeployment("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Deployment")
                .hasMessageContaining("missing")
                .hasMessageContaining("choruskube");

        assertThatThrownBy(() -> registry.requireConfigMap("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ConfigMap")
                .hasMessageContaining("missing")
                .hasMessageContaining("choruskube");
    }

    // -----------------------------------------------------------------------
    // Hard-fail: wrapper exists but missing template.yaml key
    // -----------------------------------------------------------------------

    @Test
    void hardFailsWhenWrapperMissingTemplateKey() {
        // Wrapper ConfigMap exists but has no template.yaml data key
        client.configMaps()
                .inNamespace("choruskube")
                .resource(new ConfigMapBuilder()
                        .withNewMetadata()
                        .withName("broken-wrapper")
                        .endMetadata()
                        .addToData("wrong-key", "some-value")
                        .build())
                .create();

        TemplateRegistry registry = new TemplateRegistry(client, "choruskube");

        assertThatThrownBy(() -> registry.requirePodTemplate("broken-wrapper"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken-wrapper")
                .hasMessageContaining("template.yaml");
    }

    // -----------------------------------------------------------------------
    // Caching: fetched templates survive cluster mutation until refresh()
    // -----------------------------------------------------------------------

    @Test
    void cachesFetchedTemplatesUntilRefresh() {
        createWrapper("agent-tmpl", POD_TEMPLATE_YAML);

        TemplateRegistry registry = new TemplateRegistry(client, "choruskube");
        registry.requirePodTemplate("agent-tmpl");
        var first = registry.getPodTemplate("agent-tmpl");

        // Delete the wrapper from the cluster — registry should still return the cached value
        client.configMaps().inNamespace("choruskube").withName("agent-tmpl").delete();
        var stillCached = registry.getPodTemplate("agent-tmpl");

        assertThat(stillCached).isSameAs(first);

        registry.refresh();
        assertThatThrownBy(() -> registry.requirePodTemplate("agent-tmpl")).isInstanceOf(IllegalStateException.class);
    }
}
