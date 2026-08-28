package com.choruskube.core.service;

import com.choruskube.core.dto.DocsIndexEntry;
import com.choruskube.core.dto.DocsPageResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class DocsService {

    private final ObjectMapper objectMapper;
    private List<DocsIndexEntry> index;

    public DocsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadIndex() throws IOException {
        ClassPathResource indexResource = new ClassPathResource("docs/index.json");
        if (!indexResource.exists()) {
            throw new IllegalStateException("docs/index.json not found on classpath");
        }
        List<DocsIndexEntry> parsed =
                objectMapper.readValue(indexResource.getInputStream(), new TypeReference<List<DocsIndexEntry>>() {});
        for (DocsIndexEntry entry : parsed) {
            ClassPathResource mdResource = new ClassPathResource("docs/" + entry.slug() + ".md");
            if (!mdResource.exists()) {
                throw new IllegalStateException(
                        "docs/" + entry.slug() + ".md not found on classpath (referenced by index.json)");
            }
        }
        this.index = Collections.unmodifiableList(parsed);
    }

    public List<DocsIndexEntry> getIndex() {
        return index;
    }

    public Optional<DocsPageResponse> getPage(String slug) {
        return index.stream().filter(e -> e.slug().equals(slug)).findFirst().map(e -> {
            try {
                ClassPathResource mdResource = new ClassPathResource("docs/" + slug + ".md");
                try (var stream = mdResource.getInputStream()) {
                    String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    return new DocsPageResponse(e.slug(), e.title(), content);
                }
            } catch (IOException ex) {
                throw new RuntimeException("Failed to read docs/" + slug + ".md", ex);
            }
        });
    }
}
