/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.testcase.TestCase;
import org.evosuite.testsuite.MinimizationResult;
import org.evosuite.testsuite.TestSuiteChromosome;

import java.util.Collection;

/**
 * Compatibility-only entry points for callers that have not adopted an
 * explicit phase context yet. New production code should pass a phase context
 * to {@link LlmPostProcessor} instead.
 */
final class LlmPostProcessingLegacyBridge {

    private static final PostProcessingSession SESSION = new PostProcessingSession();

    private LlmPostProcessingLegacyBridge() {
        // Utility class.
    }

    static void publishSkipped(String skipReason, MinimizationResult minimizationResult) {
        PostProcessingTelemetryPublisher.publishMinimizationContext(minimizationResult == null
                ? MinimizationResult.disabled(null)
                : minimizationResult);
        PostProcessingTelemetryPublisher.publish(new LlmPostProcessor.PostProcessingMetrics(skipReason));
    }

    static void publishFinal(TestSuiteChromosome suite, int initiallyAppliedAssertions) {
        PostProcessingAssertionReconciler.Reconciliation reconciliation =
                PostProcessingAssertionReconciler.reconcile(suite, initiallyAppliedAssertions);
        int removedCompile = compileRemovedAssertionCount();
        PostProcessingTelemetryPublisher.publishAssertionReconciliation(
                Math.max(0, reconciliation.removedUnstable() - removedCompile),
                removedCompile, reconciliation.shipped());
        LlmPostProcessor.publishFinalAssertionLifecycle(SESSION, suite);
    }

    static void recordCompileRemoved(Collection<TestCase> removedTests) {
        SESSION.recordCompileRemoved(removedTests);
    }

    static void capture(PostProcessingSession session) {
        SESSION.clear();
        if (session == null) {
            return;
        }
        SESSION.appliedAssertions.addAll(session.appliedAssertions);
        SESSION.compileRemovedAssertions.putAll(session.compileRemovedAssertions);
    }

    static void clear() {
        SESSION.clear();
    }

    private static int compileRemovedAssertionCount() {
        int count = 0;
        for (Integer occurrences : SESSION.compileRemovedAssertions.values()) {
            if (occurrences != null && occurrences > 0) {
                count += occurrences;
            }
        }
        return count;
    }
}
