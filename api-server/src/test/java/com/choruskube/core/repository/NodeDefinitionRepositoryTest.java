package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.enums.ExecutorType;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class NodeDefinitionRepositoryTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private NodeDefinitionRepository repo;

    private NodeDefinition buildDef(String name) {
        NodeDefinition def = new NodeDefinition();
        def.setName(name);
        def.setExecutorType(ExecutorType.ai);
        def.setPromptTemplate("test");
        def.setTimeoutSeconds(60);
        def.setSkills("[]");
        def.setInputSpec("{}");
        def.setOutputSpec("{}");
        def.setSecrets("[]");
        return def;
    }

    @Test
    void persistsModelField() {
        NodeDefinition def = buildDef("Test Node");
        def.setModel("claude-haiku-4-5-20251001");

        NodeDefinition saved = repo.saveAndFlush(def);

        NodeDefinition reloaded = repo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getModel()).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void modelDefaultsToNull() {
        NodeDefinition def = buildDef("Default Model Node");

        NodeDefinition saved = repo.saveAndFlush(def);

        NodeDefinition reloaded = repo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getModel()).isNull();
    }
}
