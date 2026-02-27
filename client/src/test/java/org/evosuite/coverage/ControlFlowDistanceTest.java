/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.coverage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ControlFlowDistanceTest {

    @Test
    public void testDefaultConstructor() {
        ControlFlowDistance distance = new ControlFlowDistance();
        assertEquals(0, distance.getApproachLevel());
        assertEquals(0.0, distance.getBranchDistance(), 0.001);
    }

    @Test
    public void testConstructorWithValues() {
        ControlFlowDistance distance = new ControlFlowDistance(5, 10.5);
        assertEquals(5, distance.getApproachLevel());
        assertEquals(10.5, distance.getBranchDistance(), 0.001);
    }

    @Test
    public void testConstructorWithNegativeApproachLevel() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ControlFlowDistance(-1, 0.0);
        });
    }

    @Test
    public void testConstructorWithNegativeBranchDistance() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ControlFlowDistance(0, -1.0);
        });
    }

    @Test
    public void testSetters() {
        ControlFlowDistance distance = new ControlFlowDistance();
        distance.setApproachLevel(3);
        distance.setBranchDistance(2.5);

        assertEquals(3, distance.getApproachLevel());
        assertEquals(2.5, distance.getBranchDistance(), 0.001);
    }

    @Test
    public void testSetApproachLevelNegative() {
        assertThrows(IllegalArgumentException.class, () -> {
            ControlFlowDistance distance = new ControlFlowDistance();
            distance.setApproachLevel(-1);
        });
    }

    @Test
    public void testSetBranchDistanceNegative() {
        assertThrows(IllegalArgumentException.class, () -> {
            ControlFlowDistance distance = new ControlFlowDistance();
            distance.setBranchDistance(-1.0);
        });
    }

    @Test
    public void testIncreaseApproachLevel() {
        ControlFlowDistance distance = new ControlFlowDistance(1, 0.0);
        distance.increaseApproachLevel();
        assertEquals(2, distance.getApproachLevel());
    }

    @Test
    public void testEqualsAndHashCode() {
        ControlFlowDistance d1 = new ControlFlowDistance(2, 5.0);
        ControlFlowDistance d2 = new ControlFlowDistance(2, 5.0);
        ControlFlowDistance d3 = new ControlFlowDistance(3, 5.0);
        ControlFlowDistance d4 = new ControlFlowDistance(2, 6.0);

        assertEquals(d1, d1);
        assertEquals(d1, d2);
        assertNotEquals(d1, d3);
        assertNotEquals(d1, d4);
        assertNotEquals(d1, null);
        assertNotEquals(d1, "some string");

        assertEquals(d1.hashCode(), d2.hashCode());
        assertNotEquals(d1.hashCode(), d3.hashCode()); // Not strictly required but good for distribution
    }

    @Test
    public void testCompareTo() {
        ControlFlowDistance d1 = new ControlFlowDistance(2, 5.0);
        ControlFlowDistance d2 = new ControlFlowDistance(2, 5.0);
        ControlFlowDistance d3 = new ControlFlowDistance(3, 5.0);
        ControlFlowDistance d4 = new ControlFlowDistance(2, 6.0);

        assertEquals(0, d1.compareTo(d2));
        assertTrue(d1.compareTo(d3) < 0); // 2 < 3
        assertTrue(d3.compareTo(d1) > 0);
        assertTrue(d1.compareTo(d4) < 0); // 5.0 < 6.0
        assertTrue(d4.compareTo(d1) > 0);
    }

    @Test
    public void testGetResultingBranchFitness() {
        ControlFlowDistance distance = new ControlFlowDistance(1, 1.0);
        // normalize(1.0) = 1.0 / (1.0 + 1.0) = 0.5
        // result = 1 + 0.5 = 1.5
        double fitness = distance.getResultingBranchFitness();
        assertTrue(fitness > 1.0 && fitness < 2.0);
    }
}
