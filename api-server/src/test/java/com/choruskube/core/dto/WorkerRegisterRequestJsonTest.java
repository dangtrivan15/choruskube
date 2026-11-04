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

    private static final String CHECKED_AT = "2026-11-04T00:00:00Z";

    private static final String VALID_HEALTH = ",\"credentialHealth\":["
            + "{\"kind\":\"github\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT + "\"},"
            + "{\"kind\":\"claude\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT + "\"},"
            + "{\"kind\":\"registry\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT + "\"}]";

    private String bodyWith(String credentialSourceField) {
        return bodyWith(credentialSourceField, VALID_HEALTH);
    }

    private String bodyWith(String credentialSourceField, String credentialHealthField) {
        return "{\"hostname\":\"h\",\"instanceId\":\"" + INSTANCE_ID + "\"" + credentialSourceField
                + credentialHealthField + "}";
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
        assertThat(request.credentialHealth()).hasSize(3);
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
        String body = "{\"hostname\":\"h\",\"instanceId\":\"" + INSTANCE_ID + "\",\"credentialSource\":\"local\""
                + VALID_HEALTH + ",\"somethingThisServerDoesNotKnow\":\"value\"}";

        WorkerRegisterRequest request = mapper.readValue(body, WorkerRegisterRequest.class);

        assertThat(request.credentialSource()).isEqualTo(WorkerCredentialSource.LOCAL);
    }

    @Test
    void missingCredentialHealth_fails() {
        assertThatThrownBy(() ->
                        mapper.readValue(bodyWith(",\"credentialSource\":\"local\"", ""), WorkerRegisterRequest.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullCredentialHealth_fails() {
        assertThatThrownBy(() -> mapper.readValue(
                        bodyWith(",\"credentialSource\":\"local\"", ",\"credentialHealth\":null"),
                        WorkerRegisterRequest.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void credentialHealthMissingAKind_fails() {
        String health = ",\"credentialHealth\":["
                + "{\"kind\":\"github\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT
                + "\"},"
                + "{\"kind\":\"claude\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT
                + "\"}]";

        assertThatThrownBy(() -> mapper.readValue(
                        bodyWith(",\"credentialSource\":\"local\"", health), WorkerRegisterRequest.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void credentialHealthDuplicatesAKind_fails() {
        String health = ",\"credentialHealth\":["
                + "{\"kind\":\"github\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT
                + "\"},"
                + "{\"kind\":\"github\",\"status\":\"VALID\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT
                + "\"},"
                + "{\"kind\":\"claude\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT
                + "\"}]";

        assertThatThrownBy(() -> mapper.readValue(
                        bodyWith(",\"credentialSource\":\"local\"", health), WorkerRegisterRequest.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void credentialHealthUnknownKind_fails() {
        String health = ",\"credentialHealth\":["
                + "{\"kind\":\"bogus\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT
                + "\"}]";

        assertThatThrownBy(() -> mapper.readValue(
                        bodyWith(",\"credentialSource\":\"local\"", health), WorkerRegisterRequest.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void credentialHealthUnknownStatus_fails() {
        String health = ",\"credentialHealth\":["
                + "{\"kind\":\"github\",\"status\":\"BOGUS\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT
                + "\"}]";

        assertThatThrownBy(() -> mapper.readValue(
                        bodyWith(",\"credentialSource\":\"local\"", health), WorkerRegisterRequest.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void credentialHealthMissingCheckedAt_fails() {
        String health = ",\"credentialHealth\":["
                + "{\"kind\":\"github\",\"status\":\"NOT_HELD\",\"detail\":\"\"},"
                + "{\"kind\":\"claude\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT + "\"},"
                + "{\"kind\":\"registry\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT
                + "\"}]";

        assertThatThrownBy(() -> mapper.readValue(
                        bodyWith(",\"credentialSource\":\"local\"", health), WorkerRegisterRequest.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void credentialHealthDetailOverTheCap_fails() {
        String tooLong = "x".repeat(WorkerCredentialHealth.MAX_DETAIL_LENGTH + 1);
        String health = ",\"credentialHealth\":["
                + "{\"kind\":\"github\",\"status\":\"UNREACHABLE\",\"detail\":\"" + tooLong + "\",\"checkedAt\":\""
                + CHECKED_AT + "\"},"
                + "{\"kind\":\"claude\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT + "\"},"
                + "{\"kind\":\"registry\",\"status\":\"NOT_HELD\",\"detail\":\"\",\"checkedAt\":\"" + CHECKED_AT
                + "\"}]";

        assertThatThrownBy(() -> mapper.readValue(
                        bodyWith(",\"credentialSource\":\"local\"", health), WorkerRegisterRequest.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validCredentialHealth_roundTrips() throws Exception {
        WorkerRegisterRequest request =
                mapper.readValue(bodyWith(",\"credentialSource\":\"control-plane\""), WorkerRegisterRequest.class);

        assertThat(request.credentialHealth()).hasSize(3);
    }
}
