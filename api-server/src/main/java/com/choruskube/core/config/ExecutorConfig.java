package com.choruskube.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    @Value("${executor.default-agent-image:}")
    private String defaultAgentImage;

    @Bean
    public String executorDefaultAgentImage() {
        return defaultAgentImage;
    }
}
