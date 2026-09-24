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

import java.util.function.LongSupplier;

/**
 * Decides, repeatedly during one post-processing phase, whether free heap has become too low
 * to carry on. The heap is collected explicitly at most once per phase, and once memory has
 * been found exhausted the answer stays so for the rest of the phase: collecting a nearly full
 * heap before every step of a long phase just trades an out-of-memory error for a GC death
 * spiral.
 */
final class PhaseMemoryGuard {

    private final LongSupplier freeHeapBytes;
    private final Runnable collectGarbage;
    private final long thresholdBytes;
    private boolean collected = false;
    private boolean exhausted = false;
    private long lastFreeBytes = -1;

    PhaseMemoryGuard() {
        this(PhaseMemoryGuard::currentFreeHeapBytes, System::gc, Properties.MIN_FREE_MEM * 2L);
    }

    PhaseMemoryGuard(LongSupplier freeHeapBytes, Runnable collectGarbage, long thresholdBytes) {
        this.freeHeapBytes = freeHeapBytes;
        this.collectGarbage = collectGarbage;
        this.thresholdBytes = thresholdBytes;
    }

    /**
     * Returns whether free heap is below the threshold, collecting the heap first if that has
     * not been done yet in this phase.
     *
     * @return true if the phase should stop because memory is exhausted
     */
    boolean isExhausted() {
        if (exhausted) {
            return true;
        }
        lastFreeBytes = freeHeapBytes.getAsLong();
        if (lastFreeBytes >= thresholdBytes) {
            return false;
        }
        if (!collected) {
            collected = true;
            collectGarbage.run();
            lastFreeBytes = freeHeapBytes.getAsLong();
            if (lastFreeBytes >= thresholdBytes) {
                return false;
            }
        }
        exhausted = true;
        return true;
    }

    /** Returns whether memory has already been found exhausted, without checking again. */
    boolean isExhaustedAlready() {
        return exhausted;
    }

    long getLastFreeBytes() {
        return lastFreeBytes;
    }

    long getThresholdBytes() {
        return thresholdBytes;
    }

    private static long currentFreeHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
    }
}
