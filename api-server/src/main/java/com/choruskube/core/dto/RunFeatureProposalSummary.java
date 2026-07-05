package com.choruskube.core.dto;

import jakarta.annotation.Nullable;
import java.util.UUID;

public record RunFeatureProposalSummary(
        UUID id, String title, String status, @Nullable SoftwareProjectRef softwareProject) {}
