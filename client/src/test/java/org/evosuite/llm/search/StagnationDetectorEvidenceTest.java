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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The diagnostic prompt must count the current population once: it is the "current" evidence,
 * and the tracker only supplies what earlier stagnation snapshots observed.
 */
class StagnationDetectorEvidenceTest {

    private Properties.LlmStagnationPromptMode previousMode;

    @BeforeEach
    void selectDiagnosticMode() {
        previousMode = Properties.LLM_STAGNATION_PROMPT;
        Properties.LLM_STAGNATION_PROMPT = Properties.LlmStagnationPromptMode.DIAGNOSTIC;
    }

    @AfterEach
    void restoreMode() {
        Properties.LLM_STAGNATION_PROMPT = previousMode;
    }

    @Test
    void singleThrowingAttemptIsNotEnoughEvidenceForExceptionBarrier() {
        StagnationDetector detector = new StagnationDetector(null, 1, 1, new AtomicLong()::get);
        TestFitnessFunction goal = goal();

        PromptResult prompt = detector.buildPrompt(Collections.singleton(goal),
                Collections.singletonList(throwingChromosome()), 0, 0, null);

        assertFalse(hasExceptionBarrier(prompt),
                "One failing attempt must not reach the two-attempt threshold");
    }

    @Test
    void attemptsFromEarlierSnapshotsAddToTheCurrentOne() {
        StagnationDetector detector = new StagnationDetector(null, 1, 1, new AtomicLong()::get);
        TestFitnessFunction goal = goal();
        detector.buildPrompt(Collections.singleton(goal),
                Collections.singletonList(throwingChromosome()), 0, 0, null);

        PromptResult prompt = detector.buildPrompt(Collections.singleton(goal),
                Collections.singletonList(throwingChromosome()), 0, 0, null);

        assertTrue(hasExceptionBarrier(prompt),
                "A failing attempt in each of two snapshots reaches the threshold");
    }

    private static boolean hasExceptionBarrier(PromptResult prompt) {
        List<ProblemCardType> types = prompt == null ? null : prompt.getDiagnosticCardTypes();
        return types != null && types.contains(ProblemCardType.EXCEPTION_BARRIER);
    }

    private static TestFitnessFunction goal() {
        String className = Target.class.getName();
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.getTargetClass()).thenReturn(className);
        when(goal.getTargetMethod()).thenReturn("doWork()V");
        when(goal.toString()).thenReturn(className + ".doWork()V");
        return goal;
    }

    private static TestChromosome throwingChromosome() {
        Method reflected;
        try {
            reflected = Target.class.getDeclaredMethod("doWork");
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
        when(trace.getCoveredMethods()).thenReturn(Collections.singleton(Target.class.getName() + ".doWork"));
        when(result.getTrace()).thenReturn(trace);
        when(result.hasTimeout()).thenReturn(false);
        when(result.hasTestException()).thenReturn(true);
        when(result.getFirstPositionOfThrownException()).thenReturn(0);
        when(result.getExceptionThrownAtPosition(0)).thenReturn(new IllegalStateException("ise"));
        when(result.getExecutedStatements()).thenReturn(1);
        when(testCase.toCode()).thenReturn("target.doWork();");
        when(testCase.size()).thenReturn(1);
        when(testCase.hasStatement(0)).thenReturn(true);
        when(testCase.getStatement(0)).thenReturn(statement);
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }

    private static final class Target {
        void doWork() {
            // no-op
        }
    }
}
