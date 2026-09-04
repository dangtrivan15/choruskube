package com.choruskube.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    @Value("${executor.default-agent-image:}")
    private String defaultAgentImage;

    @Value("${executor.api-server-url:http://localhost:8080}")
    private String apiServerUrl;

    @Bean
    public String executorApiServerUrl() {
        return apiServerUrl;
    }

    @Bean
    public String executorDefaultAgentImage() {
        return defaultAgentImage;
    }
}
