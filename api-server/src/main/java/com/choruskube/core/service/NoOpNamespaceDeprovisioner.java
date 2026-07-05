package com.choruskube.core.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpNamespaceDeprovisioner implements NamespaceDeprovisioner {

    @Override
    public void deprovisionByNamespace(String namespace) {
        // Single-tenant: no per-org namespaces exist, nothing to deprovision.
    }
}
