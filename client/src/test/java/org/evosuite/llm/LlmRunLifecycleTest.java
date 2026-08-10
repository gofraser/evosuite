/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
 */
package org.evosuite.llm;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

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
}
