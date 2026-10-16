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

import com.googlecode.gentyref.CaptureType;
import com.googlecode.gentyref.GenericTypeReflector;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.utils.StringUtil;

/**
 * Initialises, on the calling thread, the EvoSuite-side helper classes that test-case rendering
 * ({@code TestCase#toCode()}) uses.
 * <p>
 * Rendering also runs on the test-execution thread (for example when logging a test after an
 * out-of-memory error), and that thread is {@code Thread.stop}-ed when a test exceeds its timeout.
 * If the stop lands while one of these classes is being initialised, the class is left in the
 * erroneous state and every later use fails with {@code NoClassDefFoundError} for the lifetime of
 * the JVM. Initialising them up front, from a thread that is never stopped, removes that window.
 */
final class RenderingWarmUp {

    private static volatile boolean done;

    private RenderingWarmUp() {
    }

    static void warmUp() {
        if (done) {
            return;
        }
        try {
            initialise(StringEscapeUtils.class, StringUtils.class, ClassUtils.class, TypeUtils.class,
                    StringUtil.class, GenericTypeReflector.class, CaptureType.class);
            StringUtil.getEscapedString("Ā\n\"");
        } catch (Throwable t) {
            // Warm-up is an optimisation; failing here must never prevent test execution.
        }
        done = true;
    }

    private static void initialise(Class<?>... classes) throws ClassNotFoundException {
        for (Class<?> c : classes) {
            Class.forName(c.getName(), true, c.getClassLoader());
        }
    }
}
