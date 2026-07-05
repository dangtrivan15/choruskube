package com.choruskube.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocsIndexEntry(String slug, String title, int order, String description) {}
