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
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * {@code MockPrintWriter(OutputStream, boolean, Charset)} used to delegate straight to
 * {@code PrintWriter}'s own {@code (OutputStream, boolean, Charset)} constructor, which is a
 * Java 10 API -- {@code java.io.PrintWriter} has no {@code Charset}-taking constructor at all
 * before Java 10 (confirmed with {@code javap} against Corretto 8). It now wraps the stream in
 * an {@link java.io.OutputStreamWriter} with the given charset instead, the same idiom the
 * private {@code (Charset, File)} constructor in this class already used. These tests pin the
 * observable behaviour -- bytes written through a non-trivial charset -- so a future change to
 * that constructor cannot silently alter what gets written.
 */
public class MockPrintWriterCharsetTest {

    @Test
    public void writesUsingTheGivenCharset() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        MockPrintWriter out = new MockPrintWriter(raw, true, StandardCharsets.UTF_16);

        out.print("héllo €");
        out.flush();

        byte[] expected = "héllo €".getBytes(StandardCharsets.UTF_16);
        assertArrayEquals(expected, raw.toByteArray());
    }

    @Test
    public void matchesRealPrintWriterForTheSameCharset() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();

        MockPrintWriter mock = new MockPrintWriter(mockBytes, true, StandardCharsets.UTF_8);
        PrintWriter real = new PrintWriter(realBytes, true, StandardCharsets.UTF_8);

        String text = "café € 100%";
        mock.print(text);
        real.print(text);
        mock.flush();
        real.flush();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }
}
