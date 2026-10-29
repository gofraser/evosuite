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
package org.evosuite.assertion;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.testcase.DefaultTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SimpleMutationAssertionGeneratorCriterionTest {

    private Criterion[] saved;

    @BeforeEach
    public void saveCriterion() {
        saved = Properties.CRITERION;
    }

    @AfterEach
    public void restoreCriterion() {
        Properties.CRITERION = saved;
    }

    @Test
    public void mutationCriterionIsSelectedOnlyWhileTheHandleIsOpen() {
        Criterion[] original = new Criterion[]{Criterion.BRANCH, Criterion.LINE};
        Properties.CRITERION = original;

        try (Properties.ValueOverride ignored = SimpleMutationAssertionGenerator.mutationCriterionUnlessPresent()) {
            assertArrayEquals(new Criterion[]{Criterion.MUTATION}, Properties.CRITERION);
        }

        assertSame(original, Properties.CRITERION);
    }

    @Test
    public void anExistingMutationCriterionIsLeftAlone() {
        Criterion[] original = new Criterion[]{Criterion.BRANCH, Criterion.STRONGMUTATION};
        Properties.CRITERION = original;

        try (Properties.ValueOverride ignored = SimpleMutationAssertionGenerator.mutationCriterionUnlessPresent()) {
            assertSame(original, Properties.CRITERION);
        }

        assertSame(original, Properties.CRITERION);
    }

    @Test
    public void theCriterionComesBackWhenTheBodyThrows() {
        Criterion[] original = new Criterion[]{Criterion.BRANCH};
        Properties.CRITERION = original;

        try (Properties.ValueOverride ignored = SimpleMutationAssertionGenerator.mutationCriterionUnlessPresent()) {
            throw new IllegalStateException("assertion generation failed");
        } catch (IllegalStateException expected) {
            assertSame(original, Properties.CRITERION);
        }
    }

    @Test
    public void aSingleTestCannotBeGivenMutationAssertions() {
        assertThrows(UnsupportedOperationException.class,
                () -> new SimpleMutationAssertionGenerator().addAssertions(new DefaultTestCase()));
    }
}
