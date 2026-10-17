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
package org.evosuite.testcase.fm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.mockito.internal.matchers.LocalizedMatcher;
import org.mockito.internal.progress.ArgumentMatcherStorage;
import org.mockito.internal.progress.ThreadSafeMockingProgress;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The source text and the live form of each parameter matcher must be the same Mockito matcher.
 */
public class MethodDescriptorTypeMatcherTest {

    private static ArgumentMatcherStorage storage() {
        return ThreadSafeMockingProgress.mockingProgress().getArgumentMatcherStorage();
    }

    @AfterEach
    public void tearDown() {
        storage().reset();
    }

    /** The matcher that Mockito recorded for the single matcher call of {@code call}. */
    private static ArgumentMatcher<?> recorded(Runnable call) {
        storage().reset();
        call.run();
        List<LocalizedMatcher> matchers = storage().pullLocalizedMatchers();
        assertEquals(1, matchers.size());
        return matchers.get(0).getMatcher();
    }

    private static void assertSameMatcher(ArgumentMatcher<?> expected, ArgumentMatcher<?> actual) {
        assertSame(expected.getClass(), actual.getClass());
        assertEquals(expected.toString(), actual.toString());
        assertEquals(expected.type(), actual.type());
    }

    @Test
    public void sourceTextNamesTheMatcherThatIsExecutedForEveryEntry() throws Exception {
        for (MethodDescriptor.TypeMatcher entry : MethodDescriptor.TypeMatcher.values()) {
            String source = entry.source();
            assertTrue(source.endsWith("()"), source);
            Method named = ArgumentMatchers.class.getMethod(source.substring(0, source.length() - 2));

            ArgumentMatcher<?> fromSource = recorded(() -> {
                try {
                    named.invoke(null);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            ArgumentMatcher<?> live = recorded(entry::live);

            assertSameMatcher(fromSource, live);
            for (Class<?> type : entry.types()) {
                assertEquals(source, MethodDescriptor.matcherStringForType(type), type.getName());
                assertSame(entry, MethodDescriptor.TypeMatcher.of(type));
            }
        }
    }

    @Test
    public void everyTypeHasOneEntry() {
        Set<Class<?>> seen = new HashSet<>();
        for (MethodDescriptor.TypeMatcher entry : MethodDescriptor.TypeMatcher.values()) {
            for (Class<?> type : entry.types()) {
                assertTrue(seen.add(type), type.getName());
            }
        }
        assertEquals(new HashSet<>(Arrays.asList(int.class, Integer.class, long.class, Long.class,
                boolean.class, Boolean.class, double.class, Double.class, float.class, Float.class,
                short.class, Short.class, char.class, Character.class, String.class, List.class,
                Set.class, Map.class, java.util.Collection.class, Iterable.class)), seen);
    }

    public interface Target {
        String take(Object value);

        String take(ArrayList<String> value);

        String take(byte value);

        String take(Byte value);

        String take(int[] value);
    }

    /** Other types, byte and Byte among them, are matched with nullable(Type.class). */
    @Test
    public void otherTypesAreMatchedWithNullableInBothForms() throws Exception {
        for (Class<?> type : new Class<?>[]{Object.class, ArrayList.class, byte.class, Byte.class,
                int[].class}) {
            assertNull(MethodDescriptor.TypeMatcher.of(type));
            assertEquals("nullable(" + type.getCanonicalName() + ".class)",
                    MethodDescriptor.matcherStringForType(type));

            MethodDescriptor descriptor = new MethodDescriptor(Target.class.getMethod("take", type),
                    org.evosuite.utils.generic.GenericClassFactory.get(Target.class));
            assertEquals(MethodDescriptor.matcherStringForType(type), descriptor.getInputParameterMatchers());
            MethodDescriptor.StubTarget target = descriptor.getStubTarget(Target.class,
                    org.evosuite.utils.generic.GenericClassFactory.get(Target.class));
            assertEquals(descriptor.getInputParameterMatchers(), target.getMatchers());
            ArgumentMatcher<?> live = recorded(() -> target.executeMatcher(0));
            ArgumentMatcher<?> fromSource = recorded(() -> ArgumentMatchers.nullable(type));
            assertSameMatcher(fromSource, live);
        }
    }
}
