package com.choruskube.core.executor.template;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.PodTemplate;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches K8s template resources (PodTemplate, Deployment, ConfigMap) loaded from the api-server's
 * namespace. Each template is stored in the cluster as a <em>wrapper ConfigMap</em> whose
 * {@code data.template.yaml} key contains the serialized inner resource. The wrapper ConfigMap name
 * is what callers pass to {@code require...} / {@code get...} methods; the inner resource's
 * {@code metadata.name} is operator-friendly but overridden by the provisioner anyway.
 *
 * <p>This wrapper pattern keeps the choruskube namespace free of inert Deployment specs that would
 * otherwise cause K8s to schedule failing pods (missing per-org PVCs / ConfigMaps).
 *
 * <p>Hard-fail semantics: {@code require...} methods throw if the named wrapper ConfigMap is absent
 * or missing the {@code template.yaml} data key. Silent fallback to a hardcoded default would mask
 * operator mistakes (hard-cut migration).
 */
public class TemplateRegistry {
    private static final Logger log = LoggerFactory.getLogger(TemplateRegistry.class);

    private final KubernetesClient client;
    private final String namespace;
    private final Map<String, PodTemplate> podTemplates = new HashMap<>();
    private final Map<String, Deployment> deployments = new HashMap<>();
    private final Map<String, ConfigMap> configMaps = new HashMap<>();

    public TemplateRegistry(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    /** Fetches a PodTemplate by name (from its wrapper ConfigMap), caches it, and throws if absent. */
    public synchronized PodTemplate requirePodTemplate(String name) {
        PodTemplate cached = podTemplates.get(name);
        if (cached != null) return cached;
        PodTemplate fresh = loadFromWrapper(name, PodTemplate.class, "PodTemplate");
        podTemplates.put(name, fresh);
        log.info("Loaded PodTemplate '{}' from namespace '{}'", name, namespace);
        return fresh;
    }

    /** Fetches a Deployment by name (from its wrapper ConfigMap), caches it, and throws if absent. */
    public synchronized Deployment requireDeployment(String name) {
        Deployment cached = deployments.get(name);
        if (cached != null) return cached;
        Deployment fresh = loadFromWrapper(name, Deployment.class, "Deployment");
        deployments.put(name, fresh);
        log.info("Loaded Deployment template '{}' from namespace '{}'", name, namespace);
        return fresh;
    }

    /** Returns a cached PodTemplate by name, or null if not yet required. */
    public synchronized PodTemplate getPodTemplate(String name) {
        return podTemplates.get(name);
    }

    /** Returns a cached Deployment by name, or null if not yet required. */
    public synchronized Deployment getDeployment(String name) {
        return deployments.get(name);
    }

    /** Fetches a ConfigMap by name (from its wrapper ConfigMap), caches it, and throws if absent. */
    public synchronized ConfigMap requireConfigMap(String name) {
        var cached = configMaps.get(name);
        if (cached != null) return cached;
        ConfigMap fresh = loadFromWrapper(name, ConfigMap.class, "ConfigMap");
        configMaps.put(name, fresh);
        log.info("Loaded ConfigMap template '{}' from namespace '{}'", name, namespace);
        return fresh;
    }

    /** Returns a cached ConfigMap by name, or null if not yet required. */
    public synchronized ConfigMap getConfigMap(String name) {
        return configMaps.get(name);
    }

    /** Drops all cached templates so the next {@code require...} call refetches. */
    public synchronized void refresh() {
        podTemplates.clear();
        deployments.clear();
        configMaps.clear();
    }

    /**
     * Loads a typed resource from its wrapper ConfigMap. The wrapper ConfigMap named {@code name}
     * must exist in {@code namespace} and must contain a {@code template.yaml} data key whose value
     * is valid YAML for the requested type.
     */
    private <T> T loadFromWrapper(String name, Class<T> type, String typeLabel) {
        ConfigMap wrapper =
                client.configMaps().inNamespace(namespace).withName(name).get();
        if (wrapper == null) {
            throw new IllegalStateException("Required " + typeLabel + " template wrapper ConfigMap '" + name
                    + "' not found in namespace '" + namespace
                    + "'. Operator must supply this wrapper ConfigMap before api-server starts.");
        }
        String yaml = wrapper.getData() == null ? null : wrapper.getData().get("template.yaml");
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalStateException("Wrapper ConfigMap '" + name + "' in namespace '" + namespace
                    + "' is missing required 'template.yaml' data key");
        }
        try {
            return Serialization.unmarshal(yaml, type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to deserialize 'template.yaml' from wrapper ConfigMap '" + name + "' as " + typeLabel, e);
        }
    }
}
