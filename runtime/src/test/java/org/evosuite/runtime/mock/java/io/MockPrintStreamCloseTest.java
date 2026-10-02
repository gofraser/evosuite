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
 * Verifies that {@link MockPrintStream#close()} finalizes pending encoder state exactly once,
 * that a second explicit {@code close()} is idempotent (no error flag, no replayed bytes), and
 * that a large print's oversized scratch capacity is released afterward; see {@link
 * MockPrintStream#close()}'s own javadoc for the reentrancy and finalization mechanics this
 * guards against. The third test reaches the private {@code encoderSink} field via reflection to
 * read back its retained capacity.
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
        // MockPrintStream actually uses at runtime (declared as ByteArrayOutputStream, but the
        // runtime instance's class can expose more). If that type has no capacity() accessor,
        // this test cannot observe the backing array's size at all -- that is a limitation of
        // this test, not evidence that the sink's retained capacity is actually unbounded.
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
