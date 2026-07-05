package com.choruskube.core.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

class NoOpScopeProviderTest {

    @Test
    void scope_returnsNonNullSpecification() {
        NoOpScopeProvider provider = new NoOpScopeProvider();
        Specification<Object> spec = provider.scope(Object.class);
        assertThat(spec).isNotNull();
    }

    @Test
    void scope_predicateIsConjunction() {
        NoOpScopeProvider provider = new NoOpScopeProvider();
        Specification<Object> spec = provider.scope(Object.class);

        @SuppressWarnings("unchecked")
        Root<Object> root = mock(Root.class);
        @SuppressWarnings("unchecked")
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Predicate result = spec.toPredicate(root, query, cb);
        assertThat(result).isSameAs(conjunction);
    }
}
