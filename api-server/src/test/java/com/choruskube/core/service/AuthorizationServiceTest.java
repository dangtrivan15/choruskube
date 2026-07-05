package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationServiceTest {

    private static final UUID ENTITY_ID = UUID.randomUUID();

    @Test
    void checkOrgAccess_noopWhenAuthDisabled() {
        var service = new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false);
        // Should not throw — the always-allow strategy ignores all args
        assertThatCode(() -> service.checkOrgAccess("workflow_run", ENTITY_ID)).doesNotThrowAnyException();
    }
}
