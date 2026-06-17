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
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.response;

import org.evosuite.Properties;

/**
 * Resolves effective behavior for handling LLM-generated assertions.
 */
public final class LlmAssertionPolicyResolver {

    private LlmAssertionPolicyResolver() {
        // utility class
    }

    /**
     * Resolves the effective assertion policy for the given integration context.
     *
     * @param llmStrategyContext true for STRATEGY=LLMSTRATEGY generation paths
     * @return resolved policy
     */
    public static Properties.LlmGeneratedAssertionsPolicy resolve(boolean llmStrategyContext) {
        Properties.LlmGeneratedAssertionsPolicy configured =
                Properties.LLM_GENERATED_ASSERTIONS_POLICY;
        if (configured == null || configured == Properties.LlmGeneratedAssertionsPolicy.AUTO) {
            return llmStrategyContext
                    ? Properties.LlmGeneratedAssertionsPolicy.KEEP
                    : Properties.LlmGeneratedAssertionsPolicy.DROP;
        }
        return configured;
    }

    /**
     * @param llmStrategyContext true for STRATEGY=LLMSTRATEGY generation paths
     * @return true if parsed assertions should be kept
     */
    public static boolean keepAssertions(boolean llmStrategyContext) {
        return resolve(llmStrategyContext) == Properties.LlmGeneratedAssertionsPolicy.KEEP;
    }

    /**
     * @param llmStrategyContext true for STRATEGY=LLMSTRATEGY generation paths
     * @return instruction suffix to bias LLM assertion generation
     */
    public static String instructionSuffix(boolean llmStrategyContext) {
        if (keepAssertions(llmStrategyContext)) {
            return " Every generated test must include meaningful behavioral assertions "
                    + "for observable results, state changes, or expected exceptions. "
                    + "Do not emit assertion-free smoke tests.";
        }
        return " Do NOT include assertions (no assert* calls or Java assert statements).";
    }
}
