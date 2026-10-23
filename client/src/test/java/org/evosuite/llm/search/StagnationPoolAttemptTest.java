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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** POOL stagnation prompts name their goals as repeat-memory targets, like the async POOL prompts. */
class StagnationPoolAttemptTest {

    private Properties.LlmStagnationPromptMode previousMode;

    @BeforeEach
    void selectPoolMode() {
        previousMode = Properties.LLM_STAGNATION_PROMPT;
        Properties.LLM_STAGNATION_PROMPT = Properties.LlmStagnationPromptMode.POOL;
    }

    @AfterEach
    void restoreMode() {
        Properties.LLM_STAGNATION_PROMPT = previousMode;
    }

    @Test
    void poolPromptAttemptEntersRepeatMemory() {
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        StagnationDetector detector = new StagnationDetector(null, 1, 1, memory);
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.getTargetClass()).thenReturn("com.example.Foo");
        when(goal.getTargetMethod()).thenReturn("doWork()V");
        when(goal.toString()).thenReturn("com.example.Foo.doWork()V");

        PromptResult prompt = detector.buildPrompt(Collections.singleton(goal),
                Collections.emptyList(), 1, 0, null);
        AttemptTicket ticket = detector.registerRepeatedAttempt(prompt, true);

        assertFalse(prompt.getRepeatedInjectionTargets().isEmpty());
        assertTrue(ticket.isTracked(), "The POOL attempt must be tracked in the repeat memory");
        ticket.settleWithoutGain(true);
    }
}
