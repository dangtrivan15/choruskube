package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import java.util.Optional;
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
        // The choruskube repo has no root Gradle wrapper and no -Pe2e property; its
        // documented full regression harness is ./scripts/e2e.sh (CONTRIBUTING.md
        // "End-to-end tests"), run from the repo root. The test node executes this
        // command verbatim from the clone root.
        assertThat(saved.getTestCommand()).isEqualTo("./scripts/e2e.sh");
    }

    @Test
    void run_existingRepo_isUpdated() {
        GitRepo existing = new GitRepo();
        existing.setUrl("https://github.com/dangtrivan15/choruskube");
        when(gitRepoRepository.findByUrl(any())).thenReturn(Optional.of(existing));

        seeder.run(null);

        ArgumentCaptor<GitRepo> captor = ArgumentCaptor.forClass(GitRepo.class);
        verify(gitRepoRepository).save(captor.capture());
        GitRepo saved = captor.getValue();
        assertThat(saved.getDefaultBranch()).isEqualTo("main");
        assertThat(saved.isEnableDocker()).isTrue();
    }
}
