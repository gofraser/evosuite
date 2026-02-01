/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.coverage.rho;

import org.junit.Test;
import static org.junit.Assert.*;

public class RhoAuxTest {

    @Test
    public void testRhoCalculation() {
        // Test basic calculation: |0.5 - (ones / tests / goals)|

        // Case 1: Perfect balance (0.5)
        // ones=5, tests=10, goals=1 => rho_raw = 5/10/1 = 0.5. |0.5 - 0.5| = 0.0
        assertEquals(0.0, RhoAux.calculateRho(5, 10, 1), 0.001);

        // Case 2: No coverage
        // ones=0, tests=10, goals=1 => rho_raw = 0.0. |0.5 - 0.0| = 0.5
        assertEquals(0.5, RhoAux.calculateRho(0, 10, 1), 0.001);

        // Case 3: Full coverage
        // ones=10, tests=10, goals=1 => rho_raw = 1.0. |0.5 - 1.0| = 0.5
        assertEquals(0.5, RhoAux.calculateRho(10, 10, 1), 0.001);

        // Case 4: Zero test cases (should return 1.0 as penalty)
        assertEquals(1.0, RhoAux.calculateRho(5, 0, 1), 0.001);

        // Case 5: Zero goals (should result in Infinity for raw rho, thus large diff from 0.5?)
        // If goals=0, rho_raw = ones / tests / 0 = Infinity. |0.5 - Inf| = Inf.
        // Let's see what it returns. Java double division by zero gives Infinity.
        assertTrue(Double.isInfinite(RhoAux.calculateRho(5, 10, 0)));

        // Case 6: Multiple goals
        // ones=5, tests=10, goals=2 => rho_raw = 5/10/2 = 0.25. |0.5 - 0.25| = 0.25
        assertEquals(0.25, RhoAux.calculateRho(5, 10, 2), 0.001);
    }
}
