package com.choruskube.core.executor.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NamespaceSubstitutorTest {

    @Test
    void replacesPlaceholderInDeploymentLabels() {
        Deployment template = new DeploymentBuilder()
                .withNewMetadata()
                .withName("buildkit")
                .withLabels(Map.of("app", "buildkit", "ck/namespace", "__NAMESPACE__"))
                .endMetadata()
                .withNewSpec()
                .withNewSelector()
                .addToMatchLabels("app", "buildkit")
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        Deployment result = NamespaceSubstitutor.substitute(template, "ck-system");

        assertThat(result.getMetadata().getLabels()).containsEntry("ck/namespace", "ck-system");
    }

    @Test
    void replacesPlaceholderInConfigMapData() {
        var cm = new ConfigMapBuilder()
                .withNewMetadata()
                .withName("buildkit-config")
                .endMetadata()
                .withData(Map.of(
                        "buildkitd.toml",
                        "[registry.\"cache-registry.__NAMESPACE__.svc.cluster.local:5000\"]\n  http = true\n"))
                .build();

        var result = NamespaceSubstitutor.substitute(cm, "ck-system");

        assertThat(result.getData().get("buildkitd.toml"))
                .contains("cache-registry.ck-system.svc.cluster.local:5000")
                .doesNotContain("__NAMESPACE__");
    }

    @Test
    void leavesUnrelatedFieldsAlone() {
        Deployment template = new DeploymentBuilder()
                .withNewMetadata()
                .withName("buildkit")
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .addToMatchLabels("app", "buildkit")
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        Deployment result = NamespaceSubstitutor.substitute(template, "ck-system");

        assertThat(result.getSpec().getReplicas()).isEqualTo(1);
        assertThat(result.getMetadata().getName()).isEqualTo("buildkit");
    }

    @Test
    void substitutesPlaceholderInAnnotationValues() {
        // Annotation values are string-typed in the K8s schema, so the substitutor
        // replaces __NAMESPACE__ tokens in them just like any other string field.
        Deployment template = new DeploymentBuilder()
                .withNewMetadata()
                .withName("buildkit")
                .withAnnotations(Map.of("ck.io/replicas-hint", "__NAMESPACE__"))
                .endMetadata()
                .withNewSpec()
                .withNewSelector()
                .addToMatchLabels("app", "buildkit")
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        Deployment ok = NamespaceSubstitutor.substitute(template, "ck-system");
        assertThat(ok.getMetadata().getAnnotations()).containsEntry("ck.io/replicas-hint", "ck-system");
    }

    @Test
    void rejectsTemplateWithMalformedPlaceholderToken() {
        Deployment template = new DeploymentBuilder()
                .withNewMetadata()
                .withName("__NAMESPACE__-buildkit") // disallowed: name field is identifier-shape, not free string
                .endMetadata()
                .withNewSpec()
                .withNewSelector()
                .addToMatchLabels("app", "buildkit")
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        assertThatThrownBy(() -> NamespaceSubstitutor.substitute(template, "ck-system"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata.name")
                .hasMessageContaining("__NAMESPACE__");
    }
}
