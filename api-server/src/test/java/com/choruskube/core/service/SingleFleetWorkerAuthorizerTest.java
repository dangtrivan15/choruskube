package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.exception.ForbiddenException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SingleFleetWorkerAuthorizerTest {

    @Test
    void acceptsTheConfiguredTokenForAnyRun() {
        SingleFleetWorkerAuthorizer authorizer = new SingleFleetWorkerAuthorizer("configured-token");

        assertThatCode(() -> authorizer.requireMayActOn("configured-token", UUID.randomUUID()))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorizer.requireMayActOn("configured-token", UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnyOtherToken() {
        SingleFleetWorkerAuthorizer authorizer = new SingleFleetWorkerAuthorizer("configured-token");

        assertThatThrownBy(() -> authorizer.requireMayActOn("other", UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> authorizer.requireMayActOn(null, UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> authorizer.requireMayActOn("", UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * An unset token must fail closed. Accepting anything here would make the Worker routes
     * anonymous on every server whose operator never configured Worker registration.
     */
    @Test
    void rejectsEverythingWhenNoTokenIsConfigured() {
        assertThatThrownBy(() -> new SingleFleetWorkerAuthorizer("").requireMayActOn("", UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> new SingleFleetWorkerAuthorizer(null).requireMayActOn("x", UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
    }
}
