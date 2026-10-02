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
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.factories.JUnitTestCarvedChromosomeFactory;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
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
     * Drives the extracted {@code runDirectCoverageSweep} with two criteria: the first completes
     * (and publishes complete=true, simulating the real analyzer), the second throws {@link
     * OutOfMemoryError}. The sweep must still end up recording {@link
     * RuntimeVariable#CoverageAnalysisComplete} as incomplete rather than leaving the first
     * criterion's complete=true unrevised; see {@code runDirectCoverageSweep}'s own comment for
     * why it bypasses the overload that tracks its own time budget.
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
     * <p>Stubbing {@code CoverageAnalysis.analyzeCoverage()} itself cannot inject the failure:
     * besides running on a worker thread a static mock cannot reach (see {@link
     * org.evosuite.rmi.service.ClientNodeImpl#COVERAGE_ANALYSIS_FOR_TESTING}'s javadoc for why),
     * the real method's first stage (target-class dependency analysis) has its own
     * {@code catch (Throwable e)} that logs and returns normally, so a failure injected via, say,
     * an invalid {@code TARGET_CLASS} never reaches {@code ClientNodeImpl}'s backstop at all. This
     * test instead sets {@link
     * org.evosuite.rmi.service.ClientNodeImpl#COVERAGE_ANALYSIS_FOR_TESTING} directly to make the
     * call throw.
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
            resetCoverageAnalysisProgress();
            // client.stop() itself calls changeState(FINISHED), which advances the shared
            // TimeController singleton's phase; resetting the singleton before stop() would
            // leave it on that later phase for whichever test runs next, so stop() must come
            // first here.
            if (client != null) {
                client.stop();
            }
            TimeController.resetSingleton();
            if (previousClientNode != null) {
                clientNodeField.set(ClientServices.getInstance(), previousClientNode);
            }
        }
    }

    /**
     * Rereview4 finding 1: {@code runDirectCoverageSweep} catches an {@link OutOfMemoryError},
     * marks the sweep incomplete, and returns the goal count accumulated so far -- but the
     * SELECTED_JUNIT branch of {@link CoverageAnalysis#analyzeCoverage()} used to publish ordinary
     * {@link RuntimeVariable#Total_Goals}, {@link RuntimeVariable#Covered_Goals} and {@link
     * RuntimeVariable#Coverage} from that partial count regardless, representing a sweep that is
     * missing one or more configured criteria entirely as an ordinary, trustworthy score. These
     * three are default/essential output variables (unlike the opt-in {@code
     * CoverageAnalysisComplete} marker), so every consumer relying on the default output set would
     * see them. This drives the extracted {@code publishDirectCoverageSweepResult} directly with
     * {@code goals=2} after marking the analysis incomplete, simulating a third configured
     * criterion that {@code runDirectCoverageSweep} never reached: asserting only the incomplete
     * marker (as the pinned {@code runDirectCoverageSweepRecordsIncompleteWhenACriterionFails...}
     * test does) would miss this regression entirely, since that test never inspects what the
     * caller publishes for the omitted third criterion.
     */
    @Test
    public void incompleteDirectSweepWithholdsOrdinaryAggregates() throws Exception {
        ClientNodeImpl<?> previousClientNode = null;
        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        try {
            previousClientNode = (ClientNodeImpl<?>) clientNodeField.get(ClientServices.getInstance());
            CapturingClientNode node = new CapturingClientNode();
            clientNodeField.set(ClientServices.getInstance(), node);

            // Simulate runDirectCoverageSweep's own OOM catch: LINE and BRANCH (2 goals) measured,
            // METHOD (the third configured criterion) never reached.
            CoverageCriteriaAnalyzer.markCoverageAnalysisIncomplete();

            TestSuiteChromosome testSuite = new TestSuiteChromosome();
            invokePublishDirectCoverageSweepResult(testSuite, 2);

            assertNull(node.value(RuntimeVariable.Total_Goals),
                    "an incomplete sweep must not publish a goal total that silently omits "
                            + "the criterion that was never reached");
            assertNull(node.value(RuntimeVariable.Covered_Goals),
                    "an incomplete sweep must not publish a covered-goal count alongside a "
                            + "goal total known to be short");
            assertNull(node.value(RuntimeVariable.Coverage),
                    "an incomplete sweep must not publish a coverage ratio computed over "
                            + "a partial goal set");
        } finally {
            resetCoverageAnalysisProgress();
            if (previousClientNode != null) {
                clientNodeField.set(ClientServices.getInstance(), previousClientNode);
            }
        }
    }

    /**
     * Companion to {@link #incompleteDirectSweepWithholdsOrdinaryAggregates()}: the review also
     * flagged that if the failure happens before the very first criterion's goal count is even
     * read, {@code runDirectCoverageSweep} returns {@code goals=0}, and the (pre-fix) caller's
     * {@code goals == 0 ? 1.0 : ...} shortcut would publish {@code Coverage=1.0} -- a claim of full
     * coverage for a sweep that measured nothing at all.
     */
    @Test
    public void incompleteDirectSweepWithZeroGoalsDoesNotPublishFullCoverage() throws Exception {
        ClientNodeImpl<?> previousClientNode = null;
        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        try {
            previousClientNode = (ClientNodeImpl<?>) clientNodeField.get(ClientServices.getInstance());
            CapturingClientNode node = new CapturingClientNode();
            clientNodeField.set(ClientServices.getInstance(), node);

            CoverageCriteriaAnalyzer.markCoverageAnalysisIncomplete();

            TestSuiteChromosome testSuite = new TestSuiteChromosome();
            invokePublishDirectCoverageSweepResult(testSuite, 0);

            assertNull(node.value(RuntimeVariable.Coverage),
                    "a sweep that failed before measuring anything must not publish "
                            + "Coverage=1.0 via the zero-goal shortcut");
            assertNull(node.value(RuntimeVariable.Total_Goals));
            assertNull(node.value(RuntimeVariable.Covered_Goals));
        } finally {
            resetCoverageAnalysisProgress();
            if (previousClientNode != null) {
                clientNodeField.set(ClientServices.getInstance(), previousClientNode);
            }
        }
    }

    /**
     * Rereview4 finding 5: {@link org.evosuite.rmi.service.ClientNodeImpl#doCoverageAnalysis()}'s
     * {@code catch (Throwable t)} backstop used to call {@code markCoverageAnalysisIncomplete()}
     * unconditionally. In SELECTED_JUNIT mode, the coverage sweep and aggregate publication finish
     * before {@code analyzeCoverage()} writes the generated JUnit suite; a writer failure, or the
     * explicit unsupported {@code COVERAGE_MATRIX} exception that follows it, both happen strictly
     * after measurement is done. The unconditional backstop rewrote completeness to {@code false}
     * for these too, even though coverage measurement genuinely finished -- a false-incomplete on
     * the opt-in signal.
     *
     * <p>This reuses the {@code COVERAGE_ANALYSIS_FOR_TESTING} seam, but this time the stub first
     * marks measurement finished (the same call {@code analyzeCoverage()}'s SELECTED_JUNIT branch
     * makes right after publishing its aggregates, before the output-phase work that follows), then
     * throws -- simulating a writer/COVERAGE_MATRIX failure after a genuinely completed sweep.
     */
    @Test
    public void doCoverageAnalysisBackstopDoesNotMarkIncompleteAfterMeasurementFinished() throws Exception {
        boolean previousSandbox = Properties.SANDBOX;
        boolean previousTestArchive = Properties.TEST_ARCHIVE;
        ClientNodeImpl<?> previousClientNode = null;
        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        ClientNodeImpl<TestSuiteChromosome> client = null;
        try {
            Properties.SANDBOX = false;

            previousClientNode = (ClientNodeImpl<?>) clientNodeField.get(ClientServices.getInstance());
            CapturingClientNode node = new CapturingClientNode();
            clientNodeField.set(ClientServices.getInstance(), node);

            client = new ClientNodeImpl<>(null, "test-client");

            Field analysisOverrideField = ClientNodeImpl.class.getDeclaredField(
                    "COVERAGE_ANALYSIS_FOR_TESTING");
            analysisOverrideField.setAccessible(true);
            analysisOverrideField.set(null, (Runnable) () -> {
                markMeasurementFinishedForTest();
                throw new IllegalArgumentException(
                        "Coverage matrix not yet available when measuring coverage of a "
                                + "carved test suite");
            });
            try {
                client.doCoverageAnalysis();

                Field doneLatchField = ClientNodeImpl.class.getDeclaredField("doneLatch");
                doneLatchField.setAccessible(true);
                CountDownLatch doneLatch = (CountDownLatch) doneLatchField.get(client);
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS),
                        "doCoverageAnalysis() must still reach ClientState.DONE after the failure");
            } finally {
                analysisOverrideField.set(null, null);
            }

            assertNotEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete),
                    "a failure after measurement already finished must not retroactively mark "
                            + "an already-completed sweep incomplete");
        } finally {
            Properties.SANDBOX = previousSandbox;
            Properties.TEST_ARCHIVE = previousTestArchive;
            resetCoverageAnalysisProgress();
            CoverageAnalysis.resetMeasurementFinished();
            // See the ordering note in doCoverageAnalysisBackstopMarksIncompleteWhenAnalyzeCoverageFails:
            // client.stop() must run before TimeController.resetSingleton(), not after.
            if (client != null) {
                client.stop();
            }
            TimeController.resetSingleton();
            if (previousClientNode != null) {
                clientNodeField.set(ClientServices.getInstance(), previousClientNode);
            }
        }
    }

    /**
     * An unresolvable {@code TARGET_CLASS} fails inside {@code analyzeCoverage()}'s
     * target/classpath initialization catch, which logs the {@link Throwable} and returns
     * normally without rethrowing. Because it does not rethrow, {@code ClientNodeImpl}'s own
     * backstop -- which only runs when {@code analyzeCoverage()} escapes via an exception -- is
     * never reached, so this catch must itself mark the sweep incomplete and publish {@link
     * RuntimeVariable#CoverageAnalysisComplete}.
     *
     * <p>This drives the real {@link CoverageAnalysis#analyzeCoverage()} entry point directly --
     * not the {@code COVERAGE_ANALYSIS_FOR_TESTING} seam, which replaces the whole call and so
     * never reaches this inner catch -- with a {@code TARGET_CLASS} that cannot be found on an
     * otherwise valid classpath, the same way {@code ClientNodeImpl.doCoverageAnalysis()} would if
     * the standalone {@code -measureCoverage} target were missing from the configured classpath.
     */
    @Test
    public void analyzeCoverageMarksSweepIncompleteWhenInitialSetupFails() throws Exception {
        String previousTargetClass = Properties.TARGET_CLASS;
        ClientNodeImpl<?> previousClientNode = null;
        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        try {
            previousClientNode = (ClientNodeImpl<?>) clientNodeField.get(ClientServices.getInstance());
            CapturingClientNode node = new CapturingClientNode();
            clientNodeField.set(ClientServices.getInstance(), node);

            Properties.TARGET_CLASS = "org.evosuite.junit.NoSuchClassForRereview5Finding3";

            CoverageAnalysis.analyzeCoverage();

            assertFalse(CoverageAnalysis.isMeasurementFinished(),
                    "a failed initialization never attempted any criterion, so measurement "
                            + "cannot be considered finished");
            assertFalse(CoverageCriteriaAnalyzer.isCoverageAnalysisComplete(),
                    "a failed target/classpath initialization must not leave completeness at "
                            + "its default true");
            assertEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete),
                    "a failed initialization must publish CoverageAnalysisComplete=false, not "
                            + "leave it unpublished");
        } finally {
            Properties.TARGET_CLASS = previousTargetClass;
            resetCoverageAnalysisProgress();
            CoverageAnalysis.resetMeasurementFinished();
            CoverageAnalysis.reset();
            DependencyAnalysis.clear();
            if (previousClientNode != null) {
                clientNodeField.set(ClientServices.getInstance(), previousClientNode);
            }
        }
    }

    /**
     * {@code analyzeCoverage()}'s SELECTED_JUNIT branch must mark measurement finished as soon as
     * {@code runDirectCoverageSweep} returns, before sending statistics or publishing aggregates:
     * otherwise a failure inside either of those would look, to {@code ClientNodeImpl}'s backstop,
     * like a failure during measurement itself, even though the sweep already recorded its own
     * correct completeness verdict.
     *
     * <p>This drives the real {@code analyzeCoverage()} method body -- not a hand-rolled
     * re-sequencing of its private helpers, which would miss a regression in the method's actual
     * statement order -- through the {@code COVERAGE_ANALYSIS_FOR_TESTING} seam: the override
     * {@code Runnable} registers the carving/dependency-analysis mocks on the {@code
     * searchExecutor} worker thread that runs it (mockStatic/mockConstruction only intercept calls
     * from the thread that registered them) and then calls {@code CoverageAnalysis.analyzeCoverage()}
     * for real, with an empty configured-criteria sweep (trivially complete) and a client node that
     * throws only once the real code tries to publish {@code Total_Goals} -- an injected failure
     * strictly after measurement, simulating a statistics-export failure.
     */
    @Test
    public void analyzeCoverageSetsMeasurementFinishedBeforeStatisticsOutputWork() throws Exception {
        boolean previousSandbox = Properties.SANDBOX;
        boolean previousTestArchive = Properties.TEST_ARCHIVE;
        boolean previousNewStatistics = Properties.NEW_STATISTICS;
        String previousSelectedJunit = Properties.SELECTED_JUNIT;
        String previousJunit = Properties.JUNIT;
        Properties.Criterion[] previousCriteria = Properties.CRITERION;
        ClientNodeImpl<?> previousClientNode = null;
        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        ClientNodeImpl<TestSuiteChromosome> client = null;
        try {
            Properties.SANDBOX = false;
            Properties.NEW_STATISTICS = false; // isolate the Total_Goals publish from StatisticsSender
            Properties.SELECTED_JUNIT = "dummy"; // take the carved-suite (SELECTED_JUNIT) branch
            Properties.JUNIT = JUnit4Test.class.getName(); // resolved directly via Class.forName
            Properties.CRITERION = new Properties.Criterion[0]; // trivial, zero-goal sweep

            previousClientNode = (ClientNodeImpl<?>) clientNodeField.get(ClientServices.getInstance());
            ThrowingOnTotalGoalsClientNode node = new ThrowingOnTotalGoalsClientNode();
            clientNodeField.set(ClientServices.getInstance(), node);

            client = new ClientNodeImpl<>(null, "test-client");

            Field analysisOverrideField = ClientNodeImpl.class.getDeclaredField(
                    "COVERAGE_ANALYSIS_FOR_TESTING");
            analysisOverrideField.setAccessible(true);
            analysisOverrideField.set(null, (Runnable) () -> {
                try (MockedStatic<DependencyAnalysis> dependencyMock = mockStatic(DependencyAnalysis.class);
                     MockedConstruction<JUnitTestCarvedChromosomeFactory> carvedMock = mockConstruction(
                             JUnitTestCarvedChromosomeFactory.class,
                             (mock, context) ->
                                     when(mock.getCarvedTestSuite()).thenReturn(new TestSuiteChromosome()))) {
                    CoverageAnalysis.analyzeCoverage();
                }
            });
            try {
                client.doCoverageAnalysis();

                Field doneLatchField = ClientNodeImpl.class.getDeclaredField("doneLatch");
                doneLatchField.setAccessible(true);
                CountDownLatch doneLatch = (CountDownLatch) doneLatchField.get(client);
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS),
                        "doCoverageAnalysis() must still reach ClientState.DONE after the failure");
            } finally {
                analysisOverrideField.set(null, null);
            }

            assertTrue(node.sawTotalGoalsThrow,
                    "the injected failure must have actually fired while publishing Total_Goals, "
                            + "otherwise this test proves nothing");
            assertTrue(CoverageCriteriaAnalyzer.isCoverageAnalysisComplete(),
                    "a failure that happens only while publishing already-measured aggregates "
                            + "must not retroactively mark a genuinely finished (if trivial) "
                            + "sweep incomplete");
            assertNotEquals(Boolean.FALSE, node.value(RuntimeVariable.CoverageAnalysisComplete),
                    "the backstop must not have (re)published CoverageAnalysisComplete=false");
        } finally {
            Properties.SANDBOX = previousSandbox;
            Properties.TEST_ARCHIVE = previousTestArchive;
            Properties.NEW_STATISTICS = previousNewStatistics;
            Properties.SELECTED_JUNIT = previousSelectedJunit;
            Properties.JUNIT = previousJunit;
            Properties.CRITERION = previousCriteria;
            resetCoverageAnalysisProgress();
            CoverageAnalysis.resetMeasurementFinished();
            CoverageAnalysis.reset();
            // See the ordering note in doCoverageAnalysisBackstopMarksIncompleteWhenAnalyzeCoverageFails:
            // client.stop() must run before TimeController.resetSingleton(), not after.
            if (client != null) {
                client.stop();
            }
            TimeController.resetSingleton();
            if (previousClientNode != null) {
                clientNodeField.set(ClientServices.getInstance(), previousClientNode);
            }
        }
    }

    // ---- helpers ----

    private static void invokePublishDirectCoverageSweepResult(TestSuiteChromosome testSuite, int goals)
            throws Exception {
        Method m = CoverageAnalysis.class.getDeclaredMethod(
                "publishDirectCoverageSweepResult", TestSuiteChromosome.class, int.class);
        m.setAccessible(true);
        m.invoke(null, testSuite, goals);
    }

    private static void markMeasurementFinishedForTest() {
        try {
            Method m = CoverageAnalysis.class.getDeclaredMethod("markMeasurementFinished");
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

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

    /**
     * Captures every published variable like {@link CapturingClientNode}, but throws once {@link
     * RuntimeVariable#Total_Goals} is published -- simulating a statistics-export failure that
     * happens strictly after {@code runDirectCoverageSweep} already finished measuring.
     */
    private static final class ThrowingOnTotalGoalsClientNode extends DummyClientNodeImpl {
        private final Map<RuntimeVariable, Object> values = new LinkedHashMap<>();
        private boolean sawTotalGoalsThrow = false;

        @Override
        public void trackOutputVariable(RuntimeVariable name, Object value) {
            if (name == RuntimeVariable.Total_Goals) {
                sawTotalGoalsThrow = true;
                throw new RuntimeException("simulated statistics-export failure");
            }
            values.put(name, value);
        }

        private Object value(RuntimeVariable variable) {
            return values.get(variable);
        }
    }
}
