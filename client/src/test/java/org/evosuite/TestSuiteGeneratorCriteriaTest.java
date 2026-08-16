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
package org.evosuite;

import org.evosuite.Properties.Criterion;
import org.evosuite.setup.DependencyAnalysis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A missing call graph must degrade the context-sensitive criteria instead of aborting the run.
 */
class TestSuiteGeneratorCriteriaTest {

    private Criterion[] originalCriteria;
    private String originalTargetClass;

    @BeforeEach
    void saveState() {
        originalCriteria = Properties.CRITERION;
        originalTargetClass = Properties.TARGET_CLASS;
        // No dependency analysis has run in this test, so getCallGraph() is null - which is exactly
        // the state a class whose dependency analysis failed ends up in.
        DependencyAnalysis.clear();
    }

    @AfterEach
    void restoreState() {
        Properties.CRITERION = originalCriteria;
        Properties.TARGET_CLASS = originalTargetClass;
        DependencyAnalysis.clear();
    }

    private static void setRequestedCriteria(TestSuiteGenerator generator, Criterion[] criteria) throws Exception {
        Field field = TestSuiteGenerator.class.getDeclaredField("requestedCriteria");
        field.setAccessible(true);
        field.set(generator, criteria);
    }

    private static Criterion[] getRequestedCriteria(TestSuiteGenerator generator) throws Exception {
        Field field = TestSuiteGenerator.class.getDeclaredField("requestedCriteria");
        field.setAccessible(true);
        return (Criterion[]) field.get(generator);
    }

    @Test
    void dropsContextSensitiveCriteriaAndKeepsTheRest() {
        Properties.TARGET_CLASS = "com.example.Foo";
        Properties.CRITERION = new Criterion[]{Criterion.LINE, Criterion.CBRANCH, Criterion.BRANCH,
                Criterion.IBRANCH, Criterion.EXCEPTION};

        assertTrue(new TestSuiteGenerator().dropCallGraphDependentCriteriaIfNoCallGraph());
        assertArrayEquals(new Criterion[]{Criterion.LINE, Criterion.BRANCH, Criterion.EXCEPTION},
                Properties.CRITERION);
    }

    @Test
    void leavesCriteriaAloneWhenNoneNeedTheCallGraph() {
        Properties.CRITERION = new Criterion[]{Criterion.LINE, Criterion.BRANCH};

        assertFalse(new TestSuiteGenerator().dropCallGraphDependentCriteriaIfNoCallGraph());
        assertArrayEquals(new Criterion[]{Criterion.LINE, Criterion.BRANCH}, Properties.CRITERION);
    }

    @Test
    void dropsTheCriteriaAlsoFromTheCoverageAnalysisReplay() throws Exception {
        Properties.CRITERION = new Criterion[]{Criterion.BRANCH, Criterion.CBRANCH};
        TestSuiteGenerator generator = new TestSuiteGenerator();
        // Set up by sanitizeDebugInfoDependentCriteria(): the criteria that get restored for the
        // coverage-analysis phase. They must not smuggle CBRANCH back in.
        setRequestedCriteria(generator, new Criterion[]{Criterion.LINE, Criterion.BRANCH, Criterion.CBRANCH});

        assertTrue(generator.dropCallGraphDependentCriteriaIfNoCallGraph());
        assertArrayEquals(new Criterion[]{Criterion.BRANCH}, Properties.CRITERION);
        assertArrayEquals(new Criterion[]{Criterion.LINE, Criterion.BRANCH}, getRequestedCriteria(generator));
    }

    @Test
    void removeCallGraphDependentCriteriaIsAPureFilter() {
        assertArrayEquals(new Criterion[]{Criterion.BRANCH},
                TestSuiteGenerator.removeCallGraphDependentCriteria(
                        new Criterion[]{Criterion.CBRANCH, Criterion.BRANCH, Criterion.IBRANCH}));
        assertArrayEquals(new Criterion[0],
                TestSuiteGenerator.removeCallGraphDependentCriteria(
                        new Criterion[]{Criterion.CBRANCH, Criterion.IBRANCH}));
    }
}
