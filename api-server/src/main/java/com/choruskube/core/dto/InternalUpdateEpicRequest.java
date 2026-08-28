package com.choruskube.core.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * All fields are optional; absent (null) fields are left unchanged (PATCH semantics). A non-null
 * {@code motivation} of blank/empty string clears the motivation to null.
 *
 * <p>{@code milestoneId} follows the same PATCH semantics: {@code null} means "leave the Epic's
 * current Milestone assignment unchanged" (unlike the standalone {@code PATCH /{id}/milestone}
 * endpoint, which treats {@code null} as "clear the assignment") — this endpoint has no way to
 * distinguish "not supplied" from "explicitly clear" for a nullable field, so clearing a Milestone
 * via this internal path is not supported; use the dedicated milestone-assignment endpoint for
 * that. A non-null value must resolve to a Milestone in the same software project as the Epic.
 */
public record InternalUpdateEpicRequest(
        @Size(max = 255) String title, String description, String motivation, UUID milestoneId) {

    public InternalUpdateEpicRequest(String title, String description, String motivation) {
        this(title, description, motivation, null);
    }
}
