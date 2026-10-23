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
package org.evosuite.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmBudgetCoordinatorTest {

    @Test
    void finiteBudgetIsConsumedAtomically() {
        LlmBudgetCoordinator coordinator = LlmBudgetCoordinator.of(2);

        assertTrue(coordinator.tryAcquire(LlmFeature.SEEDING));
        assertTrue(coordinator.tryAcquire(LlmFeature.SEEDING));
        assertFalse(coordinator.tryAcquire(LlmFeature.SEEDING));
        assertEquals(0, coordinator.getRemaining(LlmFeature.SEEDING));
    }

    @Test
    void unlimitedBudgetAlwaysAcquires() {
        LlmBudgetCoordinator coordinator = LlmBudgetCoordinator.of(0);

        for (int i = 0; i < 100; i++) {
            assertTrue(coordinator.tryAcquire(LlmFeature.SEEDING));
        }
        assertEquals(-1, coordinator.getRemaining(LlmFeature.SEEDING));
    }
}
