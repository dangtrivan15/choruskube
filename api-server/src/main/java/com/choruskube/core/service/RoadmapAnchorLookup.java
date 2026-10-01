package com.choruskube.core.service;

import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers "is this id an Epic/Story/Task in this run's software project?" for a roadmap proposal's
 * {@code existingId} anchors — shared by {@link RoadmapProposalValidator} and {@link
 * RoadmapCandidatesArtifactResolver}'s anchor fill-in so the two can never disagree.
 *
 * <p>An id that doesn't exist and one that exists in a different project both come back {@link
 * Optional#empty()} — deliberately indistinguishable, so neither call site can be used to confirm
 * whether an id from another project exists at all. A Story has no {@code software_project_id} of
 * its own, so its project is read off its parent Epic — the same one-hop resolution {@code
 * InternalRunService#assertItemInProject} uses.
 */
@Component
public class RoadmapAnchorLookup {

    private final EpicRepository epicRepo;
    private final StoryRepository storyRepo;
    private final TaskRepository taskRepo;

    public RoadmapAnchorLookup(EpicRepository epicRepo, StoryRepository storyRepo, TaskRepository taskRepo) {
        this.epicRepo = epicRepo;
        this.storyRepo = storyRepo;
        this.taskRepo = taskRepo;
    }

    public Optional<Epic> epic(UUID id, UUID projectId) {
        if (id == null || projectId == null) {
            return Optional.empty();
        }
        return epicRepo.findById(id).filter(e -> projectId.equals(e.getSoftwareProjectId()));
    }

    public Optional<Story> story(UUID id, UUID projectId) {
        if (id == null || projectId == null) {
            return Optional.empty();
        }
        return storyRepo.findById(id).filter(s -> epic(s.getEpicId(), projectId).isPresent());
    }

    public Optional<Task> task(UUID id, UUID projectId) {
        if (id == null || projectId == null) {
            return Optional.empty();
        }
        return taskRepo.findById(id).filter(t -> projectId.equals(t.getSoftwareProjectId()));
    }
}
