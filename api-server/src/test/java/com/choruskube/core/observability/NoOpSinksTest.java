package com.choruskube.core.observability;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoOpSinksTest {
    @Test
    void auditNoOp_doesNothing() {
        assertThatCode(() -> new NoOpAuditSink().record("REPO_CREATED", "git_repo", UUID.randomUUID(), "{}"))
                .doesNotThrowAnyException();
    }

    @Test
    void usageNoOp_doesNothing() {
        assertThatCode(() -> new NoOpUsageSink().record("RUN_STARTED", "workflow_run", UUID.randomUUID(), null))
                .doesNotThrowAnyException();
    }
}
