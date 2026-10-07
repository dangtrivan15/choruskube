package com.choruskube.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MemoryQuantityTest {

    @Test
    void normalize_returnsNull_forNullOrBlank() {
        assertThat(MemoryQuantity.normalize("agentMemoryRequest", null)).isNull();
        assertThat(MemoryQuantity.normalize("agentMemoryRequest", "")).isNull();
        assertThat(MemoryQuantity.normalize("agentMemoryRequest", "   ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"512Mi", "4Gi", "1.5Gi", "1792Mi", "500M", "2G", "1073741824", "256Ki"})
    void normalize_acceptsKubernetesMemoryQuantities(String value) {
        assertThat(MemoryQuantity.normalize("agentMemoryRequest", value)).isEqualTo(value);
    }

    @Test
    void normalize_trimsSurroundingWhitespace() {
        assertThat(MemoryQuantity.normalize("dindMemoryRequest", " 2Gi ")).isEqualTo("2Gi");
    }

    @ParameterizedTest
    @ValueSource(strings = {"4GB", "4gi", "4 Gi", "abc", "-1Gi", "0", "0Mi", "0.0Gi", "Gi", "1.Gi", "1e9"})
    void normalize_rejectsAnythingElse_namingTheField(String value) {
        assertThatThrownBy(() -> MemoryQuantity.normalize("dindMemoryRequest", value))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("dindMemoryRequest");
    }

    @Test
    void normalize_rejectsValuesLongerThanTheColumn() {
        assertThatThrownBy(() -> MemoryQuantity.normalize("agentMemoryRequest", "1".repeat(40) + "Mi"))
                .isInstanceOf(BadRequestException.class);
    }
}
