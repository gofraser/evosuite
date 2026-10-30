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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Calls of final methods of the wrapped date classes are redirected to static wrapper methods that take
 * the receiver first. Each call in an instrumented class has to run and has to be recorded.
 */
public class FinalMethodWrapperTest {

    @BeforeEach
    public void setUp() {
        Capturer.clear();
        Capturer.startCapture();
    }

    @AfterEach
    public void tearDown() {
        Capturer.clear();
    }

    private void assertRecorded(String clientMethod, String recordedMethod, Class<?>... parameters)
            throws Exception {
        final Class<?> client = new CarvingClassLoader().loadClass("com.examples.with.carver.FinalMethodClient");
        final Object instance = client.getDeclaredConstructor().newInstance();
        final Method method = client.getMethod(clientMethod, parameters);
        final Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = new java.util.Date(0);
        }
        try {
            method.invoke(instance, arguments);
        } catch (InvocationTargetException e) {
            Assertions.fail(clientMethod + " failed in the instrumented class", e.getCause());
        }
        final CaptureLog log = Capturer.stopCapture();
        Assertions.assertTrue(log.methodNames.contains(recordedMethod),
                recordedMethod + " must be captured: " + log.methodNames);
    }

    @Test
    public void testCalendarSetDate() throws Exception {
        assertRecorded("calendarSetDate", "set");
    }

    @Test
    public void testCalendarSetMinutes() throws Exception {
        assertRecorded("calendarSetMinutes", "set");
    }

    @Test
    public void testCalendarSetSeconds() throws Exception {
        assertRecorded("calendarSetSeconds", "set");
    }

    @Test
    public void testCalendarClear() throws Exception {
        assertRecorded("calendarClear", "clear");
    }

    @Test
    public void testCalendarClearField() throws Exception {
        assertRecorded("calendarClearField", "clear");
    }

    @Test
    public void testCalendarIsSet() throws Exception {
        assertRecorded("calendarIsSet", "isSet");
    }

    @Test
    public void testCalendarSetTime() throws Exception {
        assertRecorded("calendarSetTime", "setTime", java.util.Date.class);
    }

    @Test
    public void testCalendarToInstant() throws Exception {
        assertRecorded("calendarToInstant", "toInstant");
    }

    @Test
    public void testSimpleDateFormatFormatDate() throws Exception {
        assertRecorded("simpleDateFormatFormatDate", "format", java.util.Date.class);
    }

    @Test
    public void testSimpleDateFormatFormatObject() throws Exception {
        assertRecorded("simpleDateFormatFormatObject", "format", java.util.Date.class);
    }

    @Test
    public void testSimpleDateFormatFormatBuffer() throws Exception {
        assertRecorded("simpleDateFormatFormatBuffer", "format", java.util.Date.class);
    }

    @Test
    public void testDateFormatFormatObject() throws Exception {
        assertRecorded("dateFormatFormatObject", "format", java.util.Date.class);
    }
}
