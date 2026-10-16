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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread of the code under test that outlives its test keeps passing through
 * {@link LoopCounter#checkLoop(int)} while the next test's reset() clears the shared list.
 */
public class LoopCounterConcurrentResetTest {

    @Test
    public void checkLoopSurvivesConcurrentResets() throws Exception {
        LoopCounter lc = LoopCounter.getInstance();
        boolean wasActive = lc.isActivated();
        lc.setActive(true);
        int index = 50;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long end = java.lang.System.nanoTime() + 3_000_000_000L;

        Thread resetter = new Thread(() -> {
            while (java.lang.System.nanoTime() < end && failure.get() == null) {
                lc.reset();
            }
        });
        Thread looper = new Thread(() -> {
            try {
                while (java.lang.System.nanoTime() < end && failure.get() == null) {
                    lc.checkLoop(index);
                }
            } catch (TooManyResourcesException e) {
                // legitimate, the limit may be reached
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            resetter.start();
            looper.start();
            resetter.join();
            looper.join();
        } finally {
            lc.reset();
            lc.setActive(wasActive);
        }
        Assertions.assertNull(failure.get(), String.valueOf(failure.get()));
    }
}
