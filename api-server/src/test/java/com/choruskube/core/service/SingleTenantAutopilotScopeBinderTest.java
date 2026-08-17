package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.scope.NoOpScopeProvider;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core half of the Autopilot scope boundary. There is almost no behaviour to pin — core is
 * single-tenant and has no scope to bind, so the implementation runs the work and stops — which is
 * exactly why most of what matters about this class is structural: the gate a downstream
 * implementation replaces it through, and the transaction it must never open around a pass.
 */
class SingleTenantAutopilotScopeBinderTest {

    private final SingleTenantAutopilotScopeBinder binder = new SingleTenantAutopilotScopeBinder();

    @Test
    void runsTheWorkExactlyOnce() {
        AtomicInteger runs = new AtomicInteger();

        binder.runInScopeOf(UUID.randomUUID(), runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    @Test
    void doesNotSwallowWhatThePassThrows() {
        // The caller isolates passes and re-throws the first failure; both need to see this. A
        // binder that caught it would report every pass as successful and keep the failure breaker
        // from ever noticing a broken installation.
        assertThatThrownBy(() -> binder.runInScopeOf(UUID.randomUUID(), () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void isGatedTheSameWayAsItsSiblingSeams() {
        // A downstream implementation REPLACES this bean. That only works if the gate matches the
        // one every other OSS seam uses — a different property, or a missing matchIfMissing, and
        // the two beans collide at startup or neither is registered at all.
        ConditionalOnProperty gate = SingleTenantAutopilotScopeBinder.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(SingleTenantAutopilotScopeBinder.class.getAnnotation(Component.class))
                .as("an unregistered seam is a NoSuchBeanDefinitionException at startup")
                .isNotNull();
        assertThat(gate).isNotNull();
        for (Class<?> sibling : List.of(NoOpScopeProvider.class, SingleTenantAutopilotResolver.class)) {
            ConditionalOnProperty reference = sibling.getAnnotation(ConditionalOnProperty.class);
            assertThat(gate.name()).isEqualTo(reference.name());
            assertThat(gate.havingValue()).isEqualTo(reference.havingValue());
            assertThat(gate.matchIfMissing()).isEqualTo(reference.matchIfMissing());
        }
    }

    @Test
    void opensNoTransactionAroundThePass() throws NoSuchMethodException {
        // The binder wraps a pass that is four short transactions plus a tick lease, deliberately
        // separate. Both phase templates are PROPAGATION_REQUIRED, so a @Transactional here — or on
        // the class — would merge every phase, every startForAutopilot and the lease's own acquire
        // and release into one long transaction: the exact defect that structure exists to remove.
        // AutopilotService.tick() asserts there is no ambient transaction, but it does so before
        // the binder is reached and so cannot see one opened from in here.
        //
        // If this fails, do not delete the assertion — the annotation is the regression.
        assertThat(SingleTenantAutopilotScopeBinder.class.getAnnotation(Transactional.class))
                .as("a class-level @Transactional wraps the pass just as surely as a method-level one")
                .isNull();
        assertThat(SingleTenantAutopilotScopeBinder.class
                        .getMethod("runInScopeOf", UUID.class, Runnable.class)
                        .getAnnotation(Transactional.class))
                .isNull();
        assertThat(SingleTenantAutopilotScopeBinder.class.getDeclaredConstructors()[0].getParameterTypes())
                .as("nothing to inject is nothing that can bring a transaction with it")
                .isEmpty();
    }
}
