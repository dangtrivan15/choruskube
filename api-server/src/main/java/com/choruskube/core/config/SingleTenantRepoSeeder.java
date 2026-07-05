package com.choruskube.core.config;

import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.util.RepoNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Idempotent bootstrap for the choruskube source repo. Runs after Flyway and before any seeder
 * that depends on a system-org repo or template.
 *
 * <p>Each section self-heals on every boot — drift between code-defined expected state and the DB
 * is reconciled toward the code.
 */
@Component
@Order(0)
public class SingleTenantRepoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SingleTenantRepoSeeder.class);

    private static final String CHORUSKUBE_REPO_URL = "https://github.com/dangtrivan15/choruskube";

    private final GitRepoRepository gitRepoRepository;

    @Value("${choruskube.repo.agent-image}")
    private String agentImage;

    public SingleTenantRepoSeeder(GitRepoRepository gitRepoRepository) {
        this.gitRepoRepository = gitRepoRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedRepo();
    }

    private void seedRepo() {
        GitRepo repo = gitRepoRepository.findByUrl(CHORUSKUBE_REPO_URL).orElseGet(() -> {
            log.info("SingleTenantRepoSeeder: creating GitRepo '{}'", CHORUSKUBE_REPO_URL);
            GitRepo newRepo = new GitRepo();
            newRepo.setUrl(CHORUSKUBE_REPO_URL);
            newRepo.setName(RepoNameUtil.deriveOwnerRepoName(CHORUSKUBE_REPO_URL));
            return newRepo;
        });

        repo.setDefaultBranch("main");
        repo.setTestCommand("./gradlew test -Pe2e -Dtest.reports.dir=/workspace/out/reports/api-server");
        repo.setAgentImage(agentImage);
        repo.setEnableDocker(true);
        gitRepoRepository.save(repo);
        log.info("SingleTenantRepoSeeder: upserted GitRepo '{}'", CHORUSKUBE_REPO_URL);
    }
}
