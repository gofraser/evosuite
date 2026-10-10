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

import org.evosuite.setup.TestUsageChecker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A public method can take a parameter of a private nested type
 * ({@code FurnitureController.sortFurniture(AlignedPieceOfFurniture[], ...)}). A test can neither
 * name that type nor cast to it, so the call cannot be written. The synthesizers build their calls
 * from bytecode, past the cluster's filter, and used to add such a call with an array of the
 * private type as its argument.
 */
public class PrivateParameterTypeCallTest {

    private static class Hidden {
    }

    public static class Visible {
    }

    public static int sortHidden(Hidden[] values, String key) {
        return values.length;
    }

    public static int sortVisible(Visible[] values, String key) {
        return values.length;
    }

    private static Method method(String name, Class<?> parameter) throws Exception {
        return PrivateParameterTypeCallTest.class.getMethod(name, parameter, String.class);
    }

    @Test
    public void methodWithAnArrayOfAPrivateTypeCannotBeCalledFromSource() throws Exception {
        assertTrue(TestUsageChecker.isNotCallableFromSource(method("sortHidden", Hidden[].class)));
        assertFalse(TestUsageChecker.isNotCallableFromSource(method("sortVisible", Visible[].class)));
    }
}
