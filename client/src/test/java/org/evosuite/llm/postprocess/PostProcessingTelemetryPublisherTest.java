/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import org.evosuite.statistics.RuntimeVariable;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PostProcessingTelemetryPublisherTest {

    @Test
    void valuesForPublishesTheCompletePostProcessingMetricSetAsZerosWhenInactive() {
        Map<RuntimeVariable, Object> values =
                PostProcessingTelemetryPublisher.valuesFor(
                        new LlmPostProcessor.PostProcessingMetrics("disabled"));

        assertEquals(expectedPostProcessingVariables(), values.keySet());
        for (Map.Entry<RuntimeVariable, Object> entry : values.entrySet()) {
            assertNotNull(entry.getValue(), entry.getKey().name());
        }
        assertEquals("disabled", values.get(RuntimeVariable.LLM_PostProcessing_Skip_Reason));
        assertEquals(0, values.get(RuntimeVariable.LLM_PostProcessing_Requested_Tests));
        assertEquals(0, values.get(RuntimeVariable.LLM_PostProcessing_Assertions_Applied));
    }

    private static EnumSet<RuntimeVariable> expectedPostProcessingVariables() {
        return EnumSet.of(
                RuntimeVariable.LLM_PostProcessing_Skip_Reason,
                RuntimeVariable.LLM_PostProcessing_Requested_Tests,
                RuntimeVariable.LLM_PostProcessing_Requested_Statements,
                RuntimeVariable.LLM_PostProcessing_Initial_Calls,
                RuntimeVariable.LLM_PostProcessing_Repair_Calls,
                RuntimeVariable.LLM_PostProcessing_Repair_Calls_Skipped_Budget,
                RuntimeVariable.LLM_PostProcessing_Accepted_Responses,
                RuntimeVariable.LLM_PostProcessing_Skipped_Tests,
                RuntimeVariable.LLM_PostProcessing_Cap_Skipped_Tests,
                RuntimeVariable.LLM_PostProcessing_Infrastructure_Failures,
                RuntimeVariable.LLM_PostProcessing_Rejected_Edits,
                RuntimeVariable.LLM_PostProcessing_Rejected_Unknown_Ids,
                RuntimeVariable.LLM_PostProcessing_Rejected_Duplicates,
                RuntimeVariable.LLM_PostProcessing_Rejected_Invalid_Fields,
                RuntimeVariable.LLM_PostProcessing_Rejected_Unsupported_Kinds,
                RuntimeVariable.LLM_PostProcessing_Rejected_Limit_Exceeded,
                RuntimeVariable.LLM_PostProcessing_Rejected_Compile,
                RuntimeVariable.LLM_PostProcessing_Rejected_Observed_Execution,
                RuntimeVariable.LLM_PostProcessing_Rejected_Stability_Execution,
                RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks,
                RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Infrastructure,
                RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_No_Accepted,
                RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_All,
                RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Mutation,
                RuntimeVariable.LLM_PostProcessing_Fallback_Assertions_Applied,
                RuntimeVariable.LLM_PostProcessing_Processed_Tests,
                RuntimeVariable.LLM_PostProcessing_Partially_Processed_Tests,
                RuntimeVariable.LLM_PostProcessing_Test_Names_Proposed,
                RuntimeVariable.LLM_PostProcessing_Test_Names_Applied,
                RuntimeVariable.LLM_PostProcessing_Variable_Names_Proposed,
                RuntimeVariable.LLM_PostProcessing_Variable_Names_Applied,
                RuntimeVariable.LLM_PostProcessing_Comments_Proposed,
                RuntimeVariable.LLM_PostProcessing_Comments_Applied,
                RuntimeVariable.LLM_PostProcessing_Section_Breaks_Proposed,
                RuntimeVariable.LLM_PostProcessing_Section_Breaks_Applied,
                RuntimeVariable.LLM_PostProcessing_Assertions_Proposed,
                RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_Initial,
                RuntimeVariable.LLM_PostProcessing_Assertions_Repair_Requested,
                RuntimeVariable.LLM_PostProcessing_Assertions_Proposed_After_Repair,
                RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_After_Repair,
                RuntimeVariable.LLM_PostProcessing_Assertions_Applied,
                RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable,
                RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Compile,
                RuntimeVariable.LLM_PostProcessing_Assertions_Shipped);
    }
}
