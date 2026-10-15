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
package org.evosuite;

import org.evosuite.continuous.CtgException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvoSuiteFatalCrashTest {

    @Test
    void exitsWithFailureStatusAfterReporting() {
        List<Integer> exits = new ArrayList<>();
        List<Throwable> reported = new ArrayList<>();
        Throwable crash = new IllegalStateException("boom");

        EvoSuite.exitAfterFatalCrash(crash, reported::add, exits::add);

        assertEquals(1, reported.size());
        assertEquals(crash, reported.get(0));
        assertEquals(1, exits.size());
        assertEquals(-1, (int) exits.get(0));
    }

    @Test
    void exitsWhenReportingRunsOutOfMemoryItself() {
        List<Integer> exits = new ArrayList<>();

        // The main thread died here with the catch block's own logging failing under memory
        // exhaustion, and the JVM then stayed alive on its non-daemon RMI threads.
        EvoSuite.exitAfterFatalCrash(new OutOfMemoryError("Java heap space"), t -> {
            throw new OutOfMemoryError("Java heap space");
        }, exits::add);

        assertEquals(1, exits.size());
        assertEquals(-1, (int) exits.get(0));
    }

    @Test
    void ctgErrorsReportTheirMessageAndExitWithFailure() {
        List<Integer> exits = new ArrayList<>();
        List<String> reported = new ArrayList<>();

        EvoSuite.exitAfterCtgError(new CtgException("Failed to create tmp folders in /x"), reported::add,
                exits::add);

        assertEquals(1, reported.size());
        assertEquals("Failed to create tmp folders in /x", reported.get(0));
        assertEquals(1, exits.size());
        assertEquals(1, (int) exits.get(0));
    }
}
