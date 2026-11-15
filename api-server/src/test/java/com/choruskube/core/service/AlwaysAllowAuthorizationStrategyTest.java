package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.choruskube.core.SharedContextTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class AlwaysAllowAuthorizationStrategyTest extends SharedContextTest {

    @Autowired
    private AuthorizationStrategy strategy;

    @Test
    void checkOrgAccessDoesNotThrowForAnyEntity() {
        UUID someEntity = UUID.randomUUID();
        assertThatCode(() -> strategy.checkOrgAccess("git_repo", someEntity)).doesNotThrowAnyException();
    }

    @Test
    void checkTemplateReadAccessDoesNotThrowEvenForNonSystemTemplate() {
        UUID someEntity = UUID.randomUUID();
        assertThatCode(() -> strategy.checkTemplateReadAccess(false, someEntity))
                .doesNotThrowAnyException();
    }
}
