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
package org.evosuite.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseDeadlineTest {

    @Test
    void afterAddsTheDurationAndSaturatesAtNone() {
        assertEquals(1_500L, PhaseDeadline.after(1_000L, 500L));
        assertEquals(PhaseDeadline.NONE, PhaseDeadline.after(Long.MAX_VALUE - 10L, 11L));
        assertEquals(PhaseDeadline.NONE, PhaseDeadline.after(1_000L, Long.MAX_VALUE));
        assertEquals(-1_000L + 500L, PhaseDeadline.after(-1_000L, 500L));
    }

    @Test
    void negativeDurationCountsAsZero() {
        assertEquals(1_000L, PhaseDeadline.after(1_000L, -5L));
    }

    @Test
    void noDeadlineNeverExpiresAndHasUnboundedTimeLeft() {
        assertFalse(PhaseDeadline.isExpired(PhaseDeadline.NONE));
        assertEquals(Long.MAX_VALUE, PhaseDeadline.remainingNanos(PhaseDeadline.NONE));
        assertEquals(Long.MAX_VALUE, PhaseDeadline.remainingMillis(PhaseDeadline.NONE));
    }

    @Test
    void passedDeadlineIsExpiredWithNoTimeLeft() {
        long passed = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);
        assertTrue(PhaseDeadline.isExpired(passed));
        assertTrue(PhaseDeadline.remainingNanos(passed) < 0L);
        assertEquals(0L, PhaseDeadline.remainingMillis(passed));
    }

    @Test
    void futureDeadlineIsNotExpired() {
        long deadline = PhaseDeadline.fromNow(TimeUnit.MINUTES.toNanos(1));
        assertFalse(PhaseDeadline.isExpired(deadline));
        long remaining = PhaseDeadline.remainingMillis(deadline);
        assertTrue(remaining > 0L && remaining <= TimeUnit.MINUTES.toMillis(1));
    }
}
