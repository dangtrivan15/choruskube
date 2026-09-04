package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllowAllStompSubscriptionAuthorizerTest {

    private final AllowAllStompSubscriptionAuthorizer authorizer = new AllowAllStompSubscriptionAuthorizer();

    @Test
    void allowsAnyDestination() {
        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        assertThat(authorizer.canSubscribe("/topic/runs/" + UUID.randomUUID(), org, user))
                .isTrue();
        assertThat(authorizer.canSubscribe("/topic/orgs/" + UUID.randomUUID() + "/pending-gates", org, user))
                .isTrue();
        assertThat(authorizer.canSubscribe("/topic/git-repos/" + UUID.randomUUID() + "/status", org, user))
                .isTrue();
        assertThat(authorizer.canSubscribe("/topic/anything-at-all", org, user)).isTrue();
    }
}
