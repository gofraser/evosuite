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

import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-initializes Mockito's subclass mock maker outside any per-test timeout.
 *
 * <p>The first Mockito mock creation can spend seconds loading shaded Byte Buddy
 * classes and plugins from the client jar. Doing that work lazily inside the
 * timed execution path makes an otherwise healthy test look stuck.
 */
public final class MockitoWarmUp {

    private static final Logger logger = LoggerFactory.getLogger(MockitoWarmUp.class);

    private static boolean warmedUp;
    private static int warmUpExecutionCount;

    private MockitoWarmUp() {
        // Utility class
    }

    public static synchronized void warmUp() {
        if (warmedUp) {
            return;
        }

        warmUpExecutionCount++;
        try {
            Mockito.mock(WarmUpTarget.class, Mockito.withSettings().stubOnly());
            warmedUp = true;
            logger.debug("Mockito warm-up completed.");
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable t) {
            logger.warn("Mockito warm-up failed; lazy initialization will continue during test execution: {}",
                    t.toString());
            logger.debug("Mockito warm-up failure details", t);
        }
    }

    static synchronized void resetForTests() {
        warmedUp = false;
        warmUpExecutionCount = 0;
    }

    static synchronized boolean isWarmedUpForTests() {
        return warmedUp;
    }

    static synchronized int getWarmUpExecutionCountForTests() {
        return warmUpExecutionCount;
    }

    static class WarmUpTarget {
        String ping() {
            return "pong";
        }
    }
}
