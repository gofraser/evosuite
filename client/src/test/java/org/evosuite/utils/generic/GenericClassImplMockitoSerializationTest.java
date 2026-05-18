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
package org.evosuite.utils.generic;

import org.evosuite.Properties;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.*;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.withSettings;

public class GenericClassImplMockitoSerializationTest {

    @Test
    public void shouldSerializeMockitoGeneratedClassAsStableType() throws Exception {
        String previousCp = Properties.CP;
        try {
            Properties.CP = System.getProperty("java.class.path");
            // Use SubclassByteBuddyMockMaker explicitly (by class name, not by
            // alias) to ensure the mock class has $MockitoMock$ in its name.
            // The "mock-maker-subclass" alias resolves to ByteBuddyMockMaker in
            // Mockito 5.x, which is the inline maker — not what we want here.
            Throwable mock = Mockito.mock(Throwable.class, withSettings()
                    .mockMaker("org.mockito.internal.creation.bytebuddy.SubclassByteBuddyMockMaker"));
            Class<?> mockClass = mock.getClass();
            assertTrue(mockClass.getName().contains("$MockitoMock$"));

            GenericClassImpl original = new GenericClassImpl(mockClass);
            GenericClassImpl roundTripped = serializeAndDeserialize(original);

            assertEquals(Throwable.class, roundTripped.getRawClass());
            assertFalse(roundTripped.getRawClass().getName().contains("$MockitoMock$"));
        } finally {
            Properties.CP = previousCp;
        }
    }

    @Test
    public void shouldPreserveNullOwnerTypeForTopLevelParameterizedType() throws Exception {
        Type functionType = new ParameterizedTypeImpl(Function.class, new Type[]{Object.class, Byte.class}, null);

        GenericClassImpl roundTripped = serializeAndDeserialize(new GenericClassImpl(functionType));

        assertEquals(Function.class, roundTripped.getRawClass());
        assertTrue(roundTripped.getType() instanceof ParameterizedType);
        ParameterizedType parameterizedType = (ParameterizedType) roundTripped.getType();
        assertNull(parameterizedType.getOwnerType());
        assertEquals("java.util.function.Function<java.lang.Object, java.lang.Byte>",
                roundTripped.getTypeName());

        Object mock = Mockito.mock(Function.class,
                withSettings().stubOnly().genericTypeToMock(roundTripped.getType()));
        assertNotNull(mock);
    }

    private static GenericClassImpl serializeAndDeserialize(GenericClassImpl value)
            throws IOException, ClassNotFoundException {
        byte[] serialized;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
            oos.flush();
            serialized = baos.toByteArray();
        }

        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            return (GenericClassImpl) ois.readObject();
        }
    }
}
