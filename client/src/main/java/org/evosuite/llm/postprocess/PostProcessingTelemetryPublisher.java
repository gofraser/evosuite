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
        publishAssertionReconciliation(0, 0, 0);
    }

    static EnumMap<RuntimeVariable, Object> valuesFor(LlmPostProcessor.PostProcessingMetrics metrics) {
        LlmPostProcessor.DiagnosticCounters diagnosticCounters = metrics.diagnosticCounters;
        LlmPostProcessor.FallbackCounters fallbackCounters = metrics.fallbackCounters;
        EnumMap<RuntimeVariable, Object> values = new EnumMap<>(RuntimeVariable.class);
        values.put(RuntimeVariable.LLM_PostProcessing_Skip_Reason,
                metrics.skipReason == null ? "" : metrics.skipReason);
        values.put(RuntimeVariable.LLM_PostProcessing_Requested_Tests, metrics.requestedTests);
        values.put(RuntimeVariable.LLM_PostProcessing_Requested_Statements, metrics.requestedStatements);
        values.put(RuntimeVariable.LLM_PostProcessing_Initial_Calls, metrics.initialCalls);
        values.put(RuntimeVariable.LLM_PostProcessing_Repair_Calls, metrics.repairCalls);
        values.put(RuntimeVariable.LLM_PostProcessing_Repair_Calls_Skipped_Budget,
                metrics.repairCallsSkippedBudget);
        values.put(RuntimeVariable.LLM_PostProcessing_Accepted_Responses, metrics.acceptedResponses);
        values.put(RuntimeVariable.LLM_PostProcessing_Skipped_Tests, metrics.skippedTests);
        values.put(RuntimeVariable.LLM_PostProcessing_Cap_Skipped_Tests, metrics.capSkippedTests);
        values.put(RuntimeVariable.LLM_PostProcessing_Infrastructure_Failures,
                metrics.infrastructureFailures);
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
        values.put(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Mutation,
                fallbackCounters.mutation);
        values.put(RuntimeVariable.LLM_PostProcessing_Fallback_Assertions_Applied,
                fallbackCounters.assertionsApplied);
        values.put(RuntimeVariable.LLM_PostProcessing_Processed_Tests, metrics.processedTests);
        values.put(RuntimeVariable.LLM_PostProcessing_Partially_Processed_Tests,
                metrics.partiallyProcessedTests);
        values.put(RuntimeVariable.LLM_PostProcessing_Test_Names_Proposed, metrics.testNamesProposed);
        values.put(RuntimeVariable.LLM_PostProcessing_Test_Names_Applied, metrics.testNamesApplied);
        values.put(RuntimeVariable.LLM_PostProcessing_Variable_Names_Proposed,
                metrics.variableNamesProposed);
        values.put(RuntimeVariable.LLM_PostProcessing_Variable_Names_Applied,
                metrics.variableNamesApplied);
        values.put(RuntimeVariable.LLM_PostProcessing_Comments_Proposed, metrics.commentsProposed);
        values.put(RuntimeVariable.LLM_PostProcessing_Comments_Applied, metrics.commentsApplied);
        values.put(RuntimeVariable.LLM_PostProcessing_Section_Breaks_Proposed,
                metrics.sectionBreaksProposed);
        values.put(RuntimeVariable.LLM_PostProcessing_Section_Breaks_Applied,
                metrics.sectionBreaksApplied);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertions_Proposed, metrics.assertionsProposed);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_Initial,
                metrics.assertionsAcceptedInitial);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertions_Repair_Requested,
                metrics.assertionsRepairRequested);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertions_Proposed_After_Repair,
                metrics.assertionsProposedAfterRepair);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_After_Repair,
                metrics.assertionsAcceptedAfterRepair);
        values.put(RuntimeVariable.LLM_PostProcessing_Assertions_Applied, metrics.assertionsApplied);
        return values;
    }

    static void publishAssertionReconciliation(int removedUnstable, int removedCompile, int shipped) {
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable, removedUnstable);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Compile, removedCompile);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Shipped, shipped);
    }
}
