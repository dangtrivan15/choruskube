package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TemplateWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mergeConfigWithoutExtraReturnsTheBaseUnchanged() {
        String base = "{\"loop_group\": \"impl-review\", \"needs_branch\": \"true\"}";
        assertThat(TemplateWriter.mergeConfig(base, null)).isSameAs(base);
    }

    @Test
    void mergeConfigKeepsBaseKeysAndLetsExtraWin() throws Exception {
        String merged = TemplateWriter.mergeConfig(
                "{\"loop_group\": \"impl-review\", \"effort\": \"high\"}",
                "{\"effort\": \"xhigh\", \"story_version_recheck\": {\"route\": \"rereview\"}}");
        assertThat(JSON.readTree(merged))
                .isEqualTo(JSON.readTree("{\"loop_group\": \"impl-review\", \"effort\": \"xhigh\", "
                        + "\"story_version_recheck\": {\"route\": \"rereview\"}}"));
    }

    @Test
    void mergeConfigReplacesANestedObjectWhole() throws Exception {
        JsonNode merged = JSON.readTree(TemplateWriter.mergeConfig(
                "{\"drift_recheck\": {\"route\": \"rereview\", \"stamp\": \"always\"}}",
                "{\"drift_recheck\": {\"route\": \"redraft\"}}"));
        assertThat(merged.get("drift_recheck")).isEqualTo(JSON.readTree("{\"route\": \"redraft\"}"));
    }

    @Test
    void mergeConfigRejectsAnythingButJsonObjects() {
        assertThatThrownBy(() -> TemplateWriter.mergeConfig("[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a JSON object");
        assertThatThrownBy(() -> TemplateWriter.mergeConfig("{}", "{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not valid JSON");
    }
}
