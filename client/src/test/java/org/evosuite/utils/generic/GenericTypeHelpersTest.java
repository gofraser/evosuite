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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.utils.generic;

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericTypeHelpersTest {

    @SuppressWarnings("unused")
    private static class Holder<T, U> {
        List<String> parameterized;
        List<String>[] genericArray;
        T[] variableArray;
        T variable;
        U other;
    }

    private static Type fieldType(String name) throws Exception {
        return Holder.class.getDeclaredField(name).getGenericType();
    }

    @Test
    void rawClassOrNullErasesClassesAndParameterizedTypes() throws Exception {
        assertSame(String.class, GenericUtils.rawClassOrNull(String.class));
        assertSame(List.class, GenericUtils.rawClassOrNull(fieldType("parameterized")));
        assertNull(GenericUtils.rawClassOrNull(fieldType("genericArray")));
        assertNull(GenericUtils.rawClassOrNull(fieldType("variable")));
        assertNull(GenericUtils.rawClassOrNull(null));
    }

    @Test
    void rawClassOrObjectErasesGenericArraysAndFallsBackToObject() throws Exception {
        assertSame(List.class, GenericUtils.rawClassOrObject(fieldType("parameterized")));
        assertTrue(fieldType("genericArray") instanceof GenericArrayType);
        assertSame(List[].class, GenericUtils.rawClassOrObject(fieldType("genericArray")));
        assertSame(Object.class, GenericUtils.rawClassOrObject(fieldType("variable")));
        assertSame(Object[].class, GenericUtils.rawClassOrObject(fieldType("variableArray")));
    }

    @Test
    void sameTypeVariableComparesNameAndDeclaration() throws Exception {
        TypeVariable<?> t = (TypeVariable<?>) fieldType("variable");
        TypeVariable<?> u = (TypeVariable<?>) fieldType("other");
        assertTrue(GenericUtils.sameTypeVariable(t, t));
        assertFalse(GenericUtils.sameTypeVariable(t, u));
        assertFalse(GenericUtils.sameTypeVariable(t, null));
        assertFalse(GenericUtils.sameTypeVariable(null, u));
        assertTrue(GenericUtils.sameTypeVariable(null, null));
    }

    @Test
    void sameTypeVariableMatchesDeclarationsFromAnotherClassLoader() throws Exception {
        Class<?> copy = copyInOtherLoader(Holder.class);
        assertNotSame(Holder.class, copy);
        TypeVariable<?> original = Holder.class.getTypeParameters()[0];
        TypeVariable<?> other = copy.getTypeParameters()[0];
        assertTrue(GenericUtils.sameTypeVariable(original, other));
        assertFalse(GenericUtils.sameTypeVariable(Holder.class.getTypeParameters()[1], other));
    }

    @Test
    void isSubtypeByNameFollowsSuperclassesAndInterfaces() {
        assertTrue(GenericClassUtils.isSubtypeByName(ArrayList.class, ArrayList.class));
        assertTrue(GenericClassUtils.isSubtypeByName(Object.class, ArrayList.class));
        assertTrue(GenericClassUtils.isSubtypeByName(Collection.class, ArrayList.class));
        assertTrue(GenericClassUtils.isSubtypeByName(Serializable.class, ArrayList.class));
        assertTrue(GenericClassUtils.isSubtypeByName(Collection.class, List.class));
        assertFalse(GenericClassUtils.isSubtypeByName(ArrayList.class, List.class));
        assertFalse(GenericClassUtils.isSubtypeByName(String.class, ArrayList.class));
    }

    @Test
    void isSubtypeByNameIgnoresClassLoaders() throws Exception {
        Class<?> copy = copyInOtherLoader(ArrayListSubclass.class);
        assertNotSame(ArrayListSubclass.class, copy);
        assertFalse(ArrayListSubclass.class.isAssignableFrom(copy));
        assertTrue(GenericClassUtils.isSubtypeByName(ArrayListSubclass.class, copy));
        assertTrue(GenericClassUtils.isSubtypeByName(ArrayList.class, copy));
        assertFalse(GenericClassUtils.isSubtypeByName(String.class, copy));
    }

    @Test
    void boxedAndUnboxedTypesCoverVoidAndNonWrappers() {
        assertEquals(Integer.class, GenericClassFactory.get(int.class).getBoxedType());
        assertEquals(Void.class, GenericClassFactory.get(void.class).getBoxedType());
        assertEquals(String.class, GenericClassFactory.get(String.class).getBoxedType());
        assertEquals(char.class, GenericClassFactory.get(Character.class).getUnboxedType());
        assertEquals(void.class, GenericClassFactory.get(Void.class).getUnboxedType());
        assertEquals(String.class, GenericClassFactory.get(String.class).getUnboxedType());
        assertTrue(GenericClassFactory.get(Void.class).isWrapperType());
        assertTrue(GenericClassFactory.get(Long.class).isWrapperType());
        assertFalse(GenericClassFactory.get(long.class).isWrapperType());
        assertFalse(GenericClassFactory.get(String.class).isWrapperType());
    }

    static class ArrayListSubclass extends ArrayList<String> {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Loads another copy of the class from the test classes directory in a loader that does not
     * delegate for it, so the copy has the same name but is a different Class object.
     */
    private static Class<?> copyInOtherLoader(Class<?> clazz) throws Exception {
        URL location = clazz.getProtectionDomain().getCodeSource().getLocation();
        String name = clazz.getName();
        ClassLoader loader = new URLClassLoader(new URL[]{location}, GenericTypeHelpersTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
                if (className.equals(name)) {
                    synchronized (getClassLoadingLock(className)) {
                        Class<?> loaded = findLoadedClass(className);
                        if (loaded == null) {
                            loaded = findClass(className);
                        }
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
                return super.loadClass(className, resolve);
            }
        };
        return loader.loadClass(name);
    }
}
