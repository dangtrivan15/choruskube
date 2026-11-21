package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Closes a Task as delivered by pull requests that already merged outside ChorusKube. {@code note}
 * says where and why, and becomes the Delivery Check run's prompt.
 */
public record TaskDeliveryCheckRequest(
        @NotEmpty @Size(max = 20) List<@NotBlank String> pullRequestUrls,
        @NotBlank @Size(max = 4000) String note) {}
