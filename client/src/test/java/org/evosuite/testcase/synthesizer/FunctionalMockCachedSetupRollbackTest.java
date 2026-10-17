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
package org.evosuite.testcase.synthesizer;

import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.GenerationContext;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.VariableResolutionConfig;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Replaying a cached recipe inserts the stub values in front of the mock. If one of them fails
 * after construction appended a helper at the end of the test, removing a range from the mock's
 * old position deletes the mock itself and keeps the appended helper.
 */
public class FunctionalMockCachedSetupRollbackTest {

    private String oldTargetClass;

    @BeforeEach
    public void setUp() {
        oldTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = Consumer.class.getName();
        FunctionalMockSetupCoordinator.reset();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = oldTargetClass;
        FunctionalMockSetupCoordinator.reset();
    }

    @Test
    public void failedReplayKeepsTheTemplatesOwnStatements() throws Exception {
        TestCase first = unstubbedTemplate();
        ExecutionResult initial = TestCaseExecutor.getInstance().execute(first);
        FunctionalMockSetupCoordinator.RepairProposal proposal =
                FunctionalMockSetupCoordinator.proposeRepair(first, initial);
        assertNotNull(proposal, "precondition: the missing value() return is repaired");
        assertTrue(TestCaseExecutor.getInstance().execute(proposal.getTest()).noThrownExceptions(),
                "precondition: the observed repair executes cleanly");
        FunctionalMockSetupCoordinator.recordSuccess(proposal, true);

        TestCase later = unstubbedTemplate();
        List<Statement> original = new ArrayList<>();
        for (Statement statement : later) {
            original.add(statement);
        }

        TestFactory factory = mock(TestFactory.class);
        doAnswer(invocation -> {
            TestCase target = invocation.getArgument(0);
            target.addStatement(new StringPrimitiveStatement(target, "appended"), target.size());
            throw new ConstructionFailedException("return value failed");
        }).when(factory).satisfyParameters(any(), isNull(), anyList(), isNull(), anyInt(),
                any(GenerationContext.class), any(VariableResolutionConfig.class));

        try (MockedStatic<TestFactory> factories =
                     Mockito.mockStatic(TestFactory.class, Mockito.CALLS_REAL_METHODS)) {
            factories.when(TestFactory::getInstance).thenReturn(factory);
            assertFalse(FunctionalMockSetupCoordinator.applyCachedSetup(later));
        }

        // Compare identities without assertSame: rendering a corrupted test for the message would throw.
        assertEquals(original.size(), later.size(), "the rollback left inserted statements behind");
        for (int i = 0; i < original.size(); i++) {
            assertTrue(original.get(i) == later.getStatement(i),
                    "the rollback removed the template's own statement " + i);
            assertEquals(i, later.getStatement(i).getReturnValue().getStPosition());
        }
    }

    private static TestCase unstubbedTemplate() throws Exception {
        TestCase test = new DefaultTestCase();
        test.addStatement(new StringPrimitiveStatement(test, "pre"));
        VariableReference mock = test.addStatement(new FunctionalMockStatement(
                test, Service.class, GenericClassFactory.get(Service.class)));
        Method consume = Consumer.class.getMethod("consume", Service.class);
        test.addStatement(new MethodStatement(test, new GenericMethod(consume, Consumer.class),
                null, Collections.singletonList(mock)));
        return test;
    }

    public interface Service {
        String value();
    }

    public static class Consumer {
        public static int consume(Service service) {
            String value = service.value();
            return value == null ? 0 : value.length();
        }
    }
}
