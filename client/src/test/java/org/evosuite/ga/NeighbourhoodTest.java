/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * <p>
 * This file is part of EvoSuite.
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga;

import org.evosuite.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class NeighbourhoodTest {

    @Test
    public void testMemoryLeak() {
        // Setup
        int populationSize = 10;
        Neighbourhood<DummyChromosome> neighbourhood = new Neighbourhood<>(populationSize);
        List<DummyChromosome> population = new ArrayList<>();
        for (int i = 0; i < populationSize; i++) {
            population.add(new DummyChromosome());
        }

        // Test with Linear Five Model (Default or explicit)
        Properties.MODEL = Properties.CgaModels.LINEAR_FIVE;

        // First call
        List<DummyChromosome> neighbors1 = neighbourhood.getNeighbors(population, 0);
        Assertions.assertEquals(5, neighbors1.size(), "Expected 5 neighbors for Linear Five");

        // Second call
        List<DummyChromosome> neighbors2 = neighbourhood.getNeighbors(population, 0);

        // If memory leak exists, size will be 10 (5 + 5)
        Assertions.assertEquals(5, neighbors2.size(), "Expected 5 neighbors on second call, checking for memory leak");
    }
}
