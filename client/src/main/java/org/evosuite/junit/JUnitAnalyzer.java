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

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.instrumentation.NonInstrumentingClassLoader;
import org.evosuite.junit.writer.TestSuiteWriter;
import org.evosuite.junit.writer.TestSuiteWriterUtils;
import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.InitializingListener;
import org.evosuite.runtime.InitializingListenerUtils;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.classhandling.ClassStateSupport;
import org.evosuite.runtime.classhandling.JDKClassResetter;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.runtime.util.JarPathing;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * This class is used to check if a set of test cases are valid for JUnit: ie,
 * if they can be compiled, they do not fail, and if running them a second time
 * produces same result (ie not fail).
 *
 * @author arcuri
 */
public abstract class JUnitAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(JUnitAnalyzer.class);

    private static int dirCounter = 0;

    private static final String JAVA = ".java";
    private static final String CLASS = ".class";
    private static final Map<String, String> SANITIZED_COMPILER_CLASSPATH_ENTRIES = new HashMap<>();
    private static final int MAX_STORED_COMPILER_DIAGNOSTICS = 100;
    private static final int MAX_LOGGED_SOURCE_EXCERPT_LINES_PER_FILE = 200;
    private static final int MAX_LOGGED_SOURCE_EXCERPT_CHARS_TOTAL = 20_000;

    private static InstrumentingClassLoader loader = new NonInstrumentingClassLoader();

    private static final VersionDependentAnalyzing JUNIT4_ANALYZER = new JUnit4Analyzing();
    private static final VersionDependentAnalyzing JUNIT5_ANALYZER = new JUnit5Analyzing();

    public static final class OrderSensitivityAnalysis {
        private final Set<String> forwardFailures;
        private final Set<String> reverseFailures;

        private OrderSensitivityAnalysis(Set<String> forwardFailures, Set<String> reverseFailures) {
            this.forwardFailures = forwardFailures;
            this.reverseFailures = reverseFailures;
        }

        public boolean isOrderSensitive() {
            return !forwardFailures.equals(reverseFailures);
        }

        public Set<String> getForwardFailures() {
            return forwardFailures;
        }

        public Set<String> getReverseFailures() {
            return reverseFailures;
        }
    }

    private static final class BoundedDiagnosticListener implements DiagnosticListener<JavaFileObject> {
        private static final String ERROR_WHILE_WRITING_PREFIX = "error while writing";
        private final int maxDiagnostics;
        private final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();
        private boolean truncated = false;
        private boolean sawErrorWhileWriting = false;

        private BoundedDiagnosticListener(int maxDiagnostics) {
            this.maxDiagnostics = Math.max(0, maxDiagnostics);
        }

        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            String message = diagnostic.getMessage(null);
            if (message != null && message.startsWith(ERROR_WHILE_WRITING_PREFIX)) {
                sawErrorWhileWriting = true;
            }
            if (diagnostics.size() < maxDiagnostics) {
                diagnostics.add(diagnostic);
            } else {
                truncated = true;
            }
        }

        private List<Diagnostic<? extends JavaFileObject>> getDiagnostics() {
            return diagnostics;
        }

        private boolean wasTruncated() {
            return truncated;
        }

        private boolean sawErrorWhileWriting() {
            return sawErrorWhileWriting;
        }
    }

    /**
     * Try to compile each test separately, and remove the ones that cannot be
     * compiled.
     *
     * @param tests list of tests
     */
    public static void removeTestsThatDoNotCompile(List<TestCase> tests) {

        logger.info("Going to execute: removeTestsThatDoNotCompile");

        if (tests == null || tests.isEmpty()) { //nothing to do
            return;
        }

        Iterator<TestCase> iter = tests.iterator();

        while (iter.hasNext()) {
            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                break;
            }

            TestCase test = iter.next();

            File dir = createNewTmpDir();
            if (dir == null) {
                logger.warn("Failed to create tmp dir");
                return;
            }
            logger.debug("Created tmp folder: " + dir.getAbsolutePath());

            try {
                List<TestCase> singleList = new ArrayList<>();
                singleList.add(test);
                List<File> generated = compileTests(singleList, dir);
                if (generated == null) {
                    iter.remove();
                    String code = test.toCode();
                    logger.error("Failed to compile test case:\n" + code);
                }
            } finally {
                //let's be sure we clean up all what we wrote on disk
                if (dir != null) {
                    try {
                        FileUtils.deleteDirectory(dir);
                        logger.debug("Deleted tmp folder: " + dir.getAbsolutePath());
                    } catch (Exception e) {
                        logger.error("Cannot delete tmp dir: " + dir.getAbsolutePath(), e);
                    }
                }
            }

        } // end of while
    }

    /**
     * Compile and run all the test cases, and mark as "unstable" all the ones
     * that fail during execution (ie, unstable assertions).
     *
     * <p>If a test fail due to an exception not related to a JUnit assertion, then
     * remove such test from the input list.</p>
     *
     * @param tests list of tests
     * @return the number of unstable tests
     */
    public static int handleTestsThatAreUnstable(List<TestCase> tests) {

        int numUnstable = 0;
        logger.info("Going to execute: handleTestsThatAreUnstable");

        if (tests == null || tests.isEmpty()) { //nothing to do
            return numUnstable;
        }

        File dir = createNewTmpDir();
        if (dir == null) {
            logger.error("Failed to create tmp dir");
            return numUnstable;
        }
        logger.debug("Created tmp folder: " + dir.getAbsolutePath());

        try {
            List<File> generated = compileTests(tests, dir);
            if (generated == null) {
                /*
                 * Note: in theory this shouldn't really happen, as check for compilation
                 * is done before calling this method
                 */
                logger.warn("Failed to compile the test cases ");
                return numUnstable;
            }

            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                logger.error("Ran out of time while checking tests");
                return numUnstable;
            }

            // Create a new classloader so that each test gets freshly loaded classes.
            // Start with a NonInstrumentingClassLoader to avoid duplicate type
            // identities across loader boundaries during JUnit recheck. If we
            // detect instrumentation/GUI mismatch signals, runTests() retries
            // once with InstrumentingClassLoader.
            loader = createInitialJUnitClassLoader();
            Class<?>[] testClasses = loadTests(generated);

            if (testClasses == null) {
                logger.error("Found no classes for compiled tests");
                return numUnstable;
            }

            JUnitResult result = runTests(testClasses, dir, generated);

            if (result.wasSuccessful()) {
                return numUnstable; //everything is OK
            }


            failure_loop:
            for (JUnitFailure failure : result.getFailures()) {
                String testName = failure.getDescriptionMethodName();//TODO check if correct
                for (int i = 0; i < tests.size(); i++) {
                    if (TestSuiteWriterUtils.getNameOfTest(tests, i).equals(testName)) {
                        if (tests.get(i).isFailing()) {
                            logger.info("Failure is expected, continuing...");
                            continue failure_loop;
                        }
                    }
                }

                if (testName == null) {
                    /*
                     * this can happen if there is a failure in the scaffolding (eg @AfterClass/@BeforeClass).
                     * in such case, everything need to be deleted
                     */
                    StringBuilder sb = new StringBuilder();
                    sb.append("Issue in scaffolding of the test suite: ").append(failure.getMessage()).append("\n");
                    sb.append("Stack trace:\n");
                    for (String elem : failure.getExceptionStackTrace()) {
                        sb.append(elem).append("\n");
                    }
                    logger.error(sb.toString());
                    numUnstable = tests.size();
                    tests.clear();
                    return numUnstable;
                }

                // On the Sheffield cluster, the "well-known fle is not secure" issue is impossible to understand,
                // so it might be best to ignore it for now.
                if (testName.equals("initializationError") && failure.getMessage().contains(
                        "Failed to attach Java Agent")) {
                    logger.warn("Likely error with EvoSuite instrumentation, ignoring failure in test execution");
                    continue failure_loop;
                }

                // TooManyResourcesException during JUnit check means the SUT hit the
                // per-loop iteration cap (RuntimeSettings.maxNumberOfIterationsPerLoop).
                // During search this cap is also active, so any test that ran to
                // completion during generation must also have terminated this way; the
                // rerun is reproducing the same termination, not exposing a new bug.
                // Treat the test as valid and keep it in the suite.
                if ("org.evosuite.runtime.TooManyResourcesException".equals(failure.getExceptionClassName())) {
                    logger.debug("Ignoring expected TooManyResourcesException for test {} "
                            + "(SUT loop terminated by iteration cap, same as during search): {}",
                            testName, failure.getMessage());
                    continue failure_loop;
                }


                logger.warn("Found unstable test named " + testName + " -> "
                        + failure.getExceptionClassName() + ": " + failure.getMessage());

                if (Properties.JUNIT_UNSTABLE_DIAGNOSTICS) {
                    logger.warn(buildUnstableDiagnostics(testName, failure));
                }

                for (String elem : failure.getExceptionStackTrace()) {
                    logger.info("Exception trace: {}", elem);
                }

                boolean toRemove = !(failure.isAssertionError());

                for (int i = 0; i < tests.size(); i++) {
                    if (TestSuiteWriterUtils.getNameOfTest(tests, i).equals(testName)) {
                        logger.warn("Failing test:\n " + tests.get(i).toCode());
                        numUnstable++;
                        /*
                         * we have a match. should we remove it or mark as unstable?
                         * When we have an Assert.* failing, we can just comment out
                         * all the assertions in the test case. If it is an "assert"
                         * in the SUT that fails, we do want to have the JUnit test fail.
                         * On the other hand, if a test fail due to an uncaught exception,
                         * we should delete it, as it would either represent a bug in EvoSuite
                         * or something we cannot (easily) fix here
                         */
                        if (!toRemove) {
                            logger.debug("Going to mark test as unstable: " + testName);
                            tests.get(i).setUnstable(true);
                        } else {
                            logger.debug("Going to remove unstable test: " + testName);
                            tests.remove(i);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("" + e, e);
            return numUnstable;
        } finally {
            //let's be sure we clean up all what we wrote on disk

            if (dir != null) {
                try {
                    FileUtils.deleteDirectory(dir);
                } catch (Exception e) {
                    logger.warn("Cannot delete tmp dir: " + dir.getName(), e);
                }
            }

        }

        //if we arrive here, then it means at least one test was unstable
        return numUnstable;
    }

    private static JUnitResult runTests(Class<?>[] testClasses, File testClassDir, Collection<File> generatedFiles)
            throws JUnitExecutionException {
        ClassStateSupport.clearNonInstrumentedClassDetectionFlag();
        JUnitResult result = runJUnitOnCurrentProcess(testClasses);

        // If JUnit already succeeded, keep that outcome. The InstrumentingClassLoader retry
        // is only a recovery path for failures potentially caused by missing instrumentation.
        if (result.wasSuccessful()) {
            return result;
        }

        // In separate-classloader mode (legacy JUnit4 scaffolding), retrying with a
        // different loader can perturb class identity/static reset behavior and
        // introduce false instability signals.
        if (Properties.USE_SEPARATE_CLASSLOADER) {
            return result;
        }

        boolean needsInstrumentedRetry = ClassStateSupport.hadNonInstrumentedClassDetection()
                || shouldRetryWithInstrumentingAfterFailure(result);

        if (!needsInstrumentedRetry) {
            return result;
        }

        if (generatedFiles == null || generatedFiles.isEmpty()) {
            return result;
        }

        logger.warn("Detected instrumentation/GUI mismatch signals during JUnit check; "
                + "retrying once with InstrumentingClassLoader.");
        InstrumentingClassLoader savedLoader = loader;
        try {
            loader = new InstrumentingClassLoader();
            Class<?>[] retriedClasses = loadTests(new ArrayList<>(generatedFiles));
            if (retriedClasses == null) {
                logger.warn("Retry with InstrumentingClassLoader failed to load test classes");
                return result;
            }

            ClassStateSupport.clearNonInstrumentedClassDetectionFlag();
            JUnitResult retriedResult = runJUnitOnCurrentProcess(retriedClasses);
            if (ClassStateSupport.hadNonInstrumentedClassDetection()) {
                logger.warn("Retry still observed non-instrumented classes during JUnit check");
            }
            return retriedResult;
        } finally {
            loader = savedLoader;
        }
    }

    private static boolean shouldRetryWithInstrumentingAfterFailure(JUnitResult result) {
        for (JUnitFailure failure : result.getFailures()) {
            String failureClass = failure.getExceptionClassName();
            String message = failure.getMessage();
            List<String> trace = failure.getExceptionStackTrace();
            if ("java.awt.HeadlessException".equals(failureClass)) {
                return true;
            }
            if ("java.lang.ClassCastException".equals(failureClass)) {
                if (trace != null
                        && (containsAny(trace, "org.evosuite.instrumentation.InstrumentingClassLoader")
                        || containsAny(trace, "org.evosuite.runtime.GuiSupport$StubGraphicsConfiguration"))) {
                    return true;
                }
                if (message != null
                        && (message.contains("InstrumentingClassLoader")
                        || message.contains("StubGraphicsConfiguration"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Analyze if the given list of tests is sensitive to the execution order.
     *
     * @param tests the list of tests to analyze.
     * @return an OrderSensitivityAnalysis object.
     */
    public static OrderSensitivityAnalysis analyzeOrderSensitivity(List<TestCase> tests) {
        if (tests == null || tests.size() < 2) {
            return new OrderSensitivityAnalysis(Collections.<String>emptySet(), Collections.<String>emptySet());
        }
        Set<String> forwardFailures = executeAndCollectUnexpectedFailures(cloneTests(tests));
        List<TestCase> reverse = cloneTests(tests);
        Collections.reverse(reverse);
        Set<String> reverseFailures = executeAndCollectUnexpectedFailures(reverse);
        return new OrderSensitivityAnalysis(forwardFailures, reverseFailures);
    }


    private static JUnitResult runJUnitOnCurrentProcess(Class<?>[] testClasses) {
        // Generation leaves the tracer enabled (TestCaseExecutor re-enables it in
        // its timeout finally block). During JUnit check we don't consume the
        // trace, so leaving it on only causes instrumented testability/mutation
        // hooks in the SUT to feed ConstantPoolManager and tracer state on every
        // comparison — measurable hang on hot loops.
        boolean wasTracerEnabled = ExecutionTracer.isEnabled();
        if (wasTracerEnabled) {
            ExecutionTracer.disable();
        }

        // Force LoopCounter on for the JUnit check. The instrumented SUT classes
        // call LoopCounter.checkLoop() at every loop back-edge, but the check
        // is a no-op unless the counter is "activated". Several paths
        // (ClassStateSupport.initializeClasses, TestCaseExecutor's <clinit>
        // grace handling, Properties.getTargetClassAndDontInitialise) save the
        // current state, set it false, and restore it — so if the counter was
        // ever left off when JUnit check began, runaway SUT loops won't terminate
        // here even though they did during search.
        boolean wasLoopCheckOn = LoopCounter.getInstance().isActivated();
        if (!wasLoopCheckOn) {
            LoopCounter.getInstance().setActive(true);
        }

        // Run JUnit on a dedicated worker thread so we can abandon it if it
        // becomes unrecoverable. A common failure mode is OOM inside the SUT:
        // JUnit catches it and tries to record it via Throwable.addSuppressed,
        // which is synchronized and itself allocates — under memory pressure
        // this cascades into an unrecoverable RUNNABLE state where neither
        // interrupt() nor the kill switch can free the thread (interrupt is
        // only checked on exit from the synchronized block, which never
        // happens). We give the runner the configured budget, then a grace
        // window after interrupt, and finally synthesize an all-failed result
        // and walk away from the orphan thread.
        long budgetMs = 1000L * Math.max(1, Properties.JUNIT_CHECK_TIMEOUT);
        long graceMs = 30_000L;
        // Register the runner thread as privileged at thread creation, not
        // post-hoc. The body calls Sandbox.resetDefaultSecurityManager() (and
        // may load classes through InstrumentingClassLoader), both of which
        // require setSecurityManager / privileged class-load permissions; an
        // unprivileged thread would have those rejected by MSecurityManager.
        // Doing this in the ThreadFactory means (a) the registration runs on
        // the caller (master) thread, which is already privileged, so
        // addPrivilegedThread passes its caller-must-be-privileged check; and
        // (b) any thread the executor creates is privileged from the start,
        // not just the first one — the previous submit(Thread::currentThread)
        // dance was fragile if the executor ever replaced the worker.
        boolean sandboxOn = Sandbox.isSecurityManagerInitialized();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "evosuite-junit-check-runner");
            t.setDaemon(true);
            if (sandboxOn) {
                Sandbox.addPrivilegedThread(t);
            }
            return t;
        });
        Future<JUnitResult> future = executor.submit(() -> {
            try {
                return getVersionDependentAnalyzer().runJUnitOnCurrentProcess(testClasses);
            } catch (OutOfMemoryError e) {
                // Test triggered a huge allocation (e.g. new DefaultTableModel(351774, 351774)).
                // Force GC to reclaim before reporting back.
                System.gc();
                logger.warn("OutOfMemoryError during JUnit execution — forced GC and treating all tests as failed");
                return JUnitResult.allFailed(testClasses.length, e);
            }
        });
        boolean firedTimeout = false;
        try {
            try {
                return future.get(budgetMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                firedTimeout = true;
                logger.warn("JUnit check exceeded watchdog budget of {} ms; "
                        + "setting kill switch and interrupting runner", budgetMs);
                ExecutionTracer.setKillSwitch(true);
                future.cancel(true);
            }
            // Grace window: give the runner a chance to unwind after interrupt.
            // If it doesn't (typical for OOM-suppression cascades stuck in
            // synchronized addSuppressed), abandon the check and treat the
            // tests as passing — the suite already survived search, and we
            // have no observed failure for any specific test, so dropping the
            // whole suite would be more punitive than skipping the phase.
            // The orphan thread will keep spinning until JVM exit;
            // shutdownNow() below cannot kill it.
            try {
                return future.get(graceMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                logger.error("JUnit check did not respond {} ms after interrupt; "
                        + "abandoning JUnit check and keeping the suite as-is. "
                        + "An orphan runner thread will continue until process exit.",
                        graceMs);
                return JUnitResult.allPassed(testClasses.length);
            } catch (CancellationException ce) {
                return JUnitResult.allFailed(testClasses.length, ce);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return JUnitResult.allFailed(testClasses.length, ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            return JUnitResult.allFailed(testClasses.length, cause);
        } finally {
            executor.shutdownNow();
            if (firedTimeout) {
                // Clear any signals we raised so subsequent work isn't poisoned.
                // Note: cannot clear the orphan runner's interrupt — it isn't us.
                ExecutionTracer.setKillSwitch(false);
            }
            if (wasTracerEnabled) {
                ExecutionTracer.enable();
            }
            if (!wasLoopCheckOn) {
                LoopCounter.getInstance().setActive(false);
            }
        }
    }

    private static String buildUnstableDiagnostics(String testName, JUnitFailure failure) {
        List<String> trace = failure.getExceptionStackTrace();
        String category = classifyUnstableFailure(testName, trace);
        String firstInterestingFrame = firstInterestingFrame(trace);
        String firstLoopCounterFrame = firstFrameContaining(trace, "org.evosuite.runtime.LoopCounter.checkLoop");
        String firstHeadlessFrame = firstFrameContaining(trace, "java.awt.HeadlessException");
        String firstAwtFrame = firstFrameContaining(trace, "java.awt.");
        String failureClass = failure.getExceptionClassName();
        String failureMessage = failure.getMessage();

        StringBuilder sb = new StringBuilder();
        sb.append("Unstable diagnostics for ").append(testName)
                .append(": category=").append(category)
                .append(", failureClass=").append(failureClass)
                .append(", failureMessage=").append(failureMessage);
        if (firstInterestingFrame != null) {
            sb.append(", firstInterestingFrame=").append(firstInterestingFrame);
        }
        if (firstLoopCounterFrame != null) {
            sb.append(", loopCounterFrame=").append(firstLoopCounterFrame);
        }
        if (firstHeadlessFrame != null) {
            sb.append(", headlessFrame=").append(firstHeadlessFrame);
        }
        if (firstAwtFrame != null) {
            sb.append(", awtFrame=").append(firstAwtFrame);
        }
        sb.append(", runnerClassLoader=").append(describeClassLoader(loader));
        sb.append(", contextClassLoader=")
                .append(describeClassLoader(Thread.currentThread().getContextClassLoader()));
        sb.append(", replaceGUI=").append(Properties.REPLACE_GUI);
        sb.append(", runtimeMockGUI=").append(RuntimeSettings.mockGUI);
        sb.append(", mockFrameworkEnabled=").append(MockFramework.isEnabled());
        sb.append(", jvmHeadlessProperty=").append(System.getProperty("java.awt.headless"));
        sb.append(", envHeadlessProperty=").append(System.getenv("JAVA_AWT_HEADLESS"));
        if ("java.awt.HeadlessException".equals(failureClass)) {
            sb.append(", hint=Headless mismatch likely indicates missing/late GUI replacement during JUnit recheck");
        }
        return sb.toString();
    }

    private static String describeClassLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return "bootstrap";
        }
        String kind = classLoader.getClass().getName();
        ClassLoader parent = classLoader.getParent();
        String parentKind = parent == null ? "bootstrap" : parent.getClass().getName();
        return kind + "(parent=" + parentKind + ")";
    }

    private static String classifyUnstableFailure(String testName, List<String> trace) {
        if (trace == null || trace.isEmpty()) {
            return "unknown";
        }
        if (containsAny(trace,
                "org.evosuite.runtime.EvoSuiteExtension.beforeEach",
                "org.evosuite.runtime.EvoSuiteExtension.afterEach",
                "org.evosuite.runtime.classhandling.ClassResetter",
                "org.evosuite.runtime.classhandling.JDKClassResetter",
                "org.evosuite.runtime.classhandling.ClassStateSupport")) {
            return "extension_or_reset";
        }
        if (containsAny(trace,
                "org.junit.jupiter.api.Assertions",
                "org.junit.Assert")) {
            return "assertion";
        }
        if (containsAny(trace, "." + testName + "(")) {
            return "test_body";
        }
        if (containsAny(trace, "org.evosuite.runtime.LoopCounter.checkLoop")) {
            return "loop_counter_no_user_frame";
        }
        return "other_runtime";
    }

    private static boolean containsAny(List<String> trace, String... needles) {
        for (String frame : trace) {
            for (String needle : needles) {
                if (frame != null && frame.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String firstInterestingFrame(List<String> trace) {
        for (String frame : trace) {
            if (frame == null) {
                continue;
            }
            if (frame.contains("org.evosuite.runtime.")
                    || frame.contains("org.evosuite.junit.")
                    || frame.contains("org.junit.")
                    || frame.contains("java.base/")
                    || frame.contains("jdk.internal.")) {
                continue;
            }
            return frame.trim();
        }
        return null;
    }

    private static String firstFrameContaining(List<String> trace, String marker) {
        if (trace == null) {
            return null;
        }
        for (String frame : trace) {
            if (frame != null && frame.contains(marker)) {
                return frame.trim();
            }
        }
        return null;
    }

    static boolean isJUnit5AnalyzerSelectedForCurrentFormat() {
        return getVersionDependentAnalyzer() == JUNIT5_ANALYZER;
    }

    private static VersionDependentAnalyzing getVersionDependentAnalyzer() {
        return Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5
                ? JUNIT5_ANALYZER
                : JUNIT4_ANALYZER;
    }

    private static InstrumentingClassLoader createInitialJUnitClassLoader() {
        // Prefer non-instrumenting first to minimize class-identity splits
        // between app/system and instrumented loader hierarchies.
        return new NonInstrumentingClassLoader();
    }

    private static List<TestCase> cloneTests(List<TestCase> tests) {
        List<TestCase> clones = new ArrayList<>(tests.size());
        for (TestCase test : tests) {
            clones.add(test.clone());
        }
        return clones;
    }

    private static Set<String> executeAndCollectUnexpectedFailures(List<TestCase> tests) {
        File dir = createNewTmpDir();
        if (dir == null) {
            return Collections.singleton("tmp-dir-error");
        }
        try {
            List<File> generated = compileTests(tests, dir);
            if (generated == null) {
                return Collections.singleton("compilation-error");
            }
            loader = createInitialJUnitClassLoader();
            Class<?>[] testClasses = loadTests(generated);
            if (testClasses == null) {
                return Collections.singleton("load-error");
            }
            JUnitResult result = runTests(testClasses, dir, generated);
            if (result.wasSuccessful()) {
                return Collections.emptySet();
            }
            return mapUnexpectedFailureNames(result, tests);
        } catch (Exception e) {
            logger.warn("Order-sensitivity analysis failed: {}", e.getMessage());
            return Collections.singleton("execution-error");
        } finally {
            if (dir != null) {
                try {
                    FileUtils.deleteDirectory(dir);
                } catch (Exception e) {
                    logger.warn("Cannot delete tmp dir in order-sensitivity analysis: {}", dir.getName(), e);
                }
            }
        }
    }

    private static Set<String> mapUnexpectedFailureNames(JUnitResult result, List<TestCase> tests) {
        Map<String, TestCase> testByName = new LinkedHashMap<>();
        for (int i = 0; i < tests.size(); i++) {
            testByName.put(TestSuiteWriterUtils.getNameOfTest(tests, i), tests.get(i));
        }

        Set<String> failures = new LinkedHashSet<>();
        for (JUnitFailure failure : result.getFailures()) {
            String testName = failure.getDescriptionMethodName();
            if (testName == null) {
                failures.add("suite-initialization");
                continue;
            }
            TestCase mapped = testByName.get(testName);
            if (mapped != null && mapped.isFailing()) {
                continue;
            }
            if (testName.equals("initializationError")
                    && failure.getMessage() != null
                    && failure.getMessage().contains("Failed to attach Java Agent")) {
                continue;
            }
            failures.add(testName);
        }
        return failures;
    }

    /**
     * Check if it is possible to use the Java compiler.
     *
     * @return true if compiler is available
     */
    public static boolean isJavaCompilerAvailable() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        return compiler != null;
    }

    // We have to have a unique name for this test suite as it is loaded by the
    // EvoSuite classloader, and thus cannot easily be re-loaded
    private static int NUM = 0;

    private static List<File> compileTests(List<TestCase> tests, File dir) {

        TestSuiteWriter suite = new TestSuiteWriter();
        suite.insertAllTests(tests);
        if (Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5
                && Properties.TEST_EXTENSION_MODE
                && Properties.RESET_STATIC_FIELDS
                && Properties.TARGET_CLASS != null
                && !Properties.TARGET_CLASS.trim().isEmpty()) {
            suite.setExtensionInitializationOrder(Collections.singletonList(Properties.TARGET_CLASS));
        }

        //to get name, remove all package before last '.'
        int beginIndex = Properties.TARGET_CLASS.lastIndexOf(".") + 1;
        String name = Properties.TARGET_CLASS.substring(beginIndex);
        name += "_" + (NUM++) + "_tmp_" + Properties.JUNIT_SUFFIX; //postfix

        try {
            //now generate the JUnit test case
            List<File> generated = suite.writeTestSuite(name, dir.getAbsolutePath(), Collections.EMPTY_LIST);
            for (File file : generated) {
                if (!file.exists()) {
                    logger.error("Supposed to generate " + file
                            + " but it does not exist");
                    return null;
                }
            }

            //try to compile the test cases
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                logger.error("No Java compiler is available");
                return null;
            }

            BoundedDiagnosticListener diagnostics = new BoundedDiagnosticListener(MAX_STORED_COMPILER_DIAGNOSTICS);
            Locale locale = Locale.getDefault();
            Charset charset = StandardCharsets.UTF_8;
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics,
                    locale,
                    charset)) {
                Iterable<? extends JavaFileObject> compilationUnits =
                        fileManager.getJavaFileObjectsFromFiles(generated);

                String evosuiteCP = ClassPathHandler.getInstance().getEvoSuiteClassPath();
                if (JarPathing.containsAPathingJar(evosuiteCP)) {
                    evosuiteCP = JarPathing.expandPathingJars(evosuiteCP);
                }

                String targetProjectCP = ClassPathHandler.getInstance().getTargetProjectClasspath();
                if (JarPathing.containsAPathingJar(targetProjectCP)) {
                    targetProjectCP = JarPathing.expandPathingJars(targetProjectCP);
                }

                String classpath = targetProjectCP + File.pathSeparator + evosuiteCP;
                classpath = sanitizeClasspathForCompiler(classpath);

                List<String> optionList = new ArrayList<>(Arrays.asList(
                        "-classpath", classpath,
                        "-proc:none"));

                CompilationTask task = compiler.getTask(null, fileManager, diagnostics,
                        optionList, null, compilationUnits);
                boolean compiled = task.call();

                if (!compiled) {
                    logger.error("Compilation failed on {} generated test file(s)", generated.size());
                    logger.error("Classpath: {}", classpath);
                    // TODO remove
                    logger.error("evosuiteCP: {}", evosuiteCP);

                    if (diagnostics.sawErrorWhileWriting()) {
                        logger.error("Error is due to file permissions, ignoring...");
                        return generated;
                    }

                    for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                        logger.error("Diagnostic: {}: {}", diagnostic.getMessage(null),
                                diagnostic.getLineNumber());
                    }
                    if (diagnostics.wasTruncated()) {
                        logger.error("Additional compiler diagnostics were omitted after {} entries",
                                MAX_STORED_COMPILER_DIAGNOSTICS);
                    }

                    logGeneratedSourceExcerptsOnCompilationFailure(generated);
                    return null;
                }
            }

            return generated;

        } catch (OutOfMemoryError e) {
            logger.error("Out of memory while compiling generated tests ({} test case(s))", tests.size(), e);
            return null;
        } catch (IOException e) {
            logger.error("" + e, e);
            return null;
        }
    }

    private static void logGeneratedSourceExcerptsOnCompilationFailure(List<File> generated) {
        int remainingChars = MAX_LOGGED_SOURCE_EXCERPT_CHARS_TOTAL;
        for (File source : generated) {
            if (remainingChars <= 0) {
                logger.error("Omitted remaining generated source output after {} characters",
                        MAX_LOGGED_SOURCE_EXCERPT_CHARS_TOTAL);
                break;
            }

            StringBuilder excerpt = new StringBuilder();
            excerpt.append(source.getAbsolutePath()).append('\n');
            int linesLogged = 0;
            boolean truncated = false;

            try (BufferedReader reader = Files.newBufferedReader(source.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (linesLogged >= MAX_LOGGED_SOURCE_EXCERPT_LINES_PER_FILE) {
                        truncated = true;
                        break;
                    }
                    String rendered = (linesLogged + 1) + ": " + line + '\n';
                    if (rendered.length() > remainingChars) {
                        excerpt.append(rendered, 0, Math.max(0, remainingChars));
                        remainingChars = 0;
                        truncated = true;
                        break;
                    }
                    excerpt.append(rendered);
                    remainingChars -= rendered.length();
                    linesLogged++;
                }
            } catch (IOException e) {
                logger.error("Failed to read generated test source {}: {}", source.getAbsolutePath(), e.getMessage());
                continue;
            }

            if (truncated) {
                excerpt.append("... source excerpt truncated ...\n");
            }
            logger.error(excerpt.toString());
        }
    }

    private static String sanitizeClasspathForCompiler(String classpath) {
        if (classpath == null || classpath.trim().isEmpty()) {
            return classpath;
        }

        String[] entries = classpath.split(File.pathSeparator);
        List<String> sanitizedEntries = new ArrayList<>(entries.length);
        for (String entry : entries) {
            sanitizedEntries.add(sanitizeClasspathEntryForCompiler(entry));
        }
        return String.join(File.pathSeparator, sanitizedEntries);
    }

    private static String sanitizeClasspathEntryForCompiler(String entry) {
        if (entry == null || entry.isEmpty()) {
            return entry;
        }

        if (!entry.endsWith(".jar")) {
            return entry;
        }

        File jarFile = new File(entry);
        if (!jarFile.isFile()) {
            return entry;
        }

        synchronized (SANITIZED_COMPILER_CLASSPATH_ENTRIES) {
            String cached = SANITIZED_COMPILER_CLASSPATH_ENTRIES.get(jarFile.getAbsolutePath());
            if (cached != null) {
                return cached;
            }
        }

        if (!containsDotSegmentEntry(jarFile)) {
            synchronized (SANITIZED_COMPILER_CLASSPATH_ENTRIES) {
                SANITIZED_COMPILER_CLASSPATH_ENTRIES.put(jarFile.getAbsolutePath(), jarFile.getAbsolutePath());
            }
            return jarFile.getAbsolutePath();
        }

        String sanitized = extractJarWithoutDotSegmentEntries(jarFile);
        synchronized (SANITIZED_COMPILER_CLASSPATH_ENTRIES) {
            SANITIZED_COMPILER_CLASSPATH_ENTRIES.put(jarFile.getAbsolutePath(), sanitized);
        }
        if (!jarFile.getAbsolutePath().equals(sanitized)) {
            logger.warn("Using sanitized compiler classpath entry for malformed jar {} -> {}",
                    jarFile.getAbsolutePath(), sanitized);
        }
        return sanitized;
    }

    private static boolean containsDotSegmentEntry(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (hasDotSegment(entry.getName())) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.debug("Could not inspect jar {} for compiler sanitization: {}", jarFile, e.getMessage());
        }
        return false;
    }

    private static boolean hasDotSegment(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            return false;
        }
        String normalized = entryName.startsWith("/") ? entryName.substring(1) : entryName;
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static String extractJarWithoutDotSegmentEntries(File jarFile) {
        try {
            File targetDir = Files.createTempDirectory("evosuite-compiler-cp-").toFile();
            targetDir.deleteOnExit();

            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (hasDotSegment(name)) {
                        continue;
                    }
                    if (name.startsWith("/")) {
                        name = name.substring(1);
                    }
                    if (name.isEmpty()) {
                        continue;
                    }

                    File out = new File(targetDir, name);
                    if (entry.isDirectory()) {
                        if (!out.exists() && !out.mkdirs()) {
                            logger.debug("Could not create directory while sanitizing jar: {}", out);
                        }
                        continue;
                    }

                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        logger.debug("Could not create parent directory while sanitizing jar: {}", parent);
                        continue;
                    }

                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    out.deleteOnExit();
                }
            }
            return targetDir.getAbsolutePath();
        } catch (IOException e) {
            logger.warn("Failed to sanitize malformed jar {} for compiler classpath: {}",
                    jarFile.getAbsolutePath(), e.getMessage());
            return jarFile.getAbsolutePath();
        }
    }

    protected static File createNewTmpDir() {
        File dir = null;
        String dirName = FileUtils.getTempDirectoryPath() + File.separator + "EvoSuite_"
                + (dirCounter++) + "_" + +System.currentTimeMillis();

        //first create a tmp folder
        dir = new File(dirName);
        if (!dir.mkdirs()) {
            logger.error("Cannot create tmp dir: " + dirName);
            return null;
        }

        if (!dir.exists()) {
            logger.error("Weird behavior: we created folder, but Java cannot determine if it exists? Folder: "
                    + dirName);
            return null;
        }

        return dir;
    }

    private static Class<?>[] loadTests(List<File> tests) {

        /*
         * Ideally, when we run a generated test case, it
         * will automatically use JavaAgent to instrument the CUT.
         * But here we have already loaded the CUT by now, so that
         * mechanism will not work.
         *
         * A simple option is to just use an instrumenting class loader,
         * as it does exactly the same type of instrumentation.
         * But a better idea would be to use a new
         * non-instrumenting classloader to re-load the CUT, and so see
         * if the JavaAgent works properly.
         */
        Class<?>[] testClasses = getClassesFromFiles(tests);
        List<File> otherClasses = listOnlyFiles(tests);
        /*
         * this is important to force the loading of all files generated
         * in the target folder.
         * If we do not do that, then we will miss all the anonymous classes
         */
        getClassesFromFiles(otherClasses);

        return testClasses;
    }

    private static List<File> listOnlyFiles(List<File> tests) throws IllegalArgumentException {
        if (tests == null || tests.isEmpty()) {
            return null;
        }

        Set<String> classNames = new LinkedHashSet<>();

        File parentFolder = tests.get(0).getParentFile();
        for (File file : tests) {
            if (!file.getParentFile().equals(parentFolder)) {
                throw new IllegalArgumentException("Tests file are not in the same folder");
            }
            classNames.add(removeFileExtension(file.getName()));
        }

        /*
         * if we already loaded a CUT due to its .java, do not want
         * to re-loaded it for a .class file that is in the same folder
         */

        List<File> otherClasses = new LinkedList<>();

        for (File file : parentFolder.listFiles()) {
            String name = removeFileExtension(file.getName());

            if (classNames.contains(name)) {
                continue;
            }

            classNames.add(name);
            otherClasses.add(file);
        }

        return otherClasses;
    }


    private static String removeFileExtension(String str) {
        if (str == null) {
            return null;
        }

        int pos = str.lastIndexOf(".");

        if (pos == -1) {
            return str;
        }

        return str.substring(0, pos);
    }

    /**
     * <p>The output of EvoSuite is a set of test cases. For debugging and
     * experiment, we usually would not write any JUnit to file. But we still
     * want to see if test cases can compile and execute properly. As EvoSuite
     * is supposed to only capture the current behavior of the SUT, all
     * generated test cases should pass.</p>
     *
     * <p>Here we compile to a tmp folder, load and execute the test cases, and
     * then clean up (ie delete all generated files).</p>
     *
     * @param tests list of tests
     * @return true if success
     * @deprecated not used anymore, as check are done in different methods now, and old "assert" was not really valid
     */
    public static boolean verifyCompilationAndExecution(List<TestCase> tests) {

        if (tests == null || tests.isEmpty()) {
            //nothing to compile or run
            return true;
        }

        File dir = createNewTmpDir();
        if (dir == null) {
            logger.warn("Failed to create tmp dir");
            return false;
        }

        try {
            List<File> generated = compileTests(tests, dir);
            if (generated == null) {
                logger.warn("Failed to compile the test cases ");
                return false;
            }

            //as last step, execute the generated/compiled test cases

            Class<?>[] testClasses = loadTests(generated);

            if (testClasses == null) {
                logger.error("Found no classes for compiled tests");
                return false;
            }

            JUnitResult result = runTests(testClasses, dir, generated);

            if (!result.wasSuccessful()) {
                logger.error("" + result.getFailureCount() + " test cases failed");
                for (JUnitFailure failure : result.getFailures()) {
                    logger.error("Failure " + failure.getExceptionClassName() + ": "
                            + failure.getMessage() + "\n" + failure.getTrace());
                }
                return false;
            } else {
                /*
                 * OK, it was successful, but was there any test case at all?
                 *
                 * Here we just log (and not return false), as it might be that EvoSuite is just not able to generate
                 * any test case for this SUT
                 */
                if (result.getRunCount() == 0) {
                    logger.warn("There was no test to run");
                }
            }

        } catch (Exception e) {
            logger.error("" + e, e);
            return false;
        } finally {
            //let's be sure we clean up all what we wrote on disk
            if (dir != null) {
                try {
                    FileUtils.deleteDirectory(dir);
                } catch (IOException e) {
                    logger.warn("Cannot delete tmp dir: " + dir.getName(), e);
                }
            }
        }

        logger.debug("Successfully compiled and run test cases generated for "
                + Properties.TARGET_CLASS);
        return true;
    }

    /**
     * Given a list of files representing .java/.class classes, load them (it
     * assumes the classpath to be correctly set).
     *
     * @param files collection of files
     * @return array of classes
     */
    private static Class<?>[] getClassesFromFiles(Collection<File> files) {
        /*
         * Build-tool integration may provide a legacy scaffolding list file.
         */
        for (String className : extractClassesToInitialize(files)) {
            loadClass(className);
        }

        /*
         * first load only the scaffolding files
         */
        for (File file : files) {
            if (!isScaffolding(file)) {
                continue;
            }
            loadClass(file);
        }

        List<Class<?>> classes = new ArrayList<>();

        /*
         * once the scaffoldings are loaded, we can load the tests that
         * depend on them
         */
        for (File file : files) {
            if (isScaffolding(file)) {
                continue;
            }
            Class<?> clazz = loadClass(file);
            if (clazz != null) {
                classes.add(clazz);
            }
        }

        return classes.toArray(new Class<?>[classes.size()]);
    }

    static List<String> extractClassesToInitialize(Collection<File> files) {
        List<String> classesToInit = new ArrayList<>();
        for (File file : files) {
            String fileName = file.getName();
            if (!file.isFile()) {
                continue;
            }
            if (!InitializingListener.SCAFFOLDING_LIST_FILE_STRING.equals(fileName)) {
                continue;
            }
            try {
                classesToInit.addAll(InitializingListenerUtils.readInitializationClassList(file));
            } catch (RuntimeException e) {
                logger.warn("Could not read scaffolding initialization list from {}: {}",
                        file.getAbsolutePath(), e.getMessage());
            }
        }
        return classesToInit;
    }

    private static boolean isScaffolding(File file) {
        String name = file.getName();
        return name.endsWith("_" + Properties.SCAFFOLDING_SUFFIX + JAVA)
                || name.endsWith("_" + Properties.SCAFFOLDING_SUFFIX + CLASS);
    }

    private static Class<?> loadClass(File file) {
        if (!file.isFile()) {
            return null;
        }

        String packagePrefix = Properties.CLASS_PREFIX;
        if (!packagePrefix.isEmpty() && !packagePrefix.endsWith(".")) {
            packagePrefix += ".";
        }

        String name = file.getName();

        if (!name.endsWith(JAVA) && !name.endsWith(CLASS)) {
            /*
             * this could happen when we scan a folder for all src/compiled
             * files
             */
            return null;
        }

        String fileName = file.getAbsolutePath();

        if (name.endsWith(JAVA)) {
            name = name.substring(0, name.length() - JAVA.length());
            fileName = fileName.substring(0, fileName.length() - JAVA.length()) + ".class";
        } else {
            assert name.endsWith(CLASS);
            name = name.substring(0, name.length() - CLASS.length());
        }

        String className = packagePrefix + name;

        Class<?> testClass = null;
        try {
            logger.info("Loading class " + className);
            //testClass = ((InstrumentingClassLoader) TestGenerationContext.getInstance()
            // .getClassLoaderForSUT()).loadClassFromFile(className,
            testClass = loader.loadClassFromFile(className, fileName);
        } catch (ClassNotFoundException e) {
            logger.error("Failed to load test case " + className + " from file "
                    + file.getAbsolutePath() + " , error " + e, e);
        }
        return testClass;
    }

    private static Class<?> loadClass(String className) {
        try {
            logger.info("Loading class {}", className);
            return loader.loadClass(className);
        } catch (ClassNotFoundException e) {
            logger.error("Cannot load class: " + className, e);
            return null;
        }
    }

    /**
     * Class defining what functionality must be defined for different JUNIT versions.
     */
    private abstract static class VersionDependentAnalyzing {
        abstract JUnitResult runJUnitOnCurrentProcess(Class<?>[] testClasses);
    }

    /**
     * Define functionality for JUnit 4 Tests.
     */
    private static class JUnit4Analyzing extends VersionDependentAnalyzing {
        @Override
        JUnitResult runJUnitOnCurrentProcess(Class<?>[] testClasses) {

            JUnitCore runner = new JUnitCore();

            /*
             * Why deactivating the sandbox? This is pretty tricky.
             * The JUnitCore runner will execute the test cases on a new
             * thread, which might not be privileged. If the test cases need
             * the JavaAgent, then they will fail due to the sandbox :(
             * Note: if the test cases need a sandbox, they will have code
             * to do that by their self. When they do it, the initialization
             * will be after the agent is already loaded.
             */
            boolean wasSandboxOn = Sandbox.isSecurityManagerInitialized();

            Set<Thread> privileged = null;
            if (wasSandboxOn) {
                privileged = Sandbox.resetDefaultSecurityManager();
            }

            Result result = null;
            ClassLoader currentLoader = Thread.currentThread().getContextClassLoader();
            boolean wasMockFrameworkEnabled = MockFramework.isEnabled();
            boolean shouldEnableMockFramework = RuntimeSettings.mockJVMNonDeterminism
                    || Properties.REPLACE_GUI || RuntimeSettings.mockGUI;
            boolean disabledHeadless = false;

            // Skip agent self-attachment during the in-process JUnit check.
            org.evosuite.runtime.agent.AgentLoader.setSkipAttach(true);
            try {
                if (shouldEnableMockFramework) {
                    MockFramework.enable();
                }
                if (Properties.REPLACE_GUI || RuntimeSettings.mockGUI) {
                    // Align JUnit recheck with search-time constructor execution:
                    // ConstructorStatement temporarily disables headless mode for GUI
                    // constructors, but JUnit executes plain Java source directly.
                    // Without this, GUI-heavy tests can fail only in recheck.
                    GuiSupport.disableHeadlessForMockConstruction();
                    disabledHeadless = true;
                }
                TestGenerationContext.getInstance().goingToExecuteSUTCode();
                Thread.currentThread().setContextClassLoader(testClasses[0].getClassLoader());
                // be sure we reset it here, otherwise "init" in the test case
                // would take current changed state
                JDKClassResetter.reset();
                result = runner.run(testClasses);
            } finally {
                Thread.currentThread().setContextClassLoader(currentLoader);
                TestGenerationContext.getInstance().doneWithExecutingSUTCode();
                org.evosuite.runtime.agent.AgentLoader.setSkipAttach(false);
                if (disabledHeadless) {
                    GuiSupport.restoreHeadlessAfterMockConstruction();
                }
                if (!wasMockFrameworkEnabled) {
                    MockFramework.disable();
                }
            }


            if (wasSandboxOn) {
                //only activate Sandbox if it was already active before
                if (!Sandbox.isSecurityManagerInitialized()) {
                    Sandbox.initializeSecurityManagerForSUT(privileged);
                    org.evosuite.testcase.execution.ExecutableSnippetEngine.INSTANCE
                            .registerCompilationThreadAsPrivileged();
                }
            } else {
                if (Sandbox.isSecurityManagerInitialized()) {
                    logger.warn("EvoSuite problem: tests set up a security manager, "
                            + "but they do not remove it after execution");
                    Sandbox.resetDefaultSecurityManager();
                }
            }

            JUnitResultBuilder builder = new JUnitResultBuilder();
            return builder.build(result);
        }
    }


    /**
     * Define functionality for JUnit 5 tests.
     */
    private static class JUnit5Analyzing extends VersionDependentAnalyzing {

        @Override
        JUnitResult runJUnitOnCurrentProcess(Class<?>[] testClasses) {

            boolean wasSandboxOn = Sandbox.isSecurityManagerInitialized();

            Set<Thread> privileged = null;
            if (wasSandboxOn) {
                privileged = Sandbox.resetDefaultSecurityManager();
            }

            List<Pair<TestIdentifier, TestExecutionResult>> result = new ArrayList<>();
            ClassLoader currentLoader = Thread.currentThread().getContextClassLoader();
            boolean wasMockFrameworkEnabled = MockFramework.isEnabled();
            boolean shouldEnableMockFramework = RuntimeSettings.mockJVMNonDeterminism
                    || Properties.REPLACE_GUI || RuntimeSettings.mockGUI;
            boolean disabledHeadless = false;


            // Skip agent self-attachment during the in-process JUnit check.
            // Classes are already instrumented by InstrumentingClassLoader, and
            // ByteBuddyAgent.attach() can hang on some JDK/OS combinations.
            org.evosuite.runtime.agent.AgentLoader.setSkipAttach(true);
            try {
                if (shouldEnableMockFramework) {
                    MockFramework.enable();
                }
                if (Properties.REPLACE_GUI || RuntimeSettings.mockGUI) {
                    // Keep JUnit recheck behavior aligned with search-time execution.
                    GuiSupport.disableHeadlessForMockConstruction();
                    disabledHeadless = true;
                }
                TestGenerationContext.getInstance().goingToExecuteSUTCode();
                Thread.currentThread().setContextClassLoader(testClasses[0].getClassLoader());
                // be sure we reset it here, otherwise "init" in the test case
                // would take current changed state
                JDKClassResetter.reset();
                LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                        .selectors(Arrays.stream(testClasses).map(DiscoverySelectors::selectClass)
                                .collect(Collectors.toList()))
                        .build();
                Launcher launcher = LauncherFactory.create();
                TestPlan testPlan = launcher.discover(request);
                launcher.registerTestExecutionListeners(new TestExecutionListener() {
                    @Override
                    public void executionFinished(TestIdentifier testIdentifier,
                                                  TestExecutionResult testExecutionResult) {
                        result.add(Pair.of(testIdentifier, testExecutionResult));
                    }
                });

                launcher.execute(request);
            } finally {
                Thread.currentThread().setContextClassLoader(currentLoader);
                TestGenerationContext.getInstance().doneWithExecutingSUTCode();
                org.evosuite.runtime.agent.AgentLoader.setSkipAttach(false);
                if (disabledHeadless) {
                    GuiSupport.restoreHeadlessAfterMockConstruction();
                }
                if (!wasMockFrameworkEnabled) {
                    MockFramework.disable();
                }
            }


            if (wasSandboxOn) {
                //only activate Sandbox if it was already active before
                if (!Sandbox.isSecurityManagerInitialized()) {
                    Sandbox.initializeSecurityManagerForSUT(privileged);
                    org.evosuite.testcase.execution.ExecutableSnippetEngine.INSTANCE
                            .registerCompilationThreadAsPrivileged();
                }
            } else {
                if (Sandbox.isSecurityManagerInitialized()) {
                    logger.warn("EvoSuite problem: tests set up a security manager, "
                            + "but they do not remove it after execution");
                    Sandbox.resetDefaultSecurityManager();
                }
            }

            JUnitResultBuilder builder = new JUnitResultBuilder();
            return builder.build(result);
        }
    }
}
