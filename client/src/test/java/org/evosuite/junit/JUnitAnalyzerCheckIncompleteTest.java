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

import com.examples.with.different.packagename.test.DowncastExample;
import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.junit.writer.TestSuiteWriter;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.TestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JUnit check that is abandoned before completing (watchdog timeout, javac infrastructure
 * failure, running out of memory, or simply running out of the JUnit-check time budget) used to
 * keep the suite silently: nothing downstream could tell the check never actually ran. These
 * tests drive each give-up path directly (per the task instructions, not by reproducing a real
 * hang) and check that {@link JUnitAnalyzer#isCheckIncomplete()} /
 * {@link JUnitAnalyzer#getIncompleteCheckRounds()}, and the
 * {@link RuntimeVariable#JUnit_Check_Incomplete} / {@link RuntimeVariable#JUnit_Check_Incomplete_Rounds}
 * output variables they feed, now report it.
 */
public class JUnitAnalyzerCheckIncompleteTest {

    private static final Properties.Criterion[] defaultCriterion = Properties.CRITERION;
    private static final String defaultTargetClass = Properties.TARGET_CLASS;
    private static final Properties.OutputFormat defaultTestFormat = Properties.TEST_FORMAT;
    private static final boolean defaultScaffolding = Properties.TEST_SCAFFOLDING;
    private static final int defaultJunitCheckTimeout = Properties.JUNIT_CHECK_TIMEOUT;
    private static final Properties.StoppingCondition defaultStoppingCondition = Properties.STOPPING_CONDITION;
    private static final String defaultClassPrefix = Properties.CLASS_PREFIX;
    private static final String defaultScaffoldingSuffix = Properties.SCAFFOLDING_SUFFIX;

    /** Real directory used as the parent of the never-written files in the writer-loss probes below. */
    private static final File DUMMY_DIR = new File(System.getProperty("java.io.tmpdir"));

    private ClientNodeImpl<?> previousClientNode;

    @BeforeEach
    public void setUp() {
        JUnitAnalyzer.resetMemoryExhaustion();
        TimeController.resetSingleton();
    }

    @AfterEach
    public void tearDown() throws Exception {
        JUnitAnalyzer.resetMemoryExhaustion();
        Properties.CRITERION = defaultCriterion;
        Properties.TARGET_CLASS = defaultTargetClass;
        Properties.TEST_FORMAT = defaultTestFormat;
        Properties.TEST_SCAFFOLDING = defaultScaffolding;
        Properties.JUNIT_CHECK_TIMEOUT = defaultJunitCheckTimeout;
        Properties.STOPPING_CONDITION = defaultStoppingCondition;
        Properties.CLASS_PREFIX = defaultClassPrefix;
        Properties.SCAFFOLDING_SUFFIX = defaultScaffoldingSuffix;
        TimeController.resetSingleton();
        restoreClientNodeIfInstalled();
    }

    // ---- The flag/counter mechanism itself ----

    @Test
    public void resetMemoryExhaustionClearsCheckIncompletenessAndTracksFalseBaseline() throws Exception {
        JUnitAnalyzerTestSupport.setFlag("CHECK_INCOMPLETE", true);
        JUnitAnalyzerTestSupport.setIntField("INCOMPLETE_CHECK_ROUNDS", 5);

        CapturingClientNode node = installCapturingClientNode();
        JUnitAnalyzer.resetMemoryExhaustion();

        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(0, JUnitAnalyzer.getIncompleteCheckRounds());
        assertEquals(Boolean.FALSE, node.value(RuntimeVariable.JUnit_Check_Incomplete));
        assertEquals(0, node.value(RuntimeVariable.JUnit_Check_Incomplete_Rounds));
    }

    @Test
    public void recordCheckIncompleteSetsFlagIncrementsCountAndTracksVariables() throws Exception {
        CapturingClientNode node = installCapturingClientNode();
        assertFalse(JUnitAnalyzer.isCheckIncomplete(), "normal completing path must start false");
        assertEquals(0, JUnitAnalyzer.getIncompleteCheckRounds());

        invokeRecordCheckIncomplete("first give-up");
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
        assertEquals(Boolean.TRUE, node.value(RuntimeVariable.JUnit_Check_Incomplete));
        assertEquals(1, node.value(RuntimeVariable.JUnit_Check_Incomplete_Rounds));

        invokeRecordCheckIncomplete("second give-up");
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(2, JUnitAnalyzer.getIncompleteCheckRounds());
        assertEquals(2, node.value(RuntimeVariable.JUnit_Check_Incomplete_Rounds));
    }

    // ---- The sticky give-up paths: memory exhaustion and javac infrastructure failure ----

    @Test
    public void memoryExhaustionDuringCheckRecordsIncompleteOnlyOnce() throws Exception {
        Method stop = JUnitAnalyzer.class.getDeclaredMethod(
                "stopChecksDueToMemoryExhaustion", int.class, Throwable.class);
        stop.setAccessible(true);

        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        stop.invoke(null, 3, new OutOfMemoryError("test"));
        assertTrue(JUnitAnalyzer.isMemoryExhaustedDuringCheck());
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());

        // A repeat hit while already in the exhausted state is the same give-up, not a new one.
        stop.invoke(null, 3, new OutOfMemoryError("test"));
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    @Test
    public void compileCheckDisabledDueToInfrastructureRecordsIncompleteOnlyOnce() throws Exception {
        Field disabledField = JUnitAnalyzer.class.getDeclaredField(
                "COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE");
        disabledField.setAccessible(true);
        boolean previousDisabled = disabledField.getBoolean(null);
        Field loggedField = JUnitAnalyzer.class.getDeclaredField("COMPILE_CHECK_DISABLED_LOGGED");
        loggedField.setAccessible(true);
        boolean previousLogged = loggedField.getBoolean(null);

        Method disable = JUnitAnalyzer.class.getDeclaredMethod(
                "disableCompileCheckDueToInfrastructure", Throwable.class, String.class);
        disable.setAccessible(true);
        try {
            disabledField.setBoolean(null, false);
            loggedField.setBoolean(null, false);

            assertFalse(JUnitAnalyzer.isCheckIncomplete());
            disable.invoke(null, new RuntimeException("infra"), "");
            assertTrue(JUnitAnalyzer.isCompileCheckDisabledDueToInfrastructure());
            assertTrue(JUnitAnalyzer.isCheckIncomplete());
            assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());

            // Already-disabled state: a second infrastructure failure is not a fresh give-up.
            disable.invoke(null, new RuntimeException("infra again"), "");
            assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
        } finally {
            disabledField.setBoolean(null, previousDisabled);
            loggedField.setBoolean(null, previousLogged);
        }
    }

    // ---- The plain time-budget give-up paths ----

    @Test
    public void removeTestsThatDoNotCompileRecordsIncompleteWhenTimeRunsOutBeforeChecking() throws Exception {
        Field numField = JUnitAnalyzer.class.getDeclaredField("NUM");
        numField.setAccessible(true);
        int compiledBefore = numField.getInt(null);

        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());

        JUnitAnalyzerTestSupport.forceNoTimeLeftInJUnitCheckPhase();

        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        JUnitAnalyzer.removeTestsThatDoNotCompile(tests);

        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
        assertEquals(1, tests.size(), "the unchecked test must be kept, not removed");
        assertEquals(compiledBefore, numField.getInt(null), "no compile check may have run");
    }

    @Test
    public void handleTestsThatAreUnstableRecordsIncompleteWhenTimeRunsOutBeforeRunning() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());

        JUnitAnalyzerTestSupport.forceNoTimeLeftInJUnitCheckPhase();

        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        int numUnstable = JUnitAnalyzer.handleTestsThatAreUnstable(tests);

        assertEquals(0, numUnstable);
        assertEquals(1, tests.size(), "the unchecked test must be kept, not removed or run");
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    // ---- OOM escaping the JUnit runner itself ----

    /**
     * {@code runJUnitOnCurrentProcess} submits the version-dependent runner to a worker thread
     * and catches an {@link OutOfMemoryError} that escapes that call, synthesizing an all-failed
     * {@link JUnitResult} whose {@code getFailures()} is empty. The failure-matching loop in
     * {@link JUnitAnalyzer#handleTestsThatAreUnstable(List)} then has nothing to act on, which
     * looks exactly like a check that observed no problems at all -- unless the give-up is
     * recorded separately via {@link JUnitAnalyzer#recordCheckIncomplete(String)}.
     *
     * <p>JUnit itself attributes a per-test {@link OutOfMemoryError} to that test before it would
     * ever reach the outer catch, so a SUT test method that throws does not exercise this path.
     * This test instead injects the failure via {@link
     * JUnitAnalyzer#VERSION_DEPENDENT_ANALYZER_FOR_TESTING} (see that field's javadoc for why a
     * Mockito static/construction mock cannot reach the worker thread instead): an ordinary
     * Mockito {@code mock(Class, Answer)} of the private {@code VersionDependentAnalyzing} type
     * (obtained via {@code Class.forName}, since the type cannot be named from this file) whose
     * every method throws, read directly by the worker thread so it is unaffected by the thread
     * restriction that rules out a static mock.
     */
    @Test
    public void outOfMemoryEscapingTheJUnitRunnerRecordsIncomplete() throws Exception {
        CapturingClientNode node = installCapturingClientNode();
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase()); // also selects TEST_FORMAT=JUNIT4

        assertFalse(JUnitAnalyzer.isCheckIncomplete());

        Field analyzerOverrideField = JUnitAnalyzer.class.getDeclaredField(
                "VERSION_DEPENDENT_ANALYZER_FOR_TESTING");
        analyzerOverrideField.setAccessible(true);
        Class<?> analyzerType = Class.forName("org.evosuite.junit.JUnitAnalyzer$VersionDependentAnalyzing");
        Object throwingAnalyzer = Mockito.mock(analyzerType,
                invocation -> {
                    throw new OutOfMemoryError("test");
                });

        analyzerOverrideField.set(null, throwingAnalyzer);
        try {
            int numUnstable = JUnitAnalyzer.handleTestsThatAreUnstable(tests);

            assertEquals(0, numUnstable, "the synthetic all-failed result carries no per-test "
                    + "failure to match, so nothing is marked unstable");
            assertEquals(1, tests.size(), "the unchecked test must be kept, not removed");
        } finally {
            analyzerOverrideField.set(null, null);
        }

        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
        assertEquals(Boolean.TRUE, node.value(RuntimeVariable.JUnit_Check_Incomplete));
        assertEquals(1, node.value(RuntimeVariable.JUnit_Check_Incomplete_Rounds));
    }

    // ---- Infrastructure setup failures: createNewTmpDir() itself fails ----
    //
    // Each of these points java.io.tmpdir at a plain file (not a directory), which is the
    // probe technique used to reproduce the failure: createNewTmpDir() builds its target path
    // underneath java.io.tmpdir and then calls File.mkdirs() on it, which fails whenever any
    // path component already exists as a non-directory. The property is a JVM-wide global, so
    // every test below restores it in a finally block -- failing to do so would poison every
    // later test in this fork that needs a real tmp dir.

    @Test
    public void removeTestsThatDoNotCompileRecordsIncompleteWhenTmpDirCreationFails() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());

        withPoisonedTmpDir(() -> {
            assertFalse(JUnitAnalyzer.isCheckIncomplete());
            JUnitAnalyzer.removeTestsThatDoNotCompile(tests);

            assertTrue(JUnitAnalyzer.isCheckIncomplete());
            assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
            assertEquals(1, tests.size(), "the unchecked test must be kept, not removed");
        });
    }

    @Test
    public void handleTestsThatAreUnstableRecordsIncompleteWhenTmpDirCreationFails() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());

        withPoisonedTmpDir(() -> {
            assertFalse(JUnitAnalyzer.isCheckIncomplete());
            int numUnstable = JUnitAnalyzer.handleTestsThatAreUnstable(tests);

            assertEquals(0, numUnstable);
            assertEquals(1, tests.size(), "the unchecked test must be kept, not removed or run");
            assertTrue(JUnitAnalyzer.isCheckIncomplete());
            assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
        });
    }

    @Test
    public void verifyCompilationAndExecutionRecordsIncompleteWhenTmpDirCreationFails() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());

        withPoisonedTmpDir(() -> {
            assertFalse(JUnitAnalyzer.isCheckIncomplete());
            boolean result = JUnitAnalyzer.verifyCompilationAndExecution(tests);

            assertFalse(result);
            assertTrue(JUnitAnalyzer.isCheckIncomplete());
            assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
        });
    }

    @Test
    public void executeAndCollectUnexpectedFailuresRecordsIncompleteWhenTmpDirCreationFails() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());
        tests.add(compilingTestCase());

        withPoisonedTmpDir(() -> {
            assertFalse(JUnitAnalyzer.isCheckIncomplete());

            Method m = JUnitAnalyzer.class.getDeclaredMethod(
                    "executeAndCollectUnexpectedFailures", List.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> failures = (Set<String>) m.invoke(null, tests);

            assertEquals(Collections.singleton("tmp-dir-error"), failures);
            assertTrue(JUnitAnalyzer.isCheckIncomplete());
            assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
        });
    }

    // ---- Retained-suite give-ups: the whole-suite compile/load and order-probe sentinels that
    // the per-test compile filter never saw, because by this point every test already passed
    // that filter individually. A null here is not the filter's ordinary "this generated test
    // does not compile" verdict (that case is pinned below as a non-regression), but an
    // unexpected failure of the writer/compiler/loader on a suite the filter already approved.

    /**
     * Forces {@code compileTests} to return null from inside the real
     * {@link JUnitAnalyzer#handleTestsThatAreUnstable(List)} call by making the mocked
     * {@link TestSuiteWriter} claim it generated a source file that was never written -- the same
     * fault-injection technique as the fourth-review-pass probe
     * ({@code /private/tmp/Rereview4FailureProbe.java}): {@code compileTests} checks
     * {@code file.exists()} on each entry of the writer's own returned list and gives up with
     * {@code null} if one is missing.
     */
    @Test
    public void handleTestsThatAreUnstableRecordsIncompleteWhenWholeSuiteCompileFails() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());

        try (MockedConstruction<TestSuiteWriter> ignored = Mockito.mockConstruction(
                TestSuiteWriter.class,
                (mock, context) -> Mockito.when(mock.writeTestSuite(
                                Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                        .thenReturn(Collections.singletonList(
                                new File(DUMMY_DIR, "missing-generated-source.java"))))) {

            assertFalse(JUnitAnalyzer.isCheckIncomplete());
            int numUnstable = JUnitAnalyzer.handleTestsThatAreUnstable(tests);

            assertEquals(0, numUnstable);
            assertEquals(1, tests.size(), "the unchecked test must be kept, not removed");
            assertTrue(JUnitAnalyzer.isCheckIncomplete());
            assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
        }
    }

    /**
     * Forces {@code loadTests} to return null from inside the real
     * {@link JUnitAnalyzer#handleTestsThatAreUnstable(List)} call after a genuinely successful
     * compile -- unlike the missing-source-file fault above, this exercises the
     * {@code testClasses == null} branch at the loading step, which the fourth-review-pass report
     * flagged as verified by source inspection only. The fault: point
     * {@link Properties#SCAFFOLDING_SUFFIX} at the same suffix the compile-check naming already
     * uses ({@link Properties#JUNIT_SUFFIX}, minus its leading underscore), so the real compiled
     * test file is misclassified as pure scaffolding by {@code isScaffolding(File)} and
     * contributes zero non-scaffolding classes -- {@code getClassesFromFiles} returns an empty
     * (not null) array, which {@code loadTests} also treats as "nothing to load".
     */
    @Test
    public void handleTestsThatAreUnstableRecordsIncompleteWhenLoadingCompiledClassesFails() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());
        Properties.CLASS_PREFIX = "";
        Properties.SCAFFOLDING_SUFFIX = Properties.JUNIT_SUFFIX.startsWith("_")
                ? Properties.JUNIT_SUFFIX.substring(1) : Properties.JUNIT_SUFFIX;

        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        int numUnstable = JUnitAnalyzer.handleTestsThatAreUnstable(tests);

        assertEquals(0, numUnstable);
        assertEquals(1, tests.size(), "the unchecked test must be kept, not removed");
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    @Test
    public void executeAndCollectUnexpectedFailuresRecordsIncompleteOnCompilationError() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());
        tests.add(compilingTestCase());

        try (MockedConstruction<TestSuiteWriter> ignored = Mockito.mockConstruction(
                TestSuiteWriter.class,
                (mock, context) -> Mockito.when(mock.writeTestSuite(
                                Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                        .thenReturn(Collections.singletonList(
                                new File(DUMMY_DIR, "missing-generated-source.java"))))) {

            assertFalse(JUnitAnalyzer.isCheckIncomplete());

            Method m = JUnitAnalyzer.class.getDeclaredMethod(
                    "executeAndCollectUnexpectedFailures", List.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> failures = (Set<String>) m.invoke(null, tests);

            assertEquals(Collections.singleton("compilation-error"), failures);
            assertTrue(JUnitAnalyzer.isCheckIncomplete());
            assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
        }
    }

    @Test
    public void executeAndCollectUnexpectedFailuresRecordsIncompleteOnLoadError() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());
        tests.add(compilingTestCase());
        Properties.CLASS_PREFIX = "";
        Properties.SCAFFOLDING_SUFFIX = Properties.JUNIT_SUFFIX.startsWith("_")
                ? Properties.JUNIT_SUFFIX.substring(1) : Properties.JUNIT_SUFFIX;

        assertFalse(JUnitAnalyzer.isCheckIncomplete());

        Method m = JUnitAnalyzer.class.getDeclaredMethod(
                "executeAndCollectUnexpectedFailures", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> failures = (Set<String>) m.invoke(null, tests);

        assertEquals(Collections.singleton("load-error"), failures);
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    /**
     * Forces the order probe's catch-all {@code catch (Exception e)} branch (the
     * {@code "execution-error"} sentinel) via a null entry in the test list: {@code compileTests}
     * maps {@code TestCase::clone} over the list before its own try/catch begins, so a null
     * element throws a plain {@link NullPointerException} that propagates out of
     * {@code compileTests} uncaught and lands in {@code executeAndCollectUnexpectedFailures}'s
     * outer catch -- a different, genuinely-reachable route to the same sentinel the
     * fourth-review-pass report flagged.
     */
    @Test
    public void executeAndCollectUnexpectedFailuresRecordsIncompleteOnExecutionError() throws Exception {
        List<TestCase> tests = new ArrayList<>(Arrays.asList(compilingTestCase(), null));

        assertFalse(JUnitAnalyzer.isCheckIncomplete());

        Method m = JUnitAnalyzer.class.getDeclaredMethod(
                "executeAndCollectUnexpectedFailures", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> failures = (Set<String>) m.invoke(null, tests);

        assertEquals(Collections.singleton("execution-error"), failures);
        assertTrue(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(1, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    // ---- Non-regression: the per-test compile filter's ordinary rejected-test verdict must stay
    // a plain removal, not an incomplete-check give-up, even though it shares compileTests()'s
    // null return with the paths above. ----

    @Test
    public void removeTestsThatDoNotCompileLeavesCheckIncompleteFalseWhenWriterLosesGeneratedSource() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());

        try (MockedConstruction<TestSuiteWriter> ignored = Mockito.mockConstruction(
                TestSuiteWriter.class,
                (mock, context) -> Mockito.when(mock.writeTestSuite(
                                Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                        .thenReturn(Collections.singletonList(
                                new File(DUMMY_DIR, "missing-generated-source.java"))))) {

            assertFalse(JUnitAnalyzer.isCheckIncomplete());
            JUnitAnalyzer.removeTestsThatDoNotCompile(tests);

            assertEquals(0, tests.size(), "a test that fails the compile filter is removed, as normal");
            assertFalse(JUnitAnalyzer.isCheckIncomplete(),
                    "the per-test compile filter's ordinary rejection is not an incomplete check");
            assertEquals(0, JUnitAnalyzer.getIncompleteCheckRounds());
        }
    }

    // ---- The normal, completing path ----

    @Test
    public void normalCompletingCompileFilterLeavesCheckIncompleteFalse() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.TEST_SCAFFOLDING = false;
        Properties.TARGET_CLASS = DowncastExample.class.getCanonicalName();
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());

        // Plenty of time: TimeController was never moved out of NOT_STARTED, so
        // hasTimeToExecuteATestCase() is unconditionally true.
        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        JUnitAnalyzer.removeTestsThatDoNotCompile(tests);

        assertEquals(1, tests.size(), "a compiling test must survive the filter");
        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(0, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    @Test
    public void normalCompletingInstabilityCheckLeavesCheckIncompleteFalse() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.TEST_SCAFFOLDING = false;
        Properties.TARGET_CLASS = DowncastExample.class.getCanonicalName();
        List<TestCase> tests = new ArrayList<>();
        tests.add(compilingTestCase());

        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        int numUnstable = JUnitAnalyzer.handleTestsThatAreUnstable(tests);

        assertEquals(0, numUnstable);
        assertEquals(1, tests.size());
        assertFalse(JUnitAnalyzer.isCheckIncomplete());
        assertEquals(0, JUnitAnalyzer.getIncompleteCheckRounds());
    }

    // ---- helpers ----

    private static TestCase compilingTestCase() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.TEST_SCAFFOLDING = false;
        Properties.TARGET_CLASS = DowncastExample.class.getCanonicalName();
        try {
            TestCaseBuilder builder = new TestCaseBuilder();
            builder.appendConstructor(DowncastExample.class.getConstructor());
            return builder.getDefaultTestCase();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Points {@code java.io.tmpdir} at a plain file for the duration of {@code body}, so that
     * {@link JUnitAnalyzer#createNewTmpDir()}'s {@code mkdirs()} call fails the way it would if the
     * real tmp dir were unavailable, then always restores the original property -- even on
     * failure, this must not leak a poisoned {@code java.io.tmpdir} into later tests.
     */
    private static void withPoisonedTmpDir(ThrowingRunnable body) throws Exception {
        String originalTmpDir = System.getProperty("java.io.tmpdir");
        File blocker = File.createTempFile("JUnitAnalyzerTmpDirBlocker", ".tmp");
        try {
            System.setProperty("java.io.tmpdir", blocker.getAbsolutePath());
            body.run();
        } finally {
            System.setProperty("java.io.tmpdir", originalTmpDir);
            if (!blocker.delete()) {
                blocker.deleteOnExit();
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void invokeRecordCheckIncomplete(String reason) throws Exception {
        Method record = JUnitAnalyzer.class.getDeclaredMethod("recordCheckIncomplete", String.class);
        record.setAccessible(true);
        record.invoke(null, reason);
    }

    private CapturingClientNode installCapturingClientNode() throws Exception {
        Field field = ClientServices.class.getDeclaredField("clientNode");
        field.setAccessible(true);
        previousClientNode = (ClientNodeImpl<?>) field.get(ClientServices.getInstance());
        CapturingClientNode node = new CapturingClientNode();
        field.set(ClientServices.getInstance(), node);
        return node;
    }

    private void restoreClientNodeIfInstalled() throws Exception {
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
