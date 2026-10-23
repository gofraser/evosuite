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
package org.evosuite.testcase.execution.reset;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClassReInitializerDmonResetTest {

    public static class Holder {
        public int value;
        public Service service;
    }

    public interface Service {
        int count();
    }

    private final boolean defaultDmonEnabled = Properties.DMON_ENABLED;

    @BeforeEach
    public void setUp() {
        Properties.DMON_ENABLED = true;
    }

    @AfterEach
    public void tearDown() {
        Properties.DMON_ENABLED = defaultDmonEnabled;
        ClassReInitializer.resetSingleton();
    }

    @Test
    public void testOrdinaryInstanceFieldWriteDoesNotResetOwner() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference holder = newHolder(test);
        VariableReference five = test.addStatement(new IntPrimitiveStatement(test, 5));
        test.addStatement(new AssignmentStatement(test, field(test, holder, "value"), five));

        assertEquals(Collections.emptySet(), classesToReset(test));
    }

    @Test
    public void testPromotedMockInjectionResetsOwner() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference holder = newHolder(test);
        FunctionalMockStatement mock = new FunctionalMockStatement(test, Service.class,
                GenericClassFactory.get(Service.class));
        mock.setDmonPromotion(true);
        VariableReference mockRef = test.addStatement(mock);
        test.addStatement(new AssignmentStatement(test, field(test, holder, "service"), mockRef));

        Set<String> classes = classesToReset(test);
        assertTrue(classes.contains(Holder.class.getName()), classes.toString());
    }

    private static VariableReference newHolder(DefaultTestCase test) throws Exception {
        return test.addStatement(new ConstructorStatement(test,
                new GenericConstructor(Holder.class.getConstructor(), Holder.class),
                Collections.emptyList()));
    }

    private static FieldReference field(DefaultTestCase test, VariableReference holder, String name)
            throws Exception {
        return new FieldReference(test,
                new GenericField(Holder.class.getField(name), Holder.class), holder);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> classesToReset(DefaultTestCase test) throws Exception {
        ExecutionResult result = new ExecutionResult(test);
        result.setExecutedStatements(test.size());
        Method m = ClassReInitializer.class.getDeclaredMethod("getMoreClassesToReset",
                TestCase.class, ExecutionResult.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(null, test, result);
    }
}
