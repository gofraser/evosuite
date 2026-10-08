/* Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors. */
package org.evosuite.llm.postprocess;

import org.evosuite.testsuite.MinimizationResult;

/**
 * Boundary between post-processing state transitions and runtime statistics.
 * The workflow depends on this small contract; the runtime-variable adapter
 * remains replaceable in tests and replay tooling.
 */
interface PostProcessingTelemetry {

    void publishMinimizationContext(MinimizationResult minimizationResult);

    void publish(LlmPostProcessor.PostProcessingMetrics metrics);

    void publishAssertionReconciliation(int removedUnstable, int removedCompile, int shipped);
}
