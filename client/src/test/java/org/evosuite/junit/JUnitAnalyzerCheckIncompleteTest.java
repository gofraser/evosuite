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
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.TestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
