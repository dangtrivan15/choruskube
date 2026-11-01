package com.choruskube.core.reconciler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.service.StorySpecLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    void reconcile_recordsCompletedDesigns() {
        when(lifecycle.recordCompletedDesigns()).thenReturn(2);

        reconciler.reconcile();

        verify(lifecycle).recordCompletedDesigns();
    }

    @Test
    void reconcile_failingStepIsLoggedNotThrown() {
        when(lifecycle.recordCompletedDesigns()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(reconciler::reconcile).doesNotThrowAnyException();
    }
}
