package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.exception.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunServiceInputValidationTest {

    private RunService runService;

    @BeforeEach
    void setUp() throws Exception {
        // RunService has many constructor dependencies, but validateInputs only uses objectMapper.
        // Create instance via constructor with nulls for unused deps, then inject objectMapper.
        runService = new RunService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper(),
                null,
                null,
                null,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(), // quotaService
                null, // placements
                null, // workflowClients
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null, // storyRepo
                null, // epicRepo
                null,
                null,
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null,
                null,
                null, // nodeExecutionClaimService — unused (signalHumanDecision not exercised)
                null); // escalationContextResolver — unused (escalation not exercised)
    }

    @Test
    void missingRequiredInputThrowsValidationException() {
        String schema = """
                [
                    {"name": "repo_url", "type": "string", "required": true},
                    {"name": "branch", "type": "string", "required": false}
                ]
                """;

        assertThatThrownBy(() -> runService.validateInputs(schema, Map.of("branch", "main")))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).getErrors())
                .asList()
                .anyMatch(err -> err.toString().contains("missing required input: repo_url"));
    }

    @Test
    void allRequiredInputsProvidedSucceeds() {
        String schema = """
                [
                    {"name": "repo_url", "type": "string", "required": true},
                    {"name": "branch", "type": "string", "required": true}
                ]
                """;

        assertThatCode(() -> runService.validateInputs(
                        schema, Map.of("repo_url", "https://github.com/x/y", "branch", "main")))
                .doesNotThrowAnyException();
    }

    @Test
    void emptyInputsMapWithNoRequiredFieldsSucceeds() {
        String schema = """
                [
                    {"name": "optional_flag", "type": "string", "required": false}
                ]
                """;

        assertThatCode(() -> runService.validateInputs(schema, Map.of())).doesNotThrowAnyException();
    }

    @Test
    void nullInputsWithRequiredFieldThrows() {
        String schema = """
                [
                    {"name": "repo_url", "type": "string", "required": true}
                ]
                """;

        assertThatThrownBy(() -> runService.validateInputs(schema, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void blankRequiredInputThrows() {
        String schema = """
                [
                    {"name": "repo_url", "type": "string", "required": true}
                ]
                """;

        assertThatThrownBy(() -> runService.validateInputs(schema, Map.of("repo_url", "   ")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void emptySchemaSkipsValidation() {
        assertThatCode(() -> runService.validateInputs("[]", Map.of())).doesNotThrowAnyException();
    }

    @Test
    void invalidSchemaJsonSkipsValidation() {
        assertThatCode(() -> runService.validateInputs("not valid json", Map.of("x", "y")))
                .doesNotThrowAnyException();
    }

    @Test
    void nullSchemaSkipsValidation() {
        assertThatCode(() -> runService.validateInputs(null, Map.of("x", "y"))).doesNotThrowAnyException();
    }

    @Test
    void requiredFieldWithDefaultPassesWhenOmitted() {
        String schema = """
                [
                    {"name": "repo_url", "type": "string", "required": true, "default": "https://github.com/x/y"},
                    {"name": "feature_request", "type": "string", "required": true}
                ]
                """;

        assertThatCode(() -> runService.validateInputs(schema, Map.of("feature_request", "add login")))
                .doesNotThrowAnyException();
    }

    @Test
    void requiredFieldWithDefaultStillFailsWhenExplicitlyBlank() {
        String schema = """
                [
                    {"name": "repo_url", "type": "string", "required": true, "default": "https://github.com/x/y"}
                ]
                """;

        assertThatThrownBy(() -> runService.validateInputs(schema, Map.of("repo_url", "   ")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void requiredFieldWithEmptyStringDefaultPassesWhenOmitted() {
        // Empty-string default is a valid default (consistent with mergeInputs' null-check).
        // When the field is omitted, the default "" will be applied by mergeInputs.
        String schema = """
                [
                    {"name": "project_context", "type": "string", "required": true, "default": ""}
                ]
                """;

        assertThatCode(() -> runService.validateInputs(schema, Map.of())).doesNotThrowAnyException();
    }

    @Test
    void requiredFieldWithNullDefaultFailsWhenOmitted() {
        // A JSON-null default is NOT a valid default — field is effectively required with no fallback.
        String schema = """
                [
                    {"name": "repo_url", "type": "string", "required": true, "default": null}
                ]
                """;

        assertThatThrownBy(() -> runService.validateInputs(schema, Map.of())).isInstanceOf(ValidationException.class);
    }

    // applyDefaults tests removed — logic moved to mergeInputs which has a different
    // signature (requires GraphTemplate instances). Covered by integration tests.
}
