package com.choruskube.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** What an agent pod's clone sat on, per repo — posted once by the entrypoint after cloning. */
public record RecordRepoBasesRequest(@NotNull @Valid List<RepoBaseReport> repos) {

    /**
     * {@code defaultBranch} stays optional: rejecting records from agent images that predate it
     * would leave their repos unrecorded.
     */
    public record RepoBaseReport(
            @NotNull UUID gitRepoId,
            @NotBlank String outcome,
            String baseSha,
            String defaultBranch,
            List<String> conflictedFiles) {}
}
