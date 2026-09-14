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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the &lt;clinit&gt; bookkeeping of {@link ExecutionTracer}: which class is
 * currently being initialized (for diagnostics), and the kill-switch backstop that
 * eventually terminates a static initializer that never returns.
 */
public class ExecutionTracerClassInitTest {

    @AfterEach
    public void tearDown() {
        ExecutionTracer.setKillSwitch(false);
        // clear() resets the clinit depth and the tracked names
        ExecutionTracer.getExecutionTracer().clear();
    }

    @Test
    public void testTracksInnermostClassBeingInitialized() {
        assertNull(ExecutionTracer.getCurrentClassInitName());
        assertEquals("<unknown class>", ExecutionTracer.describeCurrentClassInit());

        ExecutionTracer.enterClassInit("com.example.Outer");
        assertEquals("com.example.Outer", ExecutionTracer.getCurrentClassInitName());

        ExecutionTracer.enterClassInit("com.example.Inner");
        assertEquals("com.example.Inner", ExecutionTracer.getCurrentClassInitName());

        ExecutionTracer.exitClassInit("com.example.Inner");
        assertEquals("com.example.Outer", ExecutionTracer.getCurrentClassInitName());

        ExecutionTracer.exitClassInit("com.example.Outer");
        assertNull(ExecutionTracer.getCurrentClassInitName());
    }

    @Test
    public void testClearResetsClassInitTracking() {
        ExecutionTracer.enterClassInit("com.example.Stuck");
        ExecutionTracer.getExecutionTracer().clear();

        assertNull(ExecutionTracer.getCurrentClassInitName());
        assertEquals("<unknown class>", ExecutionTracer.describeCurrentClassInit());
    }

    @Test
    public void testDeepNestingDoesNotCorruptTracking() {
        int depth = 100; // deliberately beyond the number of names we keep
        for (int i = 0; i < depth; i++) {
            ExecutionTracer.enterClassInit("com.example.Class" + i);
        }
        for (int i = depth - 1; i >= 0; i--) {
            ExecutionTracer.exitClassInit("com.example.Class" + i);
        }

        assertNull(ExecutionTracer.getCurrentClassInitName());
    }

    /**
     * A static initializer that never returns must still be terminated: the kill switch
     * is not honoured immediately inside &lt;clinit&gt;, but after
     * MAX_CLINIT_KILL_SWITCH_CHECKS probes it is.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testKillSwitchEventuallyForcesTimeoutInsideClassInit() {
        ExecutionTracer.enterClassInit("com.example.Stuck");
        ExecutionTracer.setKillSwitch(true);

        // The first probes must not throw, otherwise the class would be left broken
        for (int i = 0; i < 1000; i++) {
            ExecutionTracer.checkTimeout();
        }

        assertThrows(TestCaseExecutor.TimeoutExceeded.class, () -> {
            for (int i = 0; i < 1_000_000; i++) {
                ExecutionTracer.checkTimeout();
            }
        });
    }

    @Test
    public void testKillSwitchThrowsImmediatelyOutsideClassInit() {
        ExecutionTracer.setKillSwitch(true);

        assertThrows(TestCaseExecutor.TimeoutExceeded.class, ExecutionTracer::checkTimeout);
    }

    /**
     * The probe counter that {@link TestCaseExecutor} uses to detect a thread that is
     * swallowing {@code TimeoutExceeded}: it counts every checkTimeout() call seen while
     * the kill switch is active, and is reset whenever the kill switch is (re)armed.
     */
    @Test
    public void testKillSwitchProbeCountTracksCallsWhileArmed() {
        ExecutionTracer.setKillSwitch(true);
        assertEquals(0, ExecutionTracer.getKillSwitchProbeCount());

        for (int i = 1; i <= 5; i++) {
            // outside <clinit>, checkTimeout() throws every time — a SUT that swallows
            // Throwable in a loop calls it again right away, which is exactly what we
            // are counting here.
            assertThrows(TestCaseExecutor.TimeoutExceeded.class, ExecutionTracer::checkTimeout);
            assertEquals(i, ExecutionTracer.getKillSwitchProbeCount());
        }
    }

    @Test
    public void testKillSwitchProbeCountResetsWhenKillSwitchIsReArmed() {
        ExecutionTracer.setKillSwitch(true);
        assertThrows(TestCaseExecutor.TimeoutExceeded.class, ExecutionTracer::checkTimeout);
        assertThrows(TestCaseExecutor.TimeoutExceeded.class, ExecutionTracer::checkTimeout);
        assertEquals(2, ExecutionTracer.getKillSwitchProbeCount());

        ExecutionTracer.setKillSwitch(false);
        assertEquals(2, ExecutionTracer.getKillSwitchProbeCount(),
                "disarming the kill switch should not itself reset the counter");

        ExecutionTracer.setKillSwitch(true);
        assertEquals(0, ExecutionTracer.getKillSwitchProbeCount(),
                "re-arming the kill switch resets the counter for the new test");
    }

    @Test
    public void testKillSwitchProbeCountDoesNotAdvanceWhileDisarmed() {
        ExecutionTracer.setKillSwitch(true); // guarantees a known (zero) starting count
        ExecutionTracer.setKillSwitch(false);

        ExecutionTracer.checkTimeout();
        ExecutionTracer.checkTimeout();
        assertEquals(0, ExecutionTracer.getKillSwitchProbeCount());
    }

    /**
     * Probes taken inside &lt;clinit&gt; count too: {@link TestCaseExecutor} only skips
     * the fast-escalation path there because {@code initialInProtectedBootstrap} already
     * excludes it, not because the counter stops moving.
     */
    @Test
    public void testKillSwitchProbeCountAlsoCountsProbesInsideClassInit() {
        ExecutionTracer.enterClassInit("com.example.Stuck");
        ExecutionTracer.setKillSwitch(true);

        for (int i = 0; i < 10; i++) {
            ExecutionTracer.checkTimeout();
        }

        assertEquals(10, ExecutionTracer.getKillSwitchProbeCount());
    }
}
