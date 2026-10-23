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
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class ExecutionTraceProxyTest {

    @Test
    public void testGetDistances() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.getTrueDistances().put(1, 10.0);
        impl.getFalseDistances().put(1, 20.0);

        ExecutionTraceProxy proxy = new ExecutionTraceProxy(impl);

        Map<Integer, Double> falseDistances = proxy.getFalseDistances();
        Map<Integer, Double> trueDistances = proxy.getTrueDistances();

        Assertions.assertEquals(20.0, falseDistances.get(1), 0.001, "Should return false distances");
        Assertions.assertEquals(10.0, trueDistances.get(1), 0.001, "Should return true distances");
    }
    @Test
    public void testClearSharedProxy() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.getTrueDistances().put(1, 10.0);
        
        ExecutionTraceProxy proxy1 = new ExecutionTraceProxy(impl);
        ExecutionTraceProxy proxy2 = (ExecutionTraceProxy) proxy1.lazyClone();
        
        Assertions.assertEquals(2, impl.getProxyCount());
        
        // Clearing proxy1 should NOT clear impl, but should detach proxy1
        proxy1.clear();
        
        Assertions.assertEquals(1, impl.getProxyCount());
        Assertions.assertEquals(10.0, impl.getTrueDistances().get(1), 0.001);
        Assertions.assertTrue(proxy1.getTrueDistances().isEmpty());
    }

    @Test
    public void testClearSoleOwnerProxy() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.getTrueDistances().put(1, 10.0);
        
        ExecutionTraceProxy proxy1 = new ExecutionTraceProxy(impl);
        
        Assertions.assertEquals(1, impl.getProxyCount());
        
        // Clearing proxy1 SHOULD clear impl
        proxy1.clear();
        
        Assertions.assertEquals(1, impl.getProxyCount());
        Assertions.assertTrue(impl.getTrueDistances().isEmpty());
    }
}
