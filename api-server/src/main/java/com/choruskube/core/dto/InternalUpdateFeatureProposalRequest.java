package com.choruskube.core.dto;

import jakarta.validation.constraints.Size;

/**
 * Internal (agent-facing) request body for updating a feature proposal. All fields are optional;
 * absent (null) fields are left unchanged (PATCH semantics). A non-null {@code motivation} of
 * blank/empty string clears the motivation to null.
 */
public record InternalUpdateFeatureProposalRequest(
        @Size(max = 255) String title, String description, String motivation) {}
