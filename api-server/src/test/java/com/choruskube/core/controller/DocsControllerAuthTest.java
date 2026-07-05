package com.choruskube.core.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.service.OrgIdentitySync;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class DocsControllerAuthTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrgSecurity orgSecurity;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private OrgIdentitySync orgIdentity;

    @Test
    void listDocs_returns403WhenCannotRead() throws Exception {
        when(orgSecurity.canRead()).thenReturn(false);
        mockMvc.perform(get("/api/v1/docs")).andExpect(status().isForbidden());
    }

    @Test
    void getDocsPage_returns403WhenCannotRead() throws Exception {
        when(orgSecurity.canRead()).thenReturn(false);
        mockMvc.perform(get("/api/v1/docs/getting-started")).andExpect(status().isForbidden());
    }

    @Test
    void listDocs_returns200WhenCanRead() throws Exception {
        when(orgSecurity.canRead()).thenReturn(true);
        mockMvc.perform(get("/api/v1/docs")).andExpect(status().isOk());
    }

    @Test
    void getDocsPage_returns200WhenCanRead() throws Exception {
        when(orgSecurity.canRead()).thenReturn(true);
        mockMvc.perform(get("/api/v1/docs/getting-started")).andExpect(status().isOk());
    }
}
