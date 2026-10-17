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

import com.examples.with.different.packagename.SimpleInteger;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * A failed preferred-value attempt must remove the statements it added wherever the factory put
 * them. Recursive construction can place a dependency at the insertion point and append another
 * one at the end of the test; removing a range from the insertion point then deletes the test's
 * own statement and keeps the appended one.
 */
public class VariableResolverPreferredValueRollbackTest {

    private DefaultTestCase test;
    private Statement first;
    private Statement last;
    private VariableResolver resolver;

    @BeforeEach
    public void setUp() throws Exception {
        TestCluster.reset();
        test = new DefaultTestCase();
        first = new IntPrimitiveStatement(test, 1);
        last = new IntPrimitiveStatement(test, 2);
        test.addStatement(first);
        test.addStatement(last);

        TestFactory factory = mock(TestFactory.class);
        doAnswer(invocation -> {
            TestCase target = invocation.getArgument(0);
            int position = invocation.getArgument(3);
            target.addStatement(new StringPrimitiveStatement(target, "at cursor"), position);
            target.addStatement(new StringPrimitiveStatement(target, "appended"), target.size());
            throw new ConstructionFailedException("dependency failed");
        }).when(factory).addConstructor(any(), any(GenericConstructor.class), any(Type.class),
                anyInt(), any(GenerationContext.class));
        resolver = new VariableResolver(factory);
    }

    @AfterEach
    public void tearDown() {
        TestCluster.reset();
    }

    @Test
    public void failedStableGeneratorKeepsTheTestsOwnStatements() throws Exception {
        TestCluster.getInstance().addGenerator(GenericClassFactory.get(SimpleInteger.class),
                new GenericConstructor(SimpleInteger.class.getConstructor(), SimpleInteger.class));

        assertNull(createPreferredValue(GenericClassFactory.get(SimpleInteger.class), 1));

        assertUnchanged();
    }

    @Test
    public void failedStandardContainerKeepsTheTestsOwnStatements() throws Exception {
        Type listOfStrings = new ParameterizedTypeImpl(List.class, new Type[]{String.class}, null);

        assertNull(createPreferredValue(GenericClassFactory.get(listOfStrings), 1));

        assertUnchanged();
    }

    private Object createPreferredValue(GenericClass<?> type, int position) throws Exception {
        Method method = VariableResolver.class.getDeclaredMethod("tryCreatePreferredConcreteValue",
                TestCase.class, GenericClass.class, int.class, GenerationContext.class);
        method.setAccessible(true);
        return method.invoke(resolver, test, type, position, new GenerationContext());
    }

    private void assertUnchanged() {
        // Compare identities without assertSame: rendering a corrupted test for the message would throw.
        assertEquals(2, test.size(), "the rollback left inserted statements behind");
        assertTrue(first == test.getStatement(0), "the rollback removed the test's own first statement");
        assertTrue(last == test.getStatement(1), "the rollback removed the test's own last statement");
        for (int i = 0; i < test.size(); i++) {
            assertEquals(i, test.getStatement(i).getReturnValue().getStPosition());
        }
    }
}
