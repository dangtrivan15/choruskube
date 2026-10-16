package com.choruskube.core.dto;

import jakarta.validation.constraints.Positive;

public record TaskIssueClosedRequest(@Positive int issueNumber) {}
