/* Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors. */
package org.evosuite.llm.postprocess;

import org.evosuite.testsuite.MinimizationResult;

/** Publishes post-processing telemetry as runtime statistics variables. */
final class RuntimePostProcessingTelemetry implements PostProcessingTelemetry {

    @Override
    public void publishMinimizationContext(MinimizationResult minimizationResult) {
        PostProcessingTelemetryPublisher.publishMinimizationContext(minimizationResult);
    }

    @Override
    public void publish(LlmPostProcessor.PostProcessingMetrics metrics) {
        PostProcessingTelemetryPublisher.publish(metrics);
    }

    @Override
    public void publishAssertionReconciliation(int removedUnstable,
                                               int removedCompile,
                                               int shipped) {
        PostProcessingTelemetryPublisher.publishAssertionReconciliation(
                removedUnstable, removedCompile, shipped);
    }
}
