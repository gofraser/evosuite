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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;

public class StandardStreamsTest {

    private PrintStream out;
    private PrintStream err;

    @BeforeEach
    public void setUp() {
        out = java.lang.System.out;
        err = java.lang.System.err;
    }

    @AfterEach
    public void tearDown() {
        java.lang.System.setOut(out);
        java.lang.System.setErr(err);
    }

    private static PrintStream stream() {
        return new RedirectingStream(java.lang.System.out);
    }

    @Test
    public void restoreReinstatesBothStreams() {
        StandardStreams.Snapshot snapshot = StandardStreams.save();
        java.lang.System.setOut(stream());
        java.lang.System.setErr(stream());
        snapshot.restore();
        Assertions.assertSame(out, java.lang.System.out);
        Assertions.assertSame(err, java.lang.System.err);
    }

    @Test
    public void snapshotsAreIndependent() {
        StandardStreams.Snapshot first = StandardStreams.save();
        PrintStream replaced = stream();
        java.lang.System.setOut(replaced);
        StandardStreams.Snapshot second = StandardStreams.save();
        java.lang.System.setOut(stream());
        first.restore();
        Assertions.assertSame(out, java.lang.System.out);
        second.restore();
        Assertions.assertSame(replaced, java.lang.System.out);
    }

    @Test
    public void restoringNullDoesNothing() {
        PrintStream replaced = stream();
        java.lang.System.setOut(replaced);
        StandardStreams.restore(null);
        Assertions.assertSame(replaced, java.lang.System.out);
    }
}
