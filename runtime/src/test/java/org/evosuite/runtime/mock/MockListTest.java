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
package org.evosuite.runtime.mock;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.java.lang.MockException;
import org.evosuite.runtime.mock.java.lang.MockRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class MockListTest {

    private static final boolean DEFAULT_JVM = RuntimeSettings.mockJVMNonDeterminism;
    private static final boolean DEFAULT_VFS = RuntimeSettings.useVFS;

    @AfterEach
    public void tearDown() {
        RuntimeSettings.mockJVMNonDeterminism = DEFAULT_JVM;
        RuntimeSettings.useVFS = DEFAULT_VFS;
    }

    @Test
    public void checkGetJVMMocks() {

        RuntimeSettings.mockJVMNonDeterminism = false;
        List<Class<? extends EvoSuiteMock>> list = MockList.getList();
        Assertions.assertFalse(list.contains(MockRuntime.class));
        Assertions.assertFalse(list.contains(MockException.class));

        RuntimeSettings.mockJVMNonDeterminism = true;
        list = MockList.getList();
        Assertions.assertTrue(list.contains(MockRuntime.class));
        Assertions.assertTrue(list.contains(MockException.class));
    }

    @Test
    public void testShouldBeMocked() {
        RuntimeSettings.mockJVMNonDeterminism = true;

        //first try with a override mock
        Assertions.assertTrue(new MockException() instanceof OverrideMock);
        Assertions.assertTrue(MockList.shouldBeMocked(Exception.class.getName()));

        //then try a static replacement one
        Assertions.assertTrue(new MockRuntime() instanceof StaticReplacementMock);
        Assertions.assertTrue(MockList.shouldBeMocked(java.lang.Runtime.class.getName()));
    }

    @Test
    public void testLookupsFollowRuntimeSettings() {
        RuntimeSettings.mockJVMNonDeterminism = true;
        RuntimeSettings.useVFS = false;
        Assertions.assertTrue(MockList.shouldBeMocked(java.lang.Runtime.class.getName()));
        Assertions.assertTrue(MockList.isAMockClass(MockRuntime.class.getCanonicalName()));
        Assertions.assertEquals(MockRuntime.class, MockList.getMockClass(java.lang.Runtime.class.getName()));
        Assertions.assertFalse(MockList.shouldBeMocked(java.io.File.class.getName()));

        // Lookups answered from a cache must not outlive the settings they were computed for
        RuntimeSettings.mockJVMNonDeterminism = false;
        Assertions.assertFalse(MockList.shouldBeMocked(java.lang.Runtime.class.getName()));
        Assertions.assertFalse(MockList.isAMockClass(MockRuntime.class.getCanonicalName()));
        Assertions.assertNull(MockList.getMockClass(java.lang.Runtime.class.getName()));

        RuntimeSettings.useVFS = true;
        Assertions.assertTrue(MockList.shouldBeMocked(java.io.File.class.getName()));

        RuntimeSettings.useVFS = false;
        Assertions.assertFalse(MockList.shouldBeMocked(java.io.File.class.getName()));
    }

    @Test
    public void testListIsACopyThatCallersMayModify() {
        RuntimeSettings.mockJVMNonDeterminism = true;
        List<Class<? extends EvoSuiteMock>> first = MockList.getList();
        int size = first.size();
        Assertions.assertTrue(size > 0);
        first.clear();
        Assertions.assertEquals(size, MockList.getList().size());
        Assertions.assertTrue(MockList.shouldBeMocked(java.lang.Runtime.class.getName()));
    }

    @Test
    public void testRepeatedLookupsAreCheap() {
        // The lookups run for every class and member the test cluster and the
        // instrumentation inspect; rebuilding the mock list on each call made
        // test cluster setup on large classpaths exhaust its time budget.
        RuntimeSettings.mockJVMNonDeterminism = true;
        MockList.shouldBeMocked(java.lang.Runtime.class.getName());
        long start = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            MockList.shouldBeMocked("com.example.NotMocked" + (i % 50));
            MockList.isAMockClass("com.example.NotMocked" + (i % 50));
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        Assertions.assertTrue(millis < 1_000, "40000 lookups took " + millis + " ms");
    }
}
