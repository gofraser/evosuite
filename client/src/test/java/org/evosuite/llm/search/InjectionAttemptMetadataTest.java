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

import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests the target-goal plumbing on {@link InjectionAttemptMetadata}.
 */
class InjectionAttemptMetadataTest {

    @Test
    void twoArgConstructorYieldsEmptyTargetGoals() {
        InjectionAttemptMetadata metadata =
                new InjectionAttemptMetadata("attempt-1", Collections.emptyList());
        assertTrue(metadata.getTargetGoals().isEmpty());
    }

    @Test
    void targetGoalsAreCopiedAndUnmodifiable() {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        List<TestFitnessFunction> goals = new ArrayList<>();
        goals.add(goal);

        InjectionAttemptMetadata metadata =
                new InjectionAttemptMetadata("attempt-2", Collections.emptyList(), goals);
        goals.clear();

        assertEquals(1, metadata.getTargetGoals().size(),
                "metadata must hold a defensive copy");
        assertThrows(UnsupportedOperationException.class,
                () -> metadata.getTargetGoals().add(goal));
    }

    @Test
    void nullTargetGoalsBecomeEmpty() {
        InjectionAttemptMetadata metadata =
                new InjectionAttemptMetadata("attempt-3", Collections.emptyList(), null);
        assertTrue(metadata.getTargetGoals().isEmpty());
    }
}
