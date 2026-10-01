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

import org.evosuite.coverage.CoverageCriteriaAnalyzer;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * {@link CoverageCriteriaAnalyzer} only marks its coverage-analysis sweep incomplete for budget
 * exits it hits internally (a per-criterion timeout, or the per-criteria loop in
 * {@code analyzeCoverage(TestSuiteChromosome)} running out of time). Two exits happen entirely
 * outside it, in the caller: {@link TestSuiteGenerator}'s own outer time guard can skip the whole
 * sweep without ever calling into the analyzer, and a criterion can throw an uncaught
 * {@link OutOfMemoryError} that the caller catches -- in both cases, nothing used to tell a
 * consumer of the opt-in {@link RuntimeVariable#CoverageAnalysisComplete} output variable that the
 * sweep did not actually finish; the OOM case could even leave {@code true} published from
 * criteria analyzed before the failure.
 *
 * <p>These tests drive the extracted {@code runCoverageAnalysisIfEnabled} caller method directly
 * (the coverage-analysis block from {@code postProcessTests}, pulled out verbatim so its exits can
 * be exercised without the rest of that very large method's side effects).
 */
public class TestSuiteGeneratorCoverageAnalysisCompletenessTest {

    private ClientNodeImpl<?> previousClientNode;
    private CapturingClientNode node;

    @BeforeEach
    public void setUp() throws Exception {
        Properties.getInstance().resetToDefaults();
        resetCoverageAnalysisProgress();
        node = installCapturingClientNode();
        TimeController.resetSingleton();
    }

    @AfterEach
    public void tearDown() throws Exception {
        Properties.getInstance().resetToDefaults();
        resetCoverageAnalysisProgress();
        restoreClientNode();
        TimeController.resetSingleton();
    }

    @Test
    public void outerTimeGuardSkipRecordsIncompleteWithoutCallingTheAnalyzer() throws Exception {
        Properties.COVERAGE = true;
        forceNoTimeLeftForATestCase();

        try (MockedStatic<CoverageCriteriaAnalyzer> mocked =
                     mockStatic(CoverageCriteriaAnalyzer.class, CALLS_REAL_METHODS)) {
            invokeRunCoverageAnalysisIfEnabled(new TestSuiteChromosome());

            mocked.verify(() -> CoverageCriteriaAnalyzer.analyzeCoverage(any(TestSuiteChromosome.class)),
                    never());
        }

        assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete));
    }

    /**
     * Note on what this actually covers: the whole {@code analyzeCoverage(TestSuiteChromosome)}
     * call is replaced by a throw here, so no criterion ever runs and no "earlier" goal counts or
     * per-criterion coverage are published before the failure -- this only verifies that an
     * {@link OutOfMemoryError} out of that single call is caught and republishes the sweep as
     * incomplete, not that any earlier numbers survive the failure. A test that established real
     * earlier numbers would need the per-criteria loop itself to run for at least one criterion
     * before the failure, which is exercised instead by {@code CoverageAnalysisTest
     * #runDirectCoverageSweepRecordsIncompleteWhenACriterionFailsAfterAnEarlierOneCompleted} for
     * the sibling SELECTED_JUNIT sweep.
     */
    @Test
    public void outOfMemoryFromAnalyzeCoverageRecordsIncomplete() throws Exception {
        Properties.COVERAGE = true;
        // Nothing started yet: TimeController.hasTimeToExecuteATestCase() is unconditionally
        // true, so the outer guard above is not what is under test here.

        try (MockedStatic<CoverageCriteriaAnalyzer> mocked =
                     mockStatic(CoverageCriteriaAnalyzer.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> CoverageCriteriaAnalyzer.analyzeCoverage(any(TestSuiteChromosome.class)))
                    .thenThrow(new OutOfMemoryError("test"));

            invokeRunCoverageAnalysisIfEnabled(new TestSuiteChromosome());
        }

        // The sweep must be republished as incomplete even though no per-criterion timeout was
        // ever observed inside the analyzer itself.
        assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete));
    }

    @Test
    public void disabledCoverageDoesNotTouchCompletenessTracking() throws Exception {
        Properties.COVERAGE = false;

        try (MockedStatic<CoverageCriteriaAnalyzer> mocked =
                     mockStatic(CoverageCriteriaAnalyzer.class, CALLS_REAL_METHODS)) {
            invokeRunCoverageAnalysisIfEnabled(new TestSuiteChromosome());

            mocked.verify(() -> CoverageCriteriaAnalyzer.analyzeCoverage(any(TestSuiteChromosome.class)),
                    never());
        }

        assertNull(node.value(RuntimeVariable.CoverageAnalysisComplete),
                "disabled coverage analysis must not publish anything, complete or otherwise");
    }

    // ---- helpers ----

    private static void forceNoTimeLeftForATestCase() {
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXTIME;
        Properties.SEARCH_BUDGET = 0;
        Properties.EXTRA_TIMEOUT = 0;
        Properties.INITIALIZATION_TIMEOUT = 0;
        Properties.MINIMIZE = false;
        Properties.ASSERTIONS = false;
        Properties.JUNIT_TESTS = false;
        TimeController.resetSingleton();
        // The CapturingClientNode's changeState() (inherited from DummyClientNodeImpl) is a
        // no-op, so this is the only thing that actually drives TimeController here; the
        // production changeState(COVERAGE_ANALYSIS) call inside the method under test leaves it
        // alone.
        TimeController.getInstance().updateState(ClientState.STARTED);
        TimeController.getInstance().updateState(ClientState.COVERAGE_ANALYSIS);
        assertFalse(TimeController.getInstance().hasTimeToExecuteATestCase(),
                "test setup must actually exhaust the overall budget");
    }

    private static void invokeRunCoverageAnalysisIfEnabled(TestSuiteChromosome testSuite) throws Exception {
        Method m = TestSuiteGenerator.class.getDeclaredMethod(
                "runCoverageAnalysisIfEnabled", TestSuiteChromosome.class);
        m.setAccessible(true);
        m.invoke(new TestSuiteGenerator(), testSuite);
    }

    private static void resetCoverageAnalysisProgress() throws Exception {
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
