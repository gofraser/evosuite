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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real JUnit check on a suite with a test that wedges where no interrupt can free it,
 * for both flavours of generated tests. The check has to name that test, keep what the earlier
 * tests found, and return long before the budget of the whole check.
 */
public class JUnitAnalyzerHungTestEndToEndTest {

    static volatile CountDownLatch release = new CountDownLatch(1);

    private Properties.OutputFormat savedFormat;

    @BeforeEach
    public void setUp() {
        release = new CountDownLatch(1);
        savedFormat = Properties.TEST_FORMAT;
        JUnitCheckRunner.HANG_LIMIT_FOR_TESTING_MS = 500L;
        JUnitCheckRunner.HANG_GRACE_FOR_TESTING_MS = 2_000L;
    }

    @AfterEach
    public void tearDown() {
        Properties.TEST_FORMAT = savedFormat;
        JUnitCheckRunner.HANG_LIMIT_FOR_TESTING_MS = 0L;
        JUnitCheckRunner.HANG_GRACE_FOR_TESTING_MS = 0L;
        release.countDown();
    }

    private static void wedge() {
        // ignores the interrupt, like a thread waiting for a class initialization lock
        while (release.getCount() > 0) {
            try {
                release.await(10, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                // keep waiting, whatever is thrown at this thread
            }
        }
    }

    private static void spin() {
        // a loop in code that checks neither for interrupts nor for anything else
        long x = 0;
        while (release.getCount() > 0) {
            x++;
        }
        if (x < 0) {
            throw new IllegalStateException();
        }
    }

    /** JUnit 4 flavour. */
    @org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
    public static class Hangs4 {
        @org.junit.Test
        public void test0() {
            org.junit.Assert.fail("fails before the hang");
        }

        @org.junit.Test
        public void test1() {
            wedge();
        }
    }

    /** JUnit 5 flavour. */
    @org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
    public static class Hangs5 {
        @org.junit.jupiter.api.Test
        void test0() {
            org.junit.jupiter.api.Assertions.fail("fails before the hang");
        }

        @org.junit.jupiter.api.Test
        void test1() {
            wedge();
        }
    }

    /** JUnit 4 flavour: a test that passes, then one that wedges. */
    @org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
    public static class PassesThenHangs4 {
        @org.junit.Test
        public void test0() {
        }

        @org.junit.Test
        public void test1() {
            wedge();
        }
    }

    /** JUnit 5 flavour: a test that passes, then one that wedges. */
    @org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
    public static class PassesThenHangs5 {
        @org.junit.jupiter.api.Test
        void test0() {
        }

        @org.junit.jupiter.api.Test
        void test1() {
            wedge();
        }
    }

    /** JUnit 4 flavour with a test that spins and tests after it. */
    @org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
    public static class Spins4 {
        @org.junit.Test
        public void test0() {
            org.junit.Assert.fail("fails before the spin");
        }

        @org.junit.Test
        public void test1() {
            spin();
        }

        @org.junit.Test
        public void test2() {
            org.junit.Assert.fail("fails after the spin");
        }
    }

    /** JUnit 5 flavour with a test that spins and tests after it. */
    @org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
    public static class Spins5 {
        @org.junit.jupiter.api.Test
        void test0() {
            org.junit.jupiter.api.Assertions.fail("fails before the spin");
        }

        @org.junit.jupiter.api.Test
        void test1() {
            spin();
        }

        @org.junit.jupiter.api.Test
        void test2() {
            org.junit.jupiter.api.Assertions.fail("fails after the spin");
        }
    }

    static volatile boolean laterTestRan;
    static volatile boolean classCleanupRan;

    /** JUnit 4 flavour: the hang clears after the check gave up, then more tests and a cleanup. */
    @org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
    public static class HangsThenMore4 {
        @org.junit.Test
        public void test0() {
            wedge();
        }

        @org.junit.Test
        public void test1() {
            laterTestRan = true;
        }

        @org.junit.AfterClass
        public static void cleanup() {
            classCleanupRan = true;
        }
    }

    /** JUnit 5 flavour: the hang clears after the check gave up, then more tests and a cleanup. */
    @org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
    public static class HangsThenMore5 {
        @org.junit.jupiter.api.Test
        void test0() {
            wedge();
        }

        @org.junit.jupiter.api.Test
        void test1() {
            laterTestRan = true;
        }

        @org.junit.jupiter.api.AfterAll
        static void cleanup() {
            classCleanupRan = true;
        }
    }

    private void assertOrphanStopsWhereItIs(Class<?> suite) throws Exception {
        laterTestRan = false;
        classCleanupRan = false;
        JUnitResult result = check(suite);
        assertFalse(result.wasSuccessful());
        // the hang clears only now, with the check long gone
        release.countDown();
        Thread.sleep(1_500L);
        assertFalse(laterTestRan, "the abandoned runner started another test");
        assertFalse(classCleanupRan, "the abandoned runner ran the class level cleanup");
    }

    @Test
    public void junit4AbandonedRunnerStartsNoFurtherTestOnceTheHangClears() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        assertOrphanStopsWhereItIs(HangsThenMore4.class);
    }

    @Test
    public void junit5AbandonedRunnerStartsNoFurtherTestOnceTheHangClears() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        assertOrphanStopsWhereItIs(HangsThenMore5.class);
    }

    private static JUnitResult check(Class<?> suite) throws Exception {
        Method run = JUnitCheckRunner.class.getDeclaredMethod("run", Class[].class);
        run.setAccessible(true);
        return (JUnitResult) run.invoke(null, (Object) new Class<?>[]{suite});
    }

    private static String describe(JUnitResult result) {
        StringBuilder sb = new StringBuilder();
        for (JUnitFailure failure : result.getFailures()) {
            sb.append(failure.getDescriptionMethodName()).append(": ")
                    .append(failure.getExceptionClassName()).append(": ").append(failure.getMessage()).append("; ");
        }
        return sb.toString();
    }

    private static void assertHangBlamed(JUnitResult result) {
        JUnitFailure hung = null;
        for (JUnitFailure failure : result.getFailures()) {
            if (JUnitCheckProgress.HUNG_TEST_EXCEPTION.equals(failure.getExceptionClassName())) {
                hung = failure;
            }
        }
        assertTrue(hung != null, "no failure blames the hung test: " + describe(result));
        assertEquals("test1", hung.getDescriptionMethodName());
        assertFalse(hung.isAssertionError());
        boolean earlierFailureKept = false;
        for (JUnitFailure failure : result.getFailures()) {
            if ("test0".equals(failure.getDescriptionMethodName())) {
                earlierFailureKept = true;
            }
        }
        assertTrue(earlierFailureKept, "the failure found before the hang was lost");
    }

    @Test
    public void junit4CheckBlamesTheHungTest() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        long start = System.nanoTime();
        JUnitResult result = check(Hangs4.class);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMs < 40_000L, "took " + tookMs + " ms");
        assertHangBlamed(result);
    }

    @Test
    public void junit5CheckBlamesTheHungTest() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        long start = System.nanoTime();
        JUnitResult result = check(Hangs5.class);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMs < 40_000L, "took " + tookMs + " ms");
        assertHangBlamed(result);
    }

    private static void assertSpinStoppedAndRunWentOn(JUnitResult result) {
        Assumptions.assumeTrue(Runtime.version().feature() < 20, "Thread.stop() is not available from Java 20");
        boolean stoppedTestBlamed = false;
        boolean laterTestRan = false;
        for (JUnitFailure failure : result.getFailures()) {
            boolean hung = JUnitCheckProgress.HUNG_TEST_EXCEPTION.equals(failure.getExceptionClassName());
            if ("test1".equals(failure.getDescriptionMethodName())) {
                stoppedTestBlamed = hung;
            }
            // test2 fails by its own assertion, which only happens if the run was not abandoned
            if ("test2".equals(failure.getDescriptionMethodName())) {
                laterTestRan = !hung;
            }
        }
        assertTrue(stoppedTestBlamed, "the stopped test is not blamed: " + describe(result));
        assertTrue(laterTestRan, "the tests after the stopped one never ran: " + describe(result));
    }

    @Test
    public void junit4CheckStopsASpinningTestAndGoesOn() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        assertSpinStoppedAndRunWentOn(check(Spins4.class));
    }

    @Test
    public void junit5CheckStopsASpinningTestAndGoesOn() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        assertSpinStoppedAndRunWentOn(check(Spins5.class));
    }

    @Test
    public void junit4RunCountOfAnAbandonedRunIsTheTestsThatFinished() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        JUnitResult result = check(PassesThenHangs4.class);
        assertEquals(1, result.getRunCount(), "one test finished, none of them failed before the hang");
    }

    @Test
    public void junit5RunCountOfAnAbandonedRunIsTheTestsThatFinished() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        JUnitResult result = check(PassesThenHangs5.class);
        assertEquals(1, result.getRunCount(), "one test finished, none of them failed before the hang");
    }
}
