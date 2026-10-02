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
package org.evosuite.junit;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.coverage.CoverageCriteriaAnalyzer;
import org.evosuite.coverage.FitnessFunctions;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.junit.examples.*;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class CoverageAnalysisTest {

    @Test
    public void isTest() {
        assertFalse(CoverageAnalysis.isTest(Not_A_Test.class));

        assertTrue(CoverageAnalysis.isTest(JUnit3Test.class));
        assertFalse(CoverageAnalysis.isTest(JUnit3Suite.class));
        assertFalse(CoverageAnalysis.isTest(AbstractJUnit3Test.class));

        assertTrue(CoverageAnalysis.isTest(JUnit4Test.class));
        assertTrue(CoverageAnalysis.isTest(JUnit4EvoSuiteTest.class));
        assertFalse(CoverageAnalysis.isTest(JUnit4Suite.class));
        assertFalse(CoverageAnalysis.isTest(JUnit4Categories.class));
        assertTrue(CoverageAnalysis.isTest(JUnit4ParameterizedTest.class));
    }

    /**
     * The SELECTED_JUNIT (carved-suite) branch of {@link CoverageAnalysis#analyzeCoverage()} loops
     * criteria and calls {@link CoverageCriteriaAnalyzer#analyzeCoverage(TestSuiteChromosome,
     * Properties.Criterion)} directly, bypassing {@link
     * CoverageCriteriaAnalyzer#analyzeCoverage(TestSuiteChromosome)}, which is the only overload
     * that records a give-up against its own time budget. Before the fix, a failure after an
     * earlier criterion already completed propagated out of the sweep entirely, leaving whatever
     * {@link RuntimeVariable#CoverageAnalysisComplete} that earlier criterion already published as
     * {@code true} unrevised. This drives the extracted {@code runDirectCoverageSweep} with two
     * criteria: the first completes (and publishes complete=true, simulating the real analyzer),
     * the second throws {@link OutOfMemoryError}.
     */
    @Test
    public void runDirectCoverageSweepRecordsIncompleteWhenACriterionFailsAfterAnEarlierOneCompleted()
            throws Exception {
        Properties.Criterion[] originalCriteria = Properties.CRITERION;
        ClientNodeImpl<?> previousClientNode = null;
        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        try {
            Properties.CRITERION =
                    new Properties.Criterion[]{Properties.Criterion.LINE, Properties.Criterion.BRANCH};
            previousClientNode = (ClientNodeImpl<?>) clientNodeField.get(ClientServices.getInstance());
            CapturingClientNode node = new CapturingClientNode();
            clientNodeField.set(ClientServices.getInstance(), node);

            TestSuiteChromosome testSuite = new TestSuiteChromosome();

            @SuppressWarnings("unchecked")
            TestFitnessFactory<TestFitnessFunction> factory = mock(TestFitnessFactory.class);
            when(factory.getCoverageGoals())
                    .thenReturn(Collections.singletonList(mock(TestFitnessFunction.class)));
            TestSuiteFitnessFunction fitnessFunction = mock(TestSuiteFitnessFunction.class);
            when(fitnessFunction.getFitness(any())).thenReturn(0.0);

            try (MockedStatic<FitnessFunctions> fitnessMock = mockStatic(FitnessFunctions.class);
                 MockedStatic<CoverageCriteriaAnalyzer> analyzerMock =
                         mockStatic(CoverageCriteriaAnalyzer.class, CALLS_REAL_METHODS)) {

                fitnessMock.when(() -> FitnessFunctions.getFitnessFactory(any())).thenReturn(factory);
                fitnessMock.when(() -> FitnessFunctions.getFitnessFunction(any())).thenReturn(fitnessFunction);

                // The completed criterion: simulate the real analyzeCoverage(testSuite, criterion)
                // publishing CoverageAnalysisComplete=true, same as it does for real when its goal
                // loop finishes within budget.
                analyzerMock.when(() -> CoverageCriteriaAnalyzer.analyzeCoverage(
                                any(TestSuiteChromosome.class), eq(Properties.Criterion.LINE)))
                        .thenAnswer(invocation -> {
                            recordCoverageAnalysisProgress(1, 1, true);
                            return null;
                        });
                // The abandoned criterion.
                analyzerMock.when(() -> CoverageCriteriaAnalyzer.analyzeCoverage(
                                any(TestSuiteChromosome.class), eq(Properties.Criterion.BRANCH)))
                        .thenThrow(new OutOfMemoryError("test"));

                int goals = invokeRunDirectCoverageSweep(testSuite);

                // Goals from both criteria are still tallied: the one that completed, and the one
                // whose goal count was read before the call that threw.
                assertEquals(2, goals);
            }

            assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete),
                    "a failure after an earlier completed criterion must revise a "
                            + "previously-published CoverageAnalysisComplete=true");
        } finally {
            Properties.CRITERION = originalCriteria;
            resetCoverageAnalysisProgress();
            if (previousClientNode != null) {
                clientNodeField.set(ClientServices.getInstance(), previousClientNode);
            }
        }
    }

    /**
     * {@link org.evosuite.rmi.service.ClientNodeImpl#doCoverageAnalysis()} is the only call site
     * of {@link CoverageAnalysis#analyzeCoverage()}, used exclusively by the standalone
     * {@code -measureCoverage} mode. Its {@code catch (Throwable t)} now calls
     * {@link CoverageCriteriaAnalyzer#markCoverageAnalysisIncomplete()} as a backstop: without it,
     * a failure part-way through {@code analyzeCoverage()} could leave a previously-published
     * {@link RuntimeVariable#CoverageAnalysisComplete}{@code =true} (from an earlier criterion in
     * its direct sweep) standing unrevised for a sweep that did not actually finish.
     *
     * <p>This drives the real {@code doCoverageAnalysis()} end to end, via the
     * test-only {@code ClientNodeImpl(Registry, String)} constructor with a {@code null} registry
     * (every master notification then becomes a no-op).
     *
     * <p>Two things rule out stubbing {@code CoverageAnalysis.analyzeCoverage()} itself as the
     * injection point. First, {@code doCoverageAnalysis()} always calls it from a dedicated
     * {@code searchExecutor} worker thread, and Mockito's static mocking only intercepts calls
     * made from the thread that registered the mock (confirmed experimentally: a trivial
     * {@code mockStatic} stub is not observed by a call made from an {@code ExecutorService}
     * thread) -- so a {@code mockStatic(CoverageAnalysis.class)} stub here silently lets the real
     * method run instead. Second, even on the right thread, the real method would not help: its
     * first stage (target-class dependency analysis) has its own {@code catch (Throwable e)} that
     * logs and returns normally, so a failure injected via, say, an invalid {@code TARGET_CLASS}
     * never reaches {@code ClientNodeImpl}'s backstop at all.
     *
     * <p><b>Test-only production seam:</b> this test instead uses {@link
     * org.evosuite.rmi.service.ClientNodeImpl#COVERAGE_ANALYSIS_FOR_TESTING}, a package-private
     * field added specifically for this test (default {@code null}; {@code doCoverageAnalysis()}
     * calls the real {@code CoverageAnalysis.analyzeCoverage()} whenever it is unset, so production
     * behavior is unchanged). It is a plain static field read directly by the worker thread, not a
     * Mockito construct, so it works regardless of which thread performs the read, and lets this
     * test make that call throw directly rather than forcing a real failure through genuine memory
     * pressure or classpath breakage.
     */
    @Test
    public void doCoverageAnalysisBackstopMarksIncompleteWhenAnalyzeCoverageFails() throws Exception {
        boolean previousSandbox = Properties.SANDBOX;
        boolean previousTestArchive = Properties.TEST_ARCHIVE;
        ClientNodeImpl<?> previousClientNode = null;
        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        ClientNodeImpl<TestSuiteChromosome> client = null;
        try {
            // Keep this test from touching the process-wide security manager; doCoverageAnalysis()
            // only (de)activates the sandbox when this is on, which is orthogonal to what is tested.
            Properties.SANDBOX = false;

            previousClientNode = (ClientNodeImpl<?>) clientNodeField.get(ClientServices.getInstance());
            CapturingClientNode node = new CapturingClientNode();
            clientNodeField.set(ClientServices.getInstance(), node);

            client = new ClientNodeImpl<>(null, "test-client");

            // Package-private seam field on a class in a different package; set reflectively.
            Field analysisOverrideField = ClientNodeImpl.class.getDeclaredField(
                    "COVERAGE_ANALYSIS_FOR_TESTING");
            analysisOverrideField.setAccessible(true);
            analysisOverrideField.set(null, (Runnable) () -> {
                throw new OutOfMemoryError("test");
            });
            try {
                client.doCoverageAnalysis();

                // doCoverageAnalysis() runs on its own searchExecutor thread; wait for it to reach
                // ClientState.DONE (reached unconditionally, after the catch block runs) rather
                // than racing the assertion below against that thread.
                Field doneLatchField = ClientNodeImpl.class.getDeclaredField("doneLatch");
                doneLatchField.setAccessible(true);
                CountDownLatch doneLatch = (CountDownLatch) doneLatchField.get(client);
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS),
                        "doCoverageAnalysis() must still reach ClientState.DONE after the failure");
            } finally {
                analysisOverrideField.set(null, null);
            }

            assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete),
                    "a failure inside analyzeCoverage() must (re)publish the sweep as incomplete");
        } finally {
            Properties.SANDBOX = previousSandbox;
            Properties.TEST_ARCHIVE = previousTestArchive;
            TimeController.resetSingleton();
            resetCoverageAnalysisProgress();
            if (client != null) {
                client.stop();
            }
            if (previousClientNode != null) {
                clientNodeField.set(ClientServices.getInstance(), previousClientNode);
            }
        }
    }

    // ---- helpers ----

    private static int invokeRunDirectCoverageSweep(TestSuiteChromosome testSuite) throws Exception {
        Method m = CoverageAnalysis.class.getDeclaredMethod("runDirectCoverageSweep", TestSuiteChromosome.class);
        m.setAccessible(true);
        return (int) m.invoke(null, testSuite);
    }

    private static void recordCoverageAnalysisProgress(int checked, int total, boolean criterionComplete)
            throws Exception {
        Method record = CoverageCriteriaAnalyzer.class.getDeclaredMethod(
                "recordCoverageAnalysisProgress", int.class, int.class, boolean.class);
        record.setAccessible(true);
        record.invoke(null, checked, total, criterionComplete);
    }

    private static void resetCoverageAnalysisProgress() throws Exception {
        Method reset = CoverageCriteriaAnalyzer.class.getDeclaredMethod("resetCoverageAnalysisProgress");
        reset.setAccessible(true);
        reset.invoke(null);
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
