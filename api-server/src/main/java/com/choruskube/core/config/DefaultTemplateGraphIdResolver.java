package com.choruskube.core.config;

import org.springframework.stereotype.Component;

@Component
public class DefaultTemplateGraphIdResolver implements TemplateGraphIdResolver {

    @Override
    public String resolve(String graphId) {
        return graphId;
    }
}
