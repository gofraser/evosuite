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
package org.evosuite.runtime.mock.java.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * {@code MockPrintStream(OutputStream, boolean, Charset)} used to delegate straight to
 * {@code PrintStream}'s own {@code (OutputStream, boolean, Charset)} constructor, which is a
 * Java 10 API absent from Java 8 (confirmed with {@code javap} against Corretto 8). It now
 * routes through the Java 8 {@code (OutputStream, boolean, String)} constructor via
 * {@link java.nio.charset.Charset#name()} instead. These tests pin the observable behaviour --
 * bytes written through a non-trivial charset -- so a future change to that constructor cannot
 * silently alter what gets written.
 */
public class MockPrintStreamCharsetTest {

    @Test
    public void writesUsingTheGivenCharset() throws UnsupportedEncodingException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        MockPrintStream out = new MockPrintStream(raw, true, StandardCharsets.UTF_16);

        out.print("héllo €");
        out.flush();

        byte[] expected = "héllo €".getBytes(StandardCharsets.UTF_16);
        assertArrayEquals(expected, raw.toByteArray());
    }

    @Test
    public void matchesRealPrintStreamForTheSameCharset() throws UnsupportedEncodingException {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();

        MockPrintStream mock = new MockPrintStream(mockBytes, true, StandardCharsets.UTF_8);
        PrintStream real = new PrintStream(realBytes, true, StandardCharsets.UTF_8);

        String text = "café € 100%";
        mock.print(text);
        real.print(text);
        mock.flush();
        real.flush();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }
}
