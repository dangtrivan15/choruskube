package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/** Every reconciler ticks through {@link SchedulingConfig}, so a default that left it off would stop them all. */
class SchedulingConfigTest {

    static class Ticker {
        final CountDownLatch ticked = new CountDownLatch(1);

        @Scheduled(fixedDelay = 10)
        void tick() {
            ticked.countDown();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class)
            .withBean(Ticker.class);

    @Test
    void scheduledMethodsTickWhenThePropertyIsUnset() {
        runner.run(context -> assertThat(context.getBean(Ticker.class).ticked.await(5, TimeUnit.SECONDS))
                .isTrue());
    }

    @Test
    void scheduledMethodsAreNeverRegisteredWhenSchedulingIsOff() {
        runner.withPropertyValues("choruskube.scheduling.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
    }
}
