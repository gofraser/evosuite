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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GenericClassImplDeserializationFallbackTest {

    @Test
    public void shouldFallbackWhenParameterizedFlagIsPresentWithoutPayload() throws Exception {
        GenericClassImpl original = new GenericClassImpl(String.class);
        byte[] malformed = serializeWithForcedParameterizedFlag(original);

        GenericClassImpl recovered = assertDoesNotThrow(() -> deserialize(malformed));
        assertEquals(String.class, recovered.getRawClass());
        assertEquals(String.class, recovered.getType());
    }

    private static byte[] serializeWithForcedParameterizedFlag(GenericClassImpl value) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos) {
                 {
                     enableReplaceObject(true);
                 }

                 @Override
                 protected Object replaceObject(Object obj) {
                     if (Boolean.FALSE.equals(obj)) {
                         return Boolean.TRUE;
                     }
                     return obj;
                 }
             }) {
            oos.writeObject(value);
            oos.flush();
            return baos.toByteArray();
        }
    }

    private static GenericClassImpl deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (GenericClassImpl) ois.readObject();
        }
    }
}
