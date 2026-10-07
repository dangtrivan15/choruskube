package com.choruskube.core.service;

import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.exception.ValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Binds a raw roadmap document body by hand rather than through {@code @RequestBody}, so a type
 * error — a non-UUID {@code existingId}/{@code repoId}, a bare array — reaches the caller as a
 * path-prefixed {@code errors[]} entry instead of a 400 that names no field.
 */
final class RoadmapDocumentBinding {

    private RoadmapDocumentBinding() {}

    static RoadmapCandidatesDocument bind(ObjectMapper objectMapper, JsonNode body) {
        try {
            return objectMapper.treeToValue(body, RoadmapCandidatesDocument.class);
        } catch (JsonMappingException e) {
            throw new ValidationException(List.of(jsonPath(e) + ": " + e.getOriginalMessage()));
        } catch (JsonProcessingException e) {
            throw new ValidationException(List.of("document: " + e.getOriginalMessage()));
        }
    }

    /** {@code epics[0].stories[1].existingId}-style path, matching the validator's own prefixes. */
    private static String jsonPath(JsonMappingException e) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "document" : path.toString();
    }
}
