/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
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
