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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PhaseMemoryGuardTest {

    @Test
    public void enoughMemoryNeedsNoCollection() {
        AtomicInteger collections = new AtomicInteger();
        PhaseMemoryGuard guard = new PhaseMemoryGuard(() -> 200L, collections::incrementAndGet, 100L);

        assertFalse(guard.isExhausted());
        assertFalse(guard.isExhausted());
        assertEquals(0, collections.get());
    }

    @Test
    public void lowMemoryIsCollectedAtMostOnceAndThenStaysExhausted() {
        AtomicInteger collections = new AtomicInteger();
        AtomicLong free = new AtomicLong(50L);
        PhaseMemoryGuard guard = new PhaseMemoryGuard(free::get, () -> {
            collections.incrementAndGet();
            free.set(150L);
        }, 100L);

        // Garbage: one collection frees enough.
        assertFalse(guard.isExhausted());
        free.set(50L);
        // Low again: no second collection in the same phase.
        assertTrue(guard.isExhausted());
        free.set(1_000L);
        // Latched for the rest of the phase.
        assertTrue(guard.isExhausted());
        assertEquals(1, collections.get());
    }

    @Test
    public void memoryThatStaysLowAfterCollectionIsExhausted() {
        AtomicInteger collections = new AtomicInteger();
        PhaseMemoryGuard guard = new PhaseMemoryGuard(() -> 10L, collections::incrementAndGet, 100L);

        assertTrue(guard.isExhausted());
        assertTrue(guard.isExhausted());
        assertEquals(1, collections.get());
        assertEquals(10L, guard.getLastFreeBytes());
    }
}
