package com.choruskube.core.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@code detail} JSON string passed to {@link AuditSink#record}. Org-free serialization
 * helper used by core record-sites — a before/after diff with optional keys.
 *
 * <p>Returns {@code null} (with a WARN log) on serialization failure so callers never let a
 * serialization error roll back the enclosing transaction.
 */
public final class AuditDetail {

    private static final Logger logger = LoggerFactory.getLogger(AuditDetail.class);

    private AuditDetail() {}

    public static String json(ObjectMapper objectMapper, Object before, Object after) {
        try {
            Map<String, Object> diff = new LinkedHashMap<>();
            if (before != null) diff.put("before", before);
            if (after != null) diff.put("after", after);
            return objectMapper.writeValueAsString(diff);
        } catch (Exception e) {
            logger.warn("Failed to serialize audit detail: {}", e.getMessage());
            return null;
        }
    }
}
