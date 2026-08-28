package com.choruskube.core.dto;

import java.time.LocalDate;

/** {@code targetDate == null} clears the date; a non-null value sets it. */
public record StoryTargetDateUpdateRequest(LocalDate targetDate) {}
