package com.choruskube.core.architecture;

import com.choruskube.ChorusKubeApplication;
import org.junit.jupiter.api.Test;

/** The production jars carry no e2e bean: those build from src/e2e/java into the e2e image's add-on. */
class E2eCodeStaysOutOfProductionTest {

    @Test
    void mainHoldsNoE2eCode() {
        E2eCodeGuard.requireNone(ChorusKubeApplication.class, "com.choruskube");
    }
}
