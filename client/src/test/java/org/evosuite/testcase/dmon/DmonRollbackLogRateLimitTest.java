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
package org.evosuite.testcase.dmon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DmonRollbackLogRateLimitTest {

    @BeforeEach
    @AfterEach
    public void reset() {
        DmonEphemeralAssist.resetDmonRollbackFailureCounts();
    }

    @Test
    public void onlyTheFirstFailuresPerSiteAreWarnings() {
        int warned = 0;
        for (int i = 0; i < 1000; i++) {
            if (DmonEphemeralAssist.shouldWarnAboutDmonRollbackFailure("A.setX(...)")) {
                warned++;
            }
        }
        assertEquals(DmonEphemeralAssist.DMON_ROLLBACK_WARNINGS_PER_SITE, warned);
    }

    @Test
    public void sitesAreCountedIndependently() {
        for (int i = 0; i < 10; i++) {
            DmonEphemeralAssist.shouldWarnAboutDmonRollbackFailure("A.setX(...)");
        }
        assertFalse(DmonEphemeralAssist.shouldWarnAboutDmonRollbackFailure("A.setX(...)"));
        assertTrue(DmonEphemeralAssist.shouldWarnAboutDmonRollbackFailure("B.setY(...)"));
    }
}
