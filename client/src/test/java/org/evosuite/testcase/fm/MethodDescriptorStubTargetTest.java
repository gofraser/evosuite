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

import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

public class MethodDescriptorStubTargetTest {

    public static class Position implements Comparable<Position> {
        @Override
        public int compareTo(Position other) {
            return 0;
        }
    }

    public interface Named {
        String name(int index);
    }

    private static MethodDescriptor descriptor(Method method, Class<?> owner) {
        return new MethodDescriptor(method, GenericClassFactory.get(owner));
    }

    @Test
    public void aMethodReachedThroughABridgeIsStubbedAsTheMethodTheBridgeForwardsTo() throws Exception {
        MethodDescriptor descriptor = descriptor(Comparable.class.getMethod("compareTo", Object.class),
                Position.class);
        GenericClass<?> owner = GenericClassFactory.get(Position.class);

        MethodDescriptor.StubTarget target = descriptor.getStubTarget(Position.class, owner);

        assertEquals(Position.class.getMethod("compareTo", Position.class), target.getMethod());
        assertFalse(target.getMethod().isBridge());
        assertEquals("nullable(" + Position.class.getCanonicalName() + ".class)", target.getMatchers());
        assertSame(target, descriptor.getStubTarget(Position.class, owner), "resolved once");
    }

    @Test
    public void aMethodOfTheMockedTypeIsStubbedAsRecorded() throws Exception {
        Method name = Named.class.getMethod("name", int.class);
        MethodDescriptor descriptor = descriptor(name, Named.class);

        MethodDescriptor.StubTarget target = descriptor.getStubTarget(Named.class,
                GenericClassFactory.get(Named.class));

        assertEquals(name, target.getMethod());
        assertEquals("anyInt()", target.getMatchers());
    }

    @Test
    public void aMethodTheMockedClassDoesNotHaveIsStubbedAsRecorded() throws Exception {
        Method name = Named.class.getMethod("name", int.class);
        MethodDescriptor descriptor = descriptor(name, Named.class);

        MethodDescriptor.StubTarget target = descriptor.getStubTarget(Position.class,
                GenericClassFactory.get(Position.class));

        assertEquals(name, target.getMethod());
        assertEquals("anyInt()", target.getMatchers());
    }
}
