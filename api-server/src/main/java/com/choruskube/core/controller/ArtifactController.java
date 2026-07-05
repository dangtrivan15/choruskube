package com.choruskube.core.controller;

import com.choruskube.core.dto.ArtifactEntry;
import com.choruskube.core.service.ArtifactService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/runs/{runId}/node-executions/{execId}/artifacts")
public class ArtifactController {

    private static final Map<String, MediaType> MEDIA_TYPES = Map.ofEntries(
            // Images
            Map.entry(".png", MediaType.IMAGE_PNG),
            Map.entry(".jpg", MediaType.IMAGE_JPEG),
            Map.entry(".jpeg", MediaType.IMAGE_JPEG),
            Map.entry(".gif", MediaType.IMAGE_GIF),
            Map.entry(".webp", MediaType.valueOf("image/webp")),
            Map.entry(".bmp", MediaType.valueOf("image/bmp")),
            Map.entry(".ico", MediaType.valueOf("image/x-icon")),
            Map.entry(".svg", MediaType.valueOf("image/svg+xml")),
            // Text / structured text
            Map.entry(".md", MediaType.valueOf("text/markdown;charset=UTF-8")),
            Map.entry(".txt", MediaType.valueOf("text/plain;charset=UTF-8")),
            Map.entry(".log", MediaType.valueOf("text/plain;charset=UTF-8")),
            Map.entry(".json", MediaType.valueOf("application/json;charset=UTF-8")),
            Map.entry(".xml", MediaType.valueOf("application/xml;charset=UTF-8")),
            Map.entry(".yaml", MediaType.valueOf("text/yaml;charset=UTF-8")),
            Map.entry(".yml", MediaType.valueOf("text/yaml;charset=UTF-8")),
            Map.entry(".csv", MediaType.valueOf("text/csv;charset=UTF-8")));

    private final ArtifactService artifactService;

    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public List<ArtifactEntry> listArtifacts(@PathVariable UUID runId, @PathVariable UUID execId) {
        return artifactService.listArtifacts(runId, execId);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{*filename}")
    public ResponseEntity<byte[]> getArtifactContent(
            @PathVariable UUID runId, @PathVariable UUID execId, @PathVariable String filename) {
        // Spring's {*name} capture includes the leading "/" — strip it before lookup.
        String name = filename.startsWith("/") ? filename.substring(1) : filename;
        byte[] content = artifactService.getArtifactBytes(runId, execId, name);
        MediaType contentType = resolveContentType(name);

        return ResponseEntity.ok()
                .contentType(contentType)
                // Prevent XSS: no scripts, styles, or embeds can execute even if the
                // browser renders the content directly (e.g. SVG navigated to by URL).
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:")
                // Prevent MIME-type sniffing so the browser respects our Content-Type.
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }

    private MediaType resolveContentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        for (var entry : MEDIA_TYPES.entrySet()) {
            if (lower.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
