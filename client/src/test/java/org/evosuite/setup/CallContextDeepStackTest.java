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
package org.evosuite.setup;

import org.evosuite.testcase.execution.MethodCall;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every method entry builds a context from the tracer's call stack. Reading a LinkedList by
 * index is linear, so a deeply recursive method (78_caloriecount's IntComparator calls itself
 * until the stack overflows) cost cubic time in the depth, and every test that called it ran
 * into the timeout.
 */
public class CallContextDeepStackTest {

    /** Counts the indexed reads, which are the expensive ones. */
    private static class CountingList extends LinkedList<MethodCall> {
        private static final long serialVersionUID = 1L;
        int indexedReads = 0;

        @Override
        public MethodCall get(int index) {
            indexedReads++;
            return super.get(index);
        }
    }

    @Test
    public void contextOfADeepStackIsBuiltWithoutIndexedReads() {
        CountingList stack = new CountingList();
        for (int i = 0; i < 5000; i++) {
            stack.push(new MethodCall("com.lts.compare.IntComparator$NormalComparator", "compare", i, 0, i));
        }

        CallContext context = new CallContext(stack);

        assertEquals(0, stack.indexedReads);
        // a sequence of three identical calls is collapsed to two
        assertEquals(2, context.size());
    }

    /**
     * Building the context incrementally while calls are pushed must give what building it from
     * the whole stack gives, whichever frames are skipped (java.*, EvoSuite's own, the empty
     * main frame) and wherever calls repeat.
     */
    @Test
    public void incrementalContextEqualsTheOneBuiltFromTheWholeStack() {
        String[] classes = {"com.a.A", "com.a.B", "java.util.HashMap", "sun.misc.Unsafe", "", "org.evosuite.Foo"};
        String[] methods = {"f", "g"};
        java.util.Random random = new java.util.Random(42);
        for (int round = 0; round < 300; round++) {
            LinkedList<MethodCall> tracked = new LinkedList<>();
            tracked.add(new MethodCall("", "", 0, 0, 0)); // the main frame the tracer starts with
            int depth = 1 + random.nextInt(40);
            for (int i = 0; i < depth; i++) {
                // runs of the same call make the repeats collapse
                String cls = classes[random.nextInt(i % 3 == 0 ? classes.length : 2)];
                String method = methods[random.nextInt(i % 4 == 0 ? methods.length : 1)];
                MethodCall call = new MethodCall(cls, method, i, 0, tracked.size());
                CallContext.extend(tracked.peek(), call, tracked.size() == 1);
                tracked.push(call);

                LinkedList<MethodCall> plain = new LinkedList<>();
                for (java.util.Iterator<MethodCall> it = tracked.descendingIterator(); it.hasNext(); ) {
                    MethodCall original = it.next();
                    plain.push(new MethodCall(original.className, original.methodName, 0, 0, 0));
                }
                CallContext incremental = new CallContext(tracked);
                CallContext whole = new CallContext(plain);
                assertEquals(whole, incremental, "round " + round + " depth " + i);
                assertEquals(whole.toString(), incremental.toString());
                assertEquals(whole.size(), incremental.size());
            }
        }
    }

    /** A call that counts how often its hash is asked for, which is what a context costs per frame. */
    private static final class CountingCall extends Call {
        private static final long serialVersionUID = 1L;
        static int hashes = 0;

        CountingCall(String className, String methodName) {
            super(className, methodName);
        }

        @Override
        public int hashCode() {
            hashes++;
            return super.hashCode();
        }
    }

    private static final class CountingMethodCall extends MethodCall {
        private final Call call;

        CountingMethodCall(String className, String methodName, int depth) {
            super(className, methodName, depth, 0, depth);
            this.call = new CountingCall(className, methodName);
        }

        @Override
        public Call asContextCall() {
            return call;
        }
    }

    /**
     * Two methods calling each other never repeat a call three times in a row, so the context
     * grows with the stack. Every entry builds a context and hashes it into a map; if that walks
     * the context, a recursion of depth n costs n^2 / 2 frame visits.
     */
    @Test
    public void contextOfAMutualRecursionIsDerivedInConstantTimePerEntry() {
        int depth = 3000;
        LinkedList<MethodCall> tracked = new LinkedList<>();
        tracked.add(new MethodCall("", "", 0, 0, 0)); // the main frame the tracer starts with
        CountingCall.hashes = 0;
        CallContext context = null;
        java.util.Map<CallContext, Integer> methodContexts = new java.util.HashMap<>();
        for (int i = 0; i < depth; i++) {
            MethodCall call = i % 2 == 0
                    ? new CountingMethodCall("com.a.A", "f", i)
                    : new CountingMethodCall("com.a.B", "g", i);
            CallContext.extend(tracked.peek(), call, tracked.size() == 1);
            tracked.push(call);
            context = new CallContext(tracked);
            methodContexts.merge(context, 1, Integer::sum);
        }

        assertTrue(CountingCall.hashes <= 2 * depth,
                "hashed " + CountingCall.hashes + " frames for " + depth + " entries");

        LinkedList<MethodCall> plain = new LinkedList<>();
        for (java.util.Iterator<MethodCall> it = tracked.descendingIterator(); it.hasNext(); ) {
            MethodCall original = it.next();
            plain.push(new MethodCall(original.className, original.methodName, 0, 0, 0));
        }
        CallContext whole = new CallContext(plain);
        assertEquals(whole, context);
        assertEquals(whole.hashCode(), context.hashCode());
        assertEquals(depth, context.size());
        assertEquals(whole.toString(), context.toString());
    }

    /** A context derived on method entry holds its calls as a chain that is not serialisable. */
    @Test
    public void contextDerivedOnEntrySurvivesSerialisation() throws Exception {
        LinkedList<MethodCall> tracked = new LinkedList<>();
        tracked.add(new MethodCall("", "", 0, 0, 0));
        for (String cls : new String[]{"com.a.A", "com.a.B", "com.a.C"}) {
            MethodCall call = new MethodCall(cls, "m", 0, 0, tracked.size());
            CallContext.extend(tracked.peek(), call, tracked.size() == 1);
            tracked.push(call);
        }
        CallContext context = new CallContext(tracked);

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(context);
        }
        CallContext copy;
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (CallContext) in.readObject();
        }

        assertEquals(context, copy);
        assertEquals(3, copy.size());
        assertEquals("com.a.A:m com.a.B:m com.a.C:m", copy.toString());
    }

    @Test
    public void contextKeepsTheOrderRootFirst() {
        LinkedList<MethodCall> stack = new LinkedList<>();
        stack.push(new MethodCall("com.example.A", "outer", 0, 0, 0));
        stack.push(new MethodCall("com.example.B", "middle", 1, 0, 1));
        stack.push(new MethodCall("com.example.C", "inner", 2, 0, 2));

        CallContext context = new CallContext(stack);

        assertEquals(3, context.size());
        assertEquals("com.example.A", context.getRootClassName());
        assertEquals("outer", context.getRootMethodName());
    }
}
