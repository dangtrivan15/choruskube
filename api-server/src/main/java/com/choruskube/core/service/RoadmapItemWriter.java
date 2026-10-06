package com.choruskube.core.service;

import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalCreateDependencyRequest;
import com.choruskube.core.dto.InternalCreateEpicRequest;
import com.choruskube.core.dto.InternalCreateStoryRequest;
import com.choruskube.core.dto.InternalCreateTaskRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import java.util.UUID;

/**
 * The write side of {@link RoadmapCandidateMaterializer}: the project a materialized document
 * lands in, and whose authority creates each row. A gate approval writes on behalf of its run
 * (no request-scoped tenant); a person's import writes as that person, through the same org checks
 * a hand-created item passes. Each method must enforce its own authorization — the materializer
 * trusts the writer to refuse anything outside {@link #softwareProjectId()}'s org.
 */
public interface RoadmapItemWriter {

    UUID softwareProjectId();

    EpicResponse createEpic(InternalCreateEpicRequest request);

    StoryResponse createStory(UUID epicId, InternalCreateStoryRequest request);

    TaskResponse createTask(UUID epicId, UUID storyId, InternalCreateTaskRequest request);

    void createDependency(InternalCreateDependencyRequest request);
}
