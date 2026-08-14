package com.choruskube.core.service;

import com.choruskube.core.dto.EscalationContext;
import com.choruskube.core.model.NodeExecution;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves a run's {@link EscalationContext}: the escalating execution (the most recent completed
 * {@code escalate} decision) plus its {@code escalation.md} front matter. Shared by {@link
 * PendingGateService} (the Approvals dashboard's pending-gate listing) and {@link RunService} (the
 * Run Detail page's per-node-execution response) so the two gate surfaces can never drift on how
 * this is built.
 *
 * <p>Extracted rather than having one service depend on the other — {@code PendingGateService} is
 * a gate-<em>listing</em> service with a different job (pagination, scope filtering) than resolving
 * a single run's node executions, so neither is a natural home for logic the other also needs.
 */
@Service
public class EscalationContextResolver {

    private static final Logger logger = LoggerFactory.getLogger(EscalationContextResolver.class);

    private final ArtifactResolutionService artifactResolutionService;
    private final ArtifactService artifactService;

    public EscalationContextResolver(
            ArtifactResolutionService artifactResolutionService, ArtifactService artifactService) {
        this.artifactResolutionService = artifactResolutionService;
        this.artifactService = artifactService;
    }

    /**
     * Resolves the escalating execution and its {@code escalation.md} front matter into an {@link
     * EscalationContext}, or {@code null} if nothing has escalated in this run yet — a Supervisor
     * gate with no escalator is not rendered with a half-empty banner.
     *
     * <p>A missing or unreadable {@code escalation.md} degrades only {@code category}/{@code
     * summary} to {@code null}; the escalator's label, exec id, and loop group still come through,
     * because a reviewer who can't see the category must still be able to route.
     */
    public EscalationContext resolve(UUID runId) {
        NodeExecution escalator = artifactResolutionService.resolveEscalatingExecution(runId);
        if (escalator == null) {
            return null;
        }

        String category = null;
        String summary = null;
        try {
            String markdown = artifactService.getArtifactContent(runId, escalator.getId(), "escalation.md");
            category = frontMatterValue(markdown, "category");
            summary = frontMatterValue(markdown, "summary");
        } catch (Exception e) {
            logger.warn("Could not read escalation.md for execution {}: {}", escalator.getId(), e.getMessage());
        }

        return new EscalationContext(
                escalator.getLabel(), escalator.getId(), escalator.getLoopGroup(), category, summary);
    }

    /**
     * Reads one {@code key: value} pair out of a leading {@code ---} front-matter block. Returns
     * {@code null} when the block or the key is absent — deliberately lenient, since the front
     * matter is agent-authored and this must never throw.
     */
    private static String frontMatterValue(String markdown, String key) {
        if (markdown == null || !markdown.startsWith("---")) {
            return null;
        }
        int end = markdown.indexOf("\n---", 3);
        if (end < 0) {
            return null;
        }
        for (String line : markdown.substring(3, end).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                String value = trimmed.substring(key.length() + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
