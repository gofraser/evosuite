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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bounded variants must produce, for every starting number, the values the mocks of the
 * random generators computed before the arithmetic was shared.
 */
public class RandomTest {

    private static final int[] STARTS = {0, 1, 2, 3, 7, 9, 10, 11, 99, 12345, Integer.MAX_VALUE - 1,
            Integer.MAX_VALUE};

    @BeforeEach
    @AfterEach
    public void reset() {
        Random.reset();
    }

    @Test
    public void testNextIntWithOrigin() {
        for (int start : STARTS) {
            Random.setNextRandom(start);
            Assertions.assertEquals(-5 + start % 17, Random.nextInt(-5, 12));
        }
    }

    @Test
    public void testNextLongWithBound() {
        for (int start : STARTS) {
            Random.setNextRandom(start);
            Assertions.assertEquals(Math.floorMod((long) start, 1000L), Random.nextLong(1000L));
            Random.setNextRandom(start);
            Assertions.assertEquals(Math.floorMod((long) start, Long.MAX_VALUE), Random.nextLong(Long.MAX_VALUE));
        }
    }

    @Test
    public void testNextLongWithOrigin() {
        for (int start : STARTS) {
            Random.setNextRandom(start);
            Assertions.assertEquals(-40L + Math.floorMod((long) start, 100L), Random.nextLong(-40L, 60L));
        }
    }

    @Test
    public void testNextDoubleWithBound() {
        for (int start : STARTS) {
            Random.setNextRandom(start);
            Assertions.assertEquals(((start % 10.0) / 10.0) * 3.5, Random.nextDouble(3.5), 0.0);
        }
    }

    @Test
    public void testNextDoubleWithOrigin() {
        for (int start : STARTS) {
            Random.setNextRandom(start);
            Assertions.assertEquals(-1.5 + ((start % 10.0) / 10.0) * (4.0 - -1.5), Random.nextDouble(-1.5, 4.0), 0.0);
        }
    }

    @Test
    public void testInvalidRangesAreRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> Random.nextInt(3, 3));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Random.nextLong(0L));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Random.nextLong(5L, 5L));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Random.nextDouble(0.0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Random.nextDouble(Double.NaN));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Random.nextDouble(2.0, 1.0));
        Assertions.assertFalse(Random.wasAccessed());
    }
}
