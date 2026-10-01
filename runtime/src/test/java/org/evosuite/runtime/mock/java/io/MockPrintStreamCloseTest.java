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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins two {@code close()} regressions introduced by the persistent-encoder fix in
 * {@code 436b722c2} (fourth-pass review findings 2 and 6).
 *
 * <p>Finding 2: {@link MockPrintStream#close()} drains {@code encoderSink} and writes its bytes,
 * but never resets the sink. {@link PrintStream#close()}'s own (always-present, unused) {@code
 * charOut} writer wraps {@code this} as its destination {@link java.io.OutputStream}, so closing
 * it re-enters this override before the real underlying stream is closed -- and the stale,
 * never-reset bytes get written a second time. A second explicit {@code close()} call replays
 * them a third time, this time against an already-closed stream, which sets the error flag where
 * a real {@link PrintStream} would not.
 *
 * <p>Finding 6: {@code encoderSink} is a plain {@link ByteArrayOutputStream}, whose {@code
 * reset()} clears the logical size but keeps the backing array at its largest-ever capacity for
 * the lifetime of the stream.
 */
public class MockPrintStreamCloseTest {

    @Test
    public void closeFlushesPendingHighSurrogateExactlyOnce() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();
        MockPrintStream mock = new MockPrintStream(mockBytes, false, StandardCharsets.UTF_8);
        PrintStream real = new PrintStream(realBytes, false, StandardCharsets.UTF_8);

        // A lone high surrogate has no partner: it must finalize to exactly one replacement
        // byte on close(), not be silently dropped and not be duplicated.
        mock.print('\uD83D');
        real.print('\uD83D');
        mock.flush();
        real.flush();
        mock.close();
        real.close();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }

    @Test
    public void secondExplicitCloseDoesNotSetErrorFlag() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();
        MockPrintStream mock = new MockPrintStream(mockBytes, false, StandardCharsets.UTF_8);
        PrintStream real = new PrintStream(realBytes, false, StandardCharsets.UTF_8);

        mock.print('\uD83D');
        real.print('\uD83D');
        mock.flush();
        real.flush();
        mock.close();
        real.close();

        // A second close() on an already-closed stream must behave like the real PrintStream:
        // no error, and no further bytes written.
        mock.close();
        real.close();

        assertEquals(real.checkError(), mock.checkError());
        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }

    @Test
    public void drainedSinkReleasesOversizedBackingArray() throws Exception {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        MockPrintStream mock = new MockPrintStream(mockBytes, false, StandardCharsets.UTF_8);

        // One very large print grows the scratch sink far beyond any normal print's needs.
        char[] big = new char[2_000_000];
        java.util.Arrays.fill(big, 'x');
        mock.print(new String(big));
        mock.flush();

        // A later, tiny print still has to work (encoder state must survive), and the sink's
        // oversized backing array must not be retained for the rest of the stream's lifetime.
        mock.print('y');
        mock.flush();

        // java.io.ByteArrayOutputStream's own backing array is not reflectively accessible here
        // without opening java.base/java.io, so capacity is queried through whatever sink type
        // MockPrintStream actually uses -- a plain ByteArrayOutputStream exposes no such thing,
        // which is itself evidence that nothing bounds its retained capacity.
        Field sinkField = MockPrintStream.class.getDeclaredField("encoderSink");
        sinkField.setAccessible(true);
        Object sink = sinkField.get(mock);

        Method capacityMethod;
        try {
            capacityMethod = sink.getClass().getDeclaredMethod("capacity");
        } catch (NoSuchMethodException e) {
            fail("encoder sink (" + sink.getClass()
                    + ") exposes no way to report its retained capacity, "
                    + "so nothing bounds it after a large drain");
            return;
        }
        capacityMethod.setAccessible(true);
        int capacity = (int) capacityMethod.invoke(sink);

        assertTrue(capacity < 2_000_000,
                "backing array should have been released after the drain, but capacity is still "
                        + capacity + " bytes");
    }
}
