/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.testcase.synthesizer;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FunctionalMockSetupCoordinatorTest {

    private boolean oldMockSetup;
    private int oldExtraExecutions;
    private int oldMaxFailedAttempts;
    private String oldTargetClass;

    @Before
    public void setUp() {
        oldMockSetup = Properties.OBJECT_RULE_MOCK_SETUP;
        oldExtraExecutions = Properties.OBJECT_RULE_SETUP_MAX_EXTRA_EXECUTIONS;
        oldMaxFailedAttempts = Properties.OBJECT_RULE_MOCK_SETUP_MAX_FAILED_ATTEMPTS;
        oldTargetClass = Properties.TARGET_CLASS;
        Properties.OBJECT_RULE_MOCK_SETUP = true;
        Properties.OBJECT_RULE_SETUP_MAX_EXTRA_EXECUTIONS = 1;
        Properties.OBJECT_RULE_MOCK_SETUP_MAX_FAILED_ATTEMPTS = 3;
        Properties.TARGET_CLASS = MockConsumer.class.getName();
        FunctionalMockSetupCoordinator.reset();
    }

    @After
    public void tearDown() {
        Properties.OBJECT_RULE_MOCK_SETUP = oldMockSetup;
        Properties.OBJECT_RULE_SETUP_MAX_EXTRA_EXECUTIONS = oldExtraExecutions;
        Properties.OBJECT_RULE_MOCK_SETUP_MAX_FAILED_ATTEMPTS = oldMaxFailedAttempts;
        Properties.TARGET_CLASS = oldTargetClass;
        FunctionalMockSetupCoordinator.reset();
    }

    @Test
    public void observesAndRepairsUnstubbedFunctionalMockOnOneClone() throws Exception {
        TestCase original = unstubbedTemplate();
        ExecutionResult initial = TestCaseExecutor.getInstance().execute(original);
        assertFalse(initial.noThrownExceptions());
        FunctionalMockStatement originalMock = (FunctionalMockStatement) original.getStatement(0);
        assertEquals(1, originalMock.getInvocationObservationsNeedingStubs().size());
        assertTrue(originalMock.getMockedMethods().isEmpty());

        FunctionalMockSetupCoordinator.RepairProposal proposal =
                FunctionalMockSetupCoordinator.proposeRepair(original, initial);

        assertNotNull(proposal);
        assertTrue(originalMock.getMockedMethods().isEmpty());
        assertTrue(proposal.getTest().size() > original.size());

        FunctionalMockSetupCoordinator.recordExtraExecution(proposal);
        ExecutionResult repaired = TestCaseExecutor.getInstance().execute(proposal.getTest());
        assertTrue(repaired.noThrownExceptions());
        assertTrue(proposal.getTest().toCode().contains("doReturn"));
        FunctionalMockSetupCoordinator.recordSuccess(proposal, true);

        assertEquals(1, FunctionalMockSetupCoordinator.getExtraExecutions());
        assertEquals(1, FunctionalMockSetupCoordinator.getAttempts());
        assertEquals(1, FunctionalMockSetupCoordinator.getSuccesses());
    }

    @Test
    public void successfulStubRecipeIsProactiveWithoutAnotherExecution() throws Exception {
        TestCase first = unstubbedTemplate();
        ExecutionResult initial = TestCaseExecutor.getInstance().execute(first);
        FunctionalMockSetupCoordinator.RepairProposal proposal =
                FunctionalMockSetupCoordinator.proposeRepair(first, initial);
        assertNotNull(proposal);
        FunctionalMockSetupCoordinator.recordExtraExecution(proposal);
        assertTrue(TestCaseExecutor.getInstance().execute(proposal.getTest()).noThrownExceptions());
        FunctionalMockSetupCoordinator.recordSuccess(proposal, true);

        TestCase later = unstubbedTemplate();
        assertTrue(FunctionalMockSetupCoordinator.applyCachedSetup(later));
        assertTrue(TestCaseExecutor.getInstance().execute(later).noThrownExceptions());

        assertEquals(1, FunctionalMockSetupCoordinator.getExtraExecutions());
        assertEquals(1, FunctionalMockSetupCoordinator.getProactiveUses());
    }

    @Test
    public void improvingButNonCoveringStubRecipeIsNotProactive() throws Exception {
        TestCase first = unstubbedTemplate();
        ExecutionResult initial = TestCaseExecutor.getInstance().execute(first);
        FunctionalMockSetupCoordinator.RepairProposal proposal =
                FunctionalMockSetupCoordinator.proposeRepair(first, initial);
        assertNotNull(proposal);
        FunctionalMockSetupCoordinator.recordExtraExecution(proposal);
        assertTrue(TestCaseExecutor.getInstance().execute(proposal.getTest()).noThrownExceptions());

        FunctionalMockSetupCoordinator.recordSuccess(proposal, false);

        TestCase later = unstubbedTemplate();
        assertFalse(FunctionalMockSetupCoordinator.applyCachedSetup(later));
        assertEquals(1, FunctionalMockSetupCoordinator.getSuccesses());
        assertEquals(0, FunctionalMockSetupCoordinator.getProactiveUses());
    }

    @Test
    public void repeatedlyUnhelpfulRecipeIsNegativelyCached() throws Exception {
        Properties.OBJECT_RULE_MOCK_SETUP_MAX_FAILED_ATTEMPTS = 2;

        for (int attempt = 0; attempt < 2; attempt++) {
            TestCase original = unstubbedTemplate();
            ExecutionResult initial = TestCaseExecutor.getInstance().execute(original);
            FunctionalMockSetupCoordinator.RepairProposal proposal =
                    FunctionalMockSetupCoordinator.proposeRepair(original, initial);
            assertNotNull(proposal);
            FunctionalMockSetupCoordinator.recordFailure(proposal);
        }

        TestCase repeated = unstubbedTemplate();
        ExecutionResult repeatedResult = TestCaseExecutor.getInstance().execute(repeated);
        assertNull(FunctionalMockSetupCoordinator.proposeRepair(repeated, repeatedResult));
        assertEquals(2, FunctionalMockSetupCoordinator.getAttempts());
        assertEquals(1, FunctionalMockSetupCoordinator.getBlacklistedRecipes());
        assertEquals(1, FunctionalMockSetupCoordinator.getNegativeCacheHits());
    }

    @Test
    public void zeroBudgetDoesNotCreateARepairCandidate() throws Exception {
        TestCase original = unstubbedTemplate();
        ExecutionResult initial = TestCaseExecutor.getInstance().execute(original);
        Properties.OBJECT_RULE_SETUP_MAX_EXTRA_EXECUTIONS = 0;

        assertEquals(null, FunctionalMockSetupCoordinator.proposeRepair(original, initial));
        assertEquals(0, FunctionalMockSetupCoordinator.getExtraExecutions());
    }

    @Test
    public void booleanMockSetupExploresBothDirectionsAndCachesWinner() throws Exception {
        Properties.OBJECT_RULE_SETUP_MAX_EXTRA_EXECUTIONS = 2;
        TestCase original = unstubbedBooleanTemplate();
        ExecutionResult initial = TestCaseExecutor.getInstance().execute(original);

        List<FunctionalMockSetupCoordinator.RepairProposal> proposals =
                FunctionalMockSetupCoordinator.proposeRepairs(original, initial);

        assertEquals(2, proposals.size());
        assertEquals(Collections.singletonList(Boolean.TRUE),
                booleanValues(proposals.get(0).getTest()));
        assertEquals(Collections.singletonList(Boolean.FALSE),
                booleanValues(proposals.get(1).getTest()));
        assertTrue(TestCaseExecutor.getInstance().execute(
                proposals.get(0).getTest()).noThrownExceptions());
        assertTrue(TestCaseExecutor.getInstance().execute(
                proposals.get(1).getTest()).noThrownExceptions());

        FunctionalMockSetupCoordinator.recordSuccess(proposals.get(0), true);
        TestCase later = unstubbedBooleanTemplate();
        assertTrue(FunctionalMockSetupCoordinator.applyCachedSetup(later));
        assertEquals(Collections.singletonList(Boolean.TRUE), booleanValues(later));
    }

    @Test
    public void genericCollectionMockReturnIsMaterializedWithOneElement() throws Exception {
        TestCase original = unstubbedCollectionTemplate();
        ExecutionResult initial = TestCaseExecutor.getInstance().execute(original);
        assertEquals(0, CollectionConsumer.lastSize);
        assertEquals(1, ((FunctionalMockStatement) original.getStatement(0))
                .getInvocationObservationsNeedingStubs().size());

        FunctionalMockSetupCoordinator.RepairProposal proposal =
                FunctionalMockSetupCoordinator.proposeRepair(original, initial);

        assertNotNull(proposal);
        ExecutionResult repaired = TestCaseExecutor.getInstance().execute(proposal.getTest());
        assertTrue(repaired.noThrownExceptions());
        assertEquals(1, CollectionConsumer.lastSize);
        assertTrue(proposal.getTest().toCode().contains(".add("));
    }

    @Test
    public void numericMockReturnUsesSafeBranchHint() throws Exception {
        TestCase original = unstubbedIntTemplate();
        ExecutionResult initial = TestCaseExecutor.getInstance().execute(original);
        assertEquals(0, IntConsumer.lastValue);

        List<FunctionalMockSetupCoordinator.RepairProposal> proposals =
                FunctionalMockSetupCoordinator.proposeRepairs(original, initial, 257);

        assertEquals(1, proposals.size());
        TestCase repaired = proposals.get(0).getTest();
        assertTrue(TestCaseExecutor.getInstance().execute(repaired).noThrownExceptions());
        assertEquals(257, IntConsumer.lastValue);
        List<Integer> values = new ArrayList<>();
        for (Statement statement : repaired) {
            if (statement instanceof IntPrimitiveStatement) {
                values.add(((IntPrimitiveStatement) statement).getValue());
            }
        }
        assertEquals(Collections.singletonList(257), values);
        assertFalse(repaired.toCode().contains("2147483647"));
    }

    private static TestCase unstubbedTemplate() throws Exception {
        TestCase test = new DefaultTestCase();
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                test, Service.class, GenericClassFactory.get(Service.class));
        VariableReference mock = test.addStatement(mockStatement);
        Method consume = MockConsumer.class.getMethod("consume", Service.class);
        test.addStatement(new MethodStatement(
                test,
                new GenericMethod(consume, MockConsumer.class),
                null,
                Collections.singletonList(mock)));
        return test;
    }

    private static TestCase unstubbedBooleanTemplate() throws Exception {
        TestCase test = new DefaultTestCase();
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                test, BooleanService.class, GenericClassFactory.get(BooleanService.class));
        VariableReference mock = test.addStatement(mockStatement);
        Method consume = MockConsumer.class.getMethod("consumeBoolean", BooleanService.class);
        test.addStatement(new MethodStatement(
                test,
                new GenericMethod(consume, MockConsumer.class),
                null,
                Collections.singletonList(mock)));
        return test;
    }

    private static TestCase unstubbedCollectionTemplate() throws Exception {
        TestCase test = new DefaultTestCase();
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                test, CollectionService.class, GenericClassFactory.get(CollectionService.class));
        VariableReference mock = test.addStatement(mockStatement);
        Method consume = CollectionConsumer.class.getMethod("consume", CollectionService.class);
        test.addStatement(new MethodStatement(test, new GenericMethod(consume, CollectionConsumer.class),
                null, Collections.singletonList(mock)));
        return test;
    }

    private static TestCase unstubbedIntTemplate() throws Exception {
        TestCase test = new DefaultTestCase();
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                test, IntService.class, GenericClassFactory.get(IntService.class));
        VariableReference mock = test.addStatement(mockStatement);
        Method consume = IntConsumer.class.getMethod("consume", IntService.class);
        test.addStatement(new MethodStatement(test, new GenericMethod(consume, IntConsumer.class),
                null, Collections.singletonList(mock)));
        return test;
    }

    private static List<Boolean> booleanValues(TestCase test) {
        List<Boolean> values = new ArrayList<>();
        for (Statement statement : test) {
            if (statement instanceof BooleanPrimitiveStatement) {
                values.add(((BooleanPrimitiveStatement) statement).getValue());
            }
        }
        return values;
    }

    public interface Service {
        String value();
    }

    public interface BooleanService {
        boolean value();
    }

    public interface CollectionService {
        List<Service> values();
    }

    public interface IntService {
        int value();
    }

    public static class CollectionConsumer {
        static int lastSize;

        public static int consume(CollectionService service) {
            lastSize = service.values().size();
            return lastSize;
        }
    }

    public static class IntConsumer {
        static int lastValue;

        public static int consume(IntService service) {
            lastValue = service.value();
            return lastValue;
        }
    }

    public static class MockConsumer {
        public static int consume(Service service) {
            return service.value().length();
        }

        public static boolean consumeBoolean(BooleanService service) {
            return service.value();
        }
    }
}
