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

import org.evosuite.utils.ParameterizedTypeImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.DescribedInvocation;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.listeners.MethodInvocationReport;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * Created by Andrea Arcuri on 27/07/15.
 */
public class EvoInvocationListenerTest {

    public interface Foo {
        int parseString(String s);

        int parseString(String s, boolean flag);

        int parseString(String s, Object obj);

        int parseString(String s, Foo foo);
    }

    public class AClassWithFinal {
        public final boolean getFoo() {
            return true;
        }
    }

    public interface AGenericClass<T> {
        boolean genericAsInput(T t);
    }

    @Test
    public void testGenerics() {

        ParameterizedTypeImpl type = new ParameterizedTypeImpl(AGenericClass.class, new Type[]{String.class}, null);
        EvoInvocationListener listener = new EvoInvocationListener(type);
        AGenericClass<String> aGenericClass = (AGenericClass<String>) mock(AGenericClass.class, withSettings().invocationListeners(listener));
        when(aGenericClass.genericAsInput(any((Class<String>) type.getActualTypeArguments()[0]))).thenReturn(true);
        listener.activate();

        boolean b = aGenericClass.genericAsInput("foo");
        assertTrue(b);

        List<MethodDescriptor> list = listener.getCopyOfMethodDescriptors();
        Assertions.assertEquals(1, list.size());
    }

    @Test
    public void testRepeatedCallsOfResolvedGenericMethodBuildOneDescriptor() {
        // The descriptor of genericAsInput is stored under its resolved matcher (String), while
        // Mockito reports the erased parameter (Object); repeated calls must still hit the cache.
        ParameterizedTypeImpl type = new ParameterizedTypeImpl(AGenericClass.class, new Type[]{String.class}, null);
        EvoInvocationListener listener = new EvoInvocationListener(type);
        AGenericClass<String> aGenericClass = (AGenericClass<String>) mock(AGenericClass.class, withSettings().invocationListeners(listener));
        listener.activate();

        aGenericClass.genericAsInput("foo");
        aGenericClass.genericAsInput("bar");
        aGenericClass.genericAsInput("baz");

        List<MethodDescriptor> list = listener.getCopyOfMethodDescriptors();
        Assertions.assertEquals(1, list.size());
        Assertions.assertEquals(3, list.get(0).getCounter());
        Assertions.assertEquals(1, listener.getDescriptorsBuiltForTests());
    }

    public abstract static class AnAbstractClass {
        public abstract int abstractMethod();

        public int concreteMethod() {
            return 0;
        }
    }

    @Test
    public void testRepeatedCallsOfIgnoredConcreteMethodBuildOneDescriptor() {
        EvoInvocationListener listener = new EvoAbstractMethodInvocationListener(AnAbstractClass.class);
        AnAbstractClass mock = mock(AnAbstractClass.class, withSettings().invocationListeners(listener));
        listener.activate();

        mock.concreteMethod();
        mock.concreteMethod();
        mock.abstractMethod();
        mock.abstractMethod();

        List<MethodDescriptor> list = listener.getCopyOfMethodDescriptors();
        Assertions.assertEquals(1, list.size());
        Assertions.assertEquals(2, list.get(0).getCounter());
        Assertions.assertEquals(2, listener.getDescriptorsBuiltForTests());
    }

    @Test
    public void testCheckGenericsProperties() throws Exception {
        AGenericClass<String> aGenericClass = (AGenericClass<String>) mock(AGenericClass.class);

        Method m = aGenericClass.getClass().getDeclaredMethod("genericAsInput", Object.class);
        assertEquals("" + Object.class.toString(), m.getParameterTypes()[0].toString());
    }

    @Test
    public void testFinal() {
        /*
            If no special instrumentation is done, we cannot handle final methods
         */
        EvoInvocationListener listener = new EvoInvocationListener(AClassWithFinal.class);
        AClassWithFinal foo = mock(AClassWithFinal.class, withSettings().invocationListeners(listener));
        listener.activate();

        foo.getFoo(); // this is not mocked

        List<MethodDescriptor> list = listener.getCopyOfMethodDescriptors();
        // With Mockito 5, final methods are mocked/intercepted by default if the inline mock maker is active or supported
        // Assert.assertEquals(0, list.size());
        Assertions.assertEquals(1, list.size());
    }


    @Test
    public void testBase() {

        EvoInvocationListener listener = new EvoInvocationListener(Foo.class);
        Foo foo = mock(Foo.class, withSettings().invocationListeners(listener));

        when(foo.parseString(any())).thenReturn(1);
        when(foo.parseString(any(), anyBoolean())).thenReturn(2);
        when(foo.parseString(any(), any(Object.class))).thenReturn(3);
        when(foo.parseString(any(), ArgumentMatchers.nullable(Foo.class))).thenReturn(4);

        List<MethodDescriptor> list = listener.getCopyOfMethodDescriptors();
        Assertions.assertEquals(0, list.size()); //not active yet
        listener.activate();

        int res = foo.parseString("foo");
        Assertions.assertEquals(1, res);

        res = foo.parseString("bar", true);
        Assertions.assertEquals(2, res);

        res = foo.parseString("foo");
        Assertions.assertEquals(1, res);

        res = foo.parseString("bar", new Object());
        Assertions.assertEquals(3, res);

        res = foo.parseString("bar", foo);
        Assertions.assertEquals(4, res);

        res = foo.parseString("bar", null);
        Assertions.assertEquals(4, res);

        list = listener.getCopyOfMethodDescriptors();
        Assertions.assertEquals(4, list.size());
    }

    /**
     * A SUT thread left over from an earlier execution may keep invoking a mock while the
     * search thread reads the listener. Readers must not trip over the writers.
     */
    @Test
    public void testReadingWhileInvocationsAreReported() throws Exception {
        List<Method> methods = new java.util.ArrayList<>();
        for (Method m : java.util.Arrays.class.getMethods()) {
            methods.add(m);
        }
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (int round = 0; round < 200 && failure.get() == null; round++) {
            EvoInvocationListener listener = new EvoInvocationListener(Object.class);
            listener.activate();
            java.util.concurrent.atomic.AtomicBoolean done =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread writer = new Thread(() -> {
                try {
                    for (Method m : methods) {
                        InvocationOnMock invocation = mock(InvocationOnMock.class,
                                withSettings().extraInterfaces(DescribedInvocation.class));
                        when(invocation.getMethod()).thenReturn(m);
                        MethodInvocationReport report = mock(MethodInvocationReport.class);
                        when(report.getInvocation()).thenReturn((DescribedInvocation) invocation);
                        listener.reportInvocation(report);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.set(true);
                }
            });
            writer.start();
            try {
                while (!done.get()) {
                    listener.getCopyOfMethodDescriptors();
                    listener.changeClassLoader(getClass().getClassLoader());
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
            writer.join();
        }
        assertEquals(null, failure.get());
    }
}
