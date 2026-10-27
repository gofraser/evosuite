/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.statistics.RuntimeVariable;

/**
 * The counters one post-processing phase accumulates. Each one is published as the runtime variable it names;
 * a counter without a variable only steers the phase.
 */
enum PostProcessingCounter {
    REQUESTED_TESTS(RuntimeVariable.LLM_PostProcessing_Requested_Tests),
    REQUESTED_CALLS(null),
    REQUESTED_STATEMENTS(RuntimeVariable.LLM_PostProcessing_Requested_Statements),
    INITIAL_CALLS(RuntimeVariable.LLM_PostProcessing_Initial_Calls),
    REPAIR_CALLS(RuntimeVariable.LLM_PostProcessing_Repair_Calls),
    REPAIR_CALLS_SKIPPED_BUDGET(RuntimeVariable.LLM_PostProcessing_Repair_Calls_Skipped_Budget),
    ACCEPTED_RESPONSES(RuntimeVariable.LLM_PostProcessing_Accepted_Responses),
    SKIPPED_TESTS(RuntimeVariable.LLM_PostProcessing_Skipped_Tests),
    CAP_SKIPPED_TESTS(RuntimeVariable.LLM_PostProcessing_Cap_Skipped_Tests),
    INFRASTRUCTURE_FAILURES(RuntimeVariable.LLM_PostProcessing_Infrastructure_Failures),
    PROCESSED_TESTS(RuntimeVariable.LLM_PostProcessing_Processed_Tests),
    PARTIALLY_PROCESSED_TESTS(RuntimeVariable.LLM_PostProcessing_Partially_Processed_Tests),
    TEST_NAMES_PROPOSED(RuntimeVariable.LLM_PostProcessing_Test_Names_Proposed),
    TEST_NAMES_APPLIED(RuntimeVariable.LLM_PostProcessing_Test_Names_Applied),
    VARIABLE_NAMES_PROPOSED(RuntimeVariable.LLM_PostProcessing_Variable_Names_Proposed),
    VARIABLE_NAMES_APPLIED(RuntimeVariable.LLM_PostProcessing_Variable_Names_Applied),
    COMMENTS_PROPOSED(RuntimeVariable.LLM_PostProcessing_Comments_Proposed),
    COMMENTS_APPLIED(RuntimeVariable.LLM_PostProcessing_Comments_Applied),
    SECTION_BREAKS_PROPOSED(RuntimeVariable.LLM_PostProcessing_Section_Breaks_Proposed),
    SECTION_BREAKS_APPLIED(RuntimeVariable.LLM_PostProcessing_Section_Breaks_Applied),
    ASSERTIONS_PROPOSED(RuntimeVariable.LLM_PostProcessing_Assertions_Proposed),
    ASSERTIONS_ACCEPTED_INITIAL(RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_Initial),
    ASSERTIONS_REPAIR_REQUESTED(RuntimeVariable.LLM_PostProcessing_Assertions_Repair_Requested),
    ASSERTIONS_PROPOSED_AFTER_REPAIR(RuntimeVariable.LLM_PostProcessing_Assertions_Proposed_After_Repair),
    ASSERTIONS_ACCEPTED_AFTER_REPAIR(RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_After_Repair),
    ASSERTIONS_APPLIED(RuntimeVariable.LLM_PostProcessing_Assertions_Applied);

    final RuntimeVariable variable;

    PostProcessingCounter(RuntimeVariable variable) {
        this.variable = variable;
    }
}
