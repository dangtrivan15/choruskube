package com.choruskube.core.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

public class TestContainersInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        DBTestContainer.start();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                ctx,
                "spring.datasource.url=" + DBTestContainer.getJdbcUrl(),
                "spring.datasource.username=" + DBTestContainer.getUsername(),
                "spring.datasource.password=" + DBTestContainer.getPassword());
    }
}
