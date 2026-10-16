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
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * {@link MockPrintStream}'s charset constructor used to re-encode every {@code print}/{@code
 * println} fragment independently via {@code String.getBytes(Charset)}. A real {@link
 * PrintStream} keeps one encoder for the stream's whole lifetime instead, so these tests compare
 * the mock's bytes against a real {@code PrintStream} across <em>multiple</em> calls on the same
 * stream -- a single call was never enough to tell the two apart, which is why the per-fragment
 * bug shipped with green tests the first time around.
 *
 * <p>Two encoder-state effects are pinned here: a stateful charset's byte-order mark must be
 * written once, not re-emitted on every call, and a surrogate pair split across two calls must
 * still encode as one code point instead of two replacement characters.
 */
public class MockPrintStreamStreamingStateTest {

    @Test
    public void utf16BomIsNotReemittedAcrossSuccessivePrintCalls() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();
        MockPrintStream mock = new MockPrintStream(mockBytes, true, StandardCharsets.UTF_16);
        PrintStream real = new PrintStream(realBytes, true, StandardCharsets.UTF_16);

        mock.print("A");
        mock.print("B");
        real.print("A");
        real.print("B");
        mock.flush();
        real.flush();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }

    @Test
    public void surrogatePairSplitAcrossTwoPrintCallsEncodesAsOneCodePoint() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();
        MockPrintStream mock = new MockPrintStream(mockBytes, true, StandardCharsets.UTF_8);
        PrintStream real = new PrintStream(realBytes, true, StandardCharsets.UTF_8);

        // U+1F600 GRINNING FACE, split across two print(char) calls.
        char high = '\uD83D';
        char low = '\uDE00';

        mock.print(high);
        mock.print(low);
        real.print(high);
        real.print(low);
        mock.flush();
        real.flush();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
        // F0 9F 98 80 is the correct UTF-8 encoding of U+1F600.
        assertArrayEquals(
                new byte[] { (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80 },
                mockBytes.toByteArray());
    }

    @Test
    public void surrogatePairSplitAcrossTwoPrintlnCallsEncodesAsOneCodePoint() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();
        MockPrintStream mock = new MockPrintStream(mockBytes, true, StandardCharsets.UTF_8);
        PrintStream real = new PrintStream(realBytes, true, StandardCharsets.UTF_8);

        char high = '\uD83D';
        char low = '\uDE00';

        mock.println(high);
        mock.println(low);
        real.println(high);
        real.println(low);
        mock.flush();
        real.flush();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }

    @Test
    public void appendPreservesEncoderStateAcrossCalls() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();
        MockPrintStream mock = new MockPrintStream(mockBytes, true, StandardCharsets.UTF_16);
        PrintStream real = new PrintStream(realBytes, true, StandardCharsets.UTF_16);

        mock.append("A").append("B");
        real.append("A").append("B");
        mock.flush();
        real.flush();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }

    @Test
    public void printfPreservesEncoderStateAcrossCalls() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();
        MockPrintStream mock = new MockPrintStream(mockBytes, true, StandardCharsets.UTF_16);
        PrintStream real = new PrintStream(realBytes, true, StandardCharsets.UTF_16);

        mock.printf("%s", "A");
        mock.printf("%s", "B");
        real.printf("%s", "A");
        real.printf("%s", "B");
        mock.flush();
        real.flush();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }

    @Test
    public void successivePrintlnCallsDoNotReemitTheBom() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();
        MockPrintStream mock = new MockPrintStream(mockBytes, true, StandardCharsets.UTF_16);
        PrintStream real = new PrintStream(realBytes, true, StandardCharsets.UTF_16);

        mock.println("A");
        mock.println("B");
        real.println("A");
        real.println("B");
        mock.flush();
        real.flush();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }
}
