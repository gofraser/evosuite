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

import static org.junit.Assert.assertFalse;
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

    @Before
    public void setUp() {
        oldRounds = Properties.FUNCTIONAL_MOCK_COMPLETION_ROUNDS;
    }

    @After
    public void tearDown() {
        Properties.FUNCTIONAL_MOCK_COMPLETION_ROUNDS = oldRounds;
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

    @Test
    public void oneRoundDoesNotCompleteTheChain() throws Exception {
        assertFalse(succeedsAfterOneMockChange(1));
    }
}
