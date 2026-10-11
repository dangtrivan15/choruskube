package com.choruskube.core.service;

import com.choruskube.core.dto.RecordRepoBasesRequest;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.NodeExecutionRepoBase;
import com.choruskube.core.repository.NodeExecutionRepoBaseRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.util.NodeExecutionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records which default-branch commit an agent pod's clones sat on; read by the approval gate. */
@Service
public class NodeExecutionRepoBaseService {

    static final Set<String> OUTCOMES = Set.of("clean", "conflict", "unavailable");

    private final NodeExecutionRepository execRepo;
    private final NodeExecutionRepoBaseRepository baseRepo;
    private final ObjectMapper objectMapper;

    public NodeExecutionRepoBaseService(
            NodeExecutionRepository execRepo, NodeExecutionRepoBaseRepository baseRepo, ObjectMapper objectMapper) {
        this.execRepo = execRepo;
        this.baseRepo = baseRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(UUID runId, UUID nodeExecId, RecordRepoBasesRequest request) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        NodeExecutionUtil.requireInRun(exec, runId);
        for (RecordRepoBasesRequest.RepoBaseReport report : request.repos()) {
            if (!OUTCOMES.contains(report.outcome())) {
                throw new BadRequestException(
                        "Unknown outcome '" + report.outcome() + "' (expected clean, conflict or unavailable)");
            }
            boolean clean = "clean".equals(report.outcome());
            if (clean && (report.baseSha() == null || report.baseSha().isBlank())) {
                throw new BadRequestException("A clean outcome needs a baseSha");
            }
            NodeExecutionRepoBase row = baseRepo.findByNodeExecutionIdAndGitRepoId(nodeExecId, report.gitRepoId())
                    .orElseGet(NodeExecutionRepoBase::new);
            row.setNodeExecutionId(nodeExecId);
            row.setGitRepoId(report.gitRepoId());
            row.setOutcome(report.outcome());
            row.setBaseSha(clean ? report.baseSha() : null);
            row.setDefaultBranch(
                    report.defaultBranch() == null || report.defaultBranch().isBlank() ? null : report.defaultBranch());
            row.setConflictedFiles(json(report.conflictedFiles() == null ? List.of() : report.conflictedFiles()));
            baseRepo.save(row);
        }
    }

    private String json(List<String> files) {
        try {
            return objectMapper.writeValueAsString(files);
        } catch (Exception e) {
            throw new BadRequestException("conflictedFiles is not a list of paths");
        }
    }
}
