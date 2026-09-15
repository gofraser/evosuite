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
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.factory.LlmTestChromosomeFactory;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the last leg of frontier wiring: {@code GeneticAlgorithm.initializeLlmAssistance}
 * finds an {@link LlmTestChromosomeFactory} inside the configured chromosome factory chain
 * and replaces its default (empty) goal supplier with the live one, so that once the GA
 * wires up, {@code LlmTestChromosomeFactory.getChromosome()} prompts with DynaMOSA's
 * frontier rather than nothing.
 *
 * <p>There is no getter for the factory's internal supplier, so this is observed the cheap
 * way this factory already supports: with {@code LLM_GOAL_FORMAT=RAW}, the goals collection
 * is rendered via each goal's own {@code toString()}, so capturing the {@link PromptResult}
 * passed to {@link LlmService#query(PromptResult, LlmFeature)} and inspecting its message
 * text is enough to prove which goals actually reached the prompt.
 */
class LlmTestChromosomeFactoryFrontierWiringTest extends MosaTestSupport {

    private boolean savedLlmTestFactory;
    private double savedProbability;
    private Properties.LlmGoalFormat savedGoalFormat;
    private int savedRepairAttempts;

    @BeforeEach
    void saveProperties() {
        savedLlmTestFactory = Properties.LLM_TEST_FACTORY;
        savedProbability = Properties.LLM_TEST_FACTORY_PROBABILITY;
        savedGoalFormat = Properties.LLM_GOAL_FORMAT;
        savedRepairAttempts = Properties.LLM_REPAIR_ATTEMPTS;
        Archive.getArchiveInstance().reset();

        Properties.LLM_TEST_FACTORY = true;
        Properties.LLM_TEST_FACTORY_PROBABILITY = 1.0;
        Properties.LLM_GOAL_FORMAT = Properties.LlmGoalFormat.RAW;
        Properties.LLM_REPAIR_ATTEMPTS = 0;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_TEST_FACTORY = savedLlmTestFactory;
        Properties.LLM_TEST_FACTORY_PROBABILITY = savedProbability;
        Properties.LLM_GOAL_FORMAT = savedGoalFormat;
        Properties.LLM_REPAIR_ATTEMPTS = savedRepairAttempts;
        Archive.getArchiveInstance().reset();
    }

    @Test
    void initializeLlmAssistanceWiresTheFrontierIntoTheTestFactory() {
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.METHOD};

        LlmService mockLlmService = mock(LlmService.class);
        when(mockLlmService.isAvailable()).thenReturn(true);
        when(mockLlmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        when(mockLlmService.query(any(PromptResult.class), eq(LlmFeature.TEST_FACTORY)))
                .thenReturn("not valid java, forces the fallback path");

        ChromosomeFactory<TestChromosome> fallback = TestChromosome::new;
        LlmTestChromosomeFactory factory = new LlmTestChromosomeFactory(
                fallback, mockLlmService, Collections::emptyList);

        WiringDynaMOSA harness = new WiringDynaMOSA(factory);
        harness.goalsManager = new MultiCriteriaManager(Collections.emptyList());

        TestFitnessFunction frontierGoal = CurrentGoalsSupplierTest.simpleGoal("frontier-goal");
        harness.goalsManager.getCurrentGoals().add(frontierGoal);
        harness.publishCurrentGoals();

        // An uncovered archive-only goal, never part of the frontier.
        Archive.getArchiveInstance().addTarget(mock(MethodCoverageTestFitness.class));

        // Before this, the factory only ever saw the empty default supplier passed to its
        // constructor; this call is what a real DynaMOSA.generateSolution() performs.
        harness.wireLlmAssistance();

        factory.getChromosome();

        ArgumentCaptor<PromptResult> promptCaptor = ArgumentCaptor.forClass(PromptResult.class);
        verify(mockLlmService).query(promptCaptor.capture(), eq(LlmFeature.TEST_FACTORY));

        String promptText = promptCaptor.getValue().getMessages().stream()
                .map(LlmMessage::getContent)
                .reduce("", (a, b) -> a + "\n" + b);

        assertTrue(promptText.contains("frontier-goal"),
                "the factory must prompt with the frontier goal once wired: " + promptText);
    }

    @Test
    void beforeWiringTheFactoryPromptsWithNoGoalsAtAll() {
        LlmService mockLlmService = mock(LlmService.class);
        when(mockLlmService.isAvailable()).thenReturn(true);
        when(mockLlmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        when(mockLlmService.query(any(PromptResult.class), eq(LlmFeature.TEST_FACTORY)))
                .thenReturn("not valid java, forces the fallback path");

        ChromosomeFactory<TestChromosome> fallback = TestChromosome::new;
        LlmTestChromosomeFactory factory = new LlmTestChromosomeFactory(
                fallback, mockLlmService, Collections::emptyList);

        // No initializeLlmAssistance call: the factory keeps its constructor-time
        // (empty) supplier.
        factory.getChromosome();

        ArgumentCaptor<PromptResult> promptCaptor = ArgumentCaptor.forClass(PromptResult.class);
        verify(mockLlmService).query(promptCaptor.capture(), eq(LlmFeature.TEST_FACTORY));

        String promptText = promptCaptor.getValue().getMessages().stream()
                .map(LlmMessage::getContent)
                .reduce("", (a, b) -> a + "\n" + b);

        assertFalse(promptText.contains("Uncovered goals:"),
                "with the default empty supplier, no goals section should be added: "
                        + promptText);
    }

    private static class WiringDynaMOSA extends DynaMOSA {
        private static final long serialVersionUID = 1L;

        WiringDynaMOSA(ChromosomeFactory<TestChromosome> factory) {
            super(factory);
        }

        void wireLlmAssistance() {
            initializeLlmAssistance(this::getCurrentGoals, false);
        }
    }
}
