package com.choruskube.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeRequirementsTest {

    @Test
    void value_object_carries_agent_image_and_enable_docker() {
        RuntimeRequirements rr = new RuntimeRequirements("registry/agent:v3", true);
        assertThat(rr.agentImage()).isEqualTo("registry/agent:v3");
        assertThat(rr.enableDocker()).isTrue();
    }

    @Test
    void agent_image_may_be_null_meaning_platform_default() {
        RuntimeRequirements rr = new RuntimeRequirements(null, false);
        assertThat(rr.agentImage()).isNull();
        assertThat(rr.enableDocker()).isFalse();
    }
}
