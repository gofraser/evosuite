/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testsuite.MinimizationResult;

import java.util.EnumMap;
import java.util.Map;

/**
 * Sole adapter from post-processing counters to the externally visible
 * RuntimeVariable contract.
 */
final class PostProcessingTelemetryPublisher {

    private PostProcessingTelemetryPublisher() {
        // Utility class.
    }

    static void publishMinimizationContext(MinimizationResult minimizationResult) {
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Minimization_Status,
                minimizationResult.getStatus().name());
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Minimization_Stop_Cause,
                minimizationResult.getUnderlyingStopCause().name());
    }

    static void publish(LlmPostProcessor.PostProcessingMetrics metrics) {
        for (Map.Entry<RuntimeVariable, Object> entry : valuesFor(metrics).entrySet()) {
            ClientServices.track(entry.getKey(), entry.getValue());
        }
    }

    static EnumMap<RuntimeVariable, Object> valuesFor(LlmPostProcessor.PostProcessingMetrics metrics) {
        LlmPostProcessor.DiagnosticCounters diagnosticCounters = metrics.diagnosticCounters;
        LlmPostProcessor.FallbackCounters fallbackCounters = metrics.fallbackCounters;
        EnumMap<RuntimeVariable, Object> values = new EnumMap<>(RuntimeVariable.class);
        values.put(RuntimeVariable.LLM_PostProcessing_Skip_Reason,
                metrics.skipReason == null ? "" : metrics.skipReason);
        for (PostProcessingCounter counter : PostProcessingCounter.values()) {
            if (counter.variable != null) {
                values.put(counter.variable, metrics.get(counter));
            }
        }
        values.put(RuntimeVariable.LLM_PostProcessing_Rejected_Edits, diagnosticCounters.total());
        values.put(RuntimeVariable.LLM_PostProcessing_Rejected_Unknown_Ids,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.UNKNOWN_ID));
        values.put(RuntimeVariable.LLM_PostProcessing_Rejected_Duplicates,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.DUPLICATE));
        values.put(RuntimeVariable.LLM_PostProcessing_Rejected_Invalid_Fields,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.INVALID_FIELD));
        values.put(RuntimeVariable.LLM_PostProcessing_Rejected_Unsupported_Kinds,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.UNSUPPORTED_KIND));
        values.put(RuntimeVariable.LLM_PostProcessing_Rejected_Limit_Exceeded,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.LIMIT_EXCEEDED));
        values.put(RuntimeVariable.LLM_PostProcessing_Rejected_Compile,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.COMPILE));
        values.put(RuntimeVariable.LLM_PostProcessing_Rejected_Observed_Execution,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION));
        values.put(RuntimeVariable.LLM_PostProcessing_Rejected_Stability_Execution,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.STABILITY_EXECUTION));
        values.put(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks, fallbackCounters.total);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Infrastructure,
                fallbackCounters.infrastructure);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_No_Accepted,
                fallbackCounters.noAccepted);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_All, fallbackCounters.all);
        // The column stays so the statistics CSV layout is unchanged; no fallback uses a mutation strategy.
        values.put(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Mutation, 0);
        values.put(RuntimeVariable.LLM_PostProcessing_Fallback_Assertions_Applied,
                fallbackCounters.assertionsApplied);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable, 0);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Compile, 0);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertions_Shipped, 0);
        return values;
    }

    static void publishAssertionReconciliation(int removedUnstable, int removedCompile, int shipped) {
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable, removedUnstable);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Compile, removedCompile);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Shipped, shipped);
    }
}
