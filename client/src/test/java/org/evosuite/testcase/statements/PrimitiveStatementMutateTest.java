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
package org.evosuite.testcase.statements;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PrimitiveStatementMutateTest {

    /**
     * Produces a new but equal Integer on its first step, so that only an equality
     * comparison notices that the value did not change.
     */
    private static final class StubbornIntStatement extends IntPrimitiveStatement {
        private static final long serialVersionUID = 1L;

        private int steps;

        private StubbornIntStatement(DefaultTestCase tc) {
            super(tc, 1000);
        }

        private void step() {
            steps++;
            value = Integer.valueOf(steps == 1 ? 1000 : 2000);
        }

        @Override
        public void delta() {
            step();
        }

        @Override
        public void randomize() {
            step();
        }
    }

    @Test
    public void mutateKeepsGoingUntilTheValueChanges() {
        DefaultTestCase tc = new DefaultTestCase();
        StubbornIntStatement statement = new StubbornIntStatement(tc);

        assertTrue(statement.mutate(tc, null));

        assertNotEquals(1000, statement.getValue().intValue());
        assertEquals(2, statement.steps);
    }
}
