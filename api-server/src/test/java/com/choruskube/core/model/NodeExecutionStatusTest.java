package com.choruskube.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.model.enums.NodeExecutionStatus;
import org.junit.jupiter.api.Test;

class NodeExecutionStatusTest {

    @Test
    void paused_valueOfRoundTrips() {
        assertThat(NodeExecutionStatus.valueOf("paused")).isEqualTo(NodeExecutionStatus.paused);
    }

    @Test
    void paused_nameRoundTrips() {
        assertThat(NodeExecutionStatus.paused.name()).isEqualTo("paused");
    }
}
