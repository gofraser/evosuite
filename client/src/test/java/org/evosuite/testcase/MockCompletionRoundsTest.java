/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A call chain through functional mocks needs one mock update per link, because a stub only
 * returns a fresh mock whose own calls are observed by the next execution.
 */
public class MockCompletionRoundsTest {

    public interface App {
        Level1 getA();
    }

    public interface Level1 {
        Level2 getB();
    }

    public interface Level2 {
        String name();
    }

    public static class C {
        public static int use(App app) {
            if (app.getA() == null) {
                throw new IllegalStateException("no A");
            }
            if (app.getA().getB() == null) {
                throw new IllegalStateException("no B");
            }
            return 1;
        }
    }

    private int oldRounds;
    private double oldContinueProbability;

    @Before
    public void setUp() {
        oldRounds = Properties.FUNCTIONAL_MOCK_COMPLETION_ROUNDS;
        oldContinueProbability = Properties.FUNCTIONAL_MOCK_COMPLETION_CONTINUE_PROBABILITY;
        Properties.FUNCTIONAL_MOCK_COMPLETION_CONTINUE_PROBABILITY = 1.0;
    }

    @After
    public void tearDown() {
        Properties.FUNCTIONAL_MOCK_COMPLETION_ROUNDS = oldRounds;
        Properties.FUNCTIONAL_MOCK_COMPLETION_CONTINUE_PROBABILITY = oldContinueProbability;
    }

    private boolean succeedsAfterOneMockChange(int rounds) throws Exception {
        Properties.FUNCTIONAL_MOCK_COMPLETION_ROUNDS = rounds;
        TestCase test = new DefaultTestCase();
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                test, App.class, GenericClassFactory.get(App.class));
        VariableReference mock = test.addStatement(mockStatement);
        test.addStatement(new MethodStatement(test,
                new GenericMethod(C.class.getMethod("use", App.class), C.class),
                null, Collections.singletonList(mock)));

        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);
        chromosome.setLastExecutionResult(TestCaseExecutor.runTest(test));
        assertFalse(chromosome.getLastExecutionResult().noThrownExceptions());

        assertTrue(chromosome.mockChange());
        return TestCaseExecutor.runTest(chromosome.getTestCase()).noThrownExceptions();
    }

    @Test
    public void threeRoundsCompleteTheChainInOneStep() throws Exception {
        assertTrue(succeedsAfterOneMockChange(3));
    }

    public static class Failing {
        public static void fail() {
            throw new IllegalStateException("stub value cannot be built");
        }
    }

    @Test
    public void aPassThatAbortsTheTestIsUndone() throws Exception {
        Properties.FUNCTIONAL_MOCK_COMPLETION_ROUNDS = 3;
        TestCase test = new DefaultTestCase();
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                test, App.class, GenericClassFactory.get(App.class));
        VariableReference mock = test.addStatement(mockStatement);
        test.addStatement(new MethodStatement(test,
                new GenericMethod(C.class.getMethod("use", App.class), C.class),
                null, Collections.singletonList(mock)));

        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);
        chromosome.setLastExecutionResult(TestCaseExecutor.runTest(test));

        // The second pass adds a statement that throws, as the construction of a stub value does
        // when the constructor of the value fails.
        int[] passes = new int[1];
        assertTrue(chromosome.mockChange(added -> {
            if (++passes[0] < 2) {
                return chromosome.mockChangePass(added);
            }
            try {
                chromosome.getTestCase().addStatement(new MethodStatement(chromosome.getTestCase(),
                        new GenericMethod(Failing.class.getMethod("fail"), Failing.class),
                        null, Collections.emptyList()), 0);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            added[0] = true;
            return true;
        }));

        // The first pass stubbed the call chain; the failing statement of the second pass is gone.
        for (int i = 0; i < chromosome.getTestCase().size(); i++) {
            assertFalse(chromosome.getTestCase().getStatement(i).toString().contains("fail"));
        }
        assertFalse(chromosome.getLastExecutionResult().getAllThrownExceptions().stream()
                .anyMatch(e -> e instanceof IllegalStateException
                        && "stub value cannot be built".equals(e.getMessage())));
    }

    @Test
    public void oneRoundDoesNotCompleteTheChain() throws Exception {
        assertFalse(succeedsAfterOneMockChange(1));
    }

    @Test
    public void furtherRoundsAreSkippedWhenNeverDrawn() throws Exception {
        Properties.FUNCTIONAL_MOCK_COMPLETION_CONTINUE_PROBABILITY = 0.0;
        assertFalse(succeedsAfterOneMockChange(3));
    }

    /** Counts how often the test is copied. */
    private static class CountingTestCase extends DefaultTestCase {
        int clones;

        @Override
        public DefaultTestCase clone() {
            clones++;
            return super.clone();
        }
    }

    private static TestChromosome chainChromosome(TestCase test) throws Exception {
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                test, App.class, GenericClassFactory.get(App.class));
        VariableReference mock = test.addStatement(mockStatement);
        test.addStatement(new MethodStatement(test,
                new GenericMethod(C.class.getMethod("use", App.class), C.class),
                null, Collections.singletonList(mock)));
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);
        chromosome.setLastExecutionResult(TestCaseExecutor.runTest(test));
        return chromosome;
    }

    @Test
    public void aPassWithNothingToCompleteCopiesNothing() throws Exception {
        Properties.FUNCTIONAL_MOCK_COMPLETION_ROUNDS = 4;
        CountingTestCase test = new CountingTestCase();
        TestChromosome chromosome = chainChromosome(test);
        int before = test.clones;

        assertTrue(chromosome.mockChange());

        // Rounds 2 and 3 complete getB() and its mock and need an undo copy; round 4 finds
        // nothing left to complete and must not copy the test.
        assertEquals(2, test.clones - before);
    }

    @Test
    public void anUndoneRoundLeavesAResultOfTheRestoredTest() throws Exception {
        Properties.FUNCTIONAL_MOCK_COMPLETION_ROUNDS = 3;
        TestChromosome chromosome = chainChromosome(new DefaultTestCase());
        int[] passes = new int[1];
        chromosome.mockChange(added -> {
            if (++passes[0] < 2) {
                return chromosome.mockChangePass(added);
            }
            try {
                chromosome.getTestCase().addStatement(new MethodStatement(chromosome.getTestCase(),
                        new GenericMethod(Failing.class.getMethod("fail"), Failing.class),
                        null, Collections.emptyList()), 0);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            added[0] = true;
            return true;
        });

        assertSame(chromosome.getTestCase(), chromosome.getLastExecutionResult().test);
    }

    @Test
    public void mutateDiscardsTheResultOfTheCompletionPasses() throws Exception {
        // The re-execution of the last pass serves the undo decision and the later operators of
        // mutate(), which read the position of the thrown exception. Afterwards the chromosome
        // is marked as changed, which drops the result, so fitness evaluation runs the test.
        double[] old = {Properties.P_TEST_DELETE, Properties.P_TEST_CHANGE, Properties.P_TEST_INSERT};
        Properties.FUNCTIONAL_MOCK_COMPLETION_ROUNDS = 3;
        Properties.P_TEST_DELETE = 0;
        Properties.P_TEST_CHANGE = 0;
        Properties.P_TEST_INSERT = 0;
        try {
            TestChromosome chromosome = chainChromosome(new DefaultTestCase());
            chromosome.mutate();
            assertTrue(chromosome.isChanged());
            assertNull(chromosome.getLastExecutionResult());
        } finally {
            Properties.P_TEST_DELETE = old[0];
            Properties.P_TEST_CHANGE = old[1];
            Properties.P_TEST_INSERT = old[2];
        }
    }
}
