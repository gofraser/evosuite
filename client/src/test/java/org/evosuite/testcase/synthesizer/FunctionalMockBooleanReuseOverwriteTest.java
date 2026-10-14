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
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Setup repair seeds a boolean mock return with the non-default value. When the return value
 * reuses a boolean that the test already passes to the code under test, setting the value on its
 * defining statement silently changes that other input as well.
 */
public class FunctionalMockBooleanReuseOverwriteTest {

    private String oldTargetClass;
    private double oldPrimitiveReuse;

    @BeforeEach
    public void setUp() {
        oldTargetClass = Properties.TARGET_CLASS;
        oldPrimitiveReuse = Properties.PRIMITIVE_REUSE_PROBABILITY;
        Properties.TARGET_CLASS = Consumer.class.getName();
        Properties.PRIMITIVE_REUSE_PROBABILITY = 1.0;
        FunctionalMockSetupCoordinator.reset();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = oldTargetClass;
        Properties.PRIMITIVE_REUSE_PROBABILITY = oldPrimitiveReuse;
        FunctionalMockSetupCoordinator.reset();
    }

    @Test
    public void repairDoesNotChangeABooleanTheTestPassesElsewhere() throws Exception {
        TestCase original = new DefaultTestCase();
        VariableReference flag = original.addStatement(
                new BooleanPrimitiveStatement(original, false));
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                original, BooleanService.class, GenericClassFactory.get(BooleanService.class));
        VariableReference mock = original.addStatement(mockStatement);
        Method consume = Consumer.class.getMethod("consume", BooleanService.class, boolean.class);
        original.addStatement(new MethodStatement(original, new GenericMethod(consume, Consumer.class),
                null, Arrays.asList(mock, flag)));

        ExecutionResult initial = TestCaseExecutor.getInstance().execute(original);
        FunctionalMockSetupCoordinator.RepairProposal proposal =
                FunctionalMockSetupCoordinator.proposeRepair(original, initial);
        assertNotNull(proposal, "precondition: the unstubbed boolean return is repaired");
        TestCase repaired = proposal.getTest();
        assertTrue(repaired.toCode().contains("doReturn"),
                "precondition: the repair adds a stub");

        BooleanPrimitiveStatement flagInRepair = (BooleanPrimitiveStatement) repaired.getStatement(0);
        assertFalse(flagInRepair.getValue(),
                "the boolean passed to consume() must keep its value; the repair reused it as the"
                        + " mock's return and overwrote it:\n" + repaired.toCode());
    }

    public interface BooleanService {
        boolean value();
    }

    public static class Consumer {
        public static boolean consume(BooleanService service, boolean flag) {
            return service.value() && !flag;
        }
    }
}
