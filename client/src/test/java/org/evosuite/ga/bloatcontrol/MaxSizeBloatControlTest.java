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
package org.evosuite.ga.bloatcontrol;

import org.evosuite.Properties;
import org.evosuite.ga.DummyChromosome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MaxSizeBloatControlTest {

    @Test
    public void testDefaultConstructor() {
        int oldMaxSize = Properties.MAX_SIZE;
        try {
            Properties.MAX_SIZE = 50;
            MaxSizeBloatControl<DummyChromosome> control = new MaxSizeBloatControl<>();
            assertEquals(50, control.getMaxSize());

            DummyChromosome c1 = new DummyChromosome(new int[50]);
            assertFalse(control.isTooLong(c1));

            DummyChromosome c2 = new DummyChromosome(new int[51]);
            assertTrue(control.isTooLong(c2));
        } finally {
            Properties.MAX_SIZE = oldMaxSize;
        }
    }

    @Test
    public void testParameterizedConstructor() {
        MaxSizeBloatControl<DummyChromosome> control = new MaxSizeBloatControl<>(10);
        assertEquals(10, control.getMaxSize());

        DummyChromosome c1 = new DummyChromosome(new int[10]);
        assertFalse(control.isTooLong(c1));

        DummyChromosome c2 = new DummyChromosome(new int[11]);
        assertTrue(control.isTooLong(c2));
    }

    @Test
    public void testCopyConstructor() {
        MaxSizeBloatControl<DummyChromosome> original = new MaxSizeBloatControl<>(20);
        MaxSizeBloatControl<DummyChromosome> copy = new MaxSizeBloatControl<>(original);

        assertEquals(20, copy.getMaxSize());

        DummyChromosome c1 = new DummyChromosome(new int[20]);
        assertFalse(copy.isTooLong(c1));

        DummyChromosome c2 = new DummyChromosome(new int[21]);
        assertTrue(copy.isTooLong(c2));
    }

    @Test
    public void testSetMaxSize() {
        MaxSizeBloatControl<DummyChromosome> control = new MaxSizeBloatControl<>(10);
        control.setMaxSize(5);
        assertEquals(5, control.getMaxSize());

        DummyChromosome c1 = new DummyChromosome(new int[5]);
        assertFalse(control.isTooLong(c1));

        DummyChromosome c2 = new DummyChromosome(new int[6]);
        assertTrue(control.isTooLong(c2));
    }
}
