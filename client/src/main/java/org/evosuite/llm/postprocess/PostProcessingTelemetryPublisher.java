/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testsuite.MinimizationResult;

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
        LlmPostProcessor.DiagnosticCounters diagnosticCounters = metrics.diagnosticCounters;
        LlmPostProcessor.FallbackCounters fallbackCounters = metrics.fallbackCounters;
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Skip_Reason,
                metrics.skipReason == null ? "" : metrics.skipReason);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Requested_Tests, metrics.requestedTests);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Requested_Statements, metrics.requestedStatements);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Initial_Calls, metrics.initialCalls);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Repair_Calls, metrics.repairCalls);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Repair_Calls_Skipped_Budget,
                metrics.repairCallsSkippedBudget);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Accepted_Responses, metrics.acceptedResponses);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Skipped_Tests, metrics.skippedTests);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Cap_Skipped_Tests, metrics.capSkippedTests);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Infrastructure_Failures,
                metrics.infrastructureFailures);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Edits, diagnosticCounters.total());
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Unknown_Ids,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.UNKNOWN_ID));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Duplicates,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.DUPLICATE));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Invalid_Fields,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.INVALID_FIELD));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Unsupported_Kinds,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.UNSUPPORTED_KIND));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Limit_Exceeded,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.LIMIT_EXCEEDED));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Compile,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.COMPILE));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Observed_Execution,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Stability_Execution,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.STABILITY_EXECUTION));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks, fallbackCounters.total);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Infrastructure,
                fallbackCounters.infrastructure);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_No_Accepted,
                fallbackCounters.noAccepted);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_All, fallbackCounters.all);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Mutation,
                fallbackCounters.mutation);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Fallback_Assertions_Applied,
                fallbackCounters.assertionsApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Processed_Tests, metrics.processedTests);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Partially_Processed_Tests,
                metrics.partiallyProcessedTests);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Test_Names_Proposed, metrics.testNamesProposed);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Test_Names_Applied, metrics.testNamesApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Variable_Names_Proposed,
                metrics.variableNamesProposed);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Variable_Names_Applied,
                metrics.variableNamesApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Comments_Proposed, metrics.commentsProposed);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Comments_Applied, metrics.commentsApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Section_Breaks_Proposed,
                metrics.sectionBreaksProposed);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Section_Breaks_Applied,
                metrics.sectionBreaksApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Proposed, metrics.assertionsProposed);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_Initial,
                metrics.assertionsAcceptedInitial);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Repair_Requested,
                metrics.assertionsRepairRequested);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Proposed_After_Repair,
                metrics.assertionsProposedAfterRepair);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_After_Repair,
                metrics.assertionsAcceptedAfterRepair);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Applied, metrics.assertionsApplied);
        publishAssertionReconciliation(0, 0, 0);
    }

    static void publishAssertionReconciliation(int removedUnstable, int removedCompile, int shipped) {
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable, removedUnstable);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Compile, removedCompile);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Shipped, shipped);
    }
}
