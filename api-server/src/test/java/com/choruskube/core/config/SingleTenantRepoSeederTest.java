package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SingleTenantRepoSeederTest {

    @Mock
    private GitRepoRepository gitRepoRepository;

    private SingleTenantRepoSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new SingleTenantRepoSeeder(gitRepoRepository);
        ReflectionTestUtils.setField(seeder, "agentImage", "ghcr.io/test/agent:1");
        when(gitRepoRepository.findByUrl(any())).thenReturn(Optional.empty());
    }

    @Test
    void run_repo_isUpserted() {
        when(gitRepoRepository.findByUrl(any())).thenReturn(Optional.empty());

        seeder.run(null);

        ArgumentCaptor<GitRepo> captor = ArgumentCaptor.forClass(GitRepo.class);
        verify(gitRepoRepository).save(captor.capture());
        GitRepo saved = captor.getValue();
        assertThat(saved.getUrl()).isEqualTo("https://github.com/dangtrivan15/choruskube");
        assertThat(saved.getDefaultBranch()).isEqualTo("main");
        assertThat(saved.getAgentImage()).isEqualTo("ghcr.io/test/agent:1");
        assertThat(saved.isEnableDocker()).isTrue();
        assertThat(saved.getTestCommand())
                .isEqualTo("./gradlew test -Pe2e -Dtest.reports.dir=/workspace/out/reports/choruskube");
    }

    /**
     * A Repo Group run eval's every repo's test_command in one pod against one /workspace/out/, so
     * two repos sharing a report root would clobber each other's api-server reports. Pin the last
     * segment of -Dtest.reports.dir to this repo's own name so a repo added later with a missing or
     * copy-pasted infix fails here instead of silently overwriting another repo's artifacts.
     */
    @Test
    void run_testCommand_reportsDirIsInfixedWithThisRepoName() {
        seeder.run(null);

        ArgumentCaptor<GitRepo> captor = ArgumentCaptor.forClass(GitRepo.class);
        verify(gitRepoRepository).save(captor.capture());
        GitRepo saved = captor.getValue();

        Matcher m = Pattern.compile("-Dtest\\.reports\\.dir=(\\S+)").matcher(saved.getTestCommand());
        assertThat(m.find()).as("test_command must set -Dtest.reports.dir").isTrue();
        String reportsDir = m.group(1);
        String repoName = RepoNameUtil.deriveRepoName(saved.getUrl());
        assertThat(repoName).isNotEmpty();
        assertThat(reportsDir.substring(reportsDir.lastIndexOf('/') + 1))
                .as("report root must end with this repo's own name, not another repo's")
                .isEqualTo(repoName);
    }

    @Test
    void run_existingRepo_isUpdated() {
        GitRepo existing = new GitRepo();
        existing.setUrl("https://github.com/dangtrivan15/choruskube");
        existing.setAgentImage("stale-image:old");
        when(gitRepoRepository.findByUrl(any())).thenReturn(Optional.of(existing));

        seeder.run(null);

        ArgumentCaptor<GitRepo> captor = ArgumentCaptor.forClass(GitRepo.class);
        verify(gitRepoRepository).save(captor.capture());
        GitRepo saved = captor.getValue();
        assertThat(saved.getDefaultBranch()).isEqualTo("main");
        assertThat(saved.isEnableDocker()).isTrue();
        assertThat(saved.getAgentImage()).isEqualTo("ghcr.io/test/agent:1");
    }
}
