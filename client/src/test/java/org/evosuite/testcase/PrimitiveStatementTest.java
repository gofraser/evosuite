/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase;

import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PrimitiveStatementTest {

    @Test
    public void testSame() {

        TestCase tc = new DefaultTestCase();
        PrimitiveStatement<?> aInt = new IntPrimitiveStatement(tc, 42);
        Assertions.assertTrue(aInt.same(aInt));
        Assertions.assertFalse(aInt.same(null));

        PrimitiveStatement<?> fooString = new StringPrimitiveStatement(tc, "foo");
        Assertions.assertFalse(aInt.same(fooString));

        PrimitiveStatement<?> nullString = new StringPrimitiveStatement(tc, null);
        Assertions.assertFalse(nullString.same(fooString));
        Assertions.assertFalse(fooString.same(nullString));


        //TODO: how to make it work?
        //PrimitiveStatement<?> anotherNullString = new StringPrimitiveStatement(tc,null);
        //Assert.assertTrue(nullString.same(anotherNullString));
        //Assert.assertTrue(anotherNullString.same(nullString));
    }
}
