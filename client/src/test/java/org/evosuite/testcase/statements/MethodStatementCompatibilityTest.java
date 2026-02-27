/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.statements;

import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

public class MethodStatementCompatibilityTest {

    private static class DeclaringType {
        public void ping() {
            // no-op
        }
    }

    private static class UnrelatedType {
        // no-op
    }

    @Test
    public void testIncompatibleCalleeRejectedEvenWithBroadOwnerType() throws Exception {
        Method method = DeclaringType.class.getDeclaredMethod("ping");
        GenericMethod genericMethod = new GenericMethod(method, Object.class);

        Assertions.assertFalse(MethodStatement.isCompatibleCalleeType(genericMethod, UnrelatedType.class));
    }
}
