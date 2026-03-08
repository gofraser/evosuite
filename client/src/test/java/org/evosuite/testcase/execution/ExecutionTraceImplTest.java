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
package org.evosuite.testcase.execution;

import org.evosuite.setup.CallContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class ExecutionTraceImplTest {

    @Test
    public void testCloneCopiesAllFields() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.getTrueDistancesSum().put(1, 10.0);
        impl.getFalseDistancesSum().put(2, 20.0);
        impl.setExplicitException(new RuntimeException("Test Exception"));
        
        CallContext ctx = new CallContext(new StackTraceElement[0]);
        Map<CallContext, Integer> contextMap = new HashMap<>();
        contextMap.put(ctx, 5);
        impl.getMethodContextCount().put("methodCall", contextMap);

        ExecutionTraceImpl clone = impl.clone();

        Assertions.assertEquals(10.0, clone.getTrueDistancesSum().get(1), 0.001);
        Assertions.assertEquals(20.0, clone.getFalseDistancesSum().get(2), 0.001);
        Assertions.assertNotNull(clone.getExplicitException());
        Assertions.assertEquals("Test Exception", clone.getExplicitException().getMessage());
        Assertions.assertEquals(5, clone.getMethodContextCount().get("methodCall").get(ctx).intValue());
        
        // Make sure it's a deep copy, not referring to the same map instances
        clone.getTrueDistancesSum().put(1, 15.0);
        Assertions.assertEquals(10.0, impl.getTrueDistancesSum().get(1), 0.001);
        
        clone.getMethodContextCount().get("methodCall").put(ctx, 10);
        Assertions.assertEquals(5, impl.getMethodContextCount().get("methodCall").get(ctx).intValue());
    }

    @Test
    public void testClearResetsAllFields() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.getTrueDistancesSum().put(1, 10.0);
        impl.getFalseDistancesSum().put(2, 20.0);
        impl.setExplicitException(new RuntimeException("Test Exception"));
        
        CallContext ctx = new CallContext(new StackTraceElement[0]);
        Map<CallContext, Integer> contextMap = new HashMap<>();
        contextMap.put(ctx, 5);
        impl.getMethodContextCount().put("methodCall", contextMap);

        impl.clear();

        Assertions.assertTrue(impl.getTrueDistancesSum().isEmpty());
        Assertions.assertTrue(impl.getFalseDistancesSum().isEmpty());
        Assertions.assertNull(impl.getExplicitException());
        Assertions.assertTrue(impl.getMethodContextCount().isEmpty());
    }
}
