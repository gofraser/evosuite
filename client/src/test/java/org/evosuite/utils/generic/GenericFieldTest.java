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

import org.evosuite.rmi.MasterClassLoader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GenericFieldTest {

    public static class FieldFixture {
        public String existingField;
    }

    public static class GenericBase<T> {
        public String name;
    }

    @Test
    public void testFieldTypeWhenOwnerSuperclassMentionsMissingType() throws Exception {
        // class Owner extends GenericBase<Missing>, where Missing cannot be loaded
        String ownerName = "org/evosuite/utils/generic/MissingArgumentOwner";
        String baseName = GenericBase.class.getName().replace('.', '/');
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ownerName,
                "L" + baseName + "<Lorg/evosuite/utils/generic/NoSuchType;>;", baseName, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, baseName, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        ClassLoader loader = new ClassLoader(GenericFieldTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(ownerName.replace('/', '.'))) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        Class<?> owner = loader.loadClass(ownerName.replace('/', '.'));
        Assertions.assertThrows(TypeNotPresentException.class, owner::getGenericSuperclass);

        GenericField genericField = new GenericField(GenericBase.class.getField("name"), owner);

        assertEquals(String.class, genericField.getFieldType());
    }

    @AfterEach
    public void tearDown() {
        MasterClassLoader.clearForTest();
    }

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

    @Test
    public void shouldUsePlaceholderFieldWhenSerializedFieldIsMissingOnMaster() throws Exception {
        Field field = FieldFixture.class.getDeclaredField("existingField");
        GenericField original = new GenericField(field, FieldFixture.class);

        byte[] serialized = serialize(original);
        byte[] tampered = replaceAsciiToken(serialized, "existingField", "missingFieldX");

        MasterClassLoader.markMasterProcess();

        GenericField recovered = deserialize(tampered);

        assertEquals("unresolvedFieldPlaceholder", recovered.getField().getName());
        assertEquals(SerializationFallbackMember.class, recovered.getField().getDeclaringClass());
    }

    @Test
    public void shouldRemainStrictWhenSerializedFieldIsMissingOnClient() throws Exception {
        Field field = FieldFixture.class.getDeclaredField("existingField");
        GenericField original = new GenericField(field, FieldFixture.class);

        byte[] serialized = serialize(original);
        byte[] tampered = replaceAsciiToken(serialized, "existingField", "missingFieldX");

        Assertions.assertThrows(IllegalStateException.class, () -> deserialize(tampered));
    }

    private static byte[] serialize(GenericField value) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
            oos.flush();
            return baos.toByteArray();
        }
    }

    private static GenericField deserialize(byte[] bytes) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (GenericField) ois.readObject();
        }
    }

    private static byte[] replaceAsciiToken(byte[] input, String from, String to) {
        byte[] source = from.getBytes(StandardCharsets.ISO_8859_1);
        byte[] target = to.getBytes(StandardCharsets.ISO_8859_1);
        if (source.length != target.length) {
            throw new IllegalArgumentException("Replacement token length must match");
        }

        byte[] out = input.clone();
        int index = indexOf(out, source);
        assertTrue(index >= 0, "Serialized payload did not contain expected token");
        System.arraycopy(target, 0, out, index, target.length);
        return out;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
