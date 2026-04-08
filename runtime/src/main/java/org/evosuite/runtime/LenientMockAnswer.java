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

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Lenient default answer for DMoN-promoted functional mocks.  Returns sensible
 * non-null values for all return types: empty strings, zero for numerics, empty
 * collections, and recursively mocked objects for non-final concrete types.
 *
 * <p>Unlike {@link ViolatedAssumptionAnswer}, this answer never throws on
 * unexpected method calls — it always returns a usable value.  This matches the
 * behavior of the ephemeral mocks used by DMoN during the search phase.
 *
 * <p>Overly-broad supertypes ({@code Object}, {@code Serializable}, etc.) return
 * {@code null} to avoid creating opaque proxy objects that cause
 * {@code ClassCastException} when the SUT casts to a concrete type.
 */
public class LenientMockAnswer implements Answer<Object> {

    @Override
    public Object answer(InvocationOnMock invocation) throws Throwable {
        if (isEofReadMethod(invocation)) {
            // Unstubbed Reader/InputStream read() returning 0 can create
            // non-progress loops. Default to EOF; explicit stubs still win.
            return -1;
        }
        return answerForType(invocation.getMethod().getReturnType());
    }

    private static Object answerForType(Class<?> type) {
        if (type == void.class || type == Void.class) {
            return null;
        }
        if (type == String.class || type == CharSequence.class) {
            return "";
        }
        if (type == boolean.class || type == Boolean.class) {
            return false;
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) 0;
        }
        if (type == short.class || type == Short.class) {
            return (short) 0;
        }
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        if (type == float.class || type == Float.class) {
            return 0.0f;
        }
        if (type == double.class || type == Double.class) {
            return 0.0;
        }
        if (type == char.class || type == Character.class) {
            return '\0';
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (List.class.isAssignableFrom(type)
                || type == Collection.class
                || type == Iterable.class) {
            return new ArrayList<>();
        }
        if (Set.class.isAssignableFrom(type)) {
            return new HashSet<>();
        }
        if (Map.class.isAssignableFrom(type)) {
            return new HashMap<>();
        }
        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }
        // Skip overly-broad supertypes to avoid opaque proxy objects that
        // cause ClassCastException when the SUT casts to a concrete type.
        if (type == Object.class
                || type == java.io.Serializable.class
                || type == Comparable.class
                || type == Cloneable.class) {
            return null;
        }
        if (!Modifier.isFinal(type.getModifiers()) && !type.isPrimitive()) {
            try {
                return Mockito.mock(type, new LenientMockAnswer());
            } catch (Exception expected) {
                // Ignore
            }
        }
        return null;
    }

    private static boolean isEofReadMethod(InvocationOnMock invocation) {
        Method method = invocation.getMethod();
        if (!"read".equals(method.getName())) {
            return false;
        }
        Class<?> returnType = method.getReturnType();
        if (!(returnType == int.class || returnType == Integer.class)) {
            return false;
        }
        Class<?> owner = method.getDeclaringClass();
        return Reader.class.isAssignableFrom(owner)
                || InputStream.class.isAssignableFrom(owner)
                || java.nio.channels.ReadableByteChannel.class.isAssignableFrom(owner);
    }
}
