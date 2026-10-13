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
import org.evosuite.rmi.ClientServices;
import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.InitializingListener;
import org.evosuite.runtime.InitializingListenerUtils;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.annotation.EvoSuiteEndsOnResourceLimit;
import org.evosuite.runtime.classhandling.ClassStateSupport;
import org.evosuite.runtime.classhandling.JDKClassResetter;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.runtime.util.JarPathing;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
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
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
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

    private static final AtomicInteger dirCounter = new AtomicInteger();

    private static final String JAVA = ".java";
    private static final String CLASS = ".class";
    private static final Map<String, String> SANITIZED_COMPILER_CLASSPATH_ENTRIES = new HashMap<>();
    private static final int MAX_STORED_COMPILER_DIAGNOSTICS = 100;
    private static final int MAX_LOGGED_SOURCE_EXCERPT_LINES_PER_FILE = 200;
    private static final int MAX_LOGGED_SOURCE_EXCERPT_CHARS_TOTAL = 20_000;
    private static final String FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE =
            "META-INF/services/java.nio.file.spi.FileSystemProvider";
    private static final int MAX_LOGGED_FILE_SYSTEM_PROVIDER_CLASSPATH_ENTRIES = 30;
    private static final int MAX_LOGGED_FILE_SYSTEM_PROVIDER_IMPLEMENTATIONS_PER_ENTRY = 10;
    private static final int MAX_LOGGED_FILE_SYSTEM_PROVIDER_SERVICE_URLS = 30;
    private static volatile boolean COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE = false;
    private static volatile boolean COMPILE_CHECK_DISABLED_LOGGED = false;
    private static volatile boolean COMPILE_DEPENDENT_EXECUTION_SKIPPED_LOGGED = false;
    /**
     * Set when a check ran out of heap (javac included, which runs in this JVM). The remaining
     * checks of the phase are skipped and the tests not checked yet are kept, as with any other
     * sign of low memory during the check; unlike a javac infrastructure failure, this does not
     * disable compile filtering for good. Cleared by {@link #resetMemoryExhaustion()}.
     */
    private static volatile boolean MEMORY_EXHAUSTED_DURING_CHECK = false;

    /**
     * Set when any JUnit check round (compile filter, instability check, or order-sensitivity
     * probe) during this run had to be abandoned before completing. The suite is kept regardless
     * (see the call sites of {@link #recordCheckIncomplete(String)}); this only records that it
     * was never fully validated, so that fact reaches the generation report instead of being
     * visible only in the log. Cleared by {@link #resetMemoryExhaustion()}.
     */
    private static volatile boolean CHECK_INCOMPLETE = false;

    /**
     * Number of distinct give-up events recorded via {@link #recordCheckIncomplete(String)}.
     */
    private static volatile int INCOMPLETE_CHECK_ROUNDS = 0;

    /**
     * The class loader of the JUnit check in progress. Every check gets a fresh one, which is
     * released (see {@link #releaseCheckLoader()}) once the check is over, so that the classes
     * it instrumented do not accumulate across the many checks of post-processing.
     */
    private static InstrumentingClassLoader loader = null;

    /**
     * Set when a JUnit check runner had to be abandoned while it may still be executing code
     * through the current check loader; that loader is then left alone rather than released
     * under the orphan's feet.
     */
    private static volatile boolean checkRunnerAbandoned = false;

    private static final VersionDependentAnalyzing JUNIT4_ANALYZER = new JUnit4Analyzing();
    private static final VersionDependentAnalyzing JUNIT5_ANALYZER = new JUnit5Analyzing();

    /**
     * Test-only override for {@link #getVersionDependentAnalyzer()}. {@code null} in production
     * (and never set outside tests). The call it replaces happens inside the submitted
     * {@link #runnerTask} of {@link #runJUnitOnCurrentProcess(Class[])}, on a dedicated worker
     * thread -- Mockito's static/construction mocking only intercepts calls made from the thread
     * that registered the mock, so it cannot reach that call. This field is the seam that lets a
     * test make the runner invocation itself throw (e.g. simulating an {@link OutOfMemoryError}
     * that already escaped JUnit's own per-test handling), the same way
     * {@code TestSuiteGenerator.freeMemoryBytesForTesting} overrides a production supplier for
     * tests.
     */
    static VersionDependentAnalyzing VERSION_DEPENDENT_ANALYZER_FOR_TESTING = null;

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
        // Errors (and OTHER, which can carry a fatal condition javac has no dedicated kind for)
        // are kept separate from everything else (warnings, mandatory warnings, notes) so that a
        // flood of warnings can never evict a real error from the bounded list, and so a caller
        // can log the two at different levels instead of dumping warnings next to errors as if
        // they had also kept the file from compiling.
        private final List<Diagnostic<? extends JavaFileObject>> errorDiagnostics = new ArrayList<>();
        private final List<Diagnostic<? extends JavaFileObject>> otherDiagnostics = new ArrayList<>();
        private boolean errorsTruncated = false;
        private boolean othersTruncated = false;
        private boolean sawErrorWhileWriting = false;

        private BoundedDiagnosticListener(int maxDiagnostics) {
            this.maxDiagnostics = Math.max(0, maxDiagnostics);
        }

        private static boolean isErrorLike(Diagnostic.Kind kind) {
            return kind == Diagnostic.Kind.ERROR || kind == Diagnostic.Kind.OTHER;
        }

        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            String message = diagnostic.getMessage(null);
            if (message != null && message.startsWith(ERROR_WHILE_WRITING_PREFIX)) {
                sawErrorWhileWriting = true;
            }
            if (isErrorLike(diagnostic.getKind())) {
                if (errorDiagnostics.size() < maxDiagnostics) {
                    errorDiagnostics.add(diagnostic);
                } else {
                    errorsTruncated = true;
                }
            } else {
                if (otherDiagnostics.size() < maxDiagnostics) {
                    otherDiagnostics.add(diagnostic);
                } else {
                    othersTruncated = true;
                }
            }
        }

        private List<Diagnostic<? extends JavaFileObject>> getErrorDiagnostics() {
            return errorDiagnostics;
        }

        private List<Diagnostic<? extends JavaFileObject>> getOtherDiagnostics() {
            return otherDiagnostics;
        }

        private boolean wasErrorsTruncated() {
            return errorsTruncated;
        }

        private boolean wasOthersTruncated() {
            return othersTruncated;
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
        if (COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE) {
            logCompileCheckDisabledIfNeeded();
            return;
        }

        Iterator<TestCase> iter = tests.iterator();

        while (iter.hasNext()) {
            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                recordCheckIncomplete("ran out of time in the compile filter; remaining test(s) kept unchecked");
                break;
            }
            if (MEMORY_EXHAUSTED_DURING_CHECK) {
                break;
            }

            TestCase test = iter.next();

            File dir = createNewTmpDir();
            if (dir == null) {
                logger.warn("Failed to create tmp dir");
                // Infrastructure setup failure, not a judgment on the remaining test(s): they are
                // kept unchecked, so record that the compile filter never actually ran on them.
                recordCheckIncomplete("compile filter: failed to create tmp dir; remaining test(s) kept unchecked");
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
                } else if (checksSuspended()) {
                    return;
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
        if (checksSuspended()) {
            logCompileDependentExecutionSkippedIfNeeded();
            return numUnstable;
        }

        File dir = createNewTmpDir();
        if (dir == null) {
            logger.error("Failed to create tmp dir");
            // Infrastructure setup failure: the test(s) are kept unchecked, exactly as the
            // compile filter's own tmp-dir failure above does, so record it the same way.
            recordCheckIncomplete("instability check: failed to create tmp dir; test(s) kept unchecked");
            return numUnstable;
        }
        logger.debug("Created tmp folder: " + dir.getAbsolutePath());

        try {
            List<File> generated = compileTests(tests, dir);
            if (generated == null) {
                /*
                 * By this point each test already passed the single-test compile filter
                 * individually, so this is not that filter's ordinary "rejected test" verdict:
                 * it means the whole-suite compile unit (or the writer output it depends on)
                 * failed in a way the per-test filter never saw. The suite is kept unchecked,
                 * so record that this round never actually validated it, same as the
                 * tmp-dir/time-budget give-ups above. Infrastructure failures that compileTests
                 * already recorded itself (OOM, javac crashes, etc.) return a non-null
                 * "generated" instead and are not re-recorded here.
                 */
                logger.warn("Failed to compile the test cases ");
                recordCheckIncomplete(
                        "instability check: whole-suite compile failed unexpectedly; test(s) kept unchecked");
                return numUnstable;
            }
            if (shouldSkipCompileDependentExecutionAfterCompile()) {
                return numUnstable;
            }

            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                logger.error("Ran out of time while checking tests");
                recordCheckIncomplete("ran out of time before running the instability check");
                return numUnstable;
            }

            // Create a new classloader so that each test gets freshly loaded classes.
            // Start with a NonInstrumentingClassLoader to avoid duplicate type
            // identities across loader boundaries during JUnit recheck. If we
            // detect instrumentation/GUI mismatch signals, runTests() retries
            // once with InstrumentingClassLoader.
            loader = createInitialJUnitClassLoader(tests);
            Class<?>[] testClasses = loadTests(generated);

            if (testClasses == null) {
                // Compilation already succeeded here, so this is an unexpected loading failure,
                // not an ordinary compile rejection: the suite is kept unchecked and the round
                // never validated it.
                logger.error("Found no classes for compiled tests");
                recordCheckIncomplete(
                        "instability check: failed to load compiled test classes; test(s) kept unchecked");
                return numUnstable;
            }

            JUnitResult result = runTests(testClasses, dir, generated);

            if (result.wasSuccessful()) {
                return numUnstable; //everything is OK
            }


            AtomicBoolean watchdogCulpritSeen = new AtomicBoolean(false);
            failure_loop:
            for (JUnitFailure failure : result.getFailures()) {
                String testName = failure.getDescriptionMethodName();//TODO check if correct
                // A test that hung is not one whose failure was expected: a test recorded as
                // failing during the search may well be the one that was killed there.
                boolean hung = JUnitCheckProgress.HUNG_TEST_EXCEPTION.equals(failure.getExceptionClassName());
                for (int i = 0; i < tests.size(); i++) {
                    if (TestSuiteWriterUtils.getNameOfTest(tests, i).equals(testName)) {
                        if (tests.get(i).isFailing() && !hung) {
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
                if ("org.evosuite.runtime.TooManyResourcesException".equals(failure.getExceptionClassName())
                        || isMockInvocationLimitFailure(failure)
                        || isWrappedTooManyResourcesFailure(failure)
                        || isStackOverflowOfTestEndingOnLimit(failure, testClasses)) {
                    logger.debug("Ignoring expected TooManyResourcesException for test {} "
                            + "(SUT loop terminated by iteration cap, same as during search): {}",
                            testName, failure.getMessage());
                    continue failure_loop;
                }
                // In JUnit re-checks, watchdog timeouts can race with loop-cap termination.
                // Treat TimeoutException as equivalent to TooManyResourcesException to avoid
                // dropping tests solely due to phase-specific watchdog scheduling.
                if ("java.util.concurrent.TimeoutException".equals(failure.getExceptionClassName())) {
                    logger.debug("Ignoring expected TimeoutException for test {} "
                                    + "(equivalent to loop-cap termination during JUnit re-check): {}",
                            testName, failure.getMessage());
                    continue failure_loop;
                }

                // Once the watchdog has given up on the run it raises the kill switch, and every
                // test after the one that hung times out on its first instrumented line. Only the
                // first such failure is evidence about a test (the one that hung); the others
                // were never given a chance to run, and removing them used to throw away most of
                // the suite for a single stuck static initializer.
                if (isWatchdogCasualty(result, failure, watchdogCulpritSeen)) {
                    logger.warn("Keeping test {}: it timed out only because the JUnit-check watchdog "
                            + "had already stopped the run", testName);
                    continue failure_loop;
                }

                // Class initialization failures are deterministic environmental issues
                // (e.g., broken static initializers) rather than assertion instability.
                // Remove those tests, but do not count them as unstable.
                if (isClassInitializerFailure(failure)) {
                    logger.warn("Removing test {} due deterministic class-initializer failure: {}: {}",
                            testName, failure.getExceptionClassName(), failure.getMessage());
                    removeTestByName(tests, testName);
                    continue failure_loop;
                }
                if (isDeterministicGuiEnvironmentFailure(failure)) {
                    logger.warn("Removing test {} due deterministic GUI-environment failure: {}: {}",
                            testName, failure.getExceptionClassName(), failure.getMessage());
                    removeTestByName(tests, testName);
                    continue failure_loop;
                }


                logger.warn("Found unstable test named " + testName + " -> "
                        + failure.getExceptionClassName() + ": " + failure.getMessage());
                if (isReflectiveAssertThrowsMismatch(failure)) {
                    logger.warn("Likely reflective assertThrows mismatch in {}: Method.invoke wraps user "
                            + "exceptions in InvocationTargetException. Prefer direct invocation for accessible "
                            + "methods, or assert InvocationTargetException and then assert on getCause().", testName);
                }

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
            releaseCheckLoader();
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

    /**
     * A mock that exceeded the invocation limit of the runtime. Mockito wraps what an invocation
     * listener throws, so the TooManyResourcesException reaches the test inside a MockitoException
     * whose message names it.
     */
    static boolean isMockInvocationLimitFailure(JUnitFailure failure) {
        if (failure == null) {
            return false;
        }
        String cls = normalizeFailureClassName(failure.getExceptionClassName());
        String message = failure.getMessage();
        // the class name carries the shading prefix when Mockito is the relocated copy
        return cls.endsWith("org.mockito.exceptions.base.MockitoException")
                && message != null && message.contains("MockInvocationLimit")
                && message.contains("TooManyResourcesException");
    }

    /**
     * Whether a timeout in a run the watchdog stopped says nothing about the test. The watchdog
     * raises the kill switch when the run exceeds its budget, and from then on every test that
     * reaches instrumented code fails at once; only the first such failure, the test that hung,
     * is evidence about a test.
     *
     * @param result          the result the failure belongs to
     * @param failure         the failure to judge
     * @param culpritSeen     whether the test that hung has been seen already; set by the first
     *                        timeout of a stopped run
     * @return true if the failure is a casualty of the stopped run and its test should be kept
     */
    static boolean isWatchdogCasualty(JUnitResult result, JUnitFailure failure,
                                      AtomicBoolean culpritSeen) {
        if (!result.wasWatchdogFired() || !isTimeoutExceeded(failure)) {
            return false;
        }
        return culpritSeen.getAndSet(true);
    }

    /**
     * A test stopped by the test executor's kill switch, whose time budget had run out.
     */
    static boolean isTimeoutExceeded(JUnitFailure failure) {
        return failure != null
                && normalizeFailureClassName(failure.getExceptionClassName())
                .endsWith("org.evosuite.testcase.execution.TestCaseExecutor$TimeoutExceeded");
    }

    /**
     * The JUnit check reads the failures itself instead of running the tests through the
     * non-functional requirement rule, so it applies the rule's allowance here: a stack overflow
     * is the expected end of a test that carries
     * {@link EvoSuiteEndsOnResourceLimit}, because unbounded recursion ends on the stack when
     * that runs out before a resource limit fires (see
     * {@link TooManyResourcesException#isExpectedLimitFailure(Throwable, boolean)}).
     */
    static boolean isStackOverflowOfTestEndingOnLimit(JUnitFailure failure, Class<?>[] testClasses) {
        if (failure == null || testClasses == null || failure.getDescriptionMethodName() == null) {
            return false;
        }
        String overflow = StackOverflowError.class.getName();
        String trace = failure.getTrace();
        boolean isOverflow = overflow.equals(failure.getExceptionClassName())
                || (trace != null && trace.contains("Caused by: " + overflow));
        if (!isOverflow) {
            return false;
        }
        for (Class<?> testClass : testClasses) {
            if (failure.getDescriptionClassName() != null
                    && !failure.getDescriptionClassName().equals(testClass.getName())) {
                continue;
            }
            for (java.lang.reflect.Method method : testClass.getDeclaredMethods()) {
                if (!method.getName().equals(failure.getDescriptionMethodName())) {
                    continue;
                }
                // by name: the tests may have been loaded by a loader with its own copy of the runtime
                for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
                    if (annotation.annotationType().getName().equals(EvoSuiteEndsOnResourceLimit.class.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * A failure that is a wrapper around the runtime's resource limit. The code under test can
     * catch the TooManyResourcesException and throw an exception of its own, as a persistence
     * layer does with {@code new PersistenceException(e.toString())}. Whether the wrapper is
     * thrown depends on which handler the limit happens to meet, so its type and message are as
     * little reproducible as the limit itself: it is the same outcome as the limit.
     */
    static boolean isWrappedTooManyResourcesFailure(JUnitFailure failure) {
        if (failure == null) {
            return false;
        }
        String limit = TooManyResourcesException.class.getName();
        String message = failure.getMessage();
        String trace = failure.getTrace();
        return (message != null && message.contains(limit)) || (trace != null && trace.contains(limit));
    }

    private static boolean isClassInitializerFailure(JUnitFailure failure) {
        if (failure == null) {
            return false;
        }
        String cls = normalizeFailureClassName(failure.getExceptionClassName());
        if (!"java.lang.ExceptionInInitializerError".equals(cls)
                && !"java.lang.NoClassDefFoundError".equals(cls)) {
            return false;
        }
        List<String> trace = failure.getExceptionStackTrace();
        if (trace == null || trace.isEmpty()) {
            return true;
        }
        for (String frame : trace) {
            if (frame != null && frame.contains("<clinit>")) {
                return true;
            }
        }
        return "java.lang.ExceptionInInitializerError".equals(cls);
    }

    private static void removeTestByName(List<TestCase> tests, String testName) {
        for (int i = 0; i < tests.size(); i++) {
            if (TestSuiteWriterUtils.getNameOfTest(tests, i).equals(testName)) {
                tests.remove(i);
                return;
            }
        }
    }

    private static boolean isDeterministicGuiEnvironmentFailure(JUnitFailure failure) {
        if (failure == null) {
            return false;
        }
        String failureClass = normalizeFailureClassName(failure.getExceptionClassName());
        String message = failure.getMessage();
        List<String> trace = failure.getExceptionStackTrace();

        if ("java.awt.AWTError".equals(failureClass)
                && isGraphicsEnvironmentNullFailure(message, trace)) {
            return true;
        }

        if (!"java.awt.HeadlessException".equals(failureClass)) {
            return false;
        }

        if (message != null && message.toLowerCase(Locale.ROOT).contains("headless")) {
            return true;
        }
        if (trace == null || trace.isEmpty()) {
            return true;
        }
        return containsAny(trace,
                "java.awt.GraphicsEnvironment.checkHeadless",
                "java.awt.Window.<init>",
                "javax.swing.JFrame.<init>",
                "javax.swing.JDialog.<init>",
                "javax.swing.JWindow.<init>");
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

        boolean needsInstrumentedRetry = shouldRetryWithInstrumentingAfterFailure(result);

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
            releaseCheckLoader();
            loader = savedLoader;
        }
    }

    private static boolean shouldRetryWithInstrumentingAfterFailure(JUnitResult result) {
        for (JUnitFailure failure : result.getFailures()) {
            String failureClass = normalizeFailureClassName(failure.getExceptionClassName());
            String message = failure.getMessage();
            List<String> trace = failure.getExceptionStackTrace();
            if ("java.awt.HeadlessException".equals(failureClass)) {
                return true;
            }
            if ("java.awt.AWTError".equals(failureClass)
                    && isGraphicsEnvironmentNullFailure(message, trace)) {
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

    private static String normalizeFailureClassName(String rawClassName) {
        if (rawClassName == null) {
            return "";
        }
        String trimmed = rawClassName.trim();
        if (trimmed.startsWith("class ")) {
            return trimmed.substring("class ".length()).trim();
        }
        return trimmed;
    }

    private static boolean isGraphicsEnvironmentNullFailure(String message, List<String> trace) {
        if (message != null
                && message.toLowerCase(Locale.ROOT).contains("local graphicsenvironment must not be null")) {
            return true;
        }
        return trace != null && containsAny(trace, "GraphicsEnvironment.getLocalGraphicsEnvironment");
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
        if (isInfrastructureOrderSensitivityFailure(forwardFailures)) {
            logger.warn("Skipping reverse order-sensitivity run after forward infrastructure error: {}",
                    forwardFailures);
            return new OrderSensitivityAnalysis(forwardFailures, Collections.<String>emptySet());
        }
        List<TestCase> reverse = cloneTests(tests);
        Collections.reverse(reverse);
        Set<String> reverseFailures = executeAndCollectUnexpectedFailures(reverse);
        return new OrderSensitivityAnalysis(forwardFailures, reverseFailures);
    }

    static boolean isInfrastructureOrderSensitivityFailure(Set<String> failures) {
        return failures != null
                && failures.size() == 1
                && failures.iterator().next().endsWith("-error");
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
        long budgetMs = computeWatchdogBudgetMs(1000L * Math.max(1, Properties.JUNIT_CHECK_TIMEOUT),
                remainingTimeInPhaseMs());
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
        // Remembered so that, if we end up abandoning this thread below, we can hand back the
        // sandbox lock it may still be holding. The generated scaffolding enters the sandbox, and
        // an abandoned runner never leaves it, which used to strand the lock for the rest of the
        // process and fail every later test execution on the acquisition timeout.
        AtomicReference<Thread> runnerThread = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "evosuite-junit-check-runner");
            t.setDaemon(true);
            if (sandboxOn) {
                Sandbox.addPrivilegedThread(t);
            }
            runnerThread.set(t);
            return t;
        });
        JUnitCheckProgress progress = new JUnitCheckProgress();
        // SUT threads left over from the tests (fixsuite's LibrarySuite starts one from the event
        // dispatch thread) outlive the generated test that turns the mock framework off, so their
        // System.exit is the real one. The runner takes the sandbox down for the run, and the JVM
        // exited whenever such a call came while no manager was installed, losing the suite.
        Sandbox.setJvmExitRefused(true);
        Future<JUnitResult> future = executor.submit(runnerTask(testClasses, progress));
        boolean firedTimeout = false;
        boolean gaveUp = false;
        try {
            try {
                return awaitRunner(future, runnerThread, progress, budgetMs, hangLimitMs(), hangGraceMs(),
                        testClasses.length);
            } catch (TimeoutException te) {
                firedTimeout = true;
                logger.warn("JUnit check exceeded watchdog budget of {} ms; "
                        + "setting kill switch and interrupting runner", budgetMs);
                ExecutionTracer.setKillSwitch(true);
                // Interrupt the runner directly rather than cancelling the Future. cancel(true)
                // interrupts too, but it also moves the Future to CANCELLED, and every later
                // get() then throws CancellationException instead of returning the runner's
                // result or timing out - which made the grace window below unreachable and sent
                // us down a path that discarded the whole suite. Interrupting the thread leaves
                // the Future live, so a runner that unwinds in time still reports its real
                // result, and one that does not falls through to the keep-the-suite branch.
                // The progress sends it, so that it is never cleared as a leftover of a hang.
                progress.interruptForBudget(runnerThread.get());
            }
            return resultAfterBudgetRanOut(future, runnerThread.get(), progress, graceMs, testClasses.length);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            // Synthesized result, not an observed one: every test is reported failed without
            // ever having been checked, so this is a give-up like the others above.
            gaveUp = true;
            recordCheckIncomplete("JUnit check runner interrupted while awaiting its result: " + ie);
            return JUnitResult.allFailed(testClasses.length, ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            // Same reasoning as the InterruptedException branch above: the result is synthesized,
            // not observed.
            gaveUp = true;
            recordCheckIncomplete("JUnit check runner failed unexpectedly while awaiting its result: " + cause);
            return JUnitResult.allFailed(testClasses.length, cause);
        } finally {
            abandonRunnerLeftRunning(future, progress, runnerThread.get(), gaveUp);
            // Interrupts a runner that has either returned or been abandoned; an abandoned one
            // parks at its next callback whatever the interrupt does to the code it is in.
            executor.shutdownNow();
            // Whichever way we leave, the runner is done with the sandbox: it either returned a
            // result (and released it itself, making this a no-op) or we are walking away from it.
            // That covers exits the paths above do not, such as an Error escaping this method while
            // it waits (OutOfMemoryError under heap pressure), an interrupt of this thread, or a
            // runner that died inside the sandbox without reaching its own release. Without it the
            // dead runner's lock stalls the next test execution for the whole acquisition timeout.
            Sandbox.reclaimSandboxLockOwnedBy(runnerThread.get());
            // Not even past a runner that was abandoned: the client has to be able to exit itself.
            Sandbox.setJvmExitRefused(false);
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

    /**
     * What the runner thread of a JUnit check executes: the run, and then, however it ended, the
     * report to the progress that the runner returned from it.
     *
     * @param testClasses the test classes to run
     * @param progress    what the runner does
     * @return the task
     */
    static Callable<JUnitResult> runnerTask(Class<?>[] testClasses, JUnitCheckProgress progress) {
        return () -> {
            try {
                return getVersionDependentAnalyzer().runJUnitOnCurrentProcess(testClasses, progress);
            } catch (OutOfMemoryError e) {
                // Test triggered a huge allocation (e.g. new DefaultTableModel(351774, 351774)).
                // Force GC to reclaim before reporting back.
                System.gc();
                logger.warn("OutOfMemoryError during JUnit execution — forced GC and treating all tests as failed");
                // This OOM escaped the whole JUnit runner (runJUnitOnCurrentProcess), rather than
                // being caught and attributed to one test the way JUnit itself catches per-test
                // throwables; the result below carries no per-test failure info, so callers that
                // read result.getFailures() (e.g. handleTestsThatAreUnstable) see an empty list
                // and treat this exactly like a run that observed nothing wrong. Record it the
                // same way the other synthesized-result give-ups in this method already do.
                recordCheckIncomplete("OutOfMemoryError escaped the JUnit runner: " + e);
                return JUnitResult.allFailed(testClasses.length, e);
            } finally {
                // The runner runs nothing more of the check: from here on it is not abandoned, so
                // its class loader is released and the sandbox not taken from it.
                progress.runEnded();
            }
        };
    }

    /**
     * The result of a check whose budget ran out and whose runner was interrupted for it.
     *
     * <p>Grace window: the runner gets a chance to unwind after the interrupt. If it does not
     * (typical for OOM-suppression cascades stuck in synchronized addSuppressed), the check is
     * abandoned and the tests are treated as passing: the suite already survived search, and
     * there is no observed failure for any specific test, so dropping the whole suite would be
     * more punitive than skipping the phase. The orphan thread parks at its next callback; the
     * shutdown of its executor cannot kill it. A runner that returns from the run just as the
     * grace runs out is not abandoned, and reports what it found.
     *
     * @param future    the runner's result
     * @param runner    the runner thread, or null if it never started
     * @param progress  what the runner did
     * @param graceMs   how long the runner gets to unwind
     * @param testCount the number of test classes the check covers
     * @return the result, flagged as produced after the watchdog fired, unless the check was
     *         cancelled
     */
    static JUnitResult resultAfterBudgetRanOut(Future<JUnitResult> future, Thread runner,
                                               JUnitCheckProgress progress, long graceMs, int testCount)
            throws InterruptedException, ExecutionException {
        try {
            return resultWithinGrace(future, progress, graceMs, testCount);
        } catch (TimeoutException te) {
            JUnitCheckProgress.Abandonment abandonment = progress.abandonAndCollect(testCount);
            String reason = "runner did not respond " + graceMs + " ms after interrupt";
            if (abandonment.abandonedTheRunner()) {
                logger.error("JUnit check did not respond {} ms after interrupt; "
                        + "abandoning JUnit check and keeping the suite as-is. "
                        + "The orphan runner thread parks at its next test or class boundary.",
                        graceMs);
                abandonRunner(runner, reason);
                return afterWatchdogAbandonedRunner(abandonment.getResult(), progress, testCount);
            }
            // It returned from the run just as the grace ran out, so there is nothing to walk away
            // from, and its result is handed to the future as it returns: take what it found.
            JUnitResult returned = resultOfRunnerThatReturned(future, progress, graceMs, testCount);
            if (returned != null) {
                return returned;
            }
            recordCheckIncomplete(reason);
            return afterWatchdogAbandonedRunner(abandonment.getResult(), progress, testCount);
        } catch (CancellationException ce) {
            // The check was cancelled rather than completed, so it observed no failure for
            // any specific test. Treating that as "every test failed" would throw away a
            // suite the search already produced because the *check* did not finish, which
            // is the wrong trade: report the tests as passing and skip the phase.
            logger.error("JUnit check was cancelled before completing; "
                    + "keeping the suite as-is without checking it.");
            String reason = "runner was cancelled before completing";
            if (progress.abandon()) {
                abandonRunner(runner, reason);
            } else {
                recordCheckIncomplete(reason);
            }
            return JUnitResult.allPassed(testCount);
        }
    }

    /**
     * The result of a runner that returned from the run, read as soon as it is handed over. The
     * runner reports that it returned before its result reaches the future, so the wait is short;
     * it is bounded all the same.
     *
     * @param future    the runner's result
     * @param progress  what the runner did
     * @param waitMs    how long to wait for the result at most
     * @param testCount the number of test classes the check covers
     * @return the result, flagged as produced after the watchdog fired, or null if the runner
     *         did not complete the run with a result after all
     */
    private static JUnitResult resultOfRunnerThatReturned(Future<JUnitResult> future, JUnitCheckProgress progress,
                                                          long waitMs, int testCount)
            throws InterruptedException {
        try {
            return resultWithinGrace(future, progress, waitMs, testCount);
        } catch (TimeoutException | ExecutionException | CancellationException e) {
            logger.warn("The JUnit check runner returned from the run without a result: {}", e.toString());
            return null;
        }
    }

    /**
     * Abandons the runner if it is still going when the wait for it ends. That is left by an exit
     * the paths of the wait do not handle, such as an interrupt of the waiting thread or an Error
     * escaping the wait: the runner must not go on with the suite alongside the work that follows.
     * Whether it is still going is decided by the progress, at the moment it abandons it, so a
     * runner that returns in the meantime is neither abandoned nor walked away from (which would
     * keep the class loader of the check from being released).
     *
     * @param future         the runner's result
     * @param progress       what the runner did
     * @param runner         the runner thread, or null if it never started
     * @param giveUpRecorded whether the exit already recorded the check as incomplete
     * @return whether the runner was abandoned
     */
    static boolean abandonRunnerLeftRunning(Future<JUnitResult> future, JUnitCheckProgress progress,
                                            Thread runner, boolean giveUpRecorded) {
        if (future.isDone() || !progress.abandon()) {
            return false;
        }
        Sandbox.abandonThread(runner);
        checkRunnerAbandoned = true;
        if (!giveUpRecorded) {
            recordCheckIncomplete("runner was still going when the wait for it ended");
        }
        return true;
    }

    /**
     * Whether a runner was abandoned since the class loader of the check was last released.
     *
     * @return whether the class loader of the current check is to be kept for an abandoned runner
     */
    static boolean isCheckRunnerAbandoned() {
        return checkRunnerAbandoned;
    }

    /**
     * Collects the result of a runner that was interrupted because the budget of the check ran
     * out, within the grace period. The result is judged like the one of a run that finished in
     * time: tests that were caught hanging are blamed, and a runner that died after a test had to
     * be stopped reports what it had found, not a failure of every test.
     *
     * @param future    the runner's result
     * @param progress  what the runner did
     * @param graceMs   how long the runner gets to unwind
     * @param testCount the number of test classes the check covers
     * @return the result, flagged as produced after the watchdog fired
     * @throws TimeoutException if the runner did not unwind in time
     */
    static JUnitResult resultWithinGrace(Future<JUnitResult> future, JUnitCheckProgress progress,
                                         long graceMs, int testCount)
            throws InterruptedException, ExecutionException, TimeoutException {
        JUnitResult result;
        try {
            result = progress.blameHungTests(future.get(graceMs, TimeUnit.MILLISECONDS));
        } catch (ExecutionException died) {
            result = resultOfDeadRunner(died, progress, testCount);
        }
        result.setWatchdogFired(true);
        return result;
    }

    /**
     * Makes the result of a check whose runner ignored the interrupt of the watchdog. The suite is
     * kept as it is, except that tests caught hanging before the budget ran out are still to be
     * dropped. As for any result produced after the watchdog fired, the timeouts in it say nothing
     * about the tests: the kill switch was raised for the run.
     *
     * @param collected what the runner recorded until it was abandoned
     * @param progress  what the runner did
     * @param testCount the number of test classes the check covers
     * @return the result, flagged as produced after the watchdog fired
     */
    static JUnitResult afterWatchdogAbandonedRunner(JUnitResult collected, JUnitCheckProgress progress,
                                                    int testCount) {
        JUnitResult result = progress.hasHungTests() ? collected : JUnitResult.allPassed(testCount);
        result.setWatchdogFired(true);
        return result;
    }

    /**
     * The result of a run whose runner thread died. Stopping the thread of a hung test can raise
     * the stop outside the test, in the engine, and end the run: the tests that hung are known,
     * the ones after them were not run. A runner that died without any test having hung is a
     * failure of the check itself.
     *
     * @param died      how the runner ended
     * @param progress  what the runner did
     * @param testCount the number of test classes the check covers
     * @return what the run found
     * @throws ExecutionException if no test hung, so there is nothing to report but the failure
     */
    private static JUnitResult resultOfDeadRunner(ExecutionException died, JUnitCheckProgress progress,
                                                  int testCount) throws ExecutionException {
        if (!progress.hasHungTests()) {
            throw died;
        }
        logger.warn("The JUnit check runner died after a test had to be stopped: {}", died.toString());
        recordCheckIncomplete("runner died after a hung test was stopped");
        return progress.deadRunnerResult(testCount);
    }

    /**
     * How long the runner gets to unwind after a test that ran too long was interrupted, before
     * the test counts as hung.
     */
    static final long HANG_GRACE_MS = 10_000L;

    /** Longest a single test may run before it counts as hung, however short its own timeout. */
    static final long MIN_HANG_LIMIT_MS = 30_000L;

    /**
     * Test-only override for {@link #hangGraceMs()}; 0 in production.
     */
    static volatile long HANG_GRACE_FOR_TESTING_MS = 0L;

    static long hangGraceMs() {
        return HANG_GRACE_FOR_TESTING_MS > 0 ? HANG_GRACE_FOR_TESTING_MS : HANG_GRACE_MS;
    }

    /**
     * Test-only override for {@link #hangLimitMs()}; 0 in production.
     */
    static volatile long HANG_LIMIT_FOR_TESTING_MS = 0L;

    /**
     * How long one test may execute before the runner is interrupted. The generated tests carry a
     * timeout of their own, a few seconds, so a test that runs ten times as long has not merely
     * been slow: it either ignores the interrupt that timeout sends or it is blocked where an
     * interrupt cannot reach it.
     *
     * @return the limit in milliseconds
     */
    static long hangLimitMs() {
        if (HANG_LIMIT_FOR_TESTING_MS > 0) {
            return HANG_LIMIT_FOR_TESTING_MS;
        }
        return Math.max(MIN_HANG_LIMIT_MS, 10L * Math.max(1, Properties.TIMEOUT));
    }

    /**
     * Waits for the runner of a JUnit check, and gives up on a single test that hangs instead of
     * waiting out the budget of the whole check.
     *
     * <p>A test can hang where nothing can free it: two threads of the code under test deadlocked
     * on class initialization, with the test thread waiting for one of them, cannot be
     * interrupted. The runner then never reaches the next test, and the watchdog used to wait for
     * the whole budget (20 minutes by default), plus its grace period, only to learn nothing about
     * which test was to blame. Here a test that runs past the limit gets one interrupt; if it is
     * still the same test after the grace period, the runner is abandoned and the result blames
     * that test, which is dropped from the suite like any other test that fails the check.
     *
     * @param future       the runner's result
     * @param runnerThread holds the runner thread
     * @param progress     what the runner is doing
     * @param budgetMs     how long the whole run may take
     * @param hangLimitMs  how long one test may run before it is interrupted
     * @param hangGraceMs  how long the runner gets to move on after that interrupt
     * @param testCount    the number of test classes the check covers
     * @return the runner's result, or the result blaming the test that hung
     * @throws TimeoutException if the whole run exceeded its budget
     */
    static JUnitResult awaitRunner(Future<JUnitResult> future, AtomicReference<Thread> runnerThread,
                                   JUnitCheckProgress progress, long budgetMs, long hangLimitMs,
                                   long hangGraceMs, int testCount)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (true) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                throw new TimeoutException();
            }
            try {
                return progress.blameHungTests(future.get(Math.min(remainingMs, 200L), TimeUnit.MILLISECONDS));
            } catch (TimeoutException stillRunning) {
                // fall through to the hang check
            } catch (ExecutionException died) {
                return resultOfDeadRunner(died, progress, testCount);
            }
            // One snapshot: the runner can move to the next test at any moment, and reading the
            // test and the event count separately could blame one test and interrupt the next.
            JUnitCheckProgress.Snapshot seen = progress.snapshot();
            String running = seen.getRunningTest();
            if (running == null || seen.getRunningMillis() < hangLimitMs) {
                continue;
            }
            long event = seen.getEventCount();
            long ranMs = seen.getRunningMillis();
            Thread runner = runnerThread.get();
            // Blaming and interrupting happen while the runner cannot move to another test, so the
            // interrupt lands on the test that hung or not at all.
            if (!progress.interruptHungTest(event, runner, ranMs)) {
                continue; // it finished or moved on while we looked
            }
            logger.warn("Test {} has been running for {} ms in the JUnit check; interrupted the runner",
                    running, ranMs);
            if (waitForRunnerToMoveOn(future, progress, event, hangGraceMs)) {
                continue;
            }
            if (progress.stopIfStill(event, runner)
                    && waitForRunnerToMoveOn(future, progress, event, hangGraceMs)) {
                logger.warn("Test {} of the JUnit check was stopped after it ignored the interrupt", running);
                continue;
            }
            // Checking that the runner is still in the test and abandoning it happen at once, so a
            // runner that finishes the test meanwhile is not abandoned in the next one.
            JUnitResult result = progress.abandonIfStill(event, ranMs + 2 * hangGraceMs, testCount);
            if (result == null) {
                continue; // it moved on before it could be abandoned
            }
            logger.error("Test {} of the JUnit check ignored the interrupt for {} ms; abandoned the "
                    + "runner and dropped the test. The orphan runner thread parks once the test ends.",
                    running, ranMs + 2 * hangGraceMs);
            abandonRunner(runner, "test " + running + " hung and ignored the interrupt");
            return result;
        }
    }

    /**
     * Waits for the runner to start or finish a test, so it is no longer in the one it was in.
     *
     * @return true if the runner moved on, or finished the run
     */
    private static boolean waitForRunnerToMoveOn(Future<JUnitResult> future, JUnitCheckProgress progress,
                                                 long event, long graceMs) throws InterruptedException {
        long graceEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs);
        while (System.nanoTime() < graceEnd) {
            if (progress.movedOnFrom(event) || future.isDone()) {
                return true;
            }
            Thread.sleep(50L);
        }
        return progress.movedOnFrom(event) || future.isDone();
    }

    /** Shortest watchdog budget a check gets, even when its phase is (nearly) over. */
    static final long MIN_WATCHDOG_BUDGET_MS = 1_000L;

    /**
     * Returns how long the watchdog lets a single JUnit run take: the configured JUnit check
     * timeout, but never more than what is left of the current phase. Each call used to get
     * the full configured timeout (20 minutes by default) however little of the phase was
     * left, so a slow check could overrun the phase, and the whole run, by that much.
     *
     * @param configuredMs     the configured JUnit check timeout
     * @param remainingPhaseMs time left in the current phase, or {@link Long#MAX_VALUE} if unknown
     * @return the watchdog budget in milliseconds
     */
    static long computeWatchdogBudgetMs(long configuredMs, long remainingPhaseMs) {
        long budget = Math.min(configuredMs, Math.max(0L, remainingPhaseMs));
        return Math.max(MIN_WATCHDOG_BUDGET_MS, budget);
    }

    private static long remainingTimeInPhaseMs() {
        try {
            return TimeController.getInstance().getRemainingTimeInPhaseMs();
        } catch (RuntimeException e) {
            // No usable phase information: fall back to the configured timeout.
            logger.debug("Cannot determine the time left in the current phase", e);
            return Long.MAX_VALUE;
        }
    }

    private static String buildUnstableDiagnostics(String testName, JUnitFailure failure) {
        List<String> trace = failure.getExceptionStackTrace();
        String category = classifyUnstableFailure(testName, trace);
        String firstInterestingFrame = firstInterestingFrame(trace);
        String firstLoopCounterFrame = firstFrameContaining(trace, "org.evosuite.runtime.LoopCounter.checkLoop");
        String firstHeadlessFrame = firstFrameContaining(trace, "java.awt.HeadlessException");
        String firstAwtFrame = firstFrameContaining(trace, "java.awt.");
        String failureClass = normalizeFailureClassName(failure.getExceptionClassName());
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
        } else if ("java.awt.AWTError".equals(failureClass)
                && isGraphicsEnvironmentNullFailure(failureMessage, trace)) {
            sb.append(", hint=GraphicsEnvironment initialization/reset issue during JUnit recheck");
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

    private static boolean isReflectiveAssertThrowsMismatch(JUnitFailure failure) {
        if (failure == null || !failure.isAssertionError()) {
            return false;
        }
        String message = failure.getMessage();
        if (message == null || !message.contains("Unexpected exception type thrown")
                || !message.contains("InvocationTargetException")) {
            return false;
        }
        List<String> trace = failure.getExceptionStackTrace();
        return trace != null && containsAny(trace, "java.lang.reflect.Method.invoke");
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
        if (VERSION_DEPENDENT_ANALYZER_FOR_TESTING != null) {
            return VERSION_DEPENDENT_ANALYZER_FOR_TESTING;
        }
        return Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5
                ? JUNIT5_ANALYZER
                : JUNIT4_ANALYZER;
    }

    private static InstrumentingClassLoader createInitialJUnitClassLoader(List<TestCase> tests) {
        // Replacements (Random/UUID/time/gui/etc.) are woven by the instrumenting
        // class loader. If replacements are enabled, JUnit recheck must execute on
        // instrumented classes to preserve deterministic behavior.
        if (Properties.REPLACE_CALLS || Properties.REPLACE_GUI || mayInvokeJvmTermination(tests)) {
            return new InstrumentingClassLoader();
        }
        return new NonInstrumentingClassLoader();
    }

    /**
     * Releases the class loader of the check that just finished and forgets it. Each check
     * instruments the SUT anew in its own loader, registering the result in the static
     * loader-keyed pools; without the release, each check kept its loader and the instrumented
     * SUT alive (tens of megabytes on a large SUT), and a post-processing phase with a hundred
     * checks ran the client out of heap.
     */
    private static void releaseCheckLoader() {
        InstrumentingClassLoader released = loader;
        loader = null;
        if (released == null) {
            return;
        }
        if (checkRunnerAbandoned) {
            // An orphan runner may still be loading classes through this loader and writing
            // to its pools; leave them alone rather than race it.
            checkRunnerAbandoned = false;
            logger.warn("Not releasing the JUnit check class loader, an abandoned runner may still use it");
            return;
        }
        TestGenerationContext.getInstance().releaseClassLoader(released);
    }

    private static InstrumentingClassLoader currentLoader() {
        if (loader == null) {
            loader = new NonInstrumentingClassLoader();
        }
        return loader;
    }

    private static boolean mayInvokeJvmTermination(List<TestCase> tests) {
        if (tests == null || tests.isEmpty()) {
            return false;
        }
        for (TestCase test : tests) {
            if (test == null) {
                continue;
            }
            for (Statement statement : test) {
                if (!(statement instanceof MethodStatement)) {
                    continue;
                }
                MethodStatement ms = (MethodStatement) statement;
                java.lang.reflect.Method m = ms.getMethod() != null ? ms.getMethod().getMethod() : null;
                if (m == null) {
                    continue;
                }
                Class<?> owner = m.getDeclaringClass();
                String name = m.getName();
                if (owner == System.class && "exit".equals(name)) {
                    return true;
                }
                if (owner == Runtime.class && ("exit".equals(name) || "halt".equals(name))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<TestCase> cloneTests(List<TestCase> tests) {
        List<TestCase> clones = new ArrayList<>(tests.size());
        for (TestCase test : tests) {
            clones.add(test.clone());
        }
        return clones;
    }

    private static Set<String> executeAndCollectUnexpectedFailures(List<TestCase> tests) {
        if (checksSuspended()) {
            logCompileDependentExecutionSkippedIfNeeded();
            return Collections.emptySet();
        }
        File dir = createNewTmpDir();
        if (dir == null) {
            // Same contract as the compile filter and instability check above: the
            // order-sensitivity probe never actually ran, so record it as abandoned rather than
            // let the caller's infrastructure-failure heuristic on "tmp-dir-error" be the only
            // trace of it.
            recordCheckIncomplete("order-sensitivity analysis: failed to create tmp dir");
            return Collections.singleton("tmp-dir-error");
        }
        try {
            List<File> generated = compileTests(tests, dir);
            if (generated == null) {
                // Same reasoning as the instability check: these tests already passed the
                // per-test compile filter, so a null here is the whole-suite compile unit (or
                // its writer output) failing in a way that filter never saw, not an ordinary
                // rejected-test verdict. compileTests itself already records incompleteness for
                // the infrastructure failures that return non-null "generated" instead.
                recordCheckIncomplete("order-sensitivity analysis: whole-suite compile failed unexpectedly");
                return Collections.singleton("compilation-error");
            }
            if (shouldSkipCompileDependentExecutionAfterCompile()) {
                return Collections.emptySet();
            }
            loader = createInitialJUnitClassLoader(tests);
            Class<?>[] testClasses = loadTests(generated);
            if (testClasses == null) {
                recordCheckIncomplete("order-sensitivity analysis: failed to load compiled test classes");
                return Collections.singleton("load-error");
            }
            JUnitResult result = runTests(testClasses, dir, generated);
            if (result.wasSuccessful()) {
                return Collections.emptySet();
            }
            return mapUnexpectedFailureNames(result, tests);
        } catch (Exception e) {
            logger.warn("Order-sensitivity analysis failed: {}", e.getMessage());
            recordCheckIncomplete("order-sensitivity analysis: unexpected exception during execution: " + e);
            return Collections.singleton("execution-error");
        } finally {
            releaseCheckLoader();
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
        // Rendering performs output-only normalization (for example, removing
        // redundant downcasts and assertions after exceptions). Compile checks
        // must not leak those mutations back into the suite being validated.
        List<TestCase> renderingCopies = tests.stream()
                .map(TestCase::clone)
                .collect(Collectors.toList());
        suite.insertAllTests(renderingCopies);
        if (Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5
                && Properties.TEST_EXTENSION_MODE
                && Properties.RESET_STATIC_FIELDS) {
            suite.setExtensionInitializationOrder(resolveExtensionInitializationOrderForCompileCheck());
        }

        //to get name, remove all package before last '.'
        int beginIndex = Properties.TARGET_CLASS.lastIndexOf(".") + 1;
        String name = Properties.TARGET_CLASS.substring(beginIndex);
        name += "_" + (NUM++) + "_tmp_" + Properties.JUNIT_SUFFIX; //postfix

        List<File> generated = null;
        String compilerClasspath = null;
        try {
            //now generate the JUnit test case
            generated = suite.writeTestSuite(name, dir.getAbsolutePath(), Collections.EMPTY_LIST);
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
            if (COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE) {
                logCompileCheckDisabledIfNeeded();
                return generated;
            }

            Locale locale = Locale.getDefault();
            Charset charset = StandardCharsets.UTF_8;
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
            compilerClasspath = classpath;

            CompilerAttemptResult attempt = runCompilerAttempt(compiler, generated, classpath, locale, charset);
            if (attempt.hasInfrastructureFailure()) {
                if (isOutOfMemory(attempt.getInfrastructureFailure())) {
                    stopChecksDueToMemoryExhaustion(tests.size(), attempt.getInfrastructureFailure());
                    return generated;
                }
                disableCompileCheckDueToInfrastructure(attempt.getInfrastructureFailure(), compilerClasspath);
                return generated;
            }

            if (!attempt.isCompiled()) {
                BoundedDiagnosticListener diagnostics = attempt.getDiagnostics();
                String compilerOutput = attempt.getCompilerOutput();
                if (reportsOutOfMemory(compilerOutput)) {
                    // javac ran out of heap, which it shares with this client: the tests were
                    // never judged, and what is exhausted is the heap, not javac.
                    stopChecksDueToMemoryExhaustion(tests.size(),
                            new OutOfMemoryError("javac: " + firstLine(compilerOutput)));
                    return generated;
                }
                logger.error("Compilation failed on {} generated test file(s)", generated.size());
                logger.error("Classpath: {}", compilerClasspath);
                // TODO remove
                logger.error("evosuiteCP: {}", evosuiteCP);

                if (diagnostics.sawErrorWhileWriting()) {
                    disableCompileCheckDueToInfrastructure(
                            new IllegalStateException("javac could not write compiled test artifacts"),
                            compilerClasspath);
                    return generated;
                }

                for (Diagnostic<?> diagnostic : diagnostics.getErrorDiagnostics()) {
                    logger.error("Diagnostic (ERROR): {}: {}", diagnostic.getMessage(null),
                            diagnostic.getLineNumber());
                }
                if (diagnostics.wasErrorsTruncated()) {
                    logger.error("Additional compiler error diagnostics were omitted after {} entries",
                            MAX_STORED_COMPILER_DIAGNOSTICS);
                }
                // Warnings/notes never kept the file from compiling - the compiler task's own
                // boolean result above is what judged that. Logged separately and at a lower
                // level so they cannot be mistaken for the reason compilation failed, which
                // previously led to counting warning-only output as a compile failure.
                for (Diagnostic<?> diagnostic : diagnostics.getOtherDiagnostics()) {
                    logger.warn("Diagnostic ({}): {}: {}", diagnostic.getKind(),
                            diagnostic.getMessage(null), diagnostic.getLineNumber());
                }
                if (diagnostics.wasOthersTruncated()) {
                    logger.warn("Additional non-error compiler diagnostics were omitted after {} entries",
                            MAX_STORED_COMPILER_DIAGNOSTICS);
                }
                if (compilerOutput != null && !compilerOutput.isEmpty()) {
                    // Output javac would otherwise have written to its own out stream (distinct
                    // from the diagnostics above), e.g. usage or internal-error messages.
                    logger.error("Compiler output: {}", compilerOutput);
                }

                if (crashedInsteadOfRejecting(compilerOutput)) {
                    // javac died on its own (it catches StackOverflowError internally and prints
                    // "An exception has occurred in the compiler"), so it never judged the test.
                    // Deleting it here would throw away a perfectly good test. Running out of
                    // heap is handled above: that is the client's memory, not a javac problem.
                    disableCompileCheckDueToInfrastructure(
                            new IllegalStateException("javac crashed: " + firstLine(compilerOutput)),
                            compilerClasspath);
                    return generated;
                }

                logGeneratedSourceExcerptsOnCompilationFailure(generated);
                return null;
            }
            if (!hasCompiledClassFilesForGeneratedSources(generated)) {
                disableCompileCheckDueToInfrastructure(
                        new IllegalStateException(
                                "javac reported success but produced no .class files for generated tests"),
                        compilerClasspath);
                return generated;
            }
            return generated;

        } catch (OutOfMemoryError e) {
            // Not a verdict on the tests: reporting them as not compiling would delete them.
            stopChecksDueToMemoryExhaustion(tests.size(), e);
            return generated;
        } catch (IOException e) {
            logger.error("" + e, e);
            return null;
        } catch (RuntimeException e) {
            if (isOutOfMemory(e)) {
                stopChecksDueToMemoryExhaustion(tests.size(), e);
                return generated;
            }
            if (isJavacInfrastructureFailure(e)) {
                disableCompileCheckDueToInfrastructure(e, compilerClasspath);
                return generated;
            }
            logger.error("Unexpected runtime exception while compiling generated tests", e);
            return null;
        } catch (Error e) {
            if (isOutOfMemory(e)) {
                stopChecksDueToMemoryExhaustion(tests.size(), e);
                return generated;
            }
            if (isJavacInfrastructureFailure(e)) {
                disableCompileCheckDueToInfrastructure(e, compilerClasspath);
                return generated;
            }
            throw e;
        }
    }

    private static CompilerAttemptResult runCompilerAttempt(JavaCompiler compiler,
                                                            List<File> generated,
                                                            String classpath,
                                                            Locale locale,
                                                            Charset charset) throws IOException {
        BoundedDiagnosticListener diagnostics = new BoundedDiagnosticListener(MAX_STORED_COMPILER_DIAGNOSTICS);
        // A null Writer here would make javac fall back to System.err for the compiler's own
        // output stream (e.g. usage/internal errors not routed through the DiagnosticListener
        // above). A leaked class-under-test thread that holds the System.err monitor then blocks
        // javac forever in Log.flush -> PrintStream.flush. Captured to a private buffer instead,
        // so compilation can never deadlock on a shared stream; its contents are logged below
        // alongside the diagnostics on failure so nothing is lost.
        StringWriter compilerOutput = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, locale, charset)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(generated);
            List<String> optionList = new ArrayList<>(Arrays.asList(
                    "-classpath", classpath,
                    "-proc:none"));
            CompilationTask task = compiler.getTask(compilerOutput, fileManager, diagnostics,
                    optionList, null, compilationUnits);
            boolean compiled = task.call();
            return CompilerAttemptResult.success(compiled, diagnostics, compilerOutput.toString());
        } catch (RuntimeException e) {
            if (isJavacInfrastructureFailure(e)) {
                return CompilerAttemptResult.infrastructureFailure(e, diagnostics, compilerOutput.toString());
            }
            throw e;
        } catch (Error e) {
            if (isJavacInfrastructureFailure(e)) {
                return CompilerAttemptResult.infrastructureFailure(e, diagnostics, compilerOutput.toString());
            }
            throw e;
        }
    }

    private static void disableCompileCheckDueToInfrastructure(Throwable failure, String compilerClasspath) {
        boolean first = !COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE;
        COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE = true;
        logger.error("Javac infrastructure failure during compile check. "
                        + "Disabling compile filtering for the remainder of this run and keeping generated tests.",
                failure);
        logFileSystemProviderClasspathDiagnostics(compilerClasspath);
        if (first) {
            recordCheckIncomplete("javac infrastructure failure disabled compile filtering: " + failure);
        }
    }

    /** Whether checks are off for the rest of the run: javac infrastructure failed or memory ran out. */
    private static boolean checksSuspended() {
        return COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE || MEMORY_EXHAUSTED_DURING_CHECK;
    }

    public static boolean isCompileCheckDisabledDueToInfrastructure() {
        return COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE;
    }

    /**
     * Whether a check ran out of memory since the last {@link #resetMemoryExhaustion()}; the
     * remaining checks are then skipped and the tests not checked yet are kept.
     *
     * @return true if memory ran out during a check
     */
    public static boolean isMemoryExhaustedDuringCheck() {
        return MEMORY_EXHAUSTED_DURING_CHECK;
    }

    /**
     * Forgets an earlier out-of-memory condition and any recorded check incompleteness, e.g. at
     * the start of a new JUnit check phase.
     */
    public static void resetMemoryExhaustion() {
        MEMORY_EXHAUSTED_DURING_CHECK = false;
        resetCheckIncompleteness();
    }

    private static void stopChecksDueToMemoryExhaustion(int numTests, Throwable failure) {
        boolean first = !MEMORY_EXHAUSTED_DURING_CHECK;
        MEMORY_EXHAUSTED_DURING_CHECK = true;
        if (first) {
            logger.warn("Ran out of memory while compiling {} generated test(s) for the JUnit check ({}). "
                            + "Skipping the remaining checks and keeping the tests not checked yet.",
                    numTests, failure == null ? "no details" : failure.toString());
            recordCheckIncomplete("out of memory during check: " + failure);
        }
    }

    /**
     * Whether any JUnit check round during this run (since the last {@link
     * #resetMemoryExhaustion()}) had to be abandoned before completing.
     *
     * @return true if at least one give-up event was recorded
     */
    public static boolean isCheckIncomplete() {
        return CHECK_INCOMPLETE;
    }

    /**
     * Number of distinct JUnit check rounds that were abandoned before completing during this run
     * (since the last {@link #resetMemoryExhaustion()}).
     *
     * @return the count of recorded give-up events
     */
    public static int getIncompleteCheckRounds() {
        return INCOMPLETE_CHECK_ROUNDS;
    }

    /** Resets the recorded check-incompleteness state and publishes the cleared baseline. */
    private static void resetCheckIncompleteness() {
        CHECK_INCOMPLETE = false;
        INCOMPLETE_CHECK_ROUNDS = 0;
        trackCheckIncompleteness();
    }

    /**
     * The bookkeeping for a JUnit check whose runner was abandoned through its
     * {@link JUnitCheckProgress}, which is what stops the runner: the orphan parks at its next
     * callback, after the rest of the code it is in (a test, its {@code @After}) has run. The kill
     * switch could not do that: it is global and cleared when the check ends.
     *
     * <p>The orphan never calls goingToEndTestCase(). The sandbox lock is taken back on its behalf
     * now rather than making the next test execution discover the leak by burning its whole
     * acquisition timeout.
     *
     * @param runner the runner thread, or null if it never started
     * @param reason why the check was abandoned
     */
    private static void abandonRunner(Thread runner, String reason) {
        Sandbox.abandonThread(runner);
        checkRunnerAbandoned = true;
        recordCheckIncomplete(reason);
    }

    /**
     * Records that a JUnit check round was abandoned before completing: the reason is logged, and
     * the fact reaches the generation report via {@link RuntimeVariable#JUnit_Check_Incomplete}
     * and {@link RuntimeVariable#JUnit_Check_Incomplete_Rounds}. Does not change whether the suite
     * (or the specific tests involved) is kept — that decision is made at each call site, same as
     * before this method existed.
     *
     * <p>Also called by {@code TestSuiteGenerator} for the rounds abandoned before this analyzer is
     * reached at all — the phase skipped for low memory, its time budget expired, its per-test loop
     * exited early, an order probe skipped, or its memory guard tripped. Those exits used to be
     * invisible outside the log, which is indistinguishable from a validated suite.
     *
     * @param reason short description of what was abandoned, for the debug log
     */
    public static void recordCheckIncomplete(String reason) {
        INCOMPLETE_CHECK_ROUNDS++;
        CHECK_INCOMPLETE = true;
        logger.debug("Recording JUnit check as incomplete: {}", reason);
        trackCheckIncompleteness();
    }

    private static void trackCheckIncompleteness() {
        ClientServices.track(RuntimeVariable.JUnit_Check_Incomplete, CHECK_INCOMPLETE);
        ClientServices.track(RuntimeVariable.JUnit_Check_Incomplete_Rounds, INCOMPLETE_CHECK_ROUNDS);
    }

    /**
     * Whether javac reported running out of heap. It catches the error itself and prints it
     * instead of throwing it, e.g. when opening a jar of the classpath fails in
     * {@code ZipFileSystem.initCEN}.
     */
    static boolean reportsOutOfMemory(String compilerOutput) {
        return compilerOutput != null && compilerOutput.contains("java.lang.OutOfMemoryError");
    }

    static boolean isOutOfMemory(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 20) {
            if (current instanceof OutOfMemoryError) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void logCompileCheckDisabledIfNeeded() {
        if (COMPILE_CHECK_DISABLED_LOGGED) {
            return;
        }
        COMPILE_CHECK_DISABLED_LOGGED = true;
        logger.warn("Compile check is disabled due to a prior javac infrastructure failure. "
                + "Generated tests will be kept without compile filtering.");
    }

    private static void logCompileDependentExecutionSkippedIfNeeded() {
        if (!COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE) {
            // Skipped because memory ran out, which was reported when it happened.
            return;
        }
        if (COMPILE_DEPENDENT_EXECUTION_SKIPPED_LOGGED) {
            return;
        }
        COMPILE_DEPENDENT_EXECUTION_SKIPPED_LOGGED = true;
        logger.warn("Skipping compile-dependent JUnit execution checks because compile check is disabled "
                + "after a javac infrastructure failure.");
    }

    private static boolean shouldSkipCompileDependentExecutionAfterCompile() {
        if (!checksSuspended()) {
            return false;
        }
        logCompileDependentExecutionSkippedIfNeeded();
        return true;
    }

    private static boolean hasCompiledClassFilesForGeneratedSources(List<File> generated) {
        if (generated == null || generated.isEmpty()) {
            return true;
        }

        final int maxLoggedMissing = 5;
        List<String> missingClassFiles = new ArrayList<>();
        int missingCount = 0;

        for (File file : generated) {
            if (file == null) {
                continue;
            }
            String absolutePath = file.getAbsolutePath();
            if (!absolutePath.endsWith(JAVA)) {
                continue;
            }
            String classPath = absolutePath.substring(0, absolutePath.length() - JAVA.length()) + CLASS;
            File classFile = new File(classPath);
            if (classFile.isFile()) {
                continue;
            }
            missingCount++;
            if (missingClassFiles.size() < maxLoggedMissing) {
                missingClassFiles.add(classFile.getAbsolutePath());
            }
        }

        if (missingCount == 0) {
            return true;
        }

        logger.error("Compilation reported success but {} expected class file(s) are missing. "
                        + "Examples: {}",
                missingCount, missingClassFiles);
        return false;
    }

    /**
     * Whether javac reported a crash of its own rather than a verdict on the test. It catches
     * {@code OutOfMemoryError} and {@code StackOverflowError} internally and asks the user to file
     * a bug, leaving {@code compiled == false} with no useful diagnostic.
     *
     * @param compilerOutput what javac wrote to its own output stream
     * @return true if the compiler crashed
     */
    private static boolean crashedInsteadOfRejecting(String compilerOutput) {
        if (compilerOutput == null || compilerOutput.isEmpty()) {
            return false;
        }
        // java.lang.OutOfMemoryError is not listed: the caller handles reportsOutOfMemory first.
        return compilerOutput.contains("An exception has occurred in the compiler")
                || compilerOutput.contains("java.lang.StackOverflowError");
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int end = text.indexOf('\n');
        return end < 0 ? text.trim() : text.substring(0, end).trim();
    }

    private static boolean isJavacInfrastructureFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof NoSuchElementException) {
                return true;
            }

            for (StackTraceElement element : current.getStackTrace()) {
                if (element == null) {
                    continue;
                }
                String className = element.getClassName();
                if (className == null) {
                    continue;
                }
                if (className.startsWith("java.util.ServiceLoader")
                        || className.startsWith("java.nio.file.spi.FileSystemProvider")
                        || className.startsWith("com.sun.tools.javac.file.FSInfo")
                        || className.startsWith("jdk.compiler/com.sun.tools.javac.file.FSInfo")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class CompilerAttemptResult {
        private final boolean compiled;
        private final BoundedDiagnosticListener diagnostics;
        private final Throwable infrastructureFailure;
        private final String compilerOutput;

        private CompilerAttemptResult(boolean compiled,
                                      BoundedDiagnosticListener diagnostics,
                                      Throwable infrastructureFailure,
                                      String compilerOutput) {
            this.compiled = compiled;
            this.diagnostics = diagnostics;
            this.infrastructureFailure = infrastructureFailure;
            this.compilerOutput = compilerOutput;
        }

        private static CompilerAttemptResult success(boolean compiled, BoundedDiagnosticListener diagnostics,
                                                      String compilerOutput) {
            return new CompilerAttemptResult(compiled, diagnostics, null, compilerOutput);
        }

        private static CompilerAttemptResult infrastructureFailure(Throwable failure,
                                                                   BoundedDiagnosticListener diagnostics,
                                                                   String compilerOutput) {
            return new CompilerAttemptResult(false, diagnostics, failure, compilerOutput);
        }

        private boolean isCompiled() {
            return compiled;
        }

        private BoundedDiagnosticListener getDiagnostics() {
            return diagnostics;
        }

        private Throwable getInfrastructureFailure() {
            return infrastructureFailure;
        }

        private boolean hasInfrastructureFailure() {
            return infrastructureFailure != null;
        }

        private String getCompilerOutput() {
            return compilerOutput;
        }
    }

    private static void logFileSystemProviderClasspathDiagnostics(String classpath) {
        Map<String, List<String>> providersByEntry = findFileSystemProviderServiceDeclarations(classpath);
        if (providersByEntry.isEmpty()) {
            logger.error("No {} declaration was found on the compiler classpath used during failure analysis",
                    FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE);
        } else {
            logFileSystemProviderDeclarations(
                    "Compiler classpath entry {} declares {} provider(s) via {}: {}",
                    providersByEntry);
        }
        logRuntimeFileSystemProviderDiagnostics();
    }

    private static void logFileSystemProviderDeclarations(String messagePattern,
                                                          Map<String, List<String>> providersByEntry) {
        int logged = 0;
        for (Map.Entry<String, List<String>> entry : providersByEntry.entrySet()) {
            if (logged >= MAX_LOGGED_FILE_SYSTEM_PROVIDER_CLASSPATH_ENTRIES) {
                logger.error("Omitted {} additional classpath entries declaring {}",
                        providersByEntry.size() - logged,
                        FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE);
                break;
            }
            List<String> providerClasses = entry.getValue();
            if (providerClasses.size() > MAX_LOGGED_FILE_SYSTEM_PROVIDER_IMPLEMENTATIONS_PER_ENTRY) {
                List<String> visibleProviders = providerClasses.subList(0,
                        MAX_LOGGED_FILE_SYSTEM_PROVIDER_IMPLEMENTATIONS_PER_ENTRY);
                logger.error(messagePattern + " ({} more omitted)",
                        entry.getKey(),
                        providerClasses.size(),
                        FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE,
                        visibleProviders,
                        providerClasses.size() - visibleProviders.size());
            } else {
                logger.error(messagePattern,
                        entry.getKey(),
                        providerClasses.size(),
                        FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE,
                        providerClasses);
            }
            logged++;
        }
    }

    private static void logRuntimeFileSystemProviderDiagnostics() {
        String defaultProviderProperty = System.getProperty("java.nio.file.spi.DefaultFileSystemProvider");
        logger.error("System property java.nio.file.spi.DefaultFileSystemProvider={}",
                defaultProviderProperty == null ? "<unset>" : defaultProviderProperty);

        String runtimeClasspath = System.getProperty("java.class.path");
        Map<String, List<String>> runtimeProviders = findFileSystemProviderServiceDeclarations(runtimeClasspath);
        if (runtimeProviders.isEmpty()) {
            logger.error("No {} declaration was found on java.class.path",
                    FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE);
        } else {
            logFileSystemProviderDeclarations(
                    "Runtime classpath entry {} declares {} provider(s) via {}: {}",
                    runtimeProviders);
        }

        logVisibleFileSystemProviderServiceResources();
    }

    private static void logVisibleFileSystemProviderServiceResources() {
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (systemLoader == null) {
            logger.error("System class loader is null while checking visible {} resources",
                    FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE);
            return;
        }

        int count = 0;
        try {
            Enumeration<URL> resources = systemLoader.getResources(FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                count++;
                if (count <= MAX_LOGGED_FILE_SYSTEM_PROVIDER_SERVICE_URLS) {
                    logger.error("System class loader resource {} for {}",
                            resource, FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to enumerate {} resources from system class loader: {}",
                    FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE, e.getMessage());
            return;
        }

        if (count == 0) {
            logger.error("System class loader has no visible {} resources",
                    FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE);
            return;
        }
        if (count > MAX_LOGGED_FILE_SYSTEM_PROVIDER_SERVICE_URLS) {
            logger.error("Omitted {} additional {} resources from system class loader",
                    count - MAX_LOGGED_FILE_SYSTEM_PROVIDER_SERVICE_URLS,
                    FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE);
        }
    }

    private static Map<String, List<String>> findFileSystemProviderServiceDeclarations(String classpath) {
        Map<String, List<String>> providersByEntry = new LinkedHashMap<>();
        if (classpath == null || classpath.trim().isEmpty()) {
            return providersByEntry;
        }

        String[] classpathEntries = classpath.split(File.pathSeparator);
        for (String entry : classpathEntries) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            File classpathEntry = new File(entry);
            List<String> providers = readFileSystemProviderServiceDeclarations(classpathEntry);
            if (!providers.isEmpty()) {
                providersByEntry.put(classpathEntry.getAbsolutePath(), providers);
            }
        }
        return providersByEntry;
    }

    private static List<String> readFileSystemProviderServiceDeclarations(File classpathEntry) {
        if (classpathEntry == null || !classpathEntry.exists()) {
            return Collections.emptyList();
        }

        if (classpathEntry.isDirectory()) {
            File serviceFile = new File(classpathEntry, FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE);
            if (!serviceFile.isFile()) {
                return Collections.emptyList();
            }
            try (BufferedReader reader = Files.newBufferedReader(serviceFile.toPath(), StandardCharsets.UTF_8)) {
                return parseServiceProviderDeclarations(reader);
            } catch (IOException e) {
                logger.debug("Could not inspect service declarations in directory {}: {}",
                        classpathEntry.getAbsolutePath(), e.getMessage());
                return Collections.emptyList();
            }
        }

        if (classpathEntry.isFile() && classpathEntry.getName().endsWith(".jar")) {
            try (JarFile jar = new JarFile(classpathEntry)) {
                JarEntry serviceEntry = jar.getJarEntry(FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE);
                if (serviceEntry == null) {
                    return Collections.emptyList();
                }
                try (InputStream in = jar.getInputStream(serviceEntry);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    return parseServiceProviderDeclarations(reader);
                }
            } catch (IOException e) {
                logger.debug("Could not inspect service declarations in jar {}: {}",
                        classpathEntry.getAbsolutePath(), e.getMessage());
            }
        }

        return Collections.emptyList();
    }

    private static List<String> parseServiceProviderDeclarations(BufferedReader reader) throws IOException {
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
            int comment = line.indexOf('#');
            String sanitized = comment >= 0 ? line.substring(0, comment) : line;
            String providerClass = sanitized.trim();
            if (!providerClass.isEmpty()) {
                providers.add(providerClass);
            }
        }
        return new ArrayList<>(providers);
    }

    private static List<String> resolveExtensionInitializationOrderForCompileCheck() {
        if (Properties.TEST_EXTENSION_PRELOAD_INITIALIZED_CLASSES) {
            return buildExtensionInitializationOrder(ClassReInitializer.getInstance().getInitializedClasses());
        }
        if (Properties.TARGET_CLASS != null && !Properties.TARGET_CLASS.trim().isEmpty()) {
            return Collections.singletonList(Properties.TARGET_CLASS.trim());
        }
        return Collections.emptyList();
    }

    private static List<String> buildExtensionInitializationOrder(List<String> observedInitializationOrder) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (Properties.TARGET_CLASS != null && !Properties.TARGET_CLASS.trim().isEmpty()) {
            ordered.add(Properties.TARGET_CLASS.trim());
        }
        if (observedInitializationOrder != null) {
            for (String className : observedInitializationOrder) {
                if (className == null) {
                    continue;
                }
                String trimmed = className.trim();
                if (!trimmed.isEmpty()) {
                    ordered.add(trimmed);
                }
            }
        }

        int maxClasses = Math.max(1, Properties.TEST_EXTENSION_PRELOAD_MAX_CLASSES);
        List<String> limited = new ArrayList<>(Math.min(maxClasses, ordered.size()));
        for (String className : ordered) {
            limited.add(className);
            if (limited.size() >= maxClasses) {
                break;
            }
        }
        return limited;
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

        boolean hasDotSegmentEntries = containsDotSegmentEntry(jarFile);
        boolean hasMalformedManifestClassPath = containsMalformedManifestClassPathEntry(jarFile);
        if (!hasDotSegmentEntries && !hasMalformedManifestClassPath) {
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

    private static boolean containsMalformedManifestClassPathEntry(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes mainAttributes = manifest.getMainAttributes();
            if (mainAttributes == null) {
                return false;
            }
            String classPath = mainAttributes.getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.trim().isEmpty()) {
                return false;
            }

            String[] entries = classPath.trim().split("\\s+");
            for (String manifestEntry : entries) {
                if (manifestEntry == null || manifestEntry.isEmpty()) {
                    continue;
                }
                if (manifestEntry.indexOf('\\') >= 0) {
                    return true;
                }
                try {
                    new URI(manifestEntry);
                } catch (URISyntaxException e) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.debug("Could not inspect jar manifest {} for compiler sanitization: {}",
                    jarFile,
                    e.getMessage());
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

    /**
     * How often a tmp dir is attempted before the check gives up on the step that needs it.
     */
    private static final int TMP_DIR_ATTEMPTS = 4;

    protected static File createNewTmpDir() {
        return createTmpDirIn(new File(FileUtils.getTempDirectoryPath()), TMP_DIR_ATTEMPTS);
    }

    /**
     * Creates a fresh, empty directory below {@code parent}.
     *
     * <p>The directory is created atomically, so a name that is already taken (several JVMs on
     * one node share /tmp) makes the attempt pick another name instead of failing, and a failure
     * of the file system is retried a few times and reported with its reason. A tmp dir that
     * cannot be created costs the JUnit check the compile filter, the instability check or the
     * order-sensitivity run, so it is worth more than one try.</p>
     *
     * @return the directory, or null if none could be created
     */
    static File createTmpDirIn(File parent, int attempts) {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0 && lastFailure != null) {
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Path dir = parent.toPath().resolve("EvoSuite_" + dirCounter.getAndIncrement() + "_"
                    + System.currentTimeMillis() + "_"
                    + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt()));
            try {
                Files.createDirectories(parent.toPath());
                Files.createDirectory(dir);
                return dir.toFile();
            } catch (FileAlreadyExistsException e) {
                if (Files.isDirectory(parent.toPath())) {
                    // The name was taken; the next attempt uses another one
                    lastFailure = null;
                    continue;
                }
                lastFailure = e;
            } catch (IOException | RuntimeException e) {
                lastFailure = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        logger.error("Cannot create tmp dir in {} after {} attempts: {} (usable space: {} bytes)",
                parent, attempts, lastFailure, parent.getUsableSpace());
        return null;
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
        Class<?>[] testClasses = getClassesFromFiles(tests, true);
        if (testClasses == null || testClasses.length == 0) {
            return null;
        }
        List<File> otherClasses = listOnlyFiles(tests);
        /*
         * this is important to force the loading of all files generated
         * in the target folder.
         * If we do not do that, then we will miss all the anonymous classes
         */
        if (otherClasses != null && !otherClasses.isEmpty()) {
            getClassesFromFiles(otherClasses, false);
        }

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
        if (checksSuspended()) {
            logCompileDependentExecutionSkippedIfNeeded();
            return true;
        }

        File dir = createNewTmpDir();
        if (dir == null) {
            logger.warn("Failed to create tmp dir");
            recordCheckIncomplete("verifyCompilationAndExecution: failed to create tmp dir");
            return false;
        }

        try {
            List<File> generated = compileTests(tests, dir);
            if (generated == null) {
                logger.warn("Failed to compile the test cases ");
                return false;
            }
            if (shouldSkipCompileDependentExecutionAfterCompile()) {
                return true;
            }

            //as last step, execute the generated/compiled test cases

            loader = createInitialJUnitClassLoader(tests);
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
            releaseCheckLoader();
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
    private static Class<?>[] getClassesFromFiles(Collection<File> files, boolean failOnLoadError) {
        if (files == null || files.isEmpty()) {
            return new Class<?>[0];
        }
        /*
         * Build-tool integration may provide a legacy scaffolding list file.
         */
        for (String className : extractClassesToInitialize(files)) {
            if (loadClass(className) == null && failOnLoadError) {
                return null;
            }
        }

        /*
         * first load only the scaffolding files
         */
        for (File file : files) {
            if (!isScaffolding(file)) {
                continue;
            }
            if (loadClass(file) == null && failOnLoadError) {
                return null;
            }
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
            if (clazz == null) {
                if (failOnLoadError) {
                    return null;
                }
                continue;
            }
            classes.add(clazz);
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
            testClass = currentLoader().loadClassFromFile(className, fileName);
        } catch (ClassNotFoundException e) {
            logger.error("Failed to load test case " + className + " from file "
                    + file.getAbsolutePath() + " , error " + e, e);
        }
        return testClass;
    }

    private static Class<?> loadClass(String className) {
        try {
            logger.info("Loading class {}", className);
            return currentLoader().loadClass(className);
        } catch (ClassNotFoundException e) {
            logger.error("Cannot load class: " + className, e);
            return null;
        }
    }

    /**
     * Class defining what functionality must be defined for different JUNIT versions.
     */
    private abstract static class VersionDependentAnalyzing {
        abstract JUnitResult runJUnitOnCurrentProcess(Class<?>[] testClasses, JUnitCheckProgress progress);
    }

    /**
     * Define functionality for JUnit 4 Tests.
     */
    private static class JUnit4Analyzing extends VersionDependentAnalyzing {
        @Override
        JUnitResult runJUnitOnCurrentProcess(Class<?>[] testClasses, JUnitCheckProgress progress) {

            JUnitCore runner = new JUnitCore();
            runner.addListener(progress.junit4Listener());

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
            try {
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
                        GuiSupport.forceRestoreHeadlessAfterMockConstructionLeak();
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
                    if (Properties.REPLACE_GUI || RuntimeSettings.mockGUI) {
                        GuiSupport.forceRestoreHeadlessAfterMockConstructionLeak();
                    }
                    if (!wasMockFrameworkEnabled) {
                        MockFramework.disable();
                    }
                }
                JUnitResultBuilder builder = new JUnitResultBuilder();
                return builder.build(result);
            } finally {
                restoreSandboxStateAfterJUnitRun(wasSandboxOn, privileged);
            }
        }
    }


    /**
     * Define functionality for JUnit 5 tests.
     */
    private static class JUnit5Analyzing extends VersionDependentAnalyzing {

        @Override
        JUnitResult runJUnitOnCurrentProcess(Class<?>[] testClasses, JUnitCheckProgress progress) {

            boolean wasSandboxOn = Sandbox.isSecurityManagerInitialized();

            Set<Thread> privileged = null;
            if (wasSandboxOn) {
                privileged = Sandbox.resetDefaultSecurityManager();
            }
            try {
                List<Pair<TestIdentifier, TestExecutionResult>> result = new CopyOnWriteArrayList<>();
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
                        GuiSupport.forceRestoreHeadlessAfterMockConstructionLeak();
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
                    launcher.registerTestExecutionListeners(progress.junit5Listener(result));

                    launcher.execute(request);
                } finally {
                    Thread.currentThread().setContextClassLoader(currentLoader);
                    TestGenerationContext.getInstance().doneWithExecutingSUTCode();
                    org.evosuite.runtime.agent.AgentLoader.setSkipAttach(false);
                    if (disabledHeadless) {
                        GuiSupport.restoreHeadlessAfterMockConstruction();
                    }
                    if (Properties.REPLACE_GUI || RuntimeSettings.mockGUI) {
                        GuiSupport.forceRestoreHeadlessAfterMockConstructionLeak();
                    }
                    if (!wasMockFrameworkEnabled) {
                        MockFramework.disable();
                    }
                }
                JUnitResultBuilder builder = new JUnitResultBuilder();
                return builder.build(result);
            } finally {
                restoreSandboxStateAfterJUnitRun(wasSandboxOn, privileged);
            }
        }
    }

    private static void restoreSandboxStateAfterJUnitRun(boolean wasSandboxOn, Set<Thread> privileged) {
        try {
            if (Sandbox.isSecurityManagerInitialized()) {
                // A test whose @After throws before reaching doneWithExecutingSUTCode() (for
                // example a class reset that fails) leaves this runner thread inside the sandbox:
                // still downgraded, still owning the sandbox lock. The suite's @AfterClass
                // cannot then restore the security manager, so the manager is retained, and the
                // dead runner's lock would stall every later test execution for the whole lock
                // timeout. This is a no-op when the suite left the sandbox cleanly.
                Sandbox.doneWithExecutingSUTCode();
            }
            if (wasSandboxOn) {
                // only activate Sandbox if it was already active before
                if (!Sandbox.isSecurityManagerInitialized()) {
                    Sandbox.initializeSecurityManagerForSUT(privileged);
                    org.evosuite.testcase.execution.ExecutableSnippetEngine.INSTANCE
                            .registerCompilationThreadAsPrivileged();
                    org.evosuite.llm.response.TestRepairLoop
                            .registerParseCompileThreadAsPrivileged();
                }
            } else if (Sandbox.isSecurityManagerInitialized()) {
                logger.warn("EvoSuite problem: tests set up a security manager, "
                        + "but they do not remove it after execution");
                Sandbox.resetDefaultSecurityManager();
            }
        } catch (RuntimeException e) {
            logger.error("Failed to restore sandbox state after JUnit check", e);
        }
    }
}
