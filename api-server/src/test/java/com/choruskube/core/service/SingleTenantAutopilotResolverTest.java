package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.model.Autopilot;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.scope.NoOpScopeProvider;
import com.choruskube.core.scope.ScopeProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The core half of the Autopilot tenancy seam: which row a caller means, and which rows the
 * scheduler passes over.
 *
 * <p>Two of these are structural rather than behavioural — the gating, and what the constructor is
 * not allowed to reach — because the point of a seam is that a downstream implementation replaces
 * it, and both are conditions such a replacement depends on rather than properties this class alone
 * has.
 */
@ExtendWith(MockitoExtension.class)
class SingleTenantAutopilotResolverTest {

    @Mock
    private AutopilotRepository autopilotRepo;

    private SingleTenantAutopilotResolver newResolver() {
        return new SingleTenantAutopilotResolver(autopilotRepo);
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
    void getOrCreate_withNoRow_insertsAndReportsTheInsert() {
        // `created` is what AutopilotService publishes the ownership event off. Getting it wrong in
        // this direction is a row with no owner, which nothing downstream can resolve.
        when(autopilotRepo.findAll()).thenReturn(List.of());

        AutopilotResolver.Resolved resolved = newResolver().getOrCreateForCurrentScope();

        verify(autopilotRepo).insertDefaults(resolved.id());
        assertThat(resolved.created()).isTrue();
    }

    @Test
    void getOrCreate_withARow_returnsItAndReportsNoInsert() {
        // And wrong in this direction is a second ownership event for a row that already has one.
        Autopilot existing = row(Instant.now(), false);
        when(autopilotRepo.findAll()).thenReturn(List.of(existing));

        AutopilotResolver.Resolved resolved = newResolver().getOrCreateForCurrentScope();

        assertThat(resolved).isEqualTo(new AutopilotResolver.Resolved(existing.getId(), false));
        verify(autopilotRepo, never()).insertDefaults(any());
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
    }

    @Test
    void findAllEngaged_withAnOrphanSecondRow_returnsOnlyTheCanonicalOne() {
        // A first-write race can leave a scope holding two engaged rows. Passing over both would
        // give one installation two concurrent passes, each counting the other's containers as
        // free capacity — max_parallel exceeded for as long as the orphan exists.
        Autopilot canonical = row(Instant.now().minus(Duration.ofHours(1)), true);
        when(autopilotRepo.findAll()).thenReturn(List.of(row(Instant.now(), true), canonical));

        assertThat(newResolver().findAllEngaged())
                .as("one Autopilot per scope, so the budget stays a budget")
                .containsExactly(canonical.getId());
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
    void reachesNeitherRequestScopedStateNorTheEventBus() {
        // Two structural guards in one place, both about what a resolver must NOT be able to do.
        //
        // ScopeProvider: findAllEngaged() runs on the tick's timer thread, and ScopeProvider reads
        // a request-scoped context that throws there.
        //
        // ApplicationEventPublisher: the ownership event belongs to AutopilotService, off the
        // `created` flag this class reports. A resolver that could publish would be a resolver a
        // downstream author could forget to make publish — and a row created without an owner
        // fails nothing at the time, which is what makes the convention version unsafe.
        //
        // If either appears here, do not delete the assertion — the parameter is the regression.
        assertThat(SingleTenantAutopilotResolver.class.getDeclaredConstructors()[0].getParameterTypes())
                .as("a timer thread has no request scope, and the seam does not own the side effect")
                .doesNotContain(ScopeProvider.class, ApplicationEventPublisher.class);
    }

    private static Autopilot row(Instant createdAt, boolean engaged) {
        Autopilot autopilot = new Autopilot();
        autopilot.setId(UUID.randomUUID());
        autopilot.setCreatedAt(createdAt);
        autopilot.setEngaged(engaged);
        return autopilot;
    }
}
