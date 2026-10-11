package com.choruskube.core.dto;

import com.choruskube.core.model.GateStamp;
import com.choruskube.core.service.GateDriftCheckService;
import com.choruskube.core.service.GateRecheckService;
import java.time.Instant;
import java.util.UUID;

/** A gate approval held for a re-check, as the run detail page shows it. */
public record GateStampResponse(
        UUID id,
        UUID gateTemplateNodeId,
        String gateLabel,
        String status,
        int recheckRounds,
        int maxRecheckRounds,
        String voidReason,
        boolean supersededByReviewer,
        Instant createdAt,
        Instant updatedAt) {

    public static GateStampResponse from(GateStamp s) {
        return new GateStampResponse(
                s.getId(),
                s.getGateTemplateNodeId(),
                s.getGateLabel(),
                s.getStatus().name(),
                s.getRecheckRounds(),
                GateDriftCheckService.MAX_RECHECK_ROUNDS,
                s.getVoidReason(),
                GateRecheckService.REVIEWER_DECIDED.equals(s.getVoidReason()),
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}
