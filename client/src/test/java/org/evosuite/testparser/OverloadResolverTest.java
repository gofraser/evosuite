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
package org.evosuite.testparser;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverloadResolverTest {

    private final OverloadResolver resolver = new OverloadResolver();

    @Test
    void resolveConstructorPrefersExactMatch() throws NoSuchMethodException {
        Constructor<?> constructor = resolver.resolveConstructor(ConstructorTarget.class, new Class<?>[]{String.class});

        assertEquals(String.class, constructor.getParameterTypes()[0]);
    }

    @Test
    void resolveMethodSupportsAutoboxing() throws NoSuchMethodException {
        Method method = resolver.resolveMethod(MethodTarget.class, "autobox", new Class<?>[]{int.class});

        assertEquals(Integer.class, method.getParameterTypes()[0]);
    }

    @Test
    void resolveMethodSupportsVarArgsExpandedAndExplicitArrayForms() throws NoSuchMethodException {
        Method explicitArray = resolver.resolveMethod(MethodTarget.class, "varargs", new Class<?>[]{String[].class});
        Method expanded = resolver.resolveMethod(MethodTarget.class, "varargs", new Class<?>[]{String.class, String.class});

        assertEquals(String[].class, explicitArray.getParameterTypes()[0]);
        assertEquals(String[].class, expanded.getParameterTypes()[0]);
    }

    @Test
    void resolveMethodSkipsBridgeMethods() throws NoSuchMethodException {
        Method method = resolver.resolveMethod(BridgeTarget.class, "compareTo", new Class<?>[]{SpecificArgument.class});

        assertFalse(method.isBridge());
        assertEquals(Argument.class, method.getParameterTypes()[0]);
    }

    @Test
    void resolveMethodPrefersMostSpecificCompatibleOverload() throws NoSuchMethodException {
        Method method = resolver.resolveMethod(MethodTarget.class, "specificity", new Class<?>[]{ArrayList.class});

        assertEquals(List.class, method.getParameterTypes()[0]);
    }

    @Test
    void resolveMethodSupportsPrimitiveWidening() throws NoSuchMethodException {
        Method method = resolver.resolveMethod(MethodTarget.class, "widen", new Class<?>[]{int.class});

        assertEquals(long.class, method.getParameterTypes()[0]);
        assertTrue(OverloadResolver.isAssignableFrom(long.class, int.class));
    }

    @Test
    void hasMethodNamedRespectsStaticRequirement() {
        assertTrue(resolver.hasMethodNamed(MethodTarget.class, "staticOnly", true));
        assertFalse(resolver.hasMethodNamed(MethodTarget.class, "instanceOnly", true));
        assertTrue(resolver.hasMethodNamed(MethodTarget.class, "instanceOnly", false));
    }

    static class ConstructorTarget {
        ConstructorTarget(Object value) {
        }

        ConstructorTarget(String value) {
        }
    }

    static class MethodTarget {
        public void autobox(Integer value) {
        }

        public void varargs(String... values) {
        }

        public void widen(long value) {
        }

        public void specificity(Collection<?> values) {
        }

        public void specificity(List<?> values) {
        }

        public static void staticOnly() {
        }

        public void instanceOnly() {
        }
    }

    static class Argument {
    }

    static class SpecificArgument extends Argument {
    }

    static class BridgeTarget implements Comparable<Argument> {
        @Override
        public int compareTo(Argument other) {
            return 0;
        }
    }
}
