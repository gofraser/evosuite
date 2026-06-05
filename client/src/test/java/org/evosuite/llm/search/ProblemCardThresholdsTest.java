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
package org.evosuite.llm.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProblemCardThresholdsTest {

    @Test
    void defaultsMatchHistoricalConstants() {
        // These values were inline static finals in ProblemCardExtractor before stage D.
        // The test pins them so future tuning is intentional rather than accidental.
        ProblemCardThresholds defaults = ProblemCardThresholds.defaults();
        assertEquals(2, defaults.minAttemptsForExceptionBarrier());
        assertEquals(2, defaults.minAttemptsForTypeBarrier());
        assertEquals(2, defaults.minPolarityGapOppositeObservations());
        assertEquals(4, defaults.minLowSteerabilityBranchExecutions());
        assertEquals(4, defaults.minAttemptsForStateDiversification());
        assertEquals(0.6, defaults.minFailureRateForPracticalAcquisitionBarrier(), 1e-9);
        assertEquals(2.0 / 3.0, defaults.minFailureRateForExceptionBarrier(), 1e-9);
        assertEquals(2.0 / 3.0, defaults.minFailureRateForStateSetupBarrier(), 1e-9);
        assertEquals(0.75, defaults.minContextShareForStateDiversification(), 1e-9);
        assertEquals(0.8, defaults.minSuccessShareForStateDiversification(), 1e-9);
    }

    @Test
    void builderOverridesIndividualThresholds() {
        ProblemCardThresholds tuned = ProblemCardThresholds.builder()
                .minAttemptsForExceptionBarrier(5)
                .minFailureRateForExceptionBarrier(0.9)
                .build();
        assertEquals(5, tuned.minAttemptsForExceptionBarrier());
        assertEquals(0.9, tuned.minFailureRateForExceptionBarrier(), 1e-9);
        // Untouched fields keep their defaults.
        assertEquals(2, tuned.minAttemptsForTypeBarrier());
    }
}
