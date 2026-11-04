package com.choruskube.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CredentialKindTest {

    @Test
    void wire_roundTripsTheConfiguredStrings() {
        assertThat(CredentialKind.GITHUB.wire()).isEqualTo("github");
        assertThat(CredentialKind.CLAUDE.wire()).isEqualTo("claude");
        assertThat(CredentialKind.REGISTRY.wire()).isEqualTo("registry");

        assertThat(CredentialKind.fromWire("github")).isEqualTo(CredentialKind.GITHUB);
        assertThat(CredentialKind.fromWire("claude")).isEqualTo(CredentialKind.CLAUDE);
        assertThat(CredentialKind.fromWire("registry")).isEqualTo(CredentialKind.REGISTRY);
    }

    @Test
    void fromWire_unknownValue_throws() {
        assertThatThrownBy(() -> CredentialKind.fromWire("bogus")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromWire_wrongCase_throws() {
        assertThatThrownBy(() -> CredentialKind.fromWire("GitHub")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromWire_nullOrEmpty_throws() {
        assertThatThrownBy(() -> CredentialKind.fromWire(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CredentialKind.fromWire("")).isInstanceOf(IllegalArgumentException.class);
    }
}
