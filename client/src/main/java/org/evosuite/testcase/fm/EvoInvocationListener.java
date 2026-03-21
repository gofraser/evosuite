/**
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
package org.evosuite.testcase.fm;

import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.mockito.invocation.DescribedInvocation;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.listeners.InvocationListener;
import org.mockito.listeners.MethodInvocationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * During the test generation, we need to know which methods have been called,
 * and how often they were called.
 * This is however not needed in the final generated JUnit tests.
 *
 * <p>Created by Andrea Arcuri on 27/07/15.
 */
public class EvoInvocationListener implements InvocationListener, Serializable {

    private static final long serialVersionUID = 8351121388007697168L;
    private static final Logger logger = LoggerFactory.getLogger(EvoInvocationListener.class);

    private final Map<String, MethodDescriptor> map = new LinkedHashMap<>();

    /**
     * Maximum total mock invocations per test execution before we throw
     * {@link TooManyResourcesException}.  This catches cases where SUT code
     * calls a mocked method in a tight loop (e.g., {@code iterable.forEach(mockedConsumer)})
     * that the {@link org.evosuite.runtime.LoopCounter} cannot reach because
     * the loop is in uninstrumented JDK code.
     */
    private static final int MAX_INVOCATIONS_PER_TEST = 10_000;

    /**
     * Counts total invocations since last {@link #activate()}.
     * Not volatile — only accessed by the test execution thread.
     */
    private int invocationCount = 0;

    /**
     * By default, we should not log events, otherwise we would end up
     * logging also cases like "when(...)" which are set before a mock is used.
     */
    private volatile boolean active = false;

    private final GenericClass<?> retvalType;

    public EvoInvocationListener(Type retvalType) {
        this.retvalType = GenericClassFactory.get(retvalType);
    }


    public EvoInvocationListener(GenericClass<?> retvalType) {
        this.retvalType = retvalType;
    }

    public void activate() {
        active = true;
        invocationCount = 0;
    }


    /**
     * Changes the class loader.
     *
     * @param loader the new class loader
     */
    public void changeClassLoader(ClassLoader loader) {
        for (MethodDescriptor descriptor : map.values()) {
            if (descriptor != null) {
                descriptor.changeClassLoader(loader);
            }
        }
    }

    /**
     * Summary.
     * @return a sorted list
     */
    public List<MethodDescriptor> getCopyOfMethodDescriptors() {
        return map.values().stream().sorted().collect(Collectors.toList());
    }

    protected boolean onlyMockAbstractMethods() {
        return false;
    }

    @Override
    public void reportInvocation(MethodInvocationReport methodInvocationReport) {

        if (!active) {
            return;
        }

        // Bail out quickly if the thread has been interrupted (e.g., timeout).
        // This runs inside the mock interceptor on the test execution thread,
        // so we want to avoid expensive work when the test is being killed.
        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        // Guard against tight loops in uninstrumented code that call a mock
        // method repeatedly (e.g., Iterable.forEach(mockedConsumer)).  The
        // LoopCounter can't help because the loop back-edge is in JDK code.
        if (++invocationCount > MAX_INVOCATIONS_PER_TEST) {
            throw new TooManyResourcesException(
                    "Mock invocation limit exceeded (" + MAX_INVOCATIONS_PER_TEST
                            + ") — likely an infinite loop calling a mocked method");
        }

        DescribedInvocation di = methodInvocationReport.getInvocation();
        Method method;

        if (di instanceof InvocationOnMock) {
            InvocationOnMock impl = (InvocationOnMock) di;
            method = impl.getMethod();
        } else {
            //hopefully it should never happen
            logger.error("DescribedInvocation is not an instance of InvocationOnMock! {}", di);
            return;
        }

        if (method.getName().equals("finalize")) {
            //ignore it, otherwise if we mock it, we ll end up in a lot of side effects... :(
            return;
        }

        // Build a cheap key from the raw Method to check the cache before
        // constructing an expensive MethodDescriptor.
        String cheapKey = method.getDeclaringClass().getName() + "." + method.getName()
                + "#" + buildRawParamKey(method);

        synchronized (map) {
            MethodDescriptor current = map.get(cheapKey);
            if (current != null) {
                current.increaseCounter();
                return;
            }
        }

        // First time seeing this method — create the descriptor.
        MethodDescriptor md = new MethodDescriptor(method, retvalType);

        if (onlyMockAbstractMethods() && !md.getGenericMethod().isAbstract()) {
            return;
        }

        synchronized (map) {
            MethodDescriptor current = map.get(md.getID());
            if (current == null) {
                current = md;
            }
            current.increaseCounter();
            map.put(md.getID(), current);
        }
    }

    private static String buildRawParamKey(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(MethodDescriptor.matcherStringForType(paramTypes[i]));
        }
        return sb.toString();
    }


}
