package com.choruskube.core.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.SharedContextTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.service.OrgIdentitySync;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class DocsControllerAuthTest extends SharedContextTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrgSecurity orgSecurity;

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
