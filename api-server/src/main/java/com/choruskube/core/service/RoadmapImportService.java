package com.choruskube.core.service;

import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalCreateDependencyRequest;
import com.choruskube.core.dto.InternalCreateEpicRequest;
import com.choruskube.core.dto.InternalCreateStoryRequest;
import com.choruskube.core.dto.InternalCreateTaskRequest;
import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.RoadmapImportResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person imports a roadmap document — the same schema an agent proposes — straight into a
 * software project, with no run or review gate. All-or-nothing: the document is validated in full
 * first, and any error while materializing rolls the whole import back, so fixing the JSON and
 * importing again never duplicates what an earlier attempt created.
 */
@Service
public class RoadmapImportService {

    private final SoftwareProjectRepository projectRepo;
    private final AuthorizationService authService;
    private final RoadmapProposalValidator validator;
    private final RoadmapCandidateMaterializer materializer;
    private final EpicService epicService;
    private final StoryService storyService;
    private final TaskService taskService;
    private final WorkItemDependencyService dependencyService;
    private final ObjectMapper objectMapper;

    public RoadmapImportService(
            SoftwareProjectRepository projectRepo,
            AuthorizationService authService,
            RoadmapProposalValidator validator,
            RoadmapCandidateMaterializer materializer,
            EpicService epicService,
            StoryService storyService,
            TaskService taskService,
            WorkItemDependencyService dependencyService,
            ObjectMapper objectMapper) {
        this.projectRepo = projectRepo;
        this.authService = authService;
        this.validator = validator;
        this.materializer = materializer;
        this.epicService = epicService;
        this.storyService = storyService;
        this.taskService = taskService;
        this.dependencyService = dependencyService;
        this.objectMapper = objectMapper;
    }

    // One transaction around validate + materialize: every create below joins it, so throwing on
    // any collected error undoes rows, audit entries and ownership mappings alike, and the
    // after-commit roadmap broadcasts never fire.
    @Transactional
    public RoadmapImportResponse importDocument(UUID softwareProjectId, JsonNode body, boolean dryRun) {
        if (!projectRepo.existsById(softwareProjectId)) {
            throw new NotFoundException("Software project not found: " + softwareProjectId);
        }
        // Anchors are only looked up inside this project, so this one check scopes them too.
        authService.checkOrgAccess("software_project", softwareProjectId);

        RoadmapCandidatesDocument doc = RoadmapDocumentBinding.bind(objectMapper, body);
        List<String> violations = validator.validateImport(softwareProjectId, doc);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        List<UUID> createdEpicIds = List.of();
        if (!dryRun) {
            MaterializationSummary result = materializer.materialize(new CallerWriter(softwareProjectId), doc);
            if (!result.errors().isEmpty()) {
                throw new ValidationException(result.errors());
            }
            createdEpicIds = result.createdEpicIds();
        }

        RoadmapProposalValidator.Summary summary = validator.summarize(doc);
        int milestones = doc.milestones() != null ? doc.milestones().size() : 0;
        return new RoadmapImportResponse(
                dryRun,
                milestones,
                summary.newEpics(),
                summary.newStories(),
                summary.newTasks(),
                summary.existingItems(),
                summary.dependencies(),
                createdEpicIds);
    }

    /**
     * Writes as the calling person through the same service methods the REST controllers use, so
     * an imported item passes the same org checks, audit and ownership mapping as a hand-created one.
     */
    private final class CallerWriter implements RoadmapItemWriter {

        private final UUID softwareProjectId;

        private CallerWriter(UUID softwareProjectId) {
            this.softwareProjectId = softwareProjectId;
        }

        @Override
        public UUID softwareProjectId() {
            return softwareProjectId;
        }

        @Override
        public EpicResponse createEpic(InternalCreateEpicRequest request) {
            EpicResponse epic = epicService.create(new EpicRequest(
                    request.title(),
                    request.description(),
                    request.motivation(),
                    softwareProjectId,
                    request.priority()));
            if (request.milestoneId() != null) {
                epic = epicService.assignMilestone(epic.id(), request.milestoneId());
            }
            return epic;
        }

        @Override
        public StoryResponse createStory(UUID epicId, InternalCreateStoryRequest request) {
            return storyService.create(
                    epicId, new StoryRequest(request.title(), request.description(), request.priority()));
        }

        @Override
        public TaskResponse createTask(UUID epicId, UUID storyId, InternalCreateTaskRequest request) {
            return taskService.create(
                    storyId, new TaskRequest(request.title(), request.description(), request.priority()));
        }

        @Override
        public void createDependency(InternalCreateDependencyRequest request) {
            dependencyService.create(new CreateDependencyRequest(
                    request.blockingItemType(),
                    request.blockingItemId(),
                    request.blockedItemType(),
                    request.blockedItemId()));
        }
    }
}
