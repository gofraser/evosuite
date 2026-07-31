/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.assertion.Assertion;

import java.util.List;

/**
 * Captures an immutable oracle snapshot for one post-processing request.
 */
final class OracleContextFactory {

    private OracleContextFactory() {
        // Utility class.
    }

    static OracleContext capture(TestCase test,
                                 ExecutionResult executionResult,
                                 ExecutionResult stabilityExecutionResult,
                                 List<Assertion> candidateAssertions,
                                 PostProcessingOptions options) {
        return capture(LlmPostProcessingPromptContext.from(
                test, executionResult, stabilityExecutionResult, candidateAssertions, options));
    }

    static OracleContext capture(LlmPostProcessingPromptContext context) {
        return OracleContext.from(context);
    }
}
