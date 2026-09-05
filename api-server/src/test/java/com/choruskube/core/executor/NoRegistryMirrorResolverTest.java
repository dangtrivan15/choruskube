package com.choruskube.core.executor;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoRegistryMirrorResolverTest {

    @Test
    void resolvesNothingForAnyRun() {
        NoRegistryMirrorResolver resolver = new NoRegistryMirrorResolver();

        assertNull(resolver.resolve(UUID.randomUUID()));
        assertNull(resolver.resolve(UUID.randomUUID()));
    }
}
