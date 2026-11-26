package com.choruskube.core.architecture.fixtures.e2eguard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/** One class per shape {@code E2eCodeGuardTest} expects the guard to find or to pass. */
public final class E2eGuardFixtures {

    private E2eGuardFixtures() {}

    @Profile("e2e")
    public static class ForE2e {}

    @Profile("!e2e")
    public static class AgainstE2e {}

    @Profile("test & !e2e")
    public static class InAnExpression {}

    public static class WithAnE2eBean {
        @Bean
        @Profile({"test", "e2e"})
        Object e2eBean() {
            return new Object();
        }
    }

    @Profile("test")
    public static class ForAnotherProfile {}

    @Profile("e2e-smoke")
    public static class ForAProfileThatOnlyStartsWithE2e {}

    public static class Unprofiled {}
}
