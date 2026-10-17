package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.model.WorkerCredentialSource;
import com.choruskube.core.repository.SingleFleetCredentialSourceRepository;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Package-local so it can reach {@link TokenDigest#hex}, the same digest {@link
 * WorkerCredentialSources} keys the table by.
 */
class WorkerCredentialSourcesTest extends BaseTest {

    private static final String TOKEN = "ckf_credential_sources_test_token";

    @Autowired
    private SingleFleetCredentialSourceRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    /** This table is shared across the whole suite's single database, not reset between tests by Spring. */
    @BeforeEach
    void clearCredentialSourceTable() {
        jdbc.update("DELETE FROM single_fleet_credential_source");
    }

    /** An {@code ObjectProvider} with no bean of its own: {@code getIfAvailable} always falls to the supplier. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<WorkerCredentialSourceResolver> noBeanProvider() {
        ObjectProvider<WorkerCredentialSourceResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenAnswer(invocation -> {
            Supplier<WorkerCredentialSourceResolver> supplier = invocation.getArgument(0);
            return supplier.get();
        });
        return provider;
    }

    private WorkerCredentialSources fallbackSources() {
        return new WorkerCredentialSources(noBeanProvider(), repository, TOKEN);
    }

    @Test
    void sourceOf_afterRecordingLocal_answersLocalForThatToken() {
        repository.record(TokenDigest.hex(TOKEN), WorkerCredentialSource.LOCAL.wire(), UUID.randomUUID());

        assertThat(fallbackSources().sourceOf(TOKEN)).isEqualTo(WorkerCredentialSource.LOCAL);
    }

    @Test
    void sourceOf_noRowAtAll_answersControlPlane() {
        assertThat(fallbackSources().sourceOf(TOKEN)).isEqualTo(WorkerCredentialSource.CONTROL_PLANE);
    }

    @Test
    void sourceOf_mismatchingCredential_isForbidden() {
        assertThatThrownBy(() -> fallbackSources().sourceOf("ckf_not_the_token"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void sourceOf_blankCredential_isForbidden() {
        assertThatThrownBy(() -> fallbackSources().sourceOf("")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void sourceOf_nullCredential_isForbidden() {
        assertThatThrownBy(() -> fallbackSources().sourceOf(null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void sourceOf_aSecondInstanceOverTheSameRepository_answersTheSameAsTheFirst_survivingARestart() {
        repository.record(TokenDigest.hex(TOKEN), WorkerCredentialSource.LOCAL.wire(), UUID.randomUUID());
        WorkerCredentialSources first = fallbackSources();
        assertThat(first.sourceOf(TOKEN)).isEqualTo(WorkerCredentialSource.LOCAL);

        WorkerCredentialSources second = fallbackSources();

        assertThat(second.sourceOf(TOKEN)).isEqualTo(WorkerCredentialSource.LOCAL);
    }

    @Test
    void sourceOf_withAStubResolverBean_isAnsweredByTheStubNotTheFallback() {
        WorkerCredentialSourceResolver stub = credential -> WorkerCredentialSource.LOCAL;
        ObjectProvider<WorkerCredentialSourceResolver> stubProvider = mock(ObjectProvider.class);
        when(stubProvider.getIfAvailable(any())).thenReturn(stub);
        WorkerCredentialSources sources = new WorkerCredentialSources(stubProvider, repository, TOKEN);

        // No token is configured to match this, and no row exists -- only the stub could answer.
        assertThat(sources.sourceOf("some-other-credential-the-stub-accepts")).isEqualTo(WorkerCredentialSource.LOCAL);
    }

    @Test
    void holdsOwnCredentials_agreesWithSourceOf() {
        repository.record(TokenDigest.hex(TOKEN), WorkerCredentialSource.LOCAL.wire(), UUID.randomUUID());
        WorkerCredentialSources sources = fallbackSources();

        assertThat(sources.holdsOwnCredentials(TOKEN)).isTrue();

        repository.record(TokenDigest.hex(TOKEN), WorkerCredentialSource.CONTROL_PLANE.wire(), UUID.randomUUID());
        WorkerCredentialSources afterChange = fallbackSources();

        assertThat(afterChange.holdsOwnCredentials(TOKEN)).isFalse();
    }
}
