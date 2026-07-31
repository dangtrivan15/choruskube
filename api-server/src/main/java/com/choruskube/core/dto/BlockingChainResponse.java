package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Readiness;
import java.util.List;
import java.util.UUID;

/** Full blocking-chain view for one Story/Task, rooted at the requested item (blocking-chain
 * feature). {@code blockedBy} is the pruned tree of upstream blockers — see
 * TransitiveReadinessResolver#blockingChainOf for the pruning rule. {@code truncated} is true
 * when the walk hit its node/depth cap before exhausting the real graph, meaning the tree may
 * omit some real blockers. */
public record BlockingChainResponse(
        String itemType,
        UUID itemId,
        String title,
        String status,
        Readiness readiness,
        List<BlockingChainNode> blockedBy,
        boolean truncated) {}
