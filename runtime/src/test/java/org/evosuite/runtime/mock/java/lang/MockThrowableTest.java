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
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.mock.MockFramework;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MockThrowableTest {

    @After
    public void tearDown() {
        MockFramework.disable();
    }

    @Test
    public void printStackTraceToWriterPrintsTheRealTraceOnlyWhenMockingIsOff() {
        MockFramework.disable();
        StringWriter out = new StringWriter();
        MockThrowable.replacement_printStackTrace(new IOException("boom"), new PrintWriter(out, true));
        assertTrue(out.toString().startsWith("java.io.IOException: boom"));
        assertFalse(out.toString().contains("<evosuite>"));
    }

    @Test
    public void printStackTraceToStreamPrintsTheRealTraceOnlyWhenMockingIsOff() {
        MockFramework.disable();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MockThrowable.replacement_printStackTrace(new IOException("boom"), new PrintStream(bytes, true));
        String text = bytes.toString();
        assertTrue(text.startsWith("java.io.IOException: boom"));
        assertFalse(text.contains("<evosuite>"));
    }

    @Test
    public void printStackTraceToWriterPrintsTheDefaultTraceOnlyWhenMockingIsOn() {
        MockFramework.enable();
        StringWriter out = new StringWriter();
        MockThrowable.replacement_printStackTrace(new IOException("boom"), new PrintWriter(out, true));
        String frame = "<evosuite>.<evosuite>(<evosuite>)\n";
        assertEquals(frame + frame + frame, out.toString());
    }
}
