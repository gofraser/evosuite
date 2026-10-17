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

import org.evosuite.TestSuiteGenerator.PhaseSkipCause;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestSuiteGeneratorPhaseGateTest {

    private static final int defaultMinFreeMem = Properties.MIN_FREE_MEM;

    @BeforeEach
    public void setUp() {
        Properties.MIN_FREE_MEM = 1;
        TestSuiteGenerator.freeMemoryBytesForTesting = null;
    }

    @AfterEach
    public void tearDown() {
        Properties.MIN_FREE_MEM = defaultMinFreeMem;
        TestSuiteGenerator.freeMemoryBytesForTesting = null;
    }

    @Test
    public void phaseWithTimeAndMemoryRuns() {
        TestSuiteGenerator.freeMemoryBytesForTesting = () -> Long.MAX_VALUE;

        assertEquals(PhaseSkipCause.NONE, new TestSuiteGenerator().checkPhase("a phase", () -> true));
    }

    @Test
    public void timeIsCheckedBeforeMemory() {
        AtomicInteger memoryReadings = new AtomicInteger();
        TestSuiteGenerator.freeMemoryBytesForTesting = () -> {
            memoryReadings.incrementAndGet();
            return 0L;
        };

        assertEquals(PhaseSkipCause.TIMEOUT, new TestSuiteGenerator().checkPhase("a phase", () -> false));
        assertEquals(0, memoryReadings.get());
    }

    @Test
    public void lowMemoryIsTheCauseEvenIfTheCheckUsedUpTheRemainingTime() {
        AtomicBoolean hasTime = new AtomicBoolean(true);
        // The low-memory check collects the heap, which can take long enough for the time
        // budget to run out meanwhile; the phase is still skipped for low memory.
        TestSuiteGenerator.freeMemoryBytesForTesting = () -> {
            hasTime.set(false);
            return 0L;
        };

        assertEquals(PhaseSkipCause.LOW_MEMORY, new TestSuiteGenerator().checkPhase("a phase", hasTime::get));
    }
}
