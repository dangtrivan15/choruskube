package com.choruskube.core.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.config.SingleTenant;
import com.choruskube.core.exception.GlobalExceptionHandler;
import com.choruskube.core.exception.UnresolvableTenantException;
import com.choruskube.core.service.StoragePrefixResolver;
import com.choruskube.core.service.UploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone (no Spring context) unit tests for AttachmentController. Keeps tests fast and avoids
 * TestContainers startup.
 */
class AttachmentControllerTest {

    private UploadService uploadService;
    private StoragePrefixResolver storagePrefixResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        uploadService = mock(UploadService.class);
        storagePrefixResolver = mock(StoragePrefixResolver.class);
        when(storagePrefixResolver.currentStoragePrefix()).thenReturn("my-org");

        AttachmentController controller = new AttachmentController(uploadService, storagePrefixResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void uploadTemp_withSingleFile_returnsStagingRefs() throws Exception {
        when(uploadService.uploadTempFiles(eq("my-org"), anyList()))
                .thenReturn("{\"report.pdf\":\"my-org/staging/abc/report.pdf\"}");

        MockMultipartFile file = new MockMultipartFile("files", "report.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/v1/attachments/temp").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagingRefs").value("{\"report.pdf\":\"my-org/staging/abc/report.pdf\"}"));
    }

    @Test
    void uploadTemp_withMultipleFiles_callsUploadService() throws Exception {
        when(uploadService.uploadTempFiles(eq("my-org"), anyList()))
                .thenReturn("{\"a.txt\":\"my-org/staging/uuid/a.txt\",\"b.txt\":\"my-org/staging/uuid/b.txt\"}");

        MockMultipartFile file1 = new MockMultipartFile("files", "a.txt", "text/plain", "a".getBytes());
        MockMultipartFile file2 = new MockMultipartFile("files", "b.txt", "text/plain", "b".getBytes());

        mockMvc.perform(multipart("/api/v1/attachments/temp").file(file1).file(file2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagingRefs").exists());
    }

    @Test
    void uploadTemp_tenantContextUnavailable_returns403() throws Exception {
        StoragePrefixResolver failingResolver = mock(StoragePrefixResolver.class);
        when(failingResolver.currentStoragePrefix())
                .thenThrow(new UnresolvableTenantException("TenantContext unavailable"));

        AttachmentController controller = new AttachmentController(uploadService, failingResolver);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        MockMultipartFile file = new MockMultipartFile("files", "f.txt", "text/plain", "x".getBytes());

        mvc.perform(multipart("/api/v1/attachments/temp").file(file)).andExpect(status().isForbidden());
    }

    @Test
    void uploadTemp_systemSlugUsedInSingleTenantMode() throws Exception {
        // The SystemStoragePrefixResolver always returns SingleTenant.SLUG ("system").
        when(storagePrefixResolver.currentStoragePrefix()).thenReturn(SingleTenant.SLUG);
        when(uploadService.uploadTempFiles(eq(SingleTenant.SLUG), anyList()))
                .thenReturn("{\"f.txt\":\"system/staging/uuid/f.txt\"}");

        MockMultipartFile file = new MockMultipartFile("files", "f.txt", "text/plain", "data".getBytes());

        mockMvc.perform(multipart("/api/v1/attachments/temp").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagingRefs").value("{\"f.txt\":\"system/staging/uuid/f.txt\"}"));
    }
}
