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

import org.evosuite.Properties;
import org.evosuite.testcase.execution.MethodCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When coverage of existing JUnit tests is measured, the test classes and JUnit itself are
 * left out of call contexts. Contexts are built on every method entry, so the excluded
 * prefixes must not be derived from {@link Properties#JUNIT} per call, and the context derived
 * from the caller's must leave out the same frames as the one built from the whole stack.
 */
public class CallContextJUnitExcludesTest {

    private String junit;

    @BeforeEach
    public void saveJUnit() {
        junit = Properties.JUNIT;
    }

    @AfterEach
    public void restoreJUnit() {
        Properties.JUNIT = junit;
    }

    @Test
    public void excludedPrefixesAreComputedOncePerJUnitSetting() {
        Properties.JUNIT = "com.t.FooTest:com.t.BarTest";
        String[] first = CallContext.excludedPrefixes();
        assertSame(first, CallContext.excludedPrefixes());
        assertTrue(Arrays.asList(first).containsAll(Arrays.asList("org.junit", "com.t.FooTest", "com.t.BarTest")));

        Properties.JUNIT = "com.t.BazTest";
        String[] second = CallContext.excludedPrefixes();
        assertNotSame(first, second);
        assertTrue(Arrays.asList(second).contains("com.t.BazTest"));
        assertTrue(!Arrays.asList(second).contains("com.t.FooTest"));

        Properties.JUNIT = "";
        assertTrue(!Arrays.asList(CallContext.excludedPrefixes()).contains("org.junit"));
    }

    @Test
    public void incrementalContextLeavesOutTheSameJUnitFramesAsTheWholeStack() {
        Properties.JUNIT = "com.t.FooTest:com.t.BarTest";
        String[] classes = {"com.a.A", "com.a.B", "com.t.FooTest", "com.t.BarTest$1",
                "org.junit.runners.ParentRunner", "java.lang.reflect.Method"};
        Random random = new Random(7);
        for (int round = 0; round < 200; round++) {
            LinkedList<MethodCall> tracked = new LinkedList<>();
            tracked.add(new MethodCall("", "", 0, 0, 0));
            int depth = 1 + random.nextInt(30);
            for (int i = 0; i < depth; i++) {
                String cls = classes[random.nextInt(classes.length)];
                MethodCall call = new MethodCall(cls, "m", i, 0, tracked.size());
                CallContext.extend(tracked.peek(), call, tracked.size() == 1);
                tracked.push(call);

                LinkedList<MethodCall> plain = new LinkedList<>();
                for (Iterator<MethodCall> it = tracked.descendingIterator(); it.hasNext(); ) {
                    MethodCall original = it.next();
                    plain.push(new MethodCall(original.className, original.methodName, 0, 0, 0));
                }
                CallContext incremental = new CallContext(tracked);
                CallContext whole = new CallContext(plain);
                assertEquals(whole, incremental, "round " + round + " depth " + i);
                assertEquals(whole.hashCode(), incremental.hashCode());
                assertEquals(whole.toString(), incremental.toString());
                // excluded frames are trimmed from both ends of the stack
                if (!whole.isEmpty()) {
                    String root = whole.getRootClassName();
                    assertTrue(!root.startsWith("com.t.") && !root.startsWith("org.junit"), whole.toString());
                }
            }
        }
    }
}
