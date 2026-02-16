/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * <p>
 * This file is part of EvoSuite.
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.setup;

import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestClusterUtilTest {

    @Test
    public void testIsAnonymousWithValidName() {
        assertFalse(TestClusterUtils.isAnonymousClass(TestClusterUtilTest.class.getName()));
    }

    @Test
    public void testIsAnonymousWithVAnonymousClassName() {
        Object o = new Object() {
            // ...
        };
        assertTrue(TestClusterUtils.isAnonymousClass(o.getClass().getName()));
    }

    @Test
    public void testIsAnonymousWithNameEndingWithDollar() {
        assertFalse(TestClusterUtils.isAnonymousClass("Option$None$"));
    }

    @Test
    public void testMakeAccessibleHandlesInaccessibleJdkMembers() throws Exception {
        Class<?> clazz;
        try {
            clazz = Class.forName("sun.print.RasterPrinterJob");
        } catch (ClassNotFoundException e) {
            Assume.assumeNoException(e);
            return;
        }

        Field field;
        try {
            field = clazz.getDeclaredField("debugPrint");
        } catch (NoSuchFieldException e) {
            Assume.assumeNoException(e);
            return;
        }

        Method method = null;
        for (Method candidate : clazz.getDeclaredMethods()) {
            if (!Modifier.isPublic(candidate.getModifiers())) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            Assume.assumeTrue("No non-public method available", false);
            return;
        }

        Constructor<?> constructor = null;
        for (Constructor<?> candidate : clazz.getDeclaredConstructors()) {
            if (!Modifier.isPublic(candidate.getModifiers())) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) {
            Assume.assumeTrue("No non-public constructor available", false);
            return;
        }

        TestClusterUtils.makeAccessible(field);
        TestClusterUtils.makeAccessible(method);
        TestClusterUtils.makeAccessible(constructor);
    }
}
