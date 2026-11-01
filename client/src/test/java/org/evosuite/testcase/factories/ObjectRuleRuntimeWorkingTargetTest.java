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
package org.evosuite.testcase.factories;

import org.evosuite.coverage.branch.Branch;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ObjectRuleRuntimeWorkingTargetTest {

    @Test
    void noEligibleBranchRecordsAnEmptyTargetForTheIteration() {
        ObjectRuleRuntime runtime = new ObjectRuleRuntime();

        Branch chosen = runtime.selectWorkingTarget(7, Collections.<Branch>emptyList(), null);

        assertNull(chosen);
        ObjectRuleSession session = runtime.getSession();
        assertNull(session.getWorkingBranch());
        assertEquals(7, session.getCurrentIteration());
        assertFalse(session.hasWorkingGoals());
    }
}
