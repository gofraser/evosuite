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
package org.evosuite.coverage;

import org.evosuite.Properties;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * On a coverage-analysis timeout, {@code CoverageCriteriaAnalyzer} used to pad the unchecked
 * goals as "not covered" and publish the resulting score against the *full* goal count, with no
 * way for a consumer to tell a fully covered suite that merely ran out of analysis budget from
 * one that was genuinely only partially covered. These tests drive the completeness-tracking
 * helpers directly (same goal-count/timed-out values {@code analyzeCoverage} itself computes)
 * rather than forcing a real {@code TimeController} timeout through the full goal-evaluation
 * pipeline, and check that {@link RuntimeVariable#CoverageAnalysisComplete},
 * {@link RuntimeVariable#CoverageAnalysisGoalsChecked} and
 * {@link RuntimeVariable#CoverageAnalysisGoalsTotal} now report it.
 */
public class CoverageCriteriaAnalyzerTest {

    private ClientNodeImpl<?> previousClientNode;
    private CapturingClientNode node;

    @BeforeEach
    public void setUp() throws Exception {
        resetProgress();
        node = installCapturingClientNode();
    }

    @AfterEach
    public void tearDown() throws Exception {
        resetProgress();
        restoreClientNode();
    }

    @Test
    public void normalCompletingCriterionRecordsCompleteWithCheckedEqualToTotal() throws Exception {
        recordProgress(12, 12, true);

        assertEquals(Boolean.TRUE, node.value(RuntimeVariable.CoverageAnalysisComplete));
        assertEquals(12, node.value(RuntimeVariable.CoverageAnalysisGoalsChecked));
        assertEquals(12, node.value(RuntimeVariable.CoverageAnalysisGoalsTotal));
    }

    @Test
    public void timedOutCriterionRecordsIncompleteWithCheckedLessThanTotal() throws Exception {
        recordProgress(7, 20, false);

        assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete));
        int checked = (int) node.value(RuntimeVariable.CoverageAnalysisGoalsChecked);
        int total = (int) node.value(RuntimeVariable.CoverageAnalysisGoalsTotal);
        assertEquals(7, checked);
        assertEquals(20, total);
        assertTrue(checked < total, "a timed-out criterion must check strictly fewer goals than it has");
    }

    @Test
    public void completenessAndGoalCountsAccumulateAcrossCriteriaInOneSweep() throws Exception {
        // First criterion finishes normally...
        recordProgress(5, 5, true);
        assertEquals(Boolean.TRUE, node.value(RuntimeVariable.CoverageAnalysisComplete));

        // ...but the sweep as a whole is incomplete once a later criterion times out, and the
        // goal tallies keep accumulating rather than being overwritten.
        recordProgress(3, 9, false);
        assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete));
        assertEquals(8, node.value(RuntimeVariable.CoverageAnalysisGoalsChecked));
        assertEquals(14, node.value(RuntimeVariable.CoverageAnalysisGoalsTotal));

        // A third, fully-checked criterion does not undo the earlier incompleteness.
        recordProgress(2, 2, true);
        assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete));
        assertEquals(10, node.value(RuntimeVariable.CoverageAnalysisGoalsChecked));
        assertEquals(16, node.value(RuntimeVariable.CoverageAnalysisGoalsTotal));
    }

    @Test
    public void markIncompleteDoesNotTouchGoalCounts() throws Exception {
        recordProgress(5, 5, true);
        assertEquals(Boolean.TRUE, node.value(RuntimeVariable.CoverageAnalysisComplete));

        // The per-criteria loop in analyzeCoverage(TestSuiteChromosome) ran out of time before a
        // whole later criterion was even analyzed: no goals to add, but no longer complete.
        markIncomplete();

        assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete));
        assertEquals(5, node.value(RuntimeVariable.CoverageAnalysisGoalsChecked));
        assertEquals(5, node.value(RuntimeVariable.CoverageAnalysisGoalsTotal));
    }

    @Test
    public void resetRestoresTheCleanBaseline() throws Exception {
        recordProgress(3, 9, false);
        assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete));

        // reset() itself does not track (production never calls it, same as coverageBitString);
        // a subsequent record makes the cleared internal state observable.
        resetProgress();
        recordProgress(0, 0, true);

        assertEquals(Boolean.TRUE, node.value(RuntimeVariable.CoverageAnalysisComplete));
        assertEquals(0, node.value(RuntimeVariable.CoverageAnalysisGoalsChecked));
        assertEquals(0, node.value(RuntimeVariable.CoverageAnalysisGoalsTotal));
    }

    @Test
    public void midCriterionOutOfMemoryRecordsGoalsAlreadyEvaluatedBeforeRethrowing() throws Exception {
        // Three goals: the first is covered, the second is not, and the third throws a
        // synthetic OutOfMemoryError from isCoveredBy -- mirroring a real criterion that OOMs
        // partway through its goal set rather than before starting or after finishing.
        TestFitnessFunction first = mock(TestFitnessFunction.class);
        TestFitnessFunction second = mock(TestFitnessFunction.class);
        TestFitnessFunction third = mock(TestFitnessFunction.class);
        doReturn(0).when(first).compareTo(any(TestFitnessFunction.class));
        doReturn(0).when(second).compareTo(any(TestFitnessFunction.class));
        doReturn(0).when(third).compareTo(any(TestFitnessFunction.class));
        doReturn(true).when(first).isCoveredBy(any(TestSuiteChromosome.class));
        doReturn(false).when(second).isCoveredBy(any(TestSuiteChromosome.class));
        doThrow(new OutOfMemoryError("synthetic OOM on third goal"))
                .when(third).isCoveredBy(any(TestSuiteChromosome.class));

        TestFitnessFactory<TestFitnessFunction> factory = mock(TestFitnessFactory.class);
        List<TestFitnessFunction> goals = new ArrayList<>(List.of(first, second, third));
        doReturn(goals).when(factory).getCoverageGoals();

        TestSuiteChromosome suite = new TestSuiteChromosome();

        Method analyzeCoverage = CoverageCriteriaAnalyzer.class.getDeclaredMethod(
                "analyzeCoverage", TestSuiteChromosome.class, Properties.Criterion.class, boolean.class);
        analyzeCoverage.setAccessible(true);

        try (MockedStatic<FitnessFunctions> fitnessFunctions = mockStatic(FitnessFunctions.class)) {
            // recalculate=false skips reinstrument()/clearCoveredGoals(), which are unrelated to
            // this goal loop and would otherwise need a live instrumented target class.
            fitnessFunctions.when(() -> FitnessFunctions.getFitnessFactory(Properties.Criterion.LINE))
                    .thenReturn(factory);

            InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                    () -> analyzeCoverage.invoke(null, suite, Properties.Criterion.LINE, false),
                    "the OutOfMemoryError from the third goal must propagate out of the goal loop, "
                            + "exactly as it does today without the fix");
            assertTrue(wrapped.getCause() instanceof OutOfMemoryError,
                    "expected the synthetic OutOfMemoryError, got " + wrapped.getCause());
        }

        // The defect: without recording progress before rethrowing, these come back as
        // false/0/0 (the fields' initial state) instead of reflecting the two goals the
        // criterion actually evaluated before the OOM.
        assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete));
        assertEquals(2, node.value(RuntimeVariable.CoverageAnalysisGoalsChecked),
                "two goals (first covered, second not) were evaluated before the OOM");
        assertEquals(3, node.value(RuntimeVariable.CoverageAnalysisGoalsTotal),
                "the criterion's known denominator is all three goals, not just the ones reached");
    }

    // ---- helpers ----

    private static void recordProgress(int checked, int total, boolean criterionComplete) throws Exception {
        Method record = CoverageCriteriaAnalyzer.class.getDeclaredMethod(
                "recordCoverageAnalysisProgress", int.class, int.class, boolean.class);
        record.setAccessible(true);
        record.invoke(null, checked, total, criterionComplete);
    }

    private static void markIncomplete() throws Exception {
        Method mark = CoverageCriteriaAnalyzer.class.getDeclaredMethod("markCoverageAnalysisIncomplete");
        mark.setAccessible(true);
        mark.invoke(null);
    }

    private static void resetProgress() throws Exception {
        Method reset = CoverageCriteriaAnalyzer.class.getDeclaredMethod("resetCoverageAnalysisProgress");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    private CapturingClientNode installCapturingClientNode() throws Exception {
        Field field = ClientServices.class.getDeclaredField("clientNode");
        field.setAccessible(true);
        previousClientNode = (ClientNodeImpl<?>) field.get(ClientServices.getInstance());
        CapturingClientNode capturing = new CapturingClientNode();
        field.set(ClientServices.getInstance(), capturing);
        return capturing;
    }

    private void restoreClientNode() throws Exception {
        if (previousClientNode == null) {
            return;
        }
        Field field = ClientServices.class.getDeclaredField("clientNode");
        field.setAccessible(true);
        field.set(ClientServices.getInstance(), previousClientNode);
        previousClientNode = null;
    }

    private static final class CapturingClientNode extends DummyClientNodeImpl {
        private final Map<RuntimeVariable, Object> values = new LinkedHashMap<>();

        @Override
        public void trackOutputVariable(RuntimeVariable name, Object value) {
            values.put(name, value);
        }

        private Object value(RuntimeVariable variable) {
            return values.get(variable);
        }
    }
}
