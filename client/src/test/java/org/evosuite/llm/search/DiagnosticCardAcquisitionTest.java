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
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins what the stagnation detector and the asynchronous producer acquire as diagnostic cards
 * for the same input: which cards they select and in which order, which counters they record,
 * and how the evidence of earlier snapshots enters the later ones.
 */
class DiagnosticCardAcquisitionTest {

    private static final String[] METHODS = {"first", "second", "third", "fourth"};

    /** The order in which the selector returns the equal-priority cards. */
    private static final List<String> CARDS_BY_PRIORITY = Arrays.asList(
            "EXCEPTION_BARRIER:first()V", "EXCEPTION_BARRIER:fourth()V", "EXCEPTION_BARRIER:second()V");

    private Properties.LlmStagnationPromptMode previousStagnationMode;
    private Properties.LlmAsyncProducerPromptMode previousAsyncMode;

    @BeforeEach
    void selectDiagnosticModes() {
        previousStagnationMode = Properties.LLM_STAGNATION_PROMPT;
        previousAsyncMode = Properties.LLM_ASYNC_PRODUCER_PROMPT;
        Properties.LLM_STAGNATION_PROMPT = Properties.LlmStagnationPromptMode.DIAGNOSTIC;
        Properties.LLM_ASYNC_PRODUCER_PROMPT = Properties.LlmAsyncProducerPromptMode.DIAGNOSTIC;
        LlmStatistics.resetDiagnosticCardCounters();
    }

    @AfterEach
    void restoreModes() {
        Properties.LLM_STAGNATION_PROMPT = previousStagnationMode;
        Properties.LLM_ASYNC_PRODUCER_PROMPT = previousAsyncMode;
        LlmStatistics.resetDiagnosticCardCounters();
    }

    @Test
    void stagnationDetectorSelectsCardsAcrossSnapshots() {
        StagnationDetector detector = new StagnationDetector(null, 1, 1, new AtomicLong()::get);
        List<TestFitnessFunction> goals = goals();
        List<TestChromosome> population = population();

        // The first snapshot has one attempt per method: below the evidence threshold.
        PromptResult first = detector.buildPrompt(goals, population, 0, 0, null);
        assertTrue(first.getDiagnosticCards().isEmpty());
        assertEquals("0/0/0/0", counters());

        // The second snapshot adds the first one's evidence; three of four cards fit the limit.
        PromptResult second = detector.buildPrompt(goals, population, 0, 0, null);
        assertEquals(CARDS_BY_PRIORITY.subList(0, 3).toString() + "@0.150", describe(second.getDiagnosticCards()));
        assertEquals("4/3/1/0", counters());
        assertEquals(
                "7505b9a5bf0a8055907312883d096c9d53f9a1582519aa7330e52a7a1bb99537",
                sha(cardSection(second)));

        PromptResult third = detector.buildPrompt(goals, population, 0, 0, null);
        assertEquals(CARDS_BY_PRIORITY.subList(0, 3).toString() + "@0.175", describe(third.getDiagnosticCards()));
        assertEquals("8/6/2/0", counters());
    }

    @Test
    void asyncProducerSelectsCardsAcrossSnapshots() throws Exception {
        AsyncLlmTestProducer producer = new AsyncLlmTestProducer(null, null, null, 1, 1, 0, null);
        List<TestFitnessFunction> goals = goals();
        List<TestChromosome> population = population();

        assertEquals(Collections.emptyList(), compute(producer, goals, population));

        // The same evidence selects at most two cards, and the producer records no counters.
        List<ProblemCard> second = compute(producer, goals, population);
        assertEquals(CARDS_BY_PRIORITY.subList(0, 2).toString() + "@0.150", describe(second));
        assertEquals(
                "2fedb8359549db9c41b01989c62cc2c5d3f7a50791b1543f28e99a06bf4aea2a",
                sha(cardSection(prompt(producer, goals, second))));

        List<ProblemCard> third = compute(producer, goals, population);
        assertEquals(CARDS_BY_PRIORITY.subList(0, 2).toString() + "@0.175", describe(third));
        assertEquals("0/0/0/0", counters());
    }

    @SuppressWarnings("unchecked")
    private static List<ProblemCard> compute(AsyncLlmTestProducer producer,
                                             Collection<TestFitnessFunction> goals,
                                             List<TestChromosome> population) throws Exception {
        Method method = AsyncLlmTestProducer.class.getDeclaredMethod("maybeComputeDiagnosticCards",
                Collection.class, List.class);
        method.setAccessible(true);
        return (List<ProblemCard>) method.invoke(producer, goals, population);
    }

    private static PromptResult prompt(AsyncLlmTestProducer producer,
                                       Collection<TestFitnessFunction> goals,
                                       List<ProblemCard> cards) throws Exception {
        Method method = AsyncLlmTestProducer.class.getDeclaredMethod("buildDiagnosticPromptForAsync",
                Collection.class, List.class, List.class);
        method.setAccessible(true);
        return (PromptResult) method.invoke(producer, goals, Collections.<TestCase>emptyList(), cards);
    }

    private static String describe(List<ProblemCard> cards) {
        List<String> titles = new ArrayList<>();
        Set<Double> priorities = new LinkedHashSet<>();
        for (ProblemCard card : cards) {
            titles.add(card.getType() + ":" + card.getRelatedGoals().get(0).getTargetMethod());
            priorities.add(card.getPriority());
        }
        return titles + "@" + String.format("%.3f", priorities.iterator().next());
    }

    private static String sha(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String counters() {
        return LlmStatistics.getDiagnosticCardsExtracted() + "/"
                + LlmStatistics.getDiagnosticCardsSelected() + "/"
                + LlmStatistics.getDiagnosticCardsDiscarded() + "/"
                + LlmStatistics.getDiagnosticCardsDeduplicated();
    }

    private static String cardSection(PromptResult prompt) {
        StringBuilder text = new StringBuilder();
        for (LlmMessage message : prompt.getMessages()) {
            String content = message.getContent();
            int start = content.indexOf("Priority problem cards:");
            if (start >= 0) {
                text.append(content.substring(start));
            }
        }
        return text.toString();
    }

    private static List<TestFitnessFunction> goals() {
        List<TestFitnessFunction> goals = new ArrayList<>();
        for (String name : METHODS) {
            String className = Target.class.getName();
            TestFitnessFunction goal = mock(TestFitnessFunction.class);
            when(goal.getTargetClass()).thenReturn(className);
            when(goal.getTargetMethod()).thenReturn(name + "()V");
            when(goal.toString()).thenReturn(className + "." + name + "()V");
            goals.add(goal);
        }
        return goals;
    }

    private static List<TestChromosome> population() {
        List<TestChromosome> population = new ArrayList<>();
        for (String name : METHODS) {
            population.add(throwingChromosome(name));
        }
        return population;
    }

    private static TestChromosome throwingChromosome(String methodName) {
        Method reflected;
        try {
            reflected = Target.class.getDeclaredMethod(methodName);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        MethodStatement statement = mock(MethodStatement.class);
        GenericMethod method = mock(GenericMethod.class);
        VariableReference callee = mock(VariableReference.class);
        when(method.getMethod()).thenReturn(reflected);
        when(method.isStatic()).thenReturn(false);
        when(statement.getMethod()).thenReturn(method);
        when(statement.getParameterReferences()).thenReturn(Collections.emptyList());
        doReturn(Target.class).when(callee).getVariableClass();
        when(statement.getCallee()).thenReturn(callee);

        TestChromosome chromosome = mock(TestChromosome.class);
        TestCase testCase = mock(TestCase.class);
        ExecutionResult result = mock(ExecutionResult.class);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(Collections.singleton(Target.class.getName() + "." + methodName));
        when(result.getTrace()).thenReturn(trace);
        when(result.hasTimeout()).thenReturn(false);
        when(result.hasTestException()).thenReturn(true);
        when(result.getFirstPositionOfThrownException()).thenReturn(0);
        when(result.getExceptionThrownAtPosition(0)).thenReturn(new IllegalStateException("ise " + methodName));
        when(result.getExecutedStatements()).thenReturn(1);
        when(testCase.toCode()).thenReturn("target." + methodName + "();");
        when(testCase.size()).thenReturn(1);
        when(testCase.hasStatement(0)).thenReturn(true);
        when(testCase.getStatement(0)).thenReturn(statement);
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }

    private static final class Target {
        void first() {
            // no-op
        }

        void second() {
            // no-op
        }

        void third() {
            // no-op
        }

        void fourth() {
            // no-op
        }
    }
}
