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

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.UUID;

import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Focused tests for the chop-point detection that {@link AllocationSensitiveSuitePruner}
 * uses to truncate archived tests when a class is registered allocation-sensitive
 * mid-search. The chop logic itself is package-private; the listener wiring is
 * covered by {@link AllocationSensitivityTest}.
 */
public class AllocationSensitiveSuitePrunerTest {

    @Test
    public void firstStatementUsing_returnsNegativeWhenAbsent() {
        TestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 11));

        int position = AllocationSensitiveSuitePruner.firstStatementUsing(test,
                "test.never.declared." + UUID.randomUUID());
        assertEquals(-1, position);
    }

    @Test
    public void firstStatementUsing_findsConstructorStatement() throws Exception {
        TestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 1));
        test.addStatement(new IntPrimitiveStatement(test, 2));

        Constructor<?> ctor = Object.class.getConstructors()[0];
        ConstructorStatement cs = new ConstructorStatement(test,
                new GenericConstructor(ctor, Object.class), new ArrayList<>());
        test.addStatement(cs);

        // Trailing statement after the constructor — chop point must point at the
        // constructor (index 2), not at the trailing statement.
        test.addStatement(new IntPrimitiveStatement(test, 3));

        int position = AllocationSensitiveSuitePruner.firstStatementUsing(test,
                Object.class.getName());
        assertEquals(2, position);
    }

    @Test
    public void firstStatementUsing_emptyTestReturnsNegative() {
        TestCase test = new DefaultTestCase();
        int position = AllocationSensitiveSuitePruner.firstStatementUsing(test,
                Object.class.getName());
        assertEquals(-1, position);
    }

    @Test
    public void chopAtFirstUse_preservesPriorStatementsAndDropsRest() throws Exception {
        TestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 10));
        test.addStatement(new IntPrimitiveStatement(test, 20));

        Constructor<?> ctor = Object.class.getConstructors()[0];
        test.addStatement(new ConstructorStatement(test,
                new GenericConstructor(ctor, Object.class), new ArrayList<>()));
        test.addStatement(new IntPrimitiveStatement(test, 30));

        assertEquals(4, test.size());

        int firstUse = AllocationSensitiveSuitePruner.firstStatementUsing(test,
                Object.class.getName());
        assertEquals(2, firstUse);

        test.chop(firstUse);
        assertEquals(2, test.size(),
                "chop(2) must keep statements [0..1] and drop the constructor + trailing");
    }
}
