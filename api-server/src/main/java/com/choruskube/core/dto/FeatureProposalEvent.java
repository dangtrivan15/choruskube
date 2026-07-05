package com.choruskube.core.dto;

import java.util.UUID;

public record FeatureProposalEvent(String type, UUID proposalId, String status) {}
