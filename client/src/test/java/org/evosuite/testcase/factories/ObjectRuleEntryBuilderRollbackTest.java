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
package org.evosuite.testcase.factories;

import com.examples.with.different.packagename.SimpleInteger;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.GenerationContext;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * A failed entry-receiver generator must remove the statements it added wherever the factory put
 * them. Truncating the test back to its old size assumes all growth is at the end; a dependency
 * inserted in front of existing statements makes that truncation delete the test's own last
 * statement and keep the inserted one.
 */
public class ObjectRuleEntryBuilderRollbackTest {

    @BeforeEach
    public void setUp() {
        TestCluster.reset();
    }

    @AfterEach
    public void tearDown() {
        TestCluster.reset();
    }

    @Test
    public void failedReceiverGeneratorKeepsTheTestsOwnStatements() throws Exception {
        TestCluster.getInstance().addGenerator(GenericClassFactory.get(SimpleInteger.class),
                new GenericConstructor(SimpleInteger.class.getConstructor(), SimpleInteger.class));

        DefaultTestCase test = new DefaultTestCase();
        Statement first = new IntPrimitiveStatement(test, 1);
        Statement last = new IntPrimitiveStatement(test, 2);
        test.addStatement(first);
        test.addStatement(last);

        TestFactory factory = mock(TestFactory.class);
        doAnswer(invocation -> {
            TestCase target = invocation.getArgument(0);
            target.addStatement(new StringPrimitiveStatement(target, "in front"), 0);
            target.addStatement(new StringPrimitiveStatement(target, "appended"), target.size());
            throw new ConstructionFailedException("dependency failed");
        }).when(factory).addConstructor(any(), any(GenericConstructor.class), any(Type.class),
                anyInt(), any(GenerationContext.class));

        try (MockedStatic<TestFactory> factories =
                     Mockito.mockStatic(TestFactory.class, Mockito.CALLS_REAL_METHODS)) {
            factories.when(TestFactory::getInstance).thenReturn(factory);
            assertNull(new ObjectRuleEntryBuilder().constructEntryReceiver(test, SimpleInteger.class));
        }

        // Compare identities without assertSame: rendering a corrupted test for the message would throw.
        assertEquals(2, test.size(), "the rollback left inserted statements behind");
        assertTrue(first == test.getStatement(0), "the rollback removed the test's own first statement");
        assertTrue(last == test.getStatement(1), "the rollback removed the test's own last statement");
        for (int i = 0; i < test.size(); i++) {
            assertEquals(i, test.getStatement(i).getReturnValue().getStPosition());
        }
    }
}
