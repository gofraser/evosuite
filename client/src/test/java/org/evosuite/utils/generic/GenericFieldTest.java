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
package org.evosuite.utils.generic;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

public class GenericFieldTest {

    @Test
    public void testConstructorHandlesInaccessibleJdkField() throws Exception {
        Class<?> declaringClass;
        Field field;
        try {
            declaringClass = Class.forName("sun.print.RasterPrinterJob");
            field = declaringClass.getDeclaredField("debugPrint");
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            Assumptions.assumeTrue(false, "Assume failed: " + e.getMessage());
            return;
        }

        GenericField genericField = new GenericField(field, declaringClass);
        Assertions.assertNotNull(genericField);
    }
}
