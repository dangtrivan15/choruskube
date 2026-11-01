package com.choruskube.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RunScopeWebMvcConfig implements WebMvcConfigurer {

    private final RunScopeInterceptor interceptor;

    public RunScopeWebMvcConfig(RunScopeInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/internal/**", "/worker/runs/**");
    }
}
