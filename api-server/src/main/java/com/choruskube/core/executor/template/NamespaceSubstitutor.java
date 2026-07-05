package com.choruskube.core.executor.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.util.Map;

/**
 * Walks a fabric8-loaded resource and replaces every {@code __NAMESPACE__} token
 * appearing in string-typed fields with the supplied namespace value.
 *
 * <p>Rejects templates that put the placeholder into {@code metadata.name} (a K8s
 * identifier field) — names should not be derived from the namespace. Other fields
 * are validated only by virtue of being string-typed in the resource schema.
 */
public final class NamespaceSubstitutor {
    public static final String PLACEHOLDER = "__NAMESPACE__";
    private static final ObjectMapper MAPPER = Serialization.jsonMapper();

    private NamespaceSubstitutor() {}

    @SuppressWarnings("unchecked")
    public static <T extends HasMetadata> T substitute(T template, String namespace) {
        if (template == null) {
            throw new IllegalArgumentException("template must not be null");
        }
        if (namespace == null) {
            throw new IllegalArgumentException("namespace must not be null");
        }
        if (template.getMetadata() != null
                && template.getMetadata().getName() != null
                && template.getMetadata().getName().contains(PLACEHOLDER)) {
            throw new IllegalArgumentException("Template's metadata.name contains " + PLACEHOLDER
                    + " — names must not be namespace-derived. Got: '"
                    + template.getMetadata().getName() + "'");
        }
        ObjectNode tree = MAPPER.valueToTree(template);
        replaceInTree(tree, namespace);
        try {
            return (T) MAPPER.treeToValue(tree, template.getClass());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to rebuild resource after substitution", e);
        }
    }

    private static void replaceInTree(JsonNode node, String namespace) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            for (Map.Entry<String, JsonNode> entry : obj.properties()) {
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    String s = value.asText();
                    if (s.contains(PLACEHOLDER)) {
                        obj.set(entry.getKey(), TextNode.valueOf(s.replace(PLACEHOLDER, namespace)));
                    }
                } else {
                    replaceInTree(value, namespace);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child.isTextual()) {
                    String s = child.asText();
                    if (s.contains(PLACEHOLDER)) {
                        arr.set(i, TextNode.valueOf(s.replace(PLACEHOLDER, namespace)));
                    }
                } else {
                    replaceInTree(child, namespace);
                }
            }
        }
    }
}
