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

package org.evosuite.testcase.statements;

import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Mockito mock of a ClassLoader subclass aborts the JVM in HotSpot's
 * ModuleEntry::create_unnamed_module, so such types must never be mocked.
 */
public class FunctionalMockClassLoaderTest {

    public abstract static class AbstractLoader extends ClassLoader {
    }

    public static class CustomLoader extends URLClassLoader {
        public CustomLoader() {
            super(new java.net.URL[0]);
        }
    }

    @Test
    public void classLoadersAreNotMockable() {
        assertFalse(FunctionalMockStatement.canBeFunctionalMocked(ClassLoader.class));
        assertFalse(FunctionalMockStatement.canBeFunctionalMocked(URLClassLoader.class));
        assertFalse(FunctionalMockStatement.canBeFunctionalMocked(AbstractLoader.class));
        assertFalse(FunctionalMockStatement.canBeFunctionalMocked(CustomLoader.class));
        assertFalse(FunctionalMockStatement.canBeFunctionalMockedIncludingSUT(CustomLoader.class));
    }

    public static class Worker extends Thread {
        public int progress() {
            return 0;
        }
    }

    @Test
    public void threadsStayMockable() {
        // A Thread mock is harmless (start() on it merely throws), so a SUT class that extends
        // Thread must remain available as a functional mock.
        assertTrue(FunctionalMockStatement.canBeFunctionalMockedIncludingSUT(Worker.class));
        assertTrue(FunctionalMockStatement.canBeFunctionalMocked(Worker.class));
        assertTrue(FunctionalMockStatement.canBeFunctionalMocked(Thread.class));
    }

    @Test
    public void modulesAreNotMockable() {
        // java.lang.Module is final and not instrumented, so the final-class rule rejects it
        assertFalse(FunctionalMockStatement.canBeFunctionalMocked(Module.class));
    }
}
