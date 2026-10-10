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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendering helpers used by {@code toCode()} must be initialised before any test runs, because
 * {@code toCode()} can execute on the test thread, which is {@code Thread.stop}-ed on timeout; a
 * stop during class initialisation leaves the class permanently unusable.
 */
public class RenderingWarmUpTest {

    private static final String HELPER = "org.apache.commons.lang3.StringEscapeUtils";

    /** Loads everything from the test classpath in a fresh loader that shares nothing with ours. */
    private static URLClassLoader isolated() throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String p : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(new File(p).toURI().toURL());
        }
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    private static boolean isInitialised(Class<?> c) throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        Method m = unsafe.getClass().getMethod("shouldBeInitialized", Class.class);
        return !(Boolean) m.invoke(unsafe, c);
    }

    @Test
    public void helperIsNotInitialisedInAFreshLoader() throws Exception {
        try (URLClassLoader loader = isolated()) {
            assertFalse(isInitialised(Class.forName(HELPER, false, loader)),
                    "sanity check: the fresh loader must start uninitialised");
        }
    }

    @Test
    public void creatingTheExecutorInitialisesRenderingHelpers() throws Exception {
        try (URLClassLoader loader = isolated()) {
            Class<?> helper = Class.forName(HELPER, false, loader);
            Class<?> executor = Class.forName(
                    "org.evosuite.testcase.execution.TestCaseExecutor", true, loader);
            executor.getMethod("getInstance").invoke(null);
            assertTrue(isInitialised(helper),
                    "StringEscapeUtils must be initialised before any test is executed");
        }
    }
}
