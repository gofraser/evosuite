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
package org.evosuite.utils;

import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ResourceControllerTest {

    /**
     * The diagnostics run when the heap is nearly full and allocate themselves, so an
     * OutOfMemoryError from them must be swallowed, not abort the generation that is just
     * about to stop the search and write its results.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void memoryDiagnosticsDoNotPropagateOutOfMemoryError() {
        GeneticAlgorithm<TestChromosome> ga = mock(GeneticAlgorithm.class);
        when(ga.getPopulation()).thenThrow(new OutOfMemoryError("Java heap space"));
        ResourceController<TestChromosome> controller = new ResourceController<>();
        controller.searchStarted(ga);

        assertDoesNotThrow(() -> controller.logMemoryDiagnostics(Runtime.getRuntime(), 0L));
    }
}
