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
 *
 * <p>A check that stands on its own, rather than being one step of a longer phase, uses a fresh
 * guard each time (see {@link #oneShot(long)}), so each such check may collect the heap once.
 */
public final class PhaseMemoryGuard {

    private final LongSupplier freeHeapBytes;
    private final Runnable collectGarbage;
    private final long thresholdBytes;
    private boolean collected = false;
    private boolean exhausted = false;
    private long lastFreeBytes = -1;

    public PhaseMemoryGuard() {
        this(PhaseMemoryGuard::currentFreeHeapBytes, System::gc, Properties.MIN_FREE_MEM * 2L);
    }

    public PhaseMemoryGuard(LongSupplier freeHeapBytes, Runnable collectGarbage, long thresholdBytes) {
        this.freeHeapBytes = freeHeapBytes;
        this.collectGarbage = collectGarbage;
        this.thresholdBytes = thresholdBytes;
    }

    /**
     * Returns a guard on the current heap for a single check against the given threshold.
     *
     * @param thresholdBytes the free heap below which memory counts as exhausted
     * @return a new guard that has not collected the heap yet
     */
    public static PhaseMemoryGuard oneShot(long thresholdBytes) {
        return new PhaseMemoryGuard(PhaseMemoryGuard::currentFreeHeapBytes, System::gc, thresholdBytes);
    }

    /**
     * Returns whether free heap is below the threshold, collecting the heap first if that has
     * not been done yet in this phase.
     *
     * @return true if the phase should stop because memory is exhausted
     */
    public boolean isExhausted() {
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

    /** Marks memory as exhausted for the rest of the phase, e.g. after an OutOfMemoryError. */
    public void markExhausted() {
        exhausted = true;
    }

    /** Returns whether memory has already been found exhausted, without checking again. */
    public boolean isExhaustedAlready() {
        return exhausted;
    }

    public long getLastFreeBytes() {
        return lastFreeBytes;
    }

    public long getThresholdBytes() {
        return thresholdBytes;
    }

    /** Free heap, including the part the JVM has not claimed from the OS yet. */
    public static long currentFreeHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
    }
}
