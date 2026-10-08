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
package org.evosuite.runtime;

import java.io.PrintStream;

/**
 * Hands System.out and System.err back after code under test replaced them.
 *
 * <p>Code under test may wrap the standard streams when it is constructed or initialised (a
 * logger that redirects them). Unless the streams are put back, one more wrapper is stacked per
 * test and every print fans out through all of them. A caller takes a {@link Snapshot} before
 * the code runs and restores it afterwards. The snapshot is a value and not shared state, so
 * tests running in parallel each use their own.
 */
public final class StandardStreams {

    private StandardStreams() {
    }

    /**
     * The standard streams at the time {@link #save()} was called.
     */
    public static final class Snapshot {
        private final PrintStream out;
        private final PrintStream err;

        private Snapshot(PrintStream out, PrintStream err) {
            this.out = out;
            this.err = err;
        }

        /**
         * Make the saved streams the current System.out and System.err again.
         */
        public void restore() {
            java.lang.System.setOut(out);
            java.lang.System.setErr(err);
        }
    }

    /**
     * Remember the current System.out and System.err.
     *
     * @return the snapshot to restore later
     */
    public static Snapshot save() {
        return new Snapshot(java.lang.System.out, java.lang.System.err);
    }

    /**
     * A snapshot of the given streams, for callers that know better than System which streams
     * are the visible ones.
     *
     * @param out the stream to make System.out
     * @param err the stream to make System.err
     * @return the snapshot to restore later
     */
    public static Snapshot of(PrintStream out, PrintStream err) {
        return new Snapshot(out, err);
    }

    /**
     * Restore a snapshot; does nothing for null so callers need no guard when the matching
     * save never ran.
     *
     * @param snapshot the snapshot taken by {@link #save()}, or null
     */
    public static void restore(Snapshot snapshot) {
        if (snapshot != null) {
            snapshot.restore();
        }
    }
}
