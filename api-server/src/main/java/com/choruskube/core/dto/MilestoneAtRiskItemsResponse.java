package com.choruskube.core.dto;

import java.util.List;

/**
 * {@code items} is sorted by {@code targetDate} ascending, then Epics before Stories on a tie.
 */
public record MilestoneAtRiskItemsResponse(List<AtRiskItem> items) {}
