package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create-only (POST) request body for an Epic. The full PUT edit uses {@link EpicUpdateRequest}
 * instead, which carries no {@code priority} — priority is set once at create time and thereafter
 * moved via {@code PATCH /{id}/priority} only, mirroring how {@code stage} is edit-immutable on the
 * PUT path and moved via {@code PATCH /{id}/stage}.
 *
 * <p>{@code priority} is nullable here: an absent value defaults to {@code Priority.medium} in the
 * service layer.
 */
public record EpicRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String description,
        String motivation,
        @NotNull UUID softwareProjectId,
        Priority priority) {

    public EpicRequest(String title, String description, String motivation, UUID softwareProjectId) {
        this(title, description, motivation, softwareProjectId, null);
    }
}
