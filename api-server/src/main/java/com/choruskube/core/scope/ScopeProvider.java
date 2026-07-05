package com.choruskube.core.scope;

import org.springframework.data.jpa.domain.Specification;

/** Scopes a query to the caller's tenant. Core default = no restriction (single-tenant sees all). */
public interface ScopeProvider {
    <T> Specification<T> scope(Class<T> entityType);
}
