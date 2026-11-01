package com.choruskube.core.config;

/** Picks the template a server-started run uses for a canonical graph id. */
public interface TemplateGraphIdResolver {

    String resolve(String graphId);
}
