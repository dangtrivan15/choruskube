package com.choruskube.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.architecture.fixtures.e2eguard.E2eGuardFixtures;
import org.junit.jupiter.api.Test;

class E2eCodeGuardTest {

    private static final String FIXTURES = "com.choruskube.core.architecture.fixtures.e2eguard";

    @Test
    void findsEveryShapeOfE2eCodeAndNothingElse() {
        assertThat(E2eCodeGuard.findings(E2eGuardFixtures.class, FIXTURES))
                .containsExactly(
                        FIXTURES + ".E2eGuardFixtures$AgainstE2e: @Profile names e2e",
                        FIXTURES + ".E2eGuardFixtures$ForE2e: @Profile names e2e",
                        FIXTURES + ".E2eGuardFixtures$InAnExpression: @Profile names e2e",
                        FIXTURES + ".E2eGuardFixtures$WithAnE2eBean.e2eBean(): @Profile names e2e",
                        FIXTURES + ".e2e.InAnE2ePackage: in an e2e package");
    }

    @Test
    void failsNamingTheRule() {
        assertThatThrownBy(() -> E2eCodeGuard.requireNone(E2eGuardFixtures.class, FIXTURES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("move it to src/e2e/java")
                .hasMessageContaining(E2eCodeGuard.RULE_URL);
    }

    @Test
    void refusesToCheckNothing() {
        assertThatThrownBy(() -> E2eCodeGuard.findings(E2eGuardFixtures.class, "com.example.absent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Imported no class under com.example.absent");
    }
}
