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
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cached stub recipe replayed onto a mock that already stubs one of the recipe's methods adds a
 * second stub for the same method ID, whose descriptor counter (observed calls) exceeds the number
 * of values added (only the missing ones). The Mockito executer walks the flat value list with a
 * running index sized by the counter, while the renderer and coordinator use the ID-to-range map,
 * so the executer misreads the next method's values and runs off the end of the list.
 */
public class FunctionalMockCachedSetupDuplicateStubTest {

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

    @Disabled("D2: mock executer indexes stub values by a running counter, renderer by ID range"
            + " - see CLEANUP-PLAN.md")
    @Test
    public void cachedRecipeOverlappingAnExistingStubExecutesCleanly() throws Exception {
        TestCase first = partiallyStubbedTemplate();
        ExecutionResult initial = TestCaseExecutor.getInstance().execute(first);
        FunctionalMockSetupCoordinator.RepairProposal proposal =
                FunctionalMockSetupCoordinator.proposeRepair(first, initial);
        assertNotNull(proposal, "precondition: the missing value()/other() returns are repaired");
        ExecutionResult repaired = TestCaseExecutor.getInstance().execute(proposal.getTest());
        assertTrue(repaired.noThrownExceptions(),
                "precondition: the observed repair executes cleanly");
        FunctionalMockSetupCoordinator.recordSuccess(proposal, true);

        TestCase later = partiallyStubbedTemplate();
        assertTrue(FunctionalMockSetupCoordinator.applyCachedSetup(later),
                "precondition: the cached recipe is replayed onto the partially stubbed mock");
        ExecutionResult result = TestCaseExecutor.getInstance().execute(later);
        assertTrue(result.noThrownExceptions(),
                "replaying the cached recipe must execute cleanly, but threw "
                        + result.getExceptionThrownAtPosition(
                                result.getFirstPositionOfThrownException())
                        + " for\n" + later.toCode());
    }

    /** {@code value()} is stubbed once, as the parser would; {@code other()} is not stubbed. */
    private static TestCase partiallyStubbedTemplate() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference existing = test.addStatement(new StringPrimitiveStatement(test, "pre"));
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                test, Service.class, GenericClassFactory.get(Service.class));
        VariableReference mock = test.addStatement(mockStatement);
        MethodDescriptor value = new MethodDescriptor(Service.class.getMethod("value"),
                GenericClassFactory.get(Service.class));
        value.increaseCounter();
        mockStatement.addMethodStubbing(value, Collections.singletonList(existing));
        Method consume = Consumer.class.getMethod("consume", Service.class);
        test.addStatement(new MethodStatement(test, new GenericMethod(consume, Consumer.class),
                null, Collections.singletonList(mock)));
        return test;
    }

    public interface Service {
        String value();

        String other();
    }

    public static class Consumer {
        public static int consume(Service service) {
            String a = service.value();
            String b = service.value();
            String c = service.other();
            return length(a) + length(b) + length(c);
        }

        private static int length(String s) {
            return s == null ? 0 : s.length();
        }
    }
}
