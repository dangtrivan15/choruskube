package com.choruskube.core.dto;

import java.time.LocalDate;

/** {@code targetDate == null} clears the date; a non-null value sets it (Decision 3). */
public record EpicTargetDateUpdateRequest(LocalDate targetDate) {}
