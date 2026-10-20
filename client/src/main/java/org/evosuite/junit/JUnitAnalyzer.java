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
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.instrumentation.NonInstrumentingClassLoader;
import org.evosuite.junit.writer.TestSuiteWriter;
import org.evosuite.junit.writer.TestSuiteWriterUtils;
import org.evosuite.runtime.InitializingListener;
import org.evosuite.runtime.InitializingListenerUtils;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.classhandling.ClassStateSupport;
import org.evosuite.runtime.classhandling.JDKClassResetter;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.util.JarPathing;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
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
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static String cachedCompilerClasspath = null;
    private static String cachedCompilerClasspathEvoSuiteInput = null;
    private static String cachedCompilerClasspathTargetInput = null;
    private static final int MAX_STORED_COMPILER_DIAGNOSTICS = 100;
    private static final int MAX_LOGGED_SOURCE_EXCERPT_LINES_PER_FILE = 200;
    private static final int MAX_LOGGED_SOURCE_EXCERPT_CHARS_TOTAL = 20_000;
    private static final String FILE_SYSTEM_PROVIDER_SERVICE_RESOURCE =
            "META-INF/services/java.nio.file.spi.FileSystemProvider";
    private static final int MAX_LOGGED_FILE_SYSTEM_PROVIDER_CLASSPATH_ENTRIES = 30;
    private static final int MAX_LOGGED_FILE_SYSTEM_PROVIDER_IMPLEMENTATIONS_PER_ENTRY = 10;
    private static final int MAX_LOGGED_FILE_SYSTEM_PROVIDER_SERVICE_URLS = 30;
    /**
     * The class loader of the JUnit check in progress. Every check gets a fresh one, which is
     * released (see {@link #releaseCheckLoader()}) once the check is over, so that the classes
     * it instrumented do not accumulate across the many checks of post-processing.
     */
    private static InstrumentingClassLoader loader = null;


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
        if (JUnitCheckState.isCompileCheckDisabled()) {
            logCompileCheckDisabledIfNeeded();
            return;
        }

        Iterator<TestCase> iter = tests.iterator();

        while (iter.hasNext()) {
            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                JUnitCheckState.recordIncomplete("ran out of time in the compile filter; remaining test(s) kept unchecked");
                break;
            }
            if (JUnitCheckState.isMemoryExhausted()) {
                break;
            }

            TestCase test = iter.next();

            File dir = createNewTmpDir();
            if (dir == null) {
                logger.warn("Failed to create tmp dir");
                // Infrastructure setup failure, not a judgment on the remaining test(s): they are
                // kept unchecked, so record that the compile filter never actually ran on them.
                JUnitCheckState.recordIncomplete("compile filter: failed to create tmp dir; remaining test(s) kept unchecked");
                return;
            }
            logger.debug("Created tmp folder: " + dir.getAbsolutePath());

            try {
                List<TestCase> singleList = new ArrayList<>();
                singleList.add(test);
                CompileOutcome compiled = compileTests(singleList, dir);
                if (compiled.isRejected()) {
                    iter.remove();
                    String code = test.toCode();
                    logger.error("Failed to compile test case:\n" + code);
                } else if (compiled.isNotJudged()) {
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

        logger.info("Going to execute: handleTestsThatAreUnstable");

        if (tests == null || tests.isEmpty()) { //nothing to do
            return 0;
        }
        return withCompiledSuite(tests, new InstabilityCheck(tests));
    }

    /**
     * The instability check of {@link #handleTestsThatAreUnstable(List)}: it counts the unstable
     * tests, and marks or removes them in the list it checks.
     */
    private static final class InstabilityCheck extends CompiledSuiteCheck<Integer> {

        private final List<TestCase> tests;
        private int numUnstable = 0;

        private InstabilityCheck(List<TestCase> tests) {
            this.tests = tests;
        }

        @Override
        Integer notRun() {
            return numUnstable;
        }

        @Override
        Integer noTmpDir() {
            logger.error("Failed to create tmp dir");
            // Infrastructure setup failure: the test(s) are kept unchecked, exactly as the
            // compile filter's own tmp-dir failure does, so record it the same way.
            JUnitCheckState.recordIncomplete("instability check: failed to create tmp dir; test(s) kept unchecked");
            return numUnstable;
        }

        @Override
        Integer rejected() {
            /*
             * By this point each test already passed the single-test compile filter
             * individually, so this is not that filter's ordinary "rejected test" verdict:
             * it means the whole-suite compile unit (or the writer output it depends on)
             * failed in a way the per-test filter never saw. The suite is kept unchecked,
             * so record that this round never actually validated it, same as the
             * tmp-dir/time-budget give-ups. Infrastructure failures that compileTests
             * already recorded itself (OOM, javac crashes, etc.) are not judged rather than
             * rejected, and are not re-recorded here.
             */
            logger.warn("Failed to compile the test cases ");
            JUnitCheckState.recordIncomplete(
                    "instability check: whole-suite compile failed unexpectedly; test(s) kept unchecked");
            return numUnstable;
        }

        @Override
        Integer beforeLoading() {
            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                logger.error("Ran out of time while checking tests");
                JUnitCheckState.recordIncomplete("ran out of time before running the instability check");
                return numUnstable;
            }
            return null;
        }

        @Override
        Integer notLoaded() {
            // Compilation already succeeded here, so this is an unexpected loading failure,
            // not an ordinary compile rejection: the suite is kept unchecked and the round
            // never validated it.
            logger.error("Found no classes for compiled tests");
            JUnitCheckState.recordIncomplete(
                    "instability check: failed to load compiled test classes; test(s) kept unchecked");
            return numUnstable;
        }

        @Override
        Integer judge(JUnitResult result, Class<?>[] testClasses) {
            if (result.wasSuccessful()) {
                return numUnstable; //everything is OK
            }

            AtomicBoolean watchdogCulpritSeen = new AtomicBoolean(false);
            // Test names are positional (and zero-padded by list size), so they are only valid
            // for the list that was written out. Resolve every failure against this snapshot and
            // remove from "tests" by identity; re-deriving names from the shrinking list would
            // point later failures at different tests.
            final List<TestCase> written = new ArrayList<>(tests);
            failure_loop:
            for (JUnitFailure failure : result.getFailures()) {
                String testName = failure.getDescriptionMethodName();//TODO check if correct
                // A test that hung is not one whose failure was expected: a test recorded as
                // failing during the search may well be the one that was killed there.
                for (int i = 0; i < written.size(); i++) {
                    if (TestSuiteWriterUtils.getNameOfTest(written, i).equals(testName)) {
                        if (JUnitFailurePolicy.isExpectedFailure(written.get(i), failure)) {
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
                if (JUnitFailurePolicy.isAgentAttachFailure(testName, failure)) {
                    logger.warn("Likely error with EvoSuite instrumentation, ignoring failure in test execution");
                    continue failure_loop;
                }

                // TooManyResourcesException during JUnit check means the SUT hit the
                // per-loop iteration cap (RuntimeSettings.maxNumberOfIterationsPerLoop).
                // During search this cap is also active, so any test that ran to
                // completion during generation must also have terminated this way; the
                // rerun is reproducing the same termination, not exposing a new bug.
                // Treat the test as valid and keep it in the suite.
                if (JUnitFailurePolicy.isResourceLimitFailure(failure, testClasses)) {
                    logger.debug("Ignoring expected TooManyResourcesException for test {} "
                            + "(SUT loop terminated by iteration cap, same as during search): {}",
                            testName, failure.getMessage());
                    continue failure_loop;
                }
                // In JUnit re-checks, watchdog timeouts can race with loop-cap termination.
                // Treat TimeoutException as equivalent to TooManyResourcesException to avoid
                // dropping tests solely due to phase-specific watchdog scheduling.
                if (JUnitFailurePolicy.isTimeoutException(failure)) {
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
                if (JUnitFailurePolicy.isWatchdogCasualty(result, failure, watchdogCulpritSeen)) {
                    logger.warn("Keeping test {}: it timed out only because the JUnit-check watchdog "
                            + "had already stopped the run", testName);
                    continue failure_loop;
                }

                // Class initialization failures are deterministic environmental issues
                // (e.g., broken static initializers) rather than assertion instability.
                // Remove those tests, but do not count them as unstable.
                if (JUnitFailurePolicy.isClassInitializerFailure(failure)) {
                    logger.warn("Removing test {} due deterministic class-initializer failure: {}: {}",
                            testName, failure.getExceptionClassName(), failure.getMessage());
                    removeTestByName(tests, written, testName);
                    continue failure_loop;
                }
                if (JUnitFailurePolicy.isDeterministicGuiEnvironmentFailure(failure)) {
                    logger.warn("Removing test {} due deterministic GUI-environment failure: {}: {}",
                            testName, failure.getExceptionClassName(), failure.getMessage());
                    removeTestByName(tests, written, testName);
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
                for (int i = 0; i < written.size(); i++) {
                    if (TestSuiteWriterUtils.getNameOfTest(written, i).equals(testName)) {
                        final TestCase failingTest = written.get(i);
                        logger.warn("Failing test:\n " + failingTest.toCode());
                        // An unstable test that is kept is written with the stubs its code shows
                        FunctionalMockStatement.completePendingStubsWithNulls(failingTest);
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
                            failingTest.setUnstable(true);
                        } else {
                            logger.debug("Going to remove unstable test: " + testName);
                            removeByIdentity(tests, failingTest);
                        }
                        break;
                    }
                }
            }

            //if we arrive here, then it means at least one test was unstable
            return numUnstable;
        }

        @Override
        Integer failed(Exception e) {
            logger.error("" + e, e);
            return numUnstable;
        }

        @Override
        void notDeleted(File dir, Exception e) {
            logger.warn("Cannot delete tmp dir: " + dir.getName(), e);
        }
    }

    private static void removeTestByName(List<TestCase> tests, List<TestCase> written, String testName) {
        for (int i = 0; i < written.size(); i++) {
            if (TestSuiteWriterUtils.getNameOfTest(written, i).equals(testName)) {
                removeByIdentity(tests, written.get(i));
                return;
            }
        }
    }

    private static void removeByIdentity(List<TestCase> tests, TestCase test) {
        for (int i = 0; i < tests.size(); i++) {
            if (tests.get(i) == test) {
                tests.remove(i);
                return;
            }
        }
    }

    private static JUnitResult runTests(Class<?>[] testClasses, File testClassDir, Collection<File> generatedFiles)
            throws JUnitExecutionException {
        ClassStateSupport.clearNonInstrumentedClassDetectionFlag();
        JUnitResult result = JUnitCheckRunner.run(testClasses);

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

        // An abandoned runner may still be executing through the current loader. A retry would
        // release its own loader in the abandoned runner's name and clear the abandonment, and
        // the original loader would then be released under the orphan.
        if (JUnitCheckRunner.isCheckRunnerAbandoned()) {
            return result;
        }

        boolean needsInstrumentedRetry = JUnitFailurePolicy.shouldRetryWithInstrumentingAfterFailure(result);

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
            JUnitResult retriedResult = JUnitCheckRunner.run(retriedClasses);
            if (ClassStateSupport.hadNonInstrumentedClassDetection()) {
                logger.warn("Retry still observed non-instrumented classes during JUnit check");
            }
            return retriedResult;
        } finally {
            releaseCheckLoader();
            loader = savedLoader;
        }
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




    private static String buildUnstableDiagnostics(String testName, JUnitFailure failure) {
        List<String> trace = failure.getExceptionStackTrace();
        String category = classifyUnstableFailure(testName, trace);
        String firstInterestingFrame = firstInterestingFrame(trace);
        String firstLoopCounterFrame = firstFrameContaining(trace, "org.evosuite.runtime.LoopCounter.checkLoop");
        String firstHeadlessFrame = firstFrameContaining(trace, "java.awt.HeadlessException");
        String firstAwtFrame = firstFrameContaining(trace, "java.awt.");
        String failureClass = JUnitFailurePolicy.normalizeFailureClassName(failure.getExceptionClassName());
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
                && JUnitFailurePolicy.isGraphicsEnvironmentNullFailure(failureMessage, trace)) {
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
        if (JUnitFailurePolicy.containsAny(trace,
                "org.evosuite.runtime.EvoSuiteExtension.beforeEach",
                "org.evosuite.runtime.EvoSuiteExtension.afterEach",
                "org.evosuite.runtime.classhandling.ClassResetter",
                "org.evosuite.runtime.classhandling.JDKClassResetter",
                "org.evosuite.runtime.classhandling.ClassStateSupport")) {
            return "extension_or_reset";
        }
        if (JUnitFailurePolicy.containsAny(trace,
                "org.junit.jupiter.api.Assertions",
                "org.junit.Assert")) {
            return "assertion";
        }
        if (JUnitFailurePolicy.containsAny(trace, "." + testName + "(")) {
            return "test_body";
        }
        if (JUnitFailurePolicy.containsAny(trace, "org.evosuite.runtime.LoopCounter.checkLoop")) {
            return "loop_counter_no_user_frame";
        }
        return "other_runtime";
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
        return trace != null && JUnitFailurePolicy.containsAny(trace, "java.lang.reflect.Method.invoke");
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
        if (JUnitCheckRunner.consumeCheckRunnerAbandoned()) {
            // An orphan runner may still be loading classes through this loader and writing
            // to its pools; leave them alone rather than race it.
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
        return withCompiledSuite(tests, new CompiledSuiteCheck<Set<String>>() {
            @Override
            Set<String> notRun() {
                return Collections.emptySet();
            }

            @Override
            Set<String> noTmpDir() {
                // Same contract as the compile filter and instability check: the
                // order-sensitivity probe never actually ran, so record it as abandoned rather
                // than let the caller's infrastructure-failure heuristic on "tmp-dir-error" be the
                // only trace of it.
                JUnitCheckState.recordIncomplete("order-sensitivity analysis: failed to create tmp dir");
                return Collections.singleton("tmp-dir-error");
            }

            @Override
            Set<String> rejected() {
                // Same reasoning as the instability check: these tests already passed the
                // per-test compile filter, so a rejection here is the whole-suite compile unit
                // (or its writer output) failing in a way that filter never saw, not an ordinary
                // rejected-test verdict.
                JUnitCheckState.recordIncomplete("order-sensitivity analysis: whole-suite compile failed unexpectedly");
                return Collections.singleton("compilation-error");
            }

            @Override
            Set<String> notLoaded() {
                JUnitCheckState.recordIncomplete("order-sensitivity analysis: failed to load compiled test classes");
                return Collections.singleton("load-error");
            }

            @Override
            Set<String> judge(JUnitResult result, Class<?>[] testClasses) {
                if (result.wasSuccessful()) {
                    return Collections.emptySet();
                }
                return mapUnexpectedFailureNames(result, tests, testClasses);
            }

            @Override
            Set<String> failed(Exception e) {
                logger.warn("Order-sensitivity analysis failed: {}", e.getMessage());
                JUnitCheckState.recordIncomplete("order-sensitivity analysis: unexpected exception during execution: " + e);
                return Collections.singleton("execution-error");
            }

            @Override
            void notDeleted(File dir, Exception e) {
                logger.warn("Cannot delete tmp dir in order-sensitivity analysis: {}", dir.getName(), e);
            }
        });
    }

    private static Set<String> mapUnexpectedFailureNames(JUnitResult result, List<TestCase> tests,
                                                          Class<?>[] testClasses) {
        Map<String, TestCase> testByName = new LinkedHashMap<>();
        for (int i = 0; i < tests.size(); i++) {
            testByName.put(TestSuiteWriterUtils.getNameOfTest(tests, i), tests.get(i));
        }

        Set<String> failures = new LinkedHashSet<>();
        AtomicBoolean watchdogCulpritSeen = new AtomicBoolean(false);
        for (JUnitFailure failure : result.getFailures()) {
            String testName = failure.getDescriptionMethodName();
            if (testName == null) {
                failures.add("suite-initialization");
                continue;
            }
            TestCase mapped = testByName.get(testName);
            if (mapped != null && JUnitFailurePolicy.isExpectedFailure(mapped, failure)) {
                continue;
            }
            if (JUnitFailurePolicy.isAgentAttachFailure(testName, failure)
                    || JUnitFailurePolicy.isResourceLimitFailure(failure, testClasses)
                    || JUnitFailurePolicy.isTimeoutException(failure)
                    || JUnitFailurePolicy.isWatchdogCasualty(result, failure, watchdogCulpritSeen)) {
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

    /** Why javac gave no verdict on the tests it was asked to compile. */
    enum NotJudgedReason {
        /** Compile checks are off for the rest of the run after a javac infrastructure failure. */
        COMPILE_CHECK_DISABLED,
        /** The heap, which javac shares with this client, ran out. */
        OUT_OF_MEMORY
    }

    /**
     * The result of compiling generated tests for a JUnit check: they compiled, javac rejected
     * them, or javac could not judge them. Tests that were not judged are kept unchecked, and so
     * are all tests checked after them, as the conditions that stop javac judging last for the
     * rest of the phase (out of memory) or the run (infrastructure failure).
     */
    static final class CompileOutcome {

        static final CompileOutcome REJECTED = new CompileOutcome(null, null);

        private final List<File> files;
        private final NotJudgedReason notJudgedReason;

        private CompileOutcome(List<File> files, NotJudgedReason notJudgedReason) {
            this.files = files;
            this.notJudgedReason = notJudgedReason;
        }

        static CompileOutcome compiled(List<File> files) {
            return new CompileOutcome(files, null);
        }

        static CompileOutcome notJudged(NotJudgedReason reason) {
            return new CompileOutcome(null, reason);
        }

        boolean isCompiled() {
            return files != null;
        }

        boolean isRejected() {
            return this == REJECTED;
        }

        boolean isNotJudged() {
            return notJudgedReason != null;
        }

        NotJudgedReason getNotJudgedReason() {
            return notJudgedReason;
        }

        /** The written source files, which javac compiled into class files next to them. */
        List<File> getFiles() {
            return files;
        }
    }

    private static CompileOutcome compileTests(List<TestCase> tests, File dir) {

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

        String compilerClasspath = null;
        try {
            //now generate the JUnit test case
            List<File> generated = suite.writeTestSuite(name, dir.getAbsolutePath(), Collections.EMPTY_LIST);
            for (File file : generated) {
                if (!file.exists()) {
                    logger.error("Supposed to generate " + file
                            + " but it does not exist");
                    return CompileOutcome.REJECTED;
                }
            }

            //try to compile the test cases
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                logger.error("No Java compiler is available");
                return CompileOutcome.REJECTED;
            }
            if (JUnitCheckState.isCompileCheckDisabled()) {
                logCompileCheckDisabledIfNeeded();
                return CompileOutcome.notJudged(NotJudgedReason.COMPILE_CHECK_DISABLED);
            }

            Locale locale = Locale.getDefault();
            Charset charset = StandardCharsets.UTF_8;
            compilerClasspath = compilerClasspathForGeneratedTests();

            CompilerAttemptResult attempt =
                    runCompilerAttempt(compiler, generated, compilerClasspath, locale, charset);
            if (attempt.hasInfrastructureFailure()) {
                if (isOutOfMemory(attempt.getInfrastructureFailure())) {
                    stopChecksDueToMemoryExhaustion(tests.size(), attempt.getInfrastructureFailure());
                    return CompileOutcome.notJudged(NotJudgedReason.OUT_OF_MEMORY);
                }
                disableCompileCheckDueToInfrastructure(attempt.getInfrastructureFailure(), compilerClasspath);
                return CompileOutcome.notJudged(NotJudgedReason.COMPILE_CHECK_DISABLED);
            }

            if (!attempt.isCompiled()) {
                BoundedDiagnosticListener diagnostics = attempt.getDiagnostics();
                String compilerOutput = attempt.getCompilerOutput();
                if (reportsOutOfMemory(compilerOutput)) {
                    // javac ran out of heap, which it shares with this client: the tests were
                    // never judged, and what is exhausted is the heap, not javac.
                    stopChecksDueToMemoryExhaustion(tests.size(),
                            new OutOfMemoryError("javac: " + firstLine(compilerOutput)));
                    return CompileOutcome.notJudged(NotJudgedReason.OUT_OF_MEMORY);
                }
                logger.error("Compilation failed on {} generated test file(s)", generated.size());
                logger.error("Classpath: {}", compilerClasspath);

                if (diagnostics.sawErrorWhileWriting()) {
                    disableCompileCheckDueToInfrastructure(
                            new IllegalStateException("javac could not write compiled test artifacts"),
                            compilerClasspath);
                    return CompileOutcome.notJudged(NotJudgedReason.COMPILE_CHECK_DISABLED);
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
                // would count warning-only output as a compile failure.
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
                    return CompileOutcome.notJudged(NotJudgedReason.COMPILE_CHECK_DISABLED);
                }

                logGeneratedSourceExcerptsOnCompilationFailure(generated);
                return CompileOutcome.REJECTED;
            }
            if (!hasCompiledClassFilesForGeneratedSources(generated)) {
                disableCompileCheckDueToInfrastructure(
                        new IllegalStateException(
                                "javac reported success but produced no .class files for generated tests"),
                        compilerClasspath);
                return CompileOutcome.notJudged(NotJudgedReason.COMPILE_CHECK_DISABLED);
            }
            return CompileOutcome.compiled(generated);

        } catch (OutOfMemoryError e) {
            // Not a verdict on the tests: reporting them as not compiling would delete them.
            stopChecksDueToMemoryExhaustion(tests.size(), e);
            return CompileOutcome.notJudged(NotJudgedReason.OUT_OF_MEMORY);
        } catch (IOException e) {
            logger.error("" + e, e);
            return CompileOutcome.REJECTED;
        } catch (RuntimeException e) {
            if (isOutOfMemory(e)) {
                stopChecksDueToMemoryExhaustion(tests.size(), e);
                return CompileOutcome.notJudged(NotJudgedReason.OUT_OF_MEMORY);
            }
            if (isJavacInfrastructureFailure(e)) {
                disableCompileCheckDueToInfrastructure(e, compilerClasspath);
                return CompileOutcome.notJudged(NotJudgedReason.COMPILE_CHECK_DISABLED);
            }
            logger.error("Unexpected runtime exception while compiling generated tests", e);
            return CompileOutcome.REJECTED;
        } catch (Error e) {
            if (isOutOfMemory(e)) {
                stopChecksDueToMemoryExhaustion(tests.size(), e);
                return CompileOutcome.notJudged(NotJudgedReason.OUT_OF_MEMORY);
            }
            if (isJavacInfrastructureFailure(e)) {
                disableCompileCheckDueToInfrastructure(e, compilerClasspath);
                return CompileOutcome.notJudged(NotJudgedReason.COMPILE_CHECK_DISABLED);
            }
            throw e;
        }
    }

    /**
     * The classpath javac compiles the generated tests against: the project classpath followed by
     * EvoSuite's own, with pathing jars expanded and malformed jars sanitized. It is computed
     * again only when the classpaths it is built from change.
     */
    static synchronized String compilerClasspathForGeneratedTests() {
        String evosuiteCP = ClassPathHandler.getInstance().getEvoSuiteClassPath();
        String targetProjectCP = ClassPathHandler.getInstance().getTargetProjectClasspath();
        if (cachedCompilerClasspath != null
                && Objects.equals(evosuiteCP, cachedCompilerClasspathEvoSuiteInput)
                && Objects.equals(targetProjectCP, cachedCompilerClasspathTargetInput)) {
            return cachedCompilerClasspath;
        }
        String expandedEvoSuiteCP = evosuiteCP;
        if (JarPathing.containsAPathingJar(expandedEvoSuiteCP)) {
            expandedEvoSuiteCP = JarPathing.expandPathingJars(expandedEvoSuiteCP);
        }
        String expandedTargetProjectCP = targetProjectCP;
        if (JarPathing.containsAPathingJar(expandedTargetProjectCP)) {
            expandedTargetProjectCP = JarPathing.expandPathingJars(expandedTargetProjectCP);
        }
        String classpath = sanitizeClasspathForCompiler(
                expandedTargetProjectCP + File.pathSeparator + expandedEvoSuiteCP);
        cachedCompilerClasspathEvoSuiteInput = evosuiteCP;
        cachedCompilerClasspathTargetInput = targetProjectCP;
        cachedCompilerClasspath = classpath;
        return classpath;
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
        boolean first = JUnitCheckState.disableCompileCheck();
        logger.error("Javac infrastructure failure during compile check. "
                        + "Disabling compile filtering for the remainder of this run and keeping generated tests.",
                failure);
        logFileSystemProviderClasspathDiagnostics(compilerClasspath);
        if (first) {
            JUnitCheckState.recordIncomplete("javac infrastructure failure disabled compile filtering: " + failure);
        }
    }

    private static void stopChecksDueToMemoryExhaustion(int numTests, Throwable failure) {
        boolean first = JUnitCheckState.markMemoryExhausted();
        if (first) {
            logger.warn("Ran out of memory while compiling {} generated test(s) for the JUnit check ({}). "
                            + "Skipping the remaining checks and keeping the tests not checked yet.",
                    numTests, failure == null ? "no details" : failure.toString());
            JUnitCheckState.recordIncomplete("out of memory during check: " + failure);
        }
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
        if (!JUnitCheckState.claimCompileCheckDisabledLog()) {
            return;
        }
        logger.warn("Compile check is disabled due to a prior javac infrastructure failure. "
                + "Generated tests will be kept without compile filtering.");
    }

    private static void logCompileDependentExecutionSkippedIfNeeded() {
        if (!JUnitCheckState.isCompileCheckDisabled()) {
            // Skipped because memory ran out, which was reported when it happened.
            return;
        }
        if (!JUnitCheckState.claimCompileDependentExecutionSkippedLog()) {
            return;
        }
        logger.warn("Skipping compile-dependent JUnit execution checks because compile check is disabled "
                + "after a javac infrastructure failure.");
    }

    private static void logCompileDependentExecutionSkippedIfNeeded(CompileOutcome compiled) {
        if (compiled.getNotJudgedReason() == NotJudgedReason.COMPILE_CHECK_DISABLED) {
            logCompileDependentExecutionSkippedIfNeeded();
        }
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

    /**
     * Orders the classes whose static state the JUnit 5 extension re-initializes: the target
     * class first, then the observed initialization order without blanks or duplicates, capped at
     * {@link Properties#TEST_EXTENSION_PRELOAD_MAX_CLASSES} (at least one).
     */
    public static List<String> buildExtensionInitializationOrder(List<String> observedInitializationOrder) {
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

        boolean hasDotSegmentEntries = CompilerClasspath.hasDotSegmentEntry(jarFile);
        boolean hasMalformedManifestClassPath = CompilerClasspath.hasMalformedManifestClassPath(jarFile);
        if (!hasDotSegmentEntries && !hasMalformedManifestClassPath) {
            synchronized (SANITIZED_COMPILER_CLASSPATH_ENTRIES) {
                SANITIZED_COMPILER_CLASSPATH_ENTRIES.put(jarFile.getAbsolutePath(), jarFile.getAbsolutePath());
            }
            return jarFile.getAbsolutePath();
        }

        // Dot-segment names are dropped because javac cannot open such entries.
        String sanitized = CompilerClasspath.extractJar(jarFile, "evosuite-compiler-cp-",
                CompilerClasspath::hasDotSegment);
        synchronized (SANITIZED_COMPILER_CLASSPATH_ENTRIES) {
            SANITIZED_COMPILER_CLASSPATH_ENTRIES.put(jarFile.getAbsolutePath(), sanitized);
        }
        if (!jarFile.getAbsolutePath().equals(sanitized)) {
            logger.warn("Using sanitized compiler classpath entry for malformed jar {} -> {}",
                    jarFile.getAbsolutePath(), sanitized);
        }
        return sanitized;
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
     * A check that compiles a whole suite into a fresh directory, loads it with a fresh check
     * loader and runs it; see {@link #withCompiledSuite(List, CompiledSuiteCheck)}. Each way of
     * not getting as far as running the suite is handed to the check, which decides what it
     * means and whether the check is left incomplete.
     *
     * @param <R> what the check returns
     */
    private abstract static class CompiledSuiteCheck<R> {

        /** Checks are suspended, or javac could not judge the suite. */
        abstract R notRun();

        /** No directory to compile into could be created. */
        abstract R noTmpDir();

        /** javac rejected the suite. */
        abstract R rejected();

        /**
         * Called once the suite has compiled, before it is loaded.
         *
         * @return what to return without running the suite, or null to go on
         */
        R beforeLoading() {
            return null;
        }

        /** The compiled suite could not be loaded. */
        abstract R notLoaded();

        /** Judges the result of running the suite. */
        abstract R judge(JUnitResult result, Class<?>[] testClasses) throws Exception;

        /** Compiling, loading, running or judging the suite threw. */
        abstract R failed(Exception e);

        /** The directory the suite was compiled into could not be deleted afterwards. */
        abstract void notDeleted(File dir, Exception e);
    }

    /**
     * Compiles the tests into a fresh directory, loads them with a fresh check loader and runs
     * them, and hands each outcome to the check. The loader is released and the directory
     * deleted afterwards.
     *
     * @param tests the suite to check
     * @param check what to make of each outcome
     * @param <R>   what the check returns
     * @return what the check made of the outcome
     */
    private static <R> R withCompiledSuite(List<TestCase> tests, CompiledSuiteCheck<R> check) {
        if (JUnitCheckState.isSuspended()) {
            logCompileDependentExecutionSkippedIfNeeded();
            return check.notRun();
        }
        File dir = createNewTmpDir();
        if (dir == null) {
            return check.noTmpDir();
        }
        logger.debug("Created tmp folder: " + dir.getAbsolutePath());

        try {
            CompileOutcome compiled = compileTests(tests, dir);
            if (compiled.isRejected()) {
                return check.rejected();
            }
            if (compiled.isNotJudged()) {
                logCompileDependentExecutionSkippedIfNeeded(compiled);
                return check.notRun();
            }
            R withoutRunning = check.beforeLoading();
            if (withoutRunning != null) {
                return withoutRunning;
            }

            // Create a new classloader so that each test gets freshly loaded classes.
            // Start with a NonInstrumentingClassLoader to avoid duplicate type
            // identities across loader boundaries during JUnit recheck. If we
            // detect instrumentation/GUI mismatch signals, runTests() retries
            // once with InstrumentingClassLoader.
            loader = createInitialJUnitClassLoader(tests);
            Class<?>[] testClasses = loadTests(compiled.getFiles());
            if (testClasses == null) {
                return check.notLoaded();
            }
            return check.judge(runTests(testClasses, dir, compiled.getFiles()), testClasses);
        } catch (Exception e) {
            return check.failed(e);
        } finally {
            releaseCheckLoader();
            //let's be sure we clean up all what we wrote on disk
            try {
                FileUtils.deleteDirectory(dir);
            } catch (Exception e) {
                check.notDeleted(dir, e);
            }
        }
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
}
