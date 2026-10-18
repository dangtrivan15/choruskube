package com.choruskube.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.model.WorkerCredentialSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Built the same way Spring Boot wires the request-body {@link ObjectMapper} — via {@link
 * Jackson2ObjectMapperBuilder} — because that builder, unlike a bare {@code new ObjectMapper()},
 * disables {@code FAIL_ON_UNKNOWN_PROPERTIES} the way the real MVC deserialization path does.
 */
class WorkerRegisterRequestJsonTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    private static final String INSTANCE_ID = "11111111-1111-1111-1111-111111111111";

    private String bodyWith(String credentialSourceField) {
        return "{\"hostname\":\"h\",\"instanceId\":\"" + INSTANCE_ID + "\"" + credentialSourceField + "}";
    }

    @Test
    void missingCredentialSource_fails() {
        assertThatThrownBy(() -> mapper.readValue(bodyWith(""), WorkerRegisterRequest.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullCredentialSource_fails() {
        assertThatThrownBy(() -> mapper.readValue(bodyWith(",\"credentialSource\":null"), WorkerRegisterRequest.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyCredentialSource_fails() {
        assertThatThrownBy(() -> mapper.readValue(bodyWith(",\"credentialSource\":\"\""), WorkerRegisterRequest.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void local_deserializesToLocal() throws Exception {
        WorkerRegisterRequest request =
                mapper.readValue(bodyWith(",\"credentialSource\":\"local\""), WorkerRegisterRequest.class);

        assertThat(request.credentialSource()).isEqualTo(WorkerCredentialSource.LOCAL);
        assertThat(request.instanceId()).isEqualTo(UUID.fromString(INSTANCE_ID));
    }

    @Test
    void controlPlane_deserializesToControlPlane() throws Exception {
        WorkerRegisterRequest request =
                mapper.readValue(bodyWith(",\"credentialSource\":\"control-plane\""), WorkerRegisterRequest.class);

        assertThat(request.credentialSource()).isEqualTo(WorkerCredentialSource.CONTROL_PLANE);
    }

    @Test
    void wrongCase_fails() {
        assertThatThrownBy(() ->
                        mapper.readValue(bodyWith(",\"credentialSource\":\"Local\""), WorkerRegisterRequest.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unrelatedUnknownProperty_isIgnoredNotAnError() throws Exception {
        String body = "{\"hostname\":\"h\",\"instanceId\":\"" + INSTANCE_ID + "\",\"credentialSource\":\"local\","
                + "\"somethingThisServerDoesNotKnow\":\"value\"}";

        WorkerRegisterRequest request = mapper.readValue(body, WorkerRegisterRequest.class);

        assertThat(request.credentialSource()).isEqualTo(WorkerCredentialSource.LOCAL);
    }
}
