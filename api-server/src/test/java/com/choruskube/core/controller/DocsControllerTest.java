package com.choruskube.core.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
public class DocsControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listDocs_returns200WithEntries() throws Exception {
        mockMvc.perform(get("/api/v1/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void listDocs_responseContainsExpectedSlug() throws Exception {
        mockMvc.perform(get("/api/v1/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'getting-started')]").exists());
    }

    @Test
    void listDocs_responseContainsDescriptionField() throws Exception {
        mockMvc.perform(get("/api/v1/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'getting-started')].description")
                        .value(hasItem(containsString("Step-by-step guide"))));
    }

    @Test
    void listDocs_allEntriesHaveNonEmptyDescriptions() throws Exception {
        mockMvc.perform(get("/api/v1/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].description", hasSize(9)));
    }

    @Test
    void getDocsPage_returns200WithContent() throws Exception {
        mockMvc.perform(get("/api/v1/docs/getting-started"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("getting-started"))
                .andExpect(jsonPath("$.title").value("Getting Started"))
                .andExpect(jsonPath("$.content").value(containsString("Overview")));
    }

    @Test
    void getDocsPage_returns404ForUnknownSlug() throws Exception {
        mockMvc.perform(get("/api/v1/docs/nonexistent-slug-xyz")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "getting-started",
                "features",
                "workflow-templates",
                "running-workflows",
                "ai-agent-nodes",
                "multi-repo-support",
                "analytics",
                "roadmap-and-proposals",
                "best-practices"
            })
    void getDocsPage_returns200ForAllKnownSlugs(String slug) throws Exception {
        mockMvc.perform(get("/api/v1/docs/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.content").isNotEmpty());
    }
}
