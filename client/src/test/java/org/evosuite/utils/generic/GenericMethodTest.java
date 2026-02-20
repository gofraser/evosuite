/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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


import org.evosuite.testcase.variable.VariableReference;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

        Assert.assertEquals(Object.class, res);
    }

    @Test
    public void testGetExactReturnType_extend() throws Exception {

        try {
            Method m = C.class.getDeclaredMethod("bar", Object.class);
            Assert.fail();
        } catch (Exception e) {
            //expected
        }

        Method m = C.class.getDeclaredMethod("bar", A.class);

        GenericMethod gm = new GenericMethod(m, C.class);
        Type res = gm.getExactReturnType(m, C.class);
        Assert.assertEquals(A.class, res);
    }

    @Test
    public void testGetExactReturnType_extend2() throws Exception {

        try {
            Method m = D.class.getDeclaredMethod("bar", A.class);
            Assert.fail();
        } catch (Exception e) {
            //expected
        }

        Method m = D.class.getDeclaredMethod("bar", B.class);

        GenericMethod gm = new GenericMethod(m, D.class);
        Type res = gm.getExactReturnType(m, D.class);
        Assert.assertEquals(B.class, res);
    }


    @Test
    public void testGetExactReturnType_staticMethod() throws Exception {

        Method m = A.class.getDeclaredMethod("bar", Object.class);

        GenericMethod gm = new GenericMethod(m, A.class);
        Type res = gm.getExactReturnType(m, A.class);

        //Check if generic types were correctly analyzed/inferred
        Assert.assertNotNull(res);
        WildcardTypeImpl wt = (WildcardTypeImpl) res;
        Assert.assertEquals(0, wt.getLowerBounds().length);
        Assert.assertEquals(1, wt.getUpperBounds().length);

        Class<?> upper = (Class<?>) wt.getUpperBounds()[0];
        Assert.assertEquals(Object.class, upper);
    }

    @Test
    public void testGetExactParameterTypes_staticMethod() throws Exception {
        Method m = A.class.getDeclaredMethod("bar", Object.class);

        GenericMethod gm = new GenericMethod(m, A.class);
        Type res = gm.getExactParameterTypes(m, A.class)[0];

        //Check if generic types were correctly analyzed/inferred
        Assert.assertNotNull(res);
        WildcardTypeImpl wt = (WildcardTypeImpl) res;
        Assert.assertEquals(0, wt.getLowerBounds().length);
        Assert.assertEquals(1, wt.getUpperBounds().length);

        Class<?> upper = (Class<?>) wt.getUpperBounds()[0];
        Assert.assertEquals(Object.class, upper);
    }

    public static class OverloadedTarget {
        public void foo(int x) {}
        public void foo(String s) {}
        public void bar() {}
    }

    @Test
    public void testIsOverloadedWithParameters() throws Exception {
        Method mFooInt = OverloadedTarget.class.getMethod("foo", int.class);
        GenericMethod gmFooInt = new GenericMethod(mFooInt, OverloadedTarget.class);

        // Case 1: Exact match - should return false
        VariableReference varInt = Mockito.mock(VariableReference.class);
        Mockito.doReturn(int.class).when(varInt).getVariableClass();
        Assert.assertFalse("Should be false for exact match", gmFooInt.isOverloaded(Arrays.asList(varInt)));

        // Case 2: Mismatch but overloaded method exists - should return true
        VariableReference varString = Mockito.mock(VariableReference.class);
        Mockito.doReturn(String.class).when(varString).getVariableClass();

        Assert.assertTrue("Should be true for mismatching parameters when overload exists", gmFooInt.isOverloaded(Arrays.asList(varString)));

        // Case 3: No overloading
        Method mBar = OverloadedTarget.class.getMethod("bar");
        GenericMethod gmBar = new GenericMethod(mBar, OverloadedTarget.class);
        Assert.assertFalse("Should be false when no overload exists", gmBar.isOverloaded(new ArrayList<>()));
    }

    @Test
    public void testEqualsAndHashCode() throws Exception {
        Method m = A.class.getDeclaredMethod("bar", Object.class);
        GenericMethod gm1 = new GenericMethod(m, A.class);
        GenericMethod gm2 = new GenericMethod(m, B.class); // Different owner

        Assert.assertEquals(gm1, gm2);
        Assert.assertEquals(gm1.hashCode(), gm2.hashCode());

        Method m2 = B.class.getDeclaredMethod("bar", Object.class); // Different method (declared in B)
        GenericMethod gm3 = new GenericMethod(m2, B.class);

        Assert.assertNotEquals(gm1, gm3);
    }

    @Test
    public void testChangeClassLoader() throws Exception {
        Method m = OverloadedTarget.class.getMethod("foo", int.class);
        GenericMethod gm = new GenericMethod(m, OverloadedTarget.class);
        verifyChangeClassLoader(gm, m);
    }

    @Test
    public void testChangeClassLoader_GenericMethod() throws Exception {
        // public static <T> T bar(T obj)
        Method m = A.class.getDeclaredMethod("bar", Object.class);
        GenericMethod gm = new GenericMethod(m, A.class);
        verifyChangeClassLoader(gm, m);
    }

    @Test
    public void testChangeClassLoader_MethodInGenericClass() throws Exception {
        // public T bar(T t)
        Method m = B.class.getDeclaredMethod("bar", Object.class);
        GenericMethod gm = new GenericMethod(m, B.class);
        verifyChangeClassLoader(gm, m);
    }

    private void verifyChangeClassLoader(GenericMethod gm, Method originalMethod) throws Exception {
        ClassLoader currentLoader = getClass().getClassLoader();
        String[] paths = System.getProperty("java.class.path").split(System.getProperty("path.separator"));
        List<URL> urlList = new ArrayList<>();
        for (String path : paths) {
            try {
                urlList.add(new java.io.File(path).toURI().toURL());
            } catch (Exception e) {
                // ignore
            }
        }
        URL[] urls = urlList.toArray(new URL[0]);
        ClassLoader newLoader = new URLClassLoader(urls, currentLoader.getParent());

        gm.changeClassLoader(newLoader);

        Method newM = gm.getMethod();
        Assert.assertNotEquals("Should be different Method object", originalMethod, newM);
        Assert.assertEquals("Should have same name", originalMethod.getName(), newM.getName());
        Assert.assertArrayEquals("Should have same parameter types",
                originalMethod.getParameterTypes(),
                newM.getParameterTypes());

        // Basic check that we can still invoke it or inspect it
        Assert.assertEquals(originalMethod.getDeclaringClass().getName(), newM.getDeclaringClass().getName());
    }
}