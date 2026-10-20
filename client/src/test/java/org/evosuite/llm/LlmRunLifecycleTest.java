/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
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
