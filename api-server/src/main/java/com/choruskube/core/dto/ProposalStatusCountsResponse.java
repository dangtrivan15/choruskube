package com.choruskube.core.dto;

import java.util.List;

public record ProposalStatusCountsResponse(long total, List<ProposalStatusCount> statuses) {}
