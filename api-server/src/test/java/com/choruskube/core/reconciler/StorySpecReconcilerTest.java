package com.choruskube.core.reconciler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.service.StorySpecLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorySpecReconcilerTest {

    @Mock
    private StorySpecLifecycleService lifecycle;

    private StorySpecReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new StorySpecReconciler(lifecycle);
    }

    /** Recording first lets a revision that just completed release its conflicts in the same pass. */
    @Test
    void reconcile_recordsCompletedDesignsThenProcessesConflicts() {
        when(lifecycle.recordCompletedDesigns()).thenReturn(2);
        when(lifecycle.processConflicts()).thenReturn(1);

        reconciler.reconcile();

        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).recordCompletedDesigns();
        order.verify(lifecycle).processConflicts();
    }

    @Test
    void reconcile_failingStepIsLoggedNotThrown() {
        when(lifecycle.recordCompletedDesigns()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(reconciler::reconcile).doesNotThrowAnyException();

        verify(lifecycle).processConflicts();
    }

    @Test
    void reconcile_failingConflictStepIsLoggedNotThrown() {
        when(lifecycle.processConflicts()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(reconciler::reconcile).doesNotThrowAnyException();

        verify(lifecycle).recordCompletedDesigns();
    }
}
