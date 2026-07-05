package com.choruskube.core.service;

import java.util.UUID;

public interface OrgReadinessGate {

    void assertReadyForCreate();

    void assertNoRunningJobsForDockerToggle(UUID gitRepoId);
}
