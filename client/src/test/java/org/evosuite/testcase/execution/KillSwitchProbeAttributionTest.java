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
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The kill-switch probe count is the evidence {@link TestCaseExecutor} uses to decide that
 * the thread under test is swallowing {@code TimeoutExceeded}, and it skips the whole
 * graceful shutdown chain in favour of {@code Thread.stop()} on the strength of it.
 *
 * <p>The counter lives on a process-wide singleton and {@link ExecutionTracer#checkTimeout()}
 * is reachable from any thread: {@code NonTargetClassAdapter} puts a bare
 * {@code INVOKESTATIC checkTimeout()} at every line of every non-target class it
 * instruments, with no thread check. A background SUT thread running one such line while
 * the thread under test sits blocked in I/O must not be read as proof that the thread under
 * test swallowed anything.
 */
public class KillSwitchProbeAttributionTest {

    private Thread trackedBefore;

    /**
     * The tracked thread is process-wide state that later tests read through
     * {@link ExecutionTracer#isThreadNeqCurrentThread()} to decide whether to trace at
     * all, so it has to go back exactly as it was rather than be left pointing here.
     */
    @BeforeEach
    public void rememberTrackedThread() throws Exception {
        Field field = ExecutionTracer.class.getDeclaredField("currentThread");
        field.setAccessible(true);
        trackedBefore = (Thread) field.get(null);
    }

    @AfterEach
    public void disarm() {
        ExecutionTracer.setKillSwitch(false);
        ExecutionTracer.setCheckCallerThread(true);
        ExecutionTracer.setThread(trackedBefore);
    }

    @Test
    public void aProbeFromTheThreadUnderTestCounts() {
        ExecutionTracer.setThread(Thread.currentThread());
        ExecutionTracer.setKillSwitch(true);
        assertEquals(0, ExecutionTracer.getKillSwitchProbeCount());

        probe();

        assertEquals(1, ExecutionTracer.getKillSwitchProbeCount());
    }

    @Test
    public void aProbeFromABackgroundSutThreadDoesNot() throws InterruptedException {
        ExecutionTracer.setThread(Thread.currentThread());
        ExecutionTracer.setKillSwitch(true);
        assertEquals(0, ExecutionTracer.getKillSwitchProbeCount());

        Thread background = new Thread(KillSwitchProbeAttributionTest::probe, "sut-background");
        background.start();
        background.join();

        assertEquals(0, ExecutionTracer.getKillSwitchProbeCount(),
                "a background SUT thread's probe says nothing about the thread under test");
    }

    /** Stands in for the {@code checkTimeout()} the instrumenter inserts at every line. */
    private static void probe() {
        try {
            ExecutionTracer.checkTimeout();
        } catch (Throwable expected) {
            // checkTimeout still throws TimeoutExceeded on every thread; only the
            // counting is restricted. Swallowing it is exactly what the SUT does.
        }
    }
}
