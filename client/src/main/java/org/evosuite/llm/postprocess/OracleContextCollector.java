/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.assertion.Assertion;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;

import java.util.List;

/**
 * Production capture boundary for one immutable oracle snapshot.
 *
 * <p>The legacy prompt-context type remains available to replay and unit-test
 * callers, but fresh requests enter through this collector so the production
 * workflow has one named capture operation.</p>
 */
final class OracleContextCollector {

    private OracleContextCollector() {
        // Utility class.
    }

    static OracleContext capture(TestCase test,
                                 ExecutionResult executionResult,
                                 ExecutionResult stabilityExecutionResult,
                                 List<Assertion> candidateAssertions,
                                 PostProcessingOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("Oracle capture requires phase options");
        }
        return OracleContext.from(LlmPostProcessingPromptContext.from(
                test, executionResult, stabilityExecutionResult, candidateAssertions, options));
    }
}
