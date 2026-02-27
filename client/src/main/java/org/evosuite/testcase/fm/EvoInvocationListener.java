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

        DescribedInvocation di = methodInvocationReport.getInvocation();
        MethodDescriptor md = null;

        if (di instanceof InvocationOnMock) {
            InvocationOnMock impl = (InvocationOnMock) di;
            Method method = impl.getMethod();
            md = new MethodDescriptor(method, retvalType);
        } else {
            //hopefully it should never happen
            logger.error("DescribedInvocation is not an instance of InvocationOnMock! {}", di);
            return;
        }

        if (md.getMethodName().equals("finalize")) {
            //ignore it, otherwise if we mock it, we ll end up in a lot of side effects... :(
            return;
        }

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


}
