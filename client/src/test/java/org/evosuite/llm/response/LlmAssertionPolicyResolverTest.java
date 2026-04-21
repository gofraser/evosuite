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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmAssertionPolicyResolverTest {

    private final Properties.LlmGeneratedAssertionsPolicy original =
            Properties.LLM_GENERATED_ASSERTIONS_POLICY;

    @AfterEach
    void restore() {
        Properties.LLM_GENERATED_ASSERTIONS_POLICY = original;
    }

    @Test
    void autoKeepsAssertionsForLlmStrategyContext() {
        Properties.LLM_GENERATED_ASSERTIONS_POLICY = Properties.LlmGeneratedAssertionsPolicy.AUTO;
        assertEquals(Properties.LlmGeneratedAssertionsPolicy.KEEP,
                LlmAssertionPolicyResolver.resolve(true));
        assertTrue(LlmAssertionPolicyResolver.keepAssertions(true));
    }

    @Test
    void autoDropsAssertionsForSearchContext() {
        Properties.LLM_GENERATED_ASSERTIONS_POLICY = Properties.LlmGeneratedAssertionsPolicy.AUTO;
        assertEquals(Properties.LlmGeneratedAssertionsPolicy.DROP,
                LlmAssertionPolicyResolver.resolve(false));
        assertFalse(LlmAssertionPolicyResolver.keepAssertions(false));
    }

    @Test
    void explicitPolicyOverridesContext() {
        Properties.LLM_GENERATED_ASSERTIONS_POLICY = Properties.LlmGeneratedAssertionsPolicy.KEEP;
        assertTrue(LlmAssertionPolicyResolver.keepAssertions(false));

        Properties.LLM_GENERATED_ASSERTIONS_POLICY = Properties.LlmGeneratedAssertionsPolicy.DROP;
        assertFalse(LlmAssertionPolicyResolver.keepAssertions(true));
    }
}

