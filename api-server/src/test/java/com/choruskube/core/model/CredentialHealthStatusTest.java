package com.choruskube.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CredentialHealthStatusTest {

    @Test
    void wire_roundTripsEveryValue() {
        for (CredentialHealthStatus status : CredentialHealthStatus.values()) {
            assertThat(CredentialHealthStatus.fromWire(status.wire())).isEqualTo(status);
        }
    }

    @Test
    void wire_isTheEnumNameItself() {
        assertThat(CredentialHealthStatus.NOT_HELD.wire()).isEqualTo("NOT_HELD");
        assertThat(CredentialHealthStatus.VALID.wire()).isEqualTo("VALID");
    }

    @Test
    void fromWire_unknownValue_throws() {
        assertThatThrownBy(() -> CredentialHealthStatus.fromWire("bogus")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromWire_wrongCase_throws() {
        assertThatThrownBy(() -> CredentialHealthStatus.fromWire("valid")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromWire_nullOrEmpty_throws() {
        assertThatThrownBy(() -> CredentialHealthStatus.fromWire(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CredentialHealthStatus.fromWire("")).isInstanceOf(IllegalArgumentException.class);
    }
}
