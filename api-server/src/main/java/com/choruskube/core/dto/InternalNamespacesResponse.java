package com.choruskube.core.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The orchestrator's namespace roster. {@code credentials} is keyed by every entry in {@code
 * namespaces}; an orchestrator predating this field reads {@code namespaces} alone and is
 * unaffected by its presence.
 */
public record InternalNamespacesResponse(
        List<String> namespaces, Map<String, NamespaceCredentialResponse> credentials) {

    /** Lists namespaces and each one's state only — never a token or an expiry. */
    @Override
    public String toString() {
        Map<String, String> states = new LinkedHashMap<>();
        if (credentials != null) {
            credentials.forEach((namespace, credential) -> states.put(namespace, credential.state()));
        }
        return "InternalNamespacesResponse{namespaces=" + namespaces + ", credentials=" + states + "}";
    }
}
