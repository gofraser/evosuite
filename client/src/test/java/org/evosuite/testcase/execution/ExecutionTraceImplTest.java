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

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.setup.CallContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class ExecutionTraceImplTest {

    @Test
    public void testCloneCopiesAllFields() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.getTrueDistances().put(1, 10.0);
        impl.getFalseDistances().put(2, 20.0);
        impl.setExplicitException(new RuntimeException("Test Exception"));
        
        CallContext ctx = new CallContext(new StackTraceElement[0]);
        Map<CallContext, Integer> contextMap = new HashMap<>();
        contextMap.put(ctx, 5);
        impl.getMethodContextCount().put("methodCall", contextMap);

        ExecutionTraceImpl clone = impl.clone();

        Assertions.assertEquals(10.0, clone.getTrueDistances().get(1), 0.001);
        Assertions.assertEquals(20.0, clone.getFalseDistances().get(2), 0.001);
        Assertions.assertNotNull(clone.getExplicitException());
        Assertions.assertEquals("Test Exception", clone.getExplicitException().getMessage());
        Assertions.assertEquals(5, clone.getMethodContextCount().get("methodCall").get(ctx).intValue());
        
        // Make sure it's a deep copy, not referring to the same map instances
        clone.getTrueDistances().put(1, 15.0);
        Assertions.assertEquals(10.0, impl.getTrueDistances().get(1), 0.001);
        
        clone.getMethodContextCount().get("methodCall").put(ctx, 10);
        Assertions.assertEquals(5, impl.getMethodContextCount().get("methodCall").get(ctx).intValue());
    }

    @Test
    public void testClearResetsAllFields() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.getTrueDistances().put(1, 10.0);
        impl.getFalseDistances().put(2, 20.0);
        impl.setExplicitException(new RuntimeException("Test Exception"));
        
        CallContext ctx = new CallContext(new StackTraceElement[0]);
        Map<CallContext, Integer> contextMap = new HashMap<>();
        contextMap.put(ctx, 5);
        impl.getMethodContextCount().put("methodCall", contextMap);

        impl.clear();

        Assertions.assertTrue(impl.getTrueDistances().isEmpty());
        Assertions.assertTrue(impl.getFalseDistances().isEmpty());
        Assertions.assertNull(impl.getExplicitException());
        Assertions.assertTrue(impl.getMethodContextCount().isEmpty());
    }

    @Test
    public void withoutMethodCallsKeepsAggregatesAndDropsCallRecords() {
        boolean traceCallsBefore = ExecutionTraceImpl.isTraceCallsEnabled();
        ExecutionTraceImpl.enableTraceCalls();
        try {
            ExecutionTraceImpl impl = new ExecutionTraceImpl();
            impl.enteredMethod("Foo", "bar()V", null);
            impl.linePassed("Foo", "bar()V", 7);
            impl.branchPassed(3, 11, 0.0, 4.0);
            impl.exitMethod("Foo", "bar()V");
            Assertions.assertEquals(1, impl.getMethodCalls().size());
            Assertions.assertEquals(1, impl.getMethodCalls().get(0).branchTrace.size());

            ExecutionTraceImpl compact = impl.withoutMethodCalls();

            Assertions.assertTrue(compact.getMethodCalls().isEmpty());
            Assertions.assertTrue(compact.getMethodCalls("Foo", "bar()V").isEmpty());
            Assertions.assertTrue(compact.getCoveredLines("Foo").contains(7));
            Assertions.assertTrue(compact.getCoveredMethods().contains("Foo.bar()V"));
            Assertions.assertEquals(0.0, compact.getTrueDistances().get(3), 0.0);
            Assertions.assertEquals(4.0, compact.getFalseDistances().get(3), 0.0);
            Assertions.assertTrue(compact.getCoveredTrueBranches().contains(3));
            // the original is untouched
            Assertions.assertEquals(1, impl.getMethodCalls().size());

            ExecutionTrace viaProxy = new ExecutionTraceProxy(impl).withoutMethodCalls();
            Assertions.assertTrue(viaProxy.getMethodCalls().isEmpty());
            Assertions.assertTrue(viaProxy.getCoveredLines("Foo").contains(7));
        } finally {
            if (traceCallsBefore) {
                ExecutionTraceImpl.enableTraceCalls();
            } else {
                ExecutionTraceImpl.disableTraceCalls();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static LinkedList<MethodCall> stackOf(ExecutionTraceImpl trace) throws Exception {
        Field field = ExecutionTraceImpl.class.getDeclaredField("stack");
        field.setAccessible(true);
        return (LinkedList<MethodCall>) field.get(trace);
    }

    /**
     * Branch contexts are recorded when method calls are instrumented (CBRANCH as an analysis
     * criterion), so each entry has to keep the context of its stack up to date then too;
     * otherwise every branch rebuilds the context from the whole stack.
     */
    @Test
    public void instrumentedMethodCallsAloneKeepTheContextOfEachEntry() throws Exception {
        boolean instrumentContext = Properties.INSTRUMENT_CONTEXT;
        boolean instrumentMethodCalls = Properties.INSTRUMENT_METHOD_CALLS;
        Criterion[] criteria = Properties.CRITERION;
        boolean contextDisabled = ExecutionTraceImpl.disableContext;
        try {
            Properties.INSTRUMENT_CONTEXT = false;
            Properties.INSTRUMENT_METHOD_CALLS = true;
            Properties.CRITERION = new Criterion[]{Criterion.BRANCH};
            ExecutionTraceImpl.disableContext = false;

            ExecutionTraceImpl impl = new ExecutionTraceImpl();
            impl.enteredMethod("com.a.A", "f()V", null);
            impl.enteredMethod("com.a.B", "g()V", null);
            impl.branchPassed(3, 11, 0.0, 4.0);

            Assertions.assertNotNull(stackOf(impl).peek().contextState);
            Assertions.assertEquals(1, impl.getPredicateContextExecutionCount().get(3).size());
            Assertions.assertTrue(impl.getMethodContextCount().containsKey("com.a.B.g()V"));
        } finally {
            Properties.INSTRUMENT_CONTEXT = instrumentContext;
            Properties.INSTRUMENT_METHOD_CALLS = instrumentMethodCalls;
            Properties.CRITERION = criteria;
            ExecutionTraceImpl.disableContext = contextDisabled;
        }
    }

    /**
     * A finished call is kept in the trace, and traces are cached with their tests; the context
     * state it held while on the stack references the context chain of every call below it.
     */
    @Test
    public void finishedCallsDoNotKeepTheirContext() throws Exception {
        boolean instrumentContext = Properties.INSTRUMENT_CONTEXT;
        boolean contextDisabled = ExecutionTraceImpl.disableContext;
        boolean traceCallsBefore = ExecutionTraceImpl.isTraceCallsEnabled();
        try {
            Properties.INSTRUMENT_CONTEXT = true;
            ExecutionTraceImpl.disableContext = false;
            ExecutionTraceImpl.enableTraceCalls();

            ExecutionTraceImpl impl = new ExecutionTraceImpl();
            impl.enteredMethod("com.a.A", "f()V", null);
            impl.enteredMethod("com.a.B", "g()V", null);
            impl.enteredMethod("com.a.C", "h()V", null);
            Assertions.assertNotNull(stackOf(impl).peek().contextState);
            impl.exitMethod("com.a.C", "h()V");
            impl.linePassed("com.a.A", "f()V", 3); // pops g, which did not report its exit
            impl.finishCalls();

            Field contextCall = MethodCall.class.getDeclaredField("contextCall");
            contextCall.setAccessible(true);
            Assertions.assertEquals(4, impl.getMethodCalls().size()); // with the main frame
            for (MethodCall call : impl.getMethodCalls()) {
                Assertions.assertNull(call.contextState, call.className);
                Assertions.assertNull(contextCall.get(call), call.className);
            }
        } finally {
            Properties.INSTRUMENT_CONTEXT = instrumentContext;
            ExecutionTraceImpl.disableContext = contextDisabled;
            if (traceCallsBefore) {
                ExecutionTraceImpl.enableTraceCalls();
            } else {
                ExecutionTraceImpl.disableTraceCalls();
            }
        }
    }
}
