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

import org.evosuite.junit.JUnitAnalyzer;
import org.evosuite.junit.JUnitAnalyzerTestSupport;
import org.evosuite.llm.postprocess.LlmPostProcessingPhaseContext;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JUnitAnalyzer} only marks a JUnit check round incomplete when execution reaches one of
 * its own internal abort paths. {@link TestSuiteGenerator#compileAndCheckTests} can give up
 * before any of those paths are ever reached: a low-memory preflight, an expired phase budget, a
 * per-test loop that runs out of time, a whole-suite/reverse-order check skipped for lack of
 * time, an order-sensitivity probe skipped for lack of time, or a memory guard that trips
 * independently of anything {@code JUnitAnalyzer} itself observed. None of those exits used to
 * reach {@link JUnitAnalyzer#isCheckIncomplete()} / {@link JUnitAnalyzer#getIncompleteCheckRounds()}
 * at all, so a harness reading the opt-in {@link RuntimeVariable#JUnit_Check_Incomplete} output
 * variable could not tell such a suite from one that was actually validated.
 *
 * <p>These tests drive the generator-level exits directly -- either through the real private
 * {@code compileAndCheckTests} entry point (reproducing the reviewer's repro) or, where driving
 * the whole method would make an unrelated, already-covered internal give-up fire at the same
 * instant, through the specific private helper that contains the fix.
 */
public class TestSuiteGeneratorJUnitCheckCompletenessTest {

    private static final int defaultMinFreeMem = Properties.MIN_FREE_MEM;
    private static final Properties.StoppingCondition defaultStoppingCondition = Properties.STOPPING_CONDITION;
    private static final int defaultJunitCheckTimeout = Properties.JUNIT_CHECK_TIMEOUT;
    private static final Properties.OutputFormat defaultTestFormat = Properties.TEST_FORMAT;
    private static final boolean defaultTestExtensionMode = Properties.TEST_EXTENSION_MODE;
    private static final boolean defaultResetStaticFields = Properties.RESET_STATIC_FIELDS;

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        JUnitAnalyzer.resetMemoryExhaustion();
        TimeController.resetSingleton();
        TestSuiteGenerator.freeMemoryBytesForTesting = null;
    }

    @AfterEach
    public void tearDown() throws Exception {
        Properties.MIN_FREE_MEM = defaultMinFreeMem;
        Properties.STOPPING_CONDITION = defaultStoppingCondition;
        Properties.JUNIT_CHECK_TIMEOUT = defaultJunitCheckTimeout;
        Properties.TEST_FORMAT = defaultTestFormat;
        Properties.TEST_EXTENSION_MODE = defaultTestExtensionMode;
        Properties.RESET_STATIC_FIELDS = defaultResetStaticFields;
        Properties.getInstance().resetToDefaults();
        JUnitAnalyzer.resetMemoryExhaustion();
        TimeController.resetSingleton();
        TestSuiteGenerator.freeMemoryBytesForTesting = null;
    }

    // ---- The generator-level exits, driven through the real compileAndCheckTests entry ----

    @Test
    public void lowMemoryPreflightSkipRecordsIncompleteAndDiscardsStaleRoundCount() throws Exception {
        // Simulate a reused JVM where an earlier, unrelated check round already left the
        // flag/counter dirty. The fix resets this *before* the preflight can return, so the
        // skip below must be recorded as exactly one fresh give-up, not seven stale ones plus one.
        JUnitAnalyzerTestSupport.setFlag("CHECK_INCOMPLETE", true);
        JUnitAnalyzerTestSupport.setIntField("INCOMPLETE_CHECK_ROUNDS", 7);

        // Stub the measurement itself rather than relying on Properties.MIN_FREE_MEM against
        // actual free heap: a JVM with a large enough max heap can have more free than even
        // Integer.MAX_VALUE bytes, so the doubled threshold would not reliably trip the preflight.
        TestSuiteGenerator.freeMemoryBytesForTesting = () -> 0L;
        Properties.MIN_FREE_MEM = 1;

        TestSuiteChromosome suite = suiteWithOneTrivialTest();

        invokeCompileAndCheckTests(suite);

        assertEquals(1, suite.size(), "the unchecked test must be kept, not removed");
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds(),
                "must report exactly this skip, not the stale count from a previous run");
    }

    @Test
    public void phaseBudgetExpiredBeforeAnyCheckRecordsIncomplete() throws Exception {
        Properties.MIN_FREE_MEM = 0;
        JUnitAnalyzerTestSupport.forceNoTimeLeftInJUnitCheckPhase();

        TestSuiteChromosome suite = suiteWithOneTrivialTest();

        invokeCompileAndCheckTests(suite);

        assertEquals(1, suite.size(), "the unchecked test must be kept, not removed");
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    @Test
    public void midBudgetTimeExhaustionRecordsEveryDistinctGiveUp() throws Exception {
        Properties.MIN_FREE_MEM = 0;
        // Enough phase time to pass the top-level isThereStillTimeInThisPhase() check (needs only
        // >=1 ms) but not enough for hasTimeToExecuteATestCase() (needs >= Properties.TIMEOUT,
        // 3000 ms by default): every later gate that uses the stricter check gives up too.
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXTIME;
        Properties.JUNIT_CHECK_TIMEOUT = 1;
        TimeController.resetSingleton();
        TimeController.getInstance().updateState(ClientState.STARTED);
        TimeController.getInstance().updateState(ClientState.JUNIT_CHECK);
        assertTrue(TimeController.getInstance().isThereStillTimeInThisPhase(),
                "test setup must leave the weaker check passing");
        assertFalse(TimeController.getInstance().hasTimeToExecuteATestCase(),
                "test setup must leave the stricter check failing");

        TestSuiteChromosome suite = suiteWithOneTrivialTest();

        invokeCompileAndCheckTests(suite);

        assertEquals(1, suite.size(), "the unchecked test must be kept, not removed");
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        // (1) JUnitAnalyzer's own pre-existing compile-filter give-up, (2) the generator's
        // per-test loop break, (3) the generator's whole-suite-check time skip: three distinct
        // exits hit the same time condition in sequence.
        assertEquals(3, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    @Test
    public void wholeSuiteCheckTimeSkipRecordsIncompleteInIsolationWithEmptySuite() throws Exception {
        Properties.MIN_FREE_MEM = 0;
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXTIME;
        Properties.JUNIT_CHECK_TIMEOUT = 1;
        TimeController.resetSingleton();
        TimeController.getInstance().updateState(ClientState.STARTED);
        TimeController.getInstance().updateState(ClientState.JUNIT_CHECK);

        // An empty suite skips the compile filter (nothing to do) and the per-test loop (no
        // elements to iterate), so the whole-suite-check skip is the *only* give-up reachable
        // here -- isolating exactly the gap fixed in compileAndCheckTests.
        TestSuiteChromosome suite = new TestSuiteChromosome();

        invokeCompileAndCheckTests(suite);

        assertEquals(0, suite.size());
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    // ---- isMemoryExhaustedForJUnitCheck: the generator's own JVM-memory guard ----

    @Test
    public void ownMemoryGuardTripRecordsIncompleteOnlyOnce() throws Exception {
        PhaseMemoryGuard guard = new PhaseMemoryGuard(() -> 0L, () -> { }, 100L);

        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        assertTrue(invokeIsMemoryExhaustedForJUnitCheck(guard, "the test checks", 2));
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());

        // Already-exhausted guard: same give-up, not a new one.
        assertTrue(invokeIsMemoryExhaustedForJUnitCheck(guard, "the test checks", 2));
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    @Test
    public void memoryGuardTripAlreadyRecordedByAnalyzerIsNotDoubleCounted() throws Exception {
        Method stop = JUnitAnalyzer.class.getDeclaredMethod(
                "stopChecksDueToMemoryExhaustion", int.class, Throwable.class);
        stop.setAccessible(true);
        stop.invoke(null, 1, new OutOfMemoryError("test"));
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());

        // This guard has not seen anything itself; it only learns of the OOM through
        // JUnitAnalyzer.isMemoryExhaustedDuringCheck(), which that call already recorded.
        PhaseMemoryGuard guard = new PhaseMemoryGuard(() -> 1_000_000L, () -> { }, 100L);
        assertTrue(invokeIsMemoryExhaustedForJUnitCheck(guard, "the test checks", 0));

        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds(),
                "must not double-count a give-up JUnitAnalyzer already recorded");
    }

    @Test
    public void healthyMemoryGuardRecordsNothing() throws Exception {
        PhaseMemoryGuard guard = new PhaseMemoryGuard(() -> 1_000_000_000L, () -> { }, 100L);

        assertFalse(invokeIsMemoryExhaustedForJUnitCheck(guard, "the test checks", 0));
        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(0, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    // ---- checkAllTestsIfTime: the reverse-order probe's own time skip ----

    @Test
    public void checkAllTestsIfTimeSkipRecordsIncomplete() throws Exception {
        JUnitAnalyzerTestSupport.forceNoTimeLeftInJUnitCheckPhase();
        List<TestCase> tests = new ArrayList<>();
        tests.add(trivialTestCase());

        assertEquals(0, invokeCheckAllTestsIfTime(tests, 0L));
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    // ---- resolveExtensionInitializationOrderIfNeeded: the order-probe time skips ----

    @Test
    public void extensionOrderResolutionSkippedForTimeBeforeBeingConsideredRecordsIncomplete() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Properties.TEST_EXTENSION_MODE = true;
        Properties.RESET_STATIC_FIELDS = true;
        JUnitAnalyzerTestSupport.forceNoTimeLeftInJUnitCheckPhase();

        List<TestCase> tests = new ArrayList<>();
        tests.add(trivialTestCase());
        tests.add(trivialTestCase());

        List<String> result = invokeResolveExtensionInitializationOrderIfNeeded(tests, 0L, 0L);

        assertTrue(result.isEmpty());
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    @Test
    public void orderSensitivityAnalysisSkippedForTimeRecordsIncomplete() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Properties.TEST_EXTENSION_MODE = true;
        Properties.RESET_STATIC_FIELDS = true;
        // Mirrors JUnitCheckDeadlineTest#orderSensitivityAnalysisNeedsTwiceItsCostInThePhase: a
        // 10 s phase budget cannot accommodate a probe whose suite run already costs 3 s (which
        // needs 2*(2*3000) = 12 s).
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXTIME;
        Properties.JUNIT_CHECK_TIMEOUT = 10;
        TimeController.resetSingleton();
        TimeController.getInstance().updateState(ClientState.STARTED);
        TimeController.getInstance().updateState(ClientState.JUNIT_CHECK);
        assertFalse(TestSuiteGenerator.hasTimeForOrderSensitivityAnalysis(3_000L),
                "test setup must actually be short of the order-sensitivity analysis budget");

        List<TestCase> tests = new ArrayList<>();
        tests.add(trivialTestCase());
        tests.add(trivialTestCase());

        List<String> result = invokeResolveExtensionInitializationOrderIfNeeded(tests, 0L, 3_000L);

        assertTrue(result.isEmpty());
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    @Test
    public void extensionOrderResolutionNotApplicableRecordsNothing() throws Exception {
        // TEST_EXTENSION_MODE is off (the default): this feature does not apply, which must not
        // be confused with an incomplete check.
        List<TestCase> tests = new ArrayList<>();
        tests.add(trivialTestCase());
        tests.add(trivialTestCase());

        List<String> result = invokeResolveExtensionInitializationOrderIfNeeded(tests, 0L, 0L);

        assertTrue(result.isEmpty());
        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(0, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    // ---- helpers ----

    private static TestSuiteChromosome suiteWithOneTrivialTest() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(trivialTestCase());
        suite.addTest(chromosome);
        return suite;
    }

    private static TestCase trivialTestCase() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new IntPrimitiveStatement(testCase, 1));
        return testCase;
    }

    private static void invokeCompileAndCheckTests(TestSuiteChromosome suite) throws Exception {
        Method m = TestSuiteGenerator.class.getDeclaredMethod(
                "compileAndCheckTests", TestSuiteChromosome.class, LlmPostProcessingPhaseContext.class);
        m.setAccessible(true);
        m.invoke(new TestSuiteGenerator(), suite, null);
    }

    private static boolean invokeIsMemoryExhaustedForJUnitCheck(
            PhaseMemoryGuard guard, String what, int unchecked) throws Exception {
        Method m = TestSuiteGenerator.class.getDeclaredMethod(
                "isMemoryExhaustedForJUnitCheck", PhaseMemoryGuard.class, String.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, guard, what, unchecked);
    }

    @SuppressWarnings("unchecked")
    private static int invokeCheckAllTestsIfTime(List<TestCase> tests, long delta) throws Exception {
        Method m = TestSuiteGenerator.class.getDeclaredMethod("checkAllTestsIfTime", List.class, long.class);
        m.setAccessible(true);
        return (int) m.invoke(null, tests, delta);
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeResolveExtensionInitializationOrderIfNeeded(
            List<TestCase> tests, long delta, long suiteRunMs) throws Exception {
        Method m = TestSuiteGenerator.class.getDeclaredMethod(
                "resolveExtensionInitializationOrderIfNeeded", List.class, long.class, long.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, tests, delta, suiteRunMs);
    }

}
