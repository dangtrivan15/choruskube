package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.choruskube.core.BaseTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "auth.enabled=false")
public class LocalOrgIdentitySyncTest extends BaseTest {

    @Autowired
    private OrgIdentitySync orgIdentity;

    @Test
    void lookupsReturnEmptyDefaults() {
        assertThat(orgIdentity.findOrgByAlias("anything")).isEmpty();
        assertThat(orgIdentity.findUserByEmail("anyone@example.com")).isEmpty();
        assertThat(orgIdentity.listOrgMembers(UUID.randomUUID(), 0, 10)).isEmpty();
        assertThat(orgIdentity.listUserOrganizations(UUID.randomUUID())).isEmpty();
        assertThat(orgIdentity.isOrgMember(UUID.randomUUID(), "x@y.z")).isFalse();
        assertThat(orgIdentity.countOrgMembers(UUID.randomUUID())).isEqualTo(0L);
    }

    @Test
    void mutationsDoNotThrow() {
        UUID kcOrgId = UUID.randomUUID();
        UUID kcUserId = UUID.randomUUID();

        assertThatCode(() -> {
                    orgIdentity.createOrganization("anything", "Anything", null);
                    orgIdentity.deleteOrganization(kcOrgId);
                    orgIdentity.createUser("a@b.c", "A", "B");
                    orgIdentity.addOrgMember(kcOrgId, kcUserId);
                    orgIdentity.removeOrgMember(kcOrgId, kcUserId);
                    orgIdentity.assignGlobalRole(kcUserId, "org-admin");
                    orgIdentity.removeGlobalRole(kcUserId, "org-admin");
                })
                .doesNotThrowAnyException();
    }
}
