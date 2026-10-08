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
package org.evosuite.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for code under test that wraps System.out and System.err when it is constructed, like
 * a logger that redirects them. Open a {@link Recording} to learn how deeply the streams were
 * wrapped at each construction; the recording is closed by the test that opened it, so no
 * state is shared between tests.
 */
public class StreamWrappingFixture {

    /** A stream that remembers the one it wraps. */
    public static class Redirector extends PrintStream {
        final PrintStream inner;

        Redirector(PrintStream inner) {
            super(new ByteArrayOutputStream());
            this.inner = inner;
        }
    }

    /** The wrapping depths seen by the constructions made while the recording is open. */
    public static final class Recording implements AutoCloseable {
        private final List<Integer> depths = new ArrayList<>();

        public List<Integer> depths() {
            return depths;
        }

        @Override
        public void close() {
            synchronized (StreamWrappingFixture.class) {
                if (current == this) {
                    current = null;
                }
            }
        }
    }

    private static Recording current;

    public static synchronized Recording record() {
        current = new Recording();
        return current;
    }

    public StreamWrappingFixture() {
        System.setOut(new Redirector(System.out));
        System.setErr(new Redirector(System.err));
        synchronized (StreamWrappingFixture.class) {
            if (current != null) {
                current.depths.add(depth(System.out));
            }
        }
    }

    public static int depth(PrintStream s) {
        int d = 0;
        while (s instanceof Redirector) {
            d++;
            s = ((Redirector) s).inner;
        }
        return d;
    }
}
