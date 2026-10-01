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
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A {@link Charset} instance does not need to be discoverable through
 * {@link Charset#forName(String)}: a working encoder/decoder pair can be supplied directly,
 * without registering a {@code java.nio.charset.spi.CharsetProvider}. {@link MockPrintStream}
 * used to resolve the charset it was given back to a name and re-look it up via
 * {@code Charset.forName}, which fails for exactly this kind of charset even though the charset
 * itself works perfectly well. This pins the fix: an unregistered, working {@link Charset} must
 * behave the same in {@link MockPrintStream} as it does in the real {@link PrintStream}.
 */
public class MockPrintStreamUnregisteredCharsetTest {

    /** A functioning charset that is deliberately never registered with a provider. */
    private static final Charset UNREGISTERED = new UnregisteredCharset();

    @Test
    public void theFixtureCharsetIsReallyUnregistered() {
        assertThrows(UnsupportedCharsetException.class,
                () -> Charset.forName(UNREGISTERED.name()));
    }

    @Test
    public void mockPrintStreamAcceptsAnUnregisteredCharsetAndWritesWithIt() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        MockPrintStream out = new MockPrintStream(raw, true, UNREGISTERED);

        out.print("ok");
        out.flush();

        assertArrayEquals(new byte[] { 111, 107 }, raw.toByteArray());
    }

    @Test
    public void matchesRealPrintStreamForAnUnregisteredCharset() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();

        MockPrintStream mock = new MockPrintStream(mockBytes, true, UNREGISTERED);
        PrintStream real = new PrintStream(realBytes, true, UNREGISTERED);

        String text = "ok, cafe 100%";
        mock.print(text);
        real.print(text);
        mock.flush();
        real.flush();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }

    @Test
    public void println_withAnUnregisteredCharsetMatchesRealPrintStream() {
        ByteArrayOutputStream mockBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream realBytes = new ByteArrayOutputStream();

        MockPrintStream mock = new MockPrintStream(mockBytes, true, UNREGISTERED);
        PrintStream real = new PrintStream(realBytes, true, UNREGISTERED);

        mock.println("line one");
        mock.println(42);
        real.println("line one");
        real.println(42);
        mock.flush();
        real.flush();

        assertArrayEquals(realBytes.toByteArray(), mockBytes.toByteArray());
    }

    /**
     * A minimal, working {@link Charset} that is never registered with
     * {@code Charset.forName}: its encoder/decoder simply delegate to US-ASCII. Named with an
     * {@code X-} prefix, the conventional space for private-use charset names, so it is
     * guaranteed not to collide with a real registered provider.
     */
    private static final class UnregisteredCharset extends Charset {

        UnregisteredCharset() {
            super("X-Unregistered-EvoProbe", null);
        }

        @Override
        public boolean contains(Charset cs) {
            return cs instanceof UnregisteredCharset;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1f, 1f) {
                private final CharsetDecoder delegate = StandardCharsets.US_ASCII.newDecoder();

                @Override
                protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
                    return delegate.decode(in, out, false);
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return new CharsetEncoder(this, 1f, 1f) {
                private final CharsetEncoder delegate = StandardCharsets.US_ASCII.newEncoder();

                @Override
                protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) {
                    return delegate.encode(in, out, false);
                }
            };
        }
    }
}
