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
package org.evosuite.llm;

import org.evosuite.Properties;
import org.evosuite.llm.prompt.ParsedFewShotExampleSource;
import org.evosuite.testcase.DefaultTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

class LlmRunLifecycleTest {

    private final Properties.LlmProvider originalProvider = Properties.LLM_PROVIDER;

    @AfterEach
    void cleanup() {
        LlmRunLifecycle.completeRun();
        Properties.LLM_PROVIDER = originalProvider;
    }

    @Test
    void completionRecreatesServiceAndClearsRunCounters() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        LlmService firstRun = LlmService.getInstance();
        LlmStatistics.recordSutConstantsAdded(3L);
        LlmStatistics.recordInitialPopulationCandidatesQueued(2L);

        LlmRunLifecycle.completeRun();

        LlmService secondRun = LlmService.getInstance();
        assertNotSame(firstRun, secondRun);
        assertEquals(0L, LlmStatistics.getSutConstantsAdded());
        assertEquals(0L, LlmStatistics.getInitialPopulationCandidatesQueued());
    }

    @Test
    void completionDropsParsedFewShotExamplesOfThePreviousRun() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        // The cache key is the same in the next run (same target class and seed sources), so
        // without an explicit reset the second run would reuse the first run's loaded tests.
        ParsedFewShotExampleSource.setInstance(
                ParsedFewShotExampleSource.ofTests(Collections.singletonList(new DefaultTestCase())));

        LlmRunLifecycle.completeRun();

        assertTrue(ParsedFewShotExampleSource.getInstance().getParsedTests().isEmpty());
    }
}
