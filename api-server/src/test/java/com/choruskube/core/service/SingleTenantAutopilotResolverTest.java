package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.model.Autopilot;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.scope.NoOpScopeProvider;
import com.choruskube.core.scope.ScopeProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The core half of the Autopilot tenancy seam: which row a caller means, and which rows the
 * scheduler passes over.
 *
 * <p>Two of these are structural rather than behavioural — the gating and the absence of a {@code
 * ScopeProvider} — because the point of a seam is that a downstream implementation can replace it,
 * and both are conditions a replacement depends on rather than properties this class alone has.
 */
@ExtendWith(MockitoExtension.class)
class SingleTenantAutopilotResolverTest {

    @Mock
    private AutopilotRepository autopilotRepo;

    private final List<Object> published = new ArrayList<>();

    private SingleTenantAutopilotResolver newResolver() {
        return new SingleTenantAutopilotResolver(autopilotRepo, published::add);
    }

    // -----------------------------------------------------------------------------------
    // forCurrentScope
    // -----------------------------------------------------------------------------------

    @Test
    void forCurrentScope_withNoRow_isEmpty() {
        when(autopilotRepo.findAll()).thenReturn(List.of());

        assertThat(newResolver().forCurrentScope()).isEmpty();
    }

    @Test
    void forCurrentScope_withTwoRows_isAlwaysTheOlder() {
        // A concurrent first-write can leave a second row behind. Ordering rather than "whichever
        // came back first" is what keeps every replica agreeing on which one is the Autopilot.
        Autopilot older = row(Instant.now().minus(Duration.ofHours(1)), true);
        Autopilot newer = row(Instant.now(), true);
        when(autopilotRepo.findAll()).thenReturn(List.of(newer, older));

        assertThat(newResolver().forCurrentScope()).contains(older.getId());
    }

    // -----------------------------------------------------------------------------------
    // getOrCreateForCurrentScope
    // -----------------------------------------------------------------------------------

    @Test
    void getOrCreate_withNoRow_insertsAndPublishesTheOwnershipEvent() {
        // The publish is the whole reason creation is a seam method. Downstream it is what writes
        // the row's ownership; without it the row exists and the scope provider cannot resolve it.
        when(autopilotRepo.findAll()).thenReturn(List.of());

        UUID id = newResolver().getOrCreateForCurrentScope();

        verify(autopilotRepo).insertDefaults(id);
        assertThat(published)
                .as("\"autopilot\" is a cross-repository contract — the ownership writer switches on it")
                .containsExactly(MappableCreated.of("autopilot", id));
    }

    @Test
    void getOrCreate_withARow_returnsItAndInsertsNothing() {
        Autopilot existing = row(Instant.now(), false);
        when(autopilotRepo.findAll()).thenReturn(List.of(existing));

        assertThat(newResolver().getOrCreateForCurrentScope()).isEqualTo(existing.getId());

        verify(autopilotRepo, never()).insertDefaults(any());
        assertThat(published)
                .as("no row was created, so nothing acquired an owner")
                .isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // findAllEngaged
    // -----------------------------------------------------------------------------------

    @Test
    void findAllEngaged_whenEngaged_returnsTheRow() {
        Autopilot engaged = row(Instant.now(), true);
        when(autopilotRepo.findAll()).thenReturn(List.of(engaged));

        assertThat(newResolver().findAllEngaged()).containsExactly(engaged.getId());
    }

    @Test
    void findAllEngaged_whenDisengaged_isEmpty() {
        when(autopilotRepo.findAll()).thenReturn(List.of(row(Instant.now(), false)));

        assertThat(newResolver().findAllEngaged()).isEmpty();
    }

    @Test
    void findAllEngaged_withNoRow_isEmpty() {
        when(autopilotRepo.findAll()).thenReturn(List.of());

        assertThat(newResolver().findAllEngaged()).isEmpty();
    }

    @Test
    void findAllEngaged_neverCreatesTheRow() {
        // The scheduler calls this from a timer thread, where the ownership event that must
        // accompany an insert cannot be resolved. Finding is all it may do.
        when(autopilotRepo.findAll()).thenReturn(List.of());

        newResolver().findAllEngaged();

        verify(autopilotRepo, never()).insertDefaults(any());
        assertThat(published).isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // The seam itself
    // -----------------------------------------------------------------------------------

    @Test
    void isGatedTheSameWayAsItsSiblingSeams() {
        // A downstream implementation REPLACES this bean. That only works if the gate matches the
        // one every other OSS seam uses — a different property, or a missing matchIfMissing, and
        // the two beans collide at startup or neither is registered at all.
        ConditionalOnProperty gate = SingleTenantAutopilotResolver.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(SingleTenantAutopilotResolver.class.getAnnotation(Component.class))
                .as("an unregistered seam is a NoSuchBeanDefinitionException at startup")
                .isNotNull();
        assertThat(gate).isNotNull();
        for (Class<?> sibling : List.of(NoOpScopeProvider.class, AllEpicsCandidateSource.class)) {
            ConditionalOnProperty reference = sibling.getAnnotation(ConditionalOnProperty.class);
            assertThat(gate.name()).isEqualTo(reference.name());
            assertThat(gate.havingValue()).isEqualTo(reference.havingValue());
            assertThat(gate.matchIfMissing()).isEqualTo(reference.matchIfMissing());
        }
    }

    @Test
    void readsNoRequestScopedTenantState() {
        // findAllEngaged() runs on the tick's timer thread, and ScopeProvider reads a
        // request-scoped context that throws there. Having no way to reach one is the enforcement;
        // the interface's javadoc is only the instruction.
        assertThat(SingleTenantAutopilotResolver.class.getDeclaredConstructors()[0].getParameterTypes())
                .as("see AutopilotResolver#findAllEngaged — a timer thread has no request scope")
                .doesNotContain(ScopeProvider.class);
    }

    private static Autopilot row(Instant createdAt, boolean engaged) {
        Autopilot autopilot = new Autopilot();
        autopilot.setId(UUID.randomUUID());
        autopilot.setCreatedAt(createdAt);
        autopilot.setEngaged(engaged);
        return autopilot;
    }
}
