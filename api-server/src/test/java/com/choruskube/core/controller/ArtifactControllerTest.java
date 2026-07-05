package com.choruskube.core.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.exception.GlobalExceptionHandler;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.service.ArtifactService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc test for ArtifactController — does not load a Spring
 * application context (and therefore does not start TestContainers), keeping
 * the test fast and avoiding context-cache pollution for other integration tests.
 */
class ArtifactControllerTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID EXEC_ID = UUID.randomUUID();
    private static final String BASE_PATH = "/api/v1/runs/" + RUN_ID + "/node-executions/" + EXEC_ID + "/artifacts";

    private ArtifactService artifactService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        artifactService = mock(ArtifactService.class);
        ArtifactController controller = new ArtifactController(artifactService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getArtifact_png_returnsImagePngContentType() throws Exception {
        byte[] pngBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "screenshot.png"))
                .thenReturn(pngBytes);

        mockMvc.perform(get(BASE_PATH + "/screenshot.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(pngBytes));
    }

    @Test
    void getArtifact_jpeg_returnsImageJpegContentType() throws Exception {
        byte[] jpegBytes = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "photo.jpeg")).thenReturn(jpegBytes);

        mockMvc.perform(get(BASE_PATH + "/photo.jpeg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(jpegBytes));
    }

    @Test
    void getArtifact_svg_returnsImageSvgXmlContentType() throws Exception {
        byte[] svgBytes = "<svg></svg>".getBytes(StandardCharsets.UTF_8);
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "diagram.svg")).thenReturn(svgBytes);

        mockMvc.perform(get(BASE_PATH + "/diagram.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml"))
                .andExpect(content().bytes(svgBytes));
    }

    @Test
    void getArtifact_markdown_returnsTextMarkdownContentType() throws Exception {
        byte[] mdBytes = "# Hello".getBytes(StandardCharsets.UTF_8);
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "readme.md")).thenReturn(mdBytes);

        mockMvc.perform(get(BASE_PATH + "/readme.md"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(content().bytes(mdBytes));
    }

    @Test
    void getArtifact_unknownExtension_returnsOctetStream() throws Exception {
        byte[] binBytes = new byte[] {0x00, 0x01, 0x02};
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "data.bin")).thenReturn(binBytes);

        mockMvc.perform(get(BASE_PATH + "/data.bin"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(binBytes));
    }

    @Test
    void getArtifact_nestedPath_routesToService() throws Exception {
        byte[] htmlBytes = "<html></html>".getBytes(StandardCharsets.UTF_8);
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "playwright-report/index.html"))
                .thenReturn(htmlBytes);

        mockMvc.perform(get(BASE_PATH + "/playwright-report/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(htmlBytes));
    }

    @Test
    void getArtifact_notFound_returns404() throws Exception {
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "missing.png"))
                .thenThrow(new NotFoundException("Artifact not found: missing.png"));

        mockMvc.perform(get(BASE_PATH + "/missing.png")).andExpect(status().isNotFound());
    }

    // --- Security headers ---

    @Test
    void getArtifact_includesContentSecurityPolicyHeader() throws Exception {
        byte[] svgBytes = "<svg><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "evil.svg")).thenReturn(svgBytes);

        mockMvc.perform(get(BASE_PATH + "/evil.svg"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                                "Content-Security-Policy",
                                "default-src 'none'; style-src 'unsafe-inline'; img-src data:"));
    }

    @Test
    void getArtifact_includesNoSniffHeader() throws Exception {
        byte[] pngBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "image.png")).thenReturn(pngBytes);

        mockMvc.perform(get(BASE_PATH + "/image.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    // --- Text MIME types ---

    @Test
    void getArtifact_txt_returnsTextPlainContentType() throws Exception {
        byte[] txtBytes = "hello world".getBytes(StandardCharsets.UTF_8);
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "notes.txt")).thenReturn(txtBytes);

        mockMvc.perform(get(BASE_PATH + "/notes.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().bytes(txtBytes));
    }

    @Test
    void getArtifact_json_returnsApplicationJsonContentType() throws Exception {
        byte[] jsonBytes = "{\"key\": \"value\"}".getBytes(StandardCharsets.UTF_8);
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "config.json")).thenReturn(jsonBytes);

        mockMvc.perform(get(BASE_PATH + "/config.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(content().bytes(jsonBytes));
    }

    @Test
    void getArtifact_log_returnsTextPlainContentType() throws Exception {
        byte[] logBytes = "2026-04-08 INFO Starting...".getBytes(StandardCharsets.UTF_8);
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "output.log")).thenReturn(logBytes);

        mockMvc.perform(get(BASE_PATH + "/output.log"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().bytes(logBytes));
    }

    @Test
    void getArtifact_yaml_returnsTextYamlContentType() throws Exception {
        byte[] yamlBytes = "key: value".getBytes(StandardCharsets.UTF_8);
        when(artifactService.getArtifactBytes(RUN_ID, EXEC_ID, "config.yaml")).thenReturn(yamlBytes);

        mockMvc.perform(get(BASE_PATH + "/config.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/yaml;charset=UTF-8"))
                .andExpect(content().bytes(yamlBytes));
    }
}
