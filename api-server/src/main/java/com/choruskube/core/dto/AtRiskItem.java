package com.choruskube.core.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One at-risk Epic or Story under a Milestone — {@code targetDate} strictly before today AND
 * {@code RollupCalculator#effectiveStatus} not {@code done} (see {@code
 * MilestoneResponse#atRiskItemCount}). Served by the drill-down endpoint {@code
 * GET /milestones/{id}/at-risk-items}.
 *
 * @param tier {@code "EPIC"} or {@code "STORY"} — which tier this item belongs to.
 * @param status The item's {@code RollupCalculator#effectiveStatus} name at read time.
 */
public record AtRiskItem(UUID id, String tier, String title, LocalDate targetDate, String status) {}
