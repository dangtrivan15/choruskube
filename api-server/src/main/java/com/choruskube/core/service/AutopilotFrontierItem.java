package com.choruskube.core.service;

import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import java.util.UUID;

/** One thing the Autopilot can start: a READY Task, or the first design of a designable Story. */
sealed interface AutopilotFrontierItem {

    /** The id a start is recorded under. Task and Story ids never collide, so one set holds both. */
    UUID id();

    UUID storyId();

    record TaskItem(Task task) implements AutopilotFrontierItem {

        @Override
        public UUID id() {
            return task.getId();
        }

        @Override
        public UUID storyId() {
            return task.getStoryId();
        }
    }

    record DesignStoryItem(Story story) implements AutopilotFrontierItem {

        @Override
        public UUID id() {
            return story.getId();
        }

        @Override
        public UUID storyId() {
            return story.getId();
        }
    }
}
