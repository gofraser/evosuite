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
package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.junit.writer.TestSuiteWriter;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stubs of a functional mock lag behind its last execution until they are completed. Rendering
 * shows them completed without changing the test, so the steps that go on to execute or write a
 * test complete them explicitly.
 */
public class PendingStubCompletionTest {

    public interface Source {
        String name();
    }

    public static class Consumer {
        public static String use(Source first, Source second) {
            return first.name() + second.name();
        }
    }

    private static final String COMPLETED_STUB =
            "doReturn((Object) null, (Object) null).when(pendingStubCompletionTest_Source0).name();";

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        Properties.TARGET_CLASS = PendingStubCompletionTest.class.getName();
        Properties.resetTargetClass();
        TimeController.resetSingleton();
    }

    @AfterEach
    public void tearDown() {
        Properties.getInstance().resetToDefaults();
        Properties.resetTargetClass();
    }

    /** A mock passed twice to a method that calls it, executed but never completed. */
    private static TestCase executedTestWithPendingStubs() throws Exception {
        TestCase tc = new DefaultTestCase();
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, Source.class,
                GenericClassFactory.get(Source.class));
        VariableReference ref = tc.addStatement(mock);
        tc.addStatement(new MethodStatement(tc, new GenericMethod(
                Consumer.class.getMethod("use", Source.class, Source.class), Consumer.class),
                null, Arrays.asList(ref, ref)));
        TestCaseExecutor.runTest(tc);
        assertTrue(mock(tc).doesNeedToUpdateInputs(), "precondition: the stubs are pending");
        return tc;
    }

    private static FunctionalMockStatement mock(TestCase tc) {
        return (FunctionalMockStatement) tc.getStatement(0);
    }

    @Test
    public void completingTheStubsGivesTheCodeThatWasRenderedBefore() throws Exception {
        TestCase tc = executedTestWithPendingStubs();
        String pending = tc.toCode();
        assertTrue(pending.contains(COMPLETED_STUB), pending);

        FunctionalMockStatement.completePendingStubsWithNulls(tc);

        assertFalse(mock(tc).doesNeedToUpdateInputs());
        assertEquals(2, mock(tc).getParameterReferences().size());
        assertEquals(pending, tc.toCode());
    }

    @Test
    public void orderingTestsOfEqualFitnessCompletesTheirStubs() throws Exception {
        TestChromosome first = new TestChromosome();
        first.setTestCase(executedTestWithPendingStubs());
        TestChromosome second = new TestChromosome();
        second.setTestCase(executedTestWithPendingStubs());

        assertEquals(0, first.compareTo(second));

        assertFalse(mock(first.getTestCase()).doesNeedToUpdateInputs());
        assertFalse(mock(second.getTestCase()).doesNeedToUpdateInputs());
    }

    @Test
    public void writingATestCompletesTheStubsOfTheExecutionItWritesFrom(@TempDir Path dir) throws Exception {
        Properties.TEST_SCAFFOLDING = false;
        Properties.NO_RUNTIME_DEPENDENCY = true;
        TestCase tc = executedTestWithPendingStubs();

        TestSuiteWriter writer = new TestSuiteWriter();
        writer.insertAllTests(Collections.singletonList(tc));
        writer.writeTestSuite("PendingStubsTest", dir.toString(), Collections.emptyList());

        Path file;
        try (Stream<Path> files = Files.walk(dir)) {
            file = files.filter(path -> path.endsWith("PendingStubsTest.java")).findFirst().get();
        }
        String written = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(written.contains(COMPLETED_STUB), written);
        assertFalse(mock(tc).doesNeedToUpdateInputs(), "the written test keeps its stubs");
    }

    @Test
    public void minimizationCompletesTheStubsOfAMockItKeeps() throws Exception {
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(executedTestWithPendingStubs());
        chromosome.setLastExecutionResult(TestCaseExecutor.runTest(chromosome.getTestCase()));
        chromosome.setChanged(false);

        // Every statement is needed: no deletion is kept
        new TestCaseMinimizer(new TestFitnessFunction() {
            @Override
            public double getFitness(TestChromosome individual, ExecutionResult result) {
                return individual.size() == 2 ? 0.0 : 1.0;
            }

            @Override
            public int compareTo(TestFitnessFunction other) {
                return 0;
            }

            @Override
            public boolean isMaximizationFunction() {
                return false;
            }

            @Override
            public String getTargetClass() {
                return PendingStubCompletionTest.class.getName();
            }

            @Override
            public String getTargetMethod() {
                return "use";
            }

            @Override
            public int hashCode() {
                return 1;
            }

            @Override
            public boolean equals(Object other) {
                return this == other;
            }
        }).minimize(chromosome);

        assertEquals(2, chromosome.size());
        assertFalse(mock(chromosome.getTestCase()).doesNeedToUpdateInputs());
    }
}
