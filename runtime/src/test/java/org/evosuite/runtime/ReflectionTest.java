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
package org.evosuite.runtime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

public class ReflectionTest {

    @Test
    public void testSetField_booleanCoercionDoesNotThrowClassCast() throws Exception {
        Field booleanField = ReflectionFixture.class.getField("flag");
        ReflectionFixture fixture = new ReflectionFixture();

        Reflection.setField(booleanField, fixture, "true");
        Assertions.assertTrue(fixture.flag);

        Reflection.setField(booleanField, fixture, 0);
        Assertions.assertFalse(fixture.flag);
    }

    @Test
    public void testSetField_incompatibleObjectTypeStillFailsAsIllegalArgument() throws Exception {
        Field stringField = ReflectionFixture.class.getField("text");
        ReflectionFixture fixture = new ReflectionFixture();

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> Reflection.setField(stringField, fixture, 123));
    }

    public static class ReflectionFixture {
        public boolean flag;
        public String text;
    }
}
