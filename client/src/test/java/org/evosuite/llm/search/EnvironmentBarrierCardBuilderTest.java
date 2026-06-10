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

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.statements.environment.AccessedEnvironment;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnvironmentBarrierCardBuilderTest {

    @Test
    void firesForFileAccessReachingTheGoalMethod() {
        ProblemCardExtractor extractor = new ProblemCardExtractor(ExtractorTraceSink.NOOP);
        TestFitnessFunction goal = goal("com.example.Reader", "load()V");

        AccessedEnvironment env = new AccessedEnvironment();
        env.addLocalFiles(Collections.singletonList("/tmp/data.txt"));
        TestChromosome reaching = chromosome(env, Collections.<String>emptySet(),
                Collections.singleton("com.example.Reader.load"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(reaching), 10);

        ProblemCard card = findCard(cards);
        assertNotNull(card, "Expected ENVIRONMENT_BARRIER when a reaching test reads the file system");
        assertEquals(ProblemCardFamily.STRUCTURAL, card.getFamily());
        assertTrue(card.getEvidence().stream().anyMatch(e -> e.contains("/tmp/data.txt")),
                "Evidence should name the accessed file");
    }

    @Test
    void firesForSystemPropertyReads() {
        ProblemCardExtractor extractor = new ProblemCardExtractor(ExtractorTraceSink.NOOP);
        TestFitnessFunction goal = goal("com.example.Config", "resolve()V");

        TestChromosome reaching = chromosome(new AccessedEnvironment(),
                Collections.singleton("user.timezone"),
                Collections.singleton("com.example.Config.resolve"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(reaching), 10);

        ProblemCard card = findCard(cards);
        assertNotNull(card, "Expected ENVIRONMENT_BARRIER when a reaching test reads a system property");
        assertTrue(card.getEvidence().stream().anyMatch(e -> e.contains("user.timezone")),
                "Evidence should name the read property");
    }

    @Test
    void doesNotFireWhenEnvironmentAccessHappensInUnrelatedMethod() {
        ProblemCardExtractor extractor = new ProblemCardExtractor(ExtractorTraceSink.NOOP);
        TestFitnessFunction goal = goal("com.example.Reader", "load()V");

        AccessedEnvironment env = new AccessedEnvironment();
        env.addLocalFiles(Collections.singletonList("/tmp/data.txt"));
        // The environment-accessing test never reaches the goal method.
        TestChromosome unrelated = chromosome(env, Collections.<String>emptySet(),
                Collections.singleton("com.example.Other.somethingElse"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(unrelated), 10);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.ENVIRONMENT_BARRIER),
                "Environment access in a test that never reaches the goal must not raise the card");
    }

    @Test
    void doesNotFireWithoutAnyEnvironmentAccess() {
        ProblemCardExtractor extractor = new ProblemCardExtractor(ExtractorTraceSink.NOOP);
        TestFitnessFunction goal = goal("com.example.Reader", "load()V");

        TestChromosome reaching = chromosome(new AccessedEnvironment(),
                Collections.<String>emptySet(),
                Collections.singleton("com.example.Reader.load"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(reaching), 10);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.ENVIRONMENT_BARRIER),
                "Reaching the goal without any environment read must not raise the card");
    }

    private static ProblemCard findCard(List<ProblemCard> cards) {
        for (ProblemCard card : cards) {
            if (card.getType() == ProblemCardType.ENVIRONMENT_BARRIER) {
                return card;
            }
        }
        return null;
    }

    private static TestFitnessFunction goal(String className, String methodName) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.getTargetClass()).thenReturn(className);
        when(goal.getTargetMethod()).thenReturn(methodName);
        when(goal.toString()).thenReturn(className + "." + methodName);
        return goal;
    }

    private static TestChromosome chromosome(AccessedEnvironment environment,
                                             Set<String> readProperties,
                                             Collection<String> coveredMethods) {
        TestChromosome chromosome = mock(TestChromosome.class);
        TestCase testCase = mock(TestCase.class);
        ExecutionResult result = mock(ExecutionResult.class);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(new java.util.LinkedHashSet<>(coveredMethods));
        when(result.getTrace()).thenReturn(trace);
        when(result.getReadProperties()).thenReturn(readProperties);
        when(testCase.getAccessedEnvironment()).thenReturn(environment);
        when(testCase.size()).thenReturn(0);
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }
}
