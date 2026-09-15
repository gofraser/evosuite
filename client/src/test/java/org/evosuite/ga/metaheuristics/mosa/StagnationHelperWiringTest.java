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
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.llm.search.StagnationLlmHelper;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Verifies the stagnation-helper wiring shared by all MOSA variants: {@link
 * AbstractMOSA#registerExternalCandidateSources} registers a source that, when drained by
 * {@link AbstractMOSA#collectExternalCandidates}, calls {@code
 * StagnationLlmHelper.maybeSubmit(covered, totalGoals, targets, population)} with DynaMOSA's
 * frontier as {@code targets} (never the wider uncovered-goal set) and {@code totalGoals}
 * computed as {@code covered + Archive.getNumberOfUncoveredTargets()} — exactly what {@code
 * DynaMOSA.generateSolution()} wires up.
 *
 * <p>{@link StagnationLlmHelper} is a {@code final} class; it is mocked directly here, which
 * requires Mockito's inline mock maker (the default since Mockito 5, already required
 * elsewhere in this module for JDK 11+ builds).
 */
class StagnationHelperWiringTest extends MosaTestSupport {

    @BeforeEach
    void resetArchive() {
        Archive.getArchiveInstance().reset();
    }

    @AfterEach
    void resetArchiveAfter() {
        Archive.getArchiveInstance().reset();
    }

    @Test
    void collectExternalCandidatesDrivesStagnationHelperWithFrontierTargetsAndTotalGoalCount() {
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.METHOD};

        WiringDynaMOSA harness = new WiringDynaMOSA();

        TestFitnessFunction frontierGoal = CurrentGoalsSupplierTest.simpleGoal("frontier");
        harness.goalsManager.getCurrentGoals().add(frontierGoal);
        harness.publishCurrentGoals();

        // Archive-only uncovered targets that are not part of the frontier: they must
        // inflate totalGoals but never appear in the prompt's target set.
        int archiveOnlyUncoveredCount = 3;
        for (int i = 0; i < archiveOnlyUncoveredCount; i++) {
            Archive.getArchiveInstance().addTarget(mock(MethodCoverageTestFitness.class));
        }

        StagnationLlmHelper mockHelper = mock(StagnationLlmHelper.class);
        harness.injectStagnationHelper(mockHelper);

        int covered = 5;
        harness.registerExternalCandidateSources(() -> covered, harness::getCurrentGoals);
        harness.collectExternalCandidates(new ArrayList<>());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<TestFitnessFunction>> targetsCaptor =
                ArgumentCaptor.forClass(Collection.class);
        verify(mockHelper).maybeSubmit(eq(covered), eq(covered + archiveOnlyUncoveredCount),
                targetsCaptor.capture(), anyList());

        Collection<TestFitnessFunction> targets = targetsCaptor.getValue();
        assertEquals(1, targets.size(), "only the frontier goal must be targeted: " + targets);
        assertTrue(targets.contains(frontierGoal), "the frontier goal must be a target");
    }

    /** Exposes the protected {@code stagnationLlmHelper} field (declared on {@code
     * GeneticAlgorithm}, a different package) for injection from the test. */
    private static class WiringDynaMOSA extends DynaMOSA {
        private static final long serialVersionUID = 1L;

        WiringDynaMOSA() {
            super(TestChromosome::new);
            this.goalsManager = new MultiCriteriaManager(Collections.emptyList());
        }

        void injectStagnationHelper(StagnationLlmHelper helper) {
            this.stagnationLlmHelper = helper;
        }
    }
}
