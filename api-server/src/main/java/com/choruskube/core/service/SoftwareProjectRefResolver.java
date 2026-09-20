package com.choruskube.core.service;

import com.choruskube.core.dto.RepoRef;
import com.choruskube.core.dto.SoftwareProjectRef;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.SoftwareProject;
import com.choruskube.core.repository.SoftwareProjectRefRow;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves a Task's/Epic's software project into a {@link SoftwareProjectRef} plus its repos for the
 * read paths, tolerating a soft-deleted project. Soft-deleting a project keeps its row so linked
 * work stays readable, so a read that filters the row out (the entity-level
 * {@code @SQLRestriction("deleted_at IS NULL")}) must not 404 the whole board/roadmap. A live
 * project is loaded as an entity (repos resolved as before); a soft-deleted one falls back to a
 * native-query ref with empty repos. {@link NotFoundException} is reserved for an id that names no
 * row at all.
 */
@Component
public class SoftwareProjectRefResolver {

    private final SoftwareProjectRepository softwareProjectRepo;

    public SoftwareProjectRefResolver(SoftwareProjectRepository softwareProjectRepo) {
        this.softwareProjectRepo = softwareProjectRepo;
    }

    public record Resolved(SoftwareProjectRef ref, List<RepoRef> repos) {}

    public Resolved resolve(UUID softwareProjectId) {
        SoftwareProject live = softwareProjectRepo.findById(softwareProjectId).orElse(null);
        if (live != null) {
            return new Resolved(refOf(live), reposOf(live));
        }
        return softwareProjectRepo
                .findRefByIdIncludingDeleted(softwareProjectId)
                .map(SoftwareProjectRefResolver::deletedResolved)
                .orElseThrow(() -> new NotFoundException("SoftwareProject not found: " + softwareProjectId));
    }

    /**
     * Batch form for list endpoints — one query for live projects, one for the soft-deleted
     * remainder. Ids that name no row at all are omitted from the map, leaving the caller to decide
     * whether a missing entry is an error.
     */
    public Map<UUID, Resolved> resolveAll(Collection<UUID> softwareProjectIds) {
        if (softwareProjectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Resolved> out = new LinkedHashMap<>();
        for (SoftwareProject live : softwareProjectRepo.findAllById(softwareProjectIds)) {
            out.put(live.getId(), new Resolved(refOf(live), reposOf(live)));
        }
        List<UUID> softDeleted = softwareProjectIds.stream()
                .filter(id -> !out.containsKey(id))
                .distinct()
                .toList();
        if (!softDeleted.isEmpty()) {
            for (SoftwareProjectRefRow row : softwareProjectRepo.findRefsByIdInIncludingDeleted(softDeleted)) {
                out.put(row.getId(), deletedResolved(row));
            }
        }
        return out;
    }

    private static Resolved deletedResolved(SoftwareProjectRefRow row) {
        return new Resolved(new SoftwareProjectRef(row.getId(), row.getType(), row.getName()), List.of());
    }

    private static SoftwareProjectRef refOf(SoftwareProject project) {
        String type = (project instanceof RepoGroup) ? "repo_group" : "git_repo";
        return new SoftwareProjectRef(project.getId(), type, project.getName());
    }

    private static List<RepoRef> reposOf(SoftwareProject project) {
        return project.resolveRepos().stream()
                .map(g -> new RepoRef(g.getId(), g.getUrl(), RepoNameUtil.deriveRepoName(g.getUrl())))
                .toList();
    }
}
