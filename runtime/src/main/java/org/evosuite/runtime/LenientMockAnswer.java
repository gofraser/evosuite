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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Lenient default answer for DMoN-promoted functional mocks.  Returns sensible
 * non-null values for all return types: empty strings, zero for numerics, empty
 * collections, and recursively mocked objects for non-final concrete types.
 *
 * <p>Unlike {@link ViolatedAssumptionAnswer}, this answer never throws on
 * unexpected method calls — it always returns a usable value.  During the search
 * the promoted mock answers with a subclass of this answer, so that the search and
 * the generated test see the same values.  The ephemeral mocks that DMoN creates
 * to probe a failure use an answer of their own, which differs on purpose.
 *
 * <p>Overly-broad supertypes ({@code Object}, {@code Serializable}, etc.) return
 * {@code null} to avoid creating opaque proxy objects that cause
 * {@code ClassCastException} when the SUT casts to a concrete type.
 */
public class LenientMockAnswer implements Answer<Object> {

    /**
     * Number of mocks that may hang off one another through unstubbed accessors. A
     * {@code getParent()} that always hands out a fresh mock has no end, so code that recurses or
     * loops up the chain (JOptionPane.getDesktopPaneForComponent) would never return.
     */
    public static final int MAX_CHAIN = 16;

    private final int chain;

    public LenientMockAnswer() {
        this(0);
    }

    /**
     * @param chain the number of mocks that the mock answering with this hangs off
     */
    protected LenientMockAnswer(int chain) {
        this.chain = chain;
    }

    /**
     * The answer of a mock that this answer hands out.
     *
     * @param chain the number of mocks that mock hangs off
     */
    protected LenientMockAnswer forMockAt(int chain) {
        return new LenientMockAnswer(chain);
    }

    /**
     * Whether an unstubbed call may return a mock of {@code type}, a reference type that is not
     * handled otherwise.
     */
    protected boolean canMock(Class<?> type) {
        return !Modifier.isFinal(type.getModifiers()) && !UnmockableTypes.isUnmockable(type);
    }

    @Override
    public Object answer(InvocationOnMock invocation) throws Throwable {
        if (isEofReadMethod(invocation)) {
            // Unstubbed Reader/InputStream read() returning 0 can create
            // non-progress loops. Default to EOF; explicit stubs still win.
            return -1;
        }
        return answerForType(invocation.getMethod().getReturnType(), chain);
    }

    private Object answerForType(Class<?> type, int chain) {
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
        if (chain < MAX_CHAIN && !type.isPrimitive() && canMock(type)) {
            try {
                return Mockito.mock(type, forMockAt(chain + 1));
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
