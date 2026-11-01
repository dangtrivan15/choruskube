package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.choruskube.core.exception.QuotaExceededException;
import org.junit.jupiter.api.Test;

/**
 * An implementation that has not opted into the concurrent-run exemption must still enforce the
 * full run-start quota, so core can ship the exempt entry before any implementation overrides it.
 */
class QuotaCheckerTest {

    @Test
    void checkRunQuotaExceptConcurrency_defaultsToTheFullCheck() {
        QuotaChecker checker = mock(QuotaChecker.class, CALLS_REAL_METHODS);

        checker.checkRunQuotaExceptConcurrency();

        verify(checker).checkRunQuota();
    }

    @Test
    void checkRunQuotaExceptConcurrency_defaultRefusesWhatTheFullCheckRefuses() {
        QuotaChecker checker = mock(QuotaChecker.class, CALLS_REAL_METHODS);
        doThrow(new QuotaExceededException("concurrent runs")).when(checker).checkRunQuota();

        assertThatThrownBy(checker::checkRunQuotaExceptConcurrency).isInstanceOf(QuotaExceededException.class);
    }
}
