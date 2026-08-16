package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.Autopilot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class AutopilotRepositoryTest extends BaseTest {

    @Autowired
    private AutopilotRepository repo;

    @Test
    void newRow_defaultsToDisengagedWithParallelismOne() {
        Autopilot saved = repo.saveAndFlush(new Autopilot());

        Autopilot reloaded = repo.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.isEngaged()).isFalse();
        assertThat(reloaded.getMaxParallel()).isEqualTo(1);
        assertThat(reloaded.getConsecutiveFailures()).isZero();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void update_bumpsUpdatedAt() throws Exception {
        Autopilot saved = repo.saveAndFlush(new Autopilot());
        java.time.Instant firstWrite = saved.getUpdatedAt();
        Thread.sleep(5);

        saved.setEngaged(true);
        repo.saveAndFlush(saved);

        assertThat(repo.findById(saved.getId()).orElseThrow().getUpdatedAt()).isAfter(firstWrite);
    }
}
