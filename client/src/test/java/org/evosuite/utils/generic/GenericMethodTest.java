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


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * Created by Andrea Arcuri on 02/07/15.
 */
public class GenericMethodTest {

    private static class A {
        public static <T> T bar(T obj) {
            return obj;
        }
    }

    public static class B<T> {
        public T bar(T t) {
            return t;
        }
    }

    public static class C<T extends A> {
        public T bar(T t) {
            return t;
        }
    }

    public static class D<T extends A> {
        public <T extends B> T bar(T t) {
            return t;
        }
    }


    @Test
    public void testGetExactReturnType() throws Exception {

        Method m = B.class.getDeclaredMethod("bar", Object.class);

        GenericMethod gm = new GenericMethod(m, B.class);
        Type res = gm.getExactReturnType(m, B.class);

        Assertions.assertEquals(Object.class, res);
    }

    @Test
    public void testGetExactReturnType_extend() throws Exception {

        try {
            Method m = C.class.getDeclaredMethod("bar", Object.class);
            Assertions.fail();
        } catch (Exception e) {
            //expected
        }

        Method m = C.class.getDeclaredMethod("bar", A.class);

        GenericMethod gm = new GenericMethod(m, C.class);
        Type res = gm.getExactReturnType(m, C.class);
        Assertions.assertEquals(A.class, res);
    }

    @Test
    public void testGetExactReturnType_extend2() throws Exception {

        try {
            Method m = D.class.getDeclaredMethod("bar", A.class);
            Assertions.fail();
        } catch (Exception e) {
            //expected
        }

        Method m = D.class.getDeclaredMethod("bar", B.class);

        GenericMethod gm = new GenericMethod(m, D.class);
        Type res = gm.getExactReturnType(m, D.class);
        Assertions.assertEquals(B.class, res);
    }


    @Test
    public void testGetExactReturnType_staticMethod() throws Exception {

        Method m = A.class.getDeclaredMethod("bar", Object.class);

        GenericMethod gm = new GenericMethod(m, A.class);
        Type res = gm.getExactReturnType(m, A.class);

        //Check if generic types were correctly analyzed/inferred
        Assertions.assertNotNull(res);
        WildcardTypeImpl wt = (WildcardTypeImpl) res;
        Assertions.assertEquals(0, wt.getLowerBounds().length);
        Assertions.assertEquals(1, wt.getUpperBounds().length);

        Class<?> upper = (Class<?>) wt.getUpperBounds()[0];
        Assertions.assertEquals(Object.class, upper);
    }

    @Test
    public void testGetExactParameterTypes_staticMethod() throws Exception {
        Method m = A.class.getDeclaredMethod("bar", Object.class);

        GenericMethod gm = new GenericMethod(m, A.class);
        Type res = gm.getExactParameterTypes(m, A.class)[0];

        //Check if generic types were correctly analyzed/inferred
        Assertions.assertNotNull(res);
        WildcardTypeImpl wt = (WildcardTypeImpl) res;
        Assertions.assertEquals(0, wt.getLowerBounds().length);
        Assertions.assertEquals(1, wt.getUpperBounds().length);

        Class<?> upper = (Class<?>) wt.getUpperBounds()[0];
        Assertions.assertEquals(Object.class, upper);
    }

    /**
     * Defines {@code class MissingArgumentSubclass extends B<NoSuchType>}, where {@code NoSuchType}
     * cannot be loaded, so its generic superclass throws {@link TypeNotPresentException}.
     */
    private static Class<?> loadSubclassOfBWithMissingArgument() throws ClassNotFoundException {
        String ownerName = "org/evosuite/utils/generic/MissingArgumentSubclass";
        String baseName = B.class.getName().replace('.', '/');
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
        ClassLoader loader = new ClassLoader(GenericMethodTest.class.getClassLoader()) {
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
        return owner;
    }

    @Test
    public void testExactTypesFallBackToErasureWhenOwnerSuperclassMentionsMissingType() throws Exception {
        Class<?> owner = loadSubclassOfBWithMissingArgument();
        Method m = B.class.getDeclaredMethod("bar", Object.class);

        GenericMethod gm = new GenericMethod(m, owner);

        Assertions.assertEquals(Object.class, gm.getExactReturnType(m, owner));
        Assertions.assertArrayEquals(new Type[]{Object.class}, gm.getExactParameterTypes(m, owner));
    }
}