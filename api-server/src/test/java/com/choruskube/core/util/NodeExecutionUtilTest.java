package com.choruskube.core.util;

import static org.junit.jupiter.api.Assertions.*;

import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeExecutionUtilTest {

    private static NodeExecution execIn(UUID runId) {
        NodeExecution exec = new NodeExecution();
        exec.setId(UUID.randomUUID());
        exec.setWorkflowRunId(runId);
        return exec;
    }

    @Test
    void requireInRun_sameRun_doesNotThrow() {
        UUID runId = UUID.randomUUID();
        assertDoesNotThrow(() -> NodeExecutionUtil.requireInRun(execIn(runId), runId));
    }

    @Test
    void requireInRun_differentRun_throwsNotFound() {
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        NodeExecution exec = execIn(otherRunId);

        NotFoundException ex = assertThrows(NotFoundException.class, () -> NodeExecutionUtil.requireInRun(exec, runId));

        assertTrue(ex.getMessage().contains(exec.getId().toString()));
        assertFalse(ex.getMessage().contains(runId.toString()));
    }

    @Test
    void requireInRun_nullRunOnExecution_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> NodeExecutionUtil.requireInRun(execIn(null), UUID.randomUUID()));
    }
}
