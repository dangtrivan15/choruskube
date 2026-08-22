package com.choruskube.core.dto;

import java.util.List;

/**
 * Response body for {@code GET /milestones/{id}/at-risk-items} — the drill-down detail behind
 * {@code MilestoneResponse#atRiskItemCount}. {@code items} is sorted by {@code targetDate}
 * ascending, then Epics before Stories on a tie.
 */
public record MilestoneAtRiskItemsResponse(List<AtRiskItem> items) {}
