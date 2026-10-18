package com.choruskube.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkerCredentialSourceTest {

    @Test
    void wire_roundTripsTheConfiguredStrings() {
        assertThat(WorkerCredentialSource.CONTROL_PLANE.wire()).isEqualTo("control-plane");
        assertThat(WorkerCredentialSource.LOCAL.wire()).isEqualTo("local");

        assertThat(WorkerCredentialSource.fromWire("control-plane")).isEqualTo(WorkerCredentialSource.CONTROL_PLANE);
        assertThat(WorkerCredentialSource.fromWire("local")).isEqualTo(WorkerCredentialSource.LOCAL);
    }

    @Test
    void fromWire_nullOrEmpty_returnsNull() {
        assertThat(WorkerCredentialSource.fromWire(null)).isNull();
        assertThat(WorkerCredentialSource.fromWire("")).isNull();
    }

    @Test
    void fromWire_wrongCase_throws() {
        assertThatThrownBy(() -> WorkerCredentialSource.fromWire("Local")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromWire_unknownValues_throw() {
        assertThatThrownBy(() -> WorkerCredentialSource.fromWire("control_plane"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkerCredentialSource.fromWire("detect"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
