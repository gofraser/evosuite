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

import java.lang.reflect.Field;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UsedReferenceSearcherTest {

    @Test
    public void searchingFromANullTargetLeavesTheTargetValid() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference target = test.addStatement(new NullStatement(test, Holder.class));
        Field field = Holder.class.getDeclaredField("name");

        new UsedReferenceSearcher().searchRelevantFieldWritingReferenceInTest(test, field, target);

        // Callers keep using the target they passed in, so it must still be defined in the test.
        assertEquals(0, assertDoesNotThrow(target::getStPosition));
        assertTrue(test.isValid());
        test.clone();
    }

    public static class Holder {
        private String name;

        public Holder(String name) {
            this.name = name;
        }
    }
}
