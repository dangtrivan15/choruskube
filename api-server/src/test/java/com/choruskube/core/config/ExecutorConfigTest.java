package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.executor.CredentialSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExecutorConfig} JSON parsing behaviour.
 *
 * <p>Verifies that malformed JSON env vars cause a fast startup failure
 * instead of silently falling back to empty collections.
 */
class ExecutorConfigTest {

    @Test
    void parseJson_validListJson_returnsDeserializedList() {
        String json = "[{\"source\":\"my-secret\",\"delivery\":\"env\",\"mountPath\":\"/mnt\",\"readOnly\":true}]";

        List<CredentialSpec> result =
                ExecutorConfig.parseJson(json, new TypeReference<List<CredentialSpec>>() {}, "TEST_VAR");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().source()).isEqualTo("my-secret");
        assertThat(result.getFirst().delivery()).isEqualTo("env");
    }

    @Test
    void parseJson_validMapJson_returnsDeserializedMap() {
        String json = "{\"API_KEY\":\"secret123\",\"DB_PASS\":\"pass456\"}";

        Map<String, String> result =
                ExecutorConfig.parseJson(json, new TypeReference<Map<String, String>>() {}, "DOCKER_SECRET_MAP");

        assertThat(result).hasSize(2);
        assertThat(result).containsEntry("API_KEY", "secret123");
        assertThat(result).containsEntry("DB_PASS", "pass456");
    }

    @Test
    void parseJson_emptyArrayJson_returnsEmptyList() {
        String json = "[]";

        List<CredentialSpec> result =
                ExecutorConfig.parseJson(json, new TypeReference<List<CredentialSpec>>() {}, "TEST_VAR");

        assertThat(result).isEmpty();
    }

    @Test
    void parseJson_emptyObjectJson_returnsEmptyMap() {
        String json = "{}";

        Map<String, String> result =
                ExecutorConfig.parseJson(json, new TypeReference<Map<String, String>>() {}, "TEST_VAR");

        assertThat(result).isEmpty();
    }

    @Test
    void parseJson_malformedJson_throwsIllegalState() {
        String malformedJson = "{not valid json}";

        assertThatThrownBy(() -> ExecutorConfig.parseJson(
                        malformedJson, new TypeReference<List<CredentialSpec>>() {}, "K8S_AGENT_SECRETS"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse K8S_AGENT_SECRETS")
                .hasMessageContaining("fix the JSON value or unset the variable")
                .hasMessageContaining(malformedJson)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void parseJson_malformedMapJson_throwsIllegalState() {
        String malformedJson = "not-a-map";

        assertThatThrownBy(() -> ExecutorConfig.parseJson(
                        malformedJson, new TypeReference<Map<String, String>>() {}, "DOCKER_SECRET_MAP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse DOCKER_SECRET_MAP")
                .hasMessageContaining(malformedJson);
    }

    @Test
    void parseJson_wrongSchemaJson_throwsIllegalState() {
        // Valid JSON but wrong schema — string where object expected
        String wrongSchema = "\"just-a-string\"";

        assertThatThrownBy(() -> ExecutorConfig.parseJson(
                        wrongSchema, new TypeReference<List<CredentialSpec>>() {}, "DOCKER_AGENT_SECRETS"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse DOCKER_AGENT_SECRETS");
    }
}
