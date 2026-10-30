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
package org.evosuite.testcarver.wrapper;

import org.evosuite.testcarver.capture.CaptureLog;
import org.evosuite.testcarver.capture.Capturer;
import org.evosuite.testcarver.extraction.CarvingClassLoader;
import org.evosuite.testcarver.wrapper.java.text.DateFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DateFormatWrapperTest {

    @BeforeEach
    public void setUp() {
        Capturer.clear();
        Capturer.startCapture();
    }

    @AfterEach
    public void tearDown() {
        Capturer.clear();
    }

    @Test
    public void testFactoryCallIsCaptured() {
        DateFormat.getDateInstance_final();
        final CaptureLog log = Capturer.stopCapture();

        Assertions.assertTrue(log.methodNames.contains("getDateInstance"));
    }

    /**
     * Carves the code of a class that is instrumented by the carving class loader: the call on the
     * formatter returned by the factory has to be redirected to the wrapper, and has to be recorded.
     */
    @Test
    public void testCallOnFactoryResultIsCapturedInInstrumentedCode() throws Exception {
        final Class<?> client = new CarvingClassLoader().loadClass("com.examples.with.carver.DateFormatClient");
        final Object instance = client.getDeclaredConstructor().newInstance();
        final java.util.Date date = new java.util.Date(0);

        final Object formatted = client.getMethod("formatWithFactory", java.util.Date.class).invoke(instance, date);
        final CaptureLog log = Capturer.stopCapture();

        Assertions.assertEquals(java.text.DateFormat.getDateInstance().format(date), formatted);
        Assertions.assertTrue(log.methodNames.contains("getDateInstance"), "" + log.methodNames);
        Assertions.assertTrue(log.methodNames.contains("format"),
                "the call on the formatter returned by the factory must be captured: " + log.methodNames);
    }
}
