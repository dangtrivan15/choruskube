package com.choruskube.core.scope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpScopeProvider implements ScopeProvider {
    @Override
    public <T> Specification<T> scope(Class<T> entityType) {
        // No predicate → single-tenant sees everything.
        return (root, query, cb) -> cb.conjunction();
    }
}
