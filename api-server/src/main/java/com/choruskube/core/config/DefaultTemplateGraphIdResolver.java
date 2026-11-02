package com.choruskube.core.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")
public class DefaultTemplateGraphIdResolver implements TemplateGraphIdResolver {

    @Override
    public String resolve(String graphId) {
        return graphId;
    }
}
