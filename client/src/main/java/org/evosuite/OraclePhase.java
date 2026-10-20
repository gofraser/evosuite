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

import org.evosuite.TestSuiteGenerator.PhaseSkipCause;
import org.evosuite.classpath.ClassPathHacker;
import org.evosuite.junit.JUnitAnalyzer;
import org.evosuite.junit.JUnitCheckState;
import org.evosuite.llm.postprocess.LlmPostProcessingPhaseContext;
import org.evosuite.llm.postprocess.LlmPostProcessor;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.statistics.StatisticsSender;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testsuite.MinimizationResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The part of post-processing that decides a suite's oracles: standard assertion generation,
 * unified LLM post-processing, and the final JUnit compile-and-run check.
 */
final class OraclePhase {

    private static final Logger logger = LoggerFactory.getLogger(OraclePhase.class);

    private OraclePhase() {
    }

    /** Apply assertions, unified LLM post-processing, and final JUnit validation. */
    static void run(TestSuiteChromosome testSuite,
                    MinimizationResult minimizationResult,
                    ReplayStructureSnapshot replayStructure) {

        // Create the phase and perform its cheap availability preflight before
        // deciding whether it is safe to suppress EvoSuite's normal oracles.
        LlmPostProcessor postProcessor = new LlmPostProcessor();
        LlmPostProcessingPhaseContext postProcessingContext =
                postProcessor.createPhaseContext();
        boolean generateStandardAssertions = shouldGenerateStandardAssertions(
                postProcessor.canStartUnifiedPostProcessing(postProcessingContext));
        if (generateStandardAssertions) {
            // Assertion generation and validation can reinstrument the SUT and
            // change the class loader stored in statements. Keep assertion-free
            // canonical clones and transfer only retained assertions afterward.
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Generating assertions");
            // progressMonitor.setCurrentPhase("Generating assertions");
            ClientServices.getInstance().getClientNode().changeState(ClientState.ASSERTION_GENERATION);
            PhaseSkipCause skipCause = TestSuiteGenerator.checkPhase("assertion generation",
                    TimeController.getInstance()::hasTimeToExecuteATestCase);
            if (skipCause == PhaseSkipCause.TIMEOUT) {
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Skipping assertion generation because not enough time is left");
            } else if (skipCause == PhaseSkipCause.NONE) {
                TestSuiteGeneratorHelper.addAssertions(testSuite);
            }
            StatisticsSender.sendIndividualToMaster(testSuite); // FIXME: can we
            // pass the list
            // of
            // testsuitechromosomes?
        }

        if (replayStructure != null && isMutationLlmReplay()) {
            // Mutation assertion generation may simplify or replace working
            // statements. Move only its retained assertions onto the pristine
            // replay structure before the LLM sees the suite, then retain that
            // mutation oracle as the fallback if final LLM/JUnit validation
            // rejects a hybrid test.
            replayStructure.restore(testSuite);
            replayStructure = ReplayStructureSnapshot.captureWithFallbackAssertions(testSuite);
        }

        int llmPostProcessingAssertionsApplied = 0;
        // Keep one explicit post-processing session from phase entry through
        // final JUnit reconciliation, including skipped/failed phase paths.
        if (Properties.LLM_POSTPROCESSING_ENABLED) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Running unified LLM post-processing");
            ClientServices.getInstance().getClientNode().changeState(ClientState.LLM_POSTPROCESSING);
            PhaseSkipCause skipCause = TestSuiteGenerator.checkPhase("unified LLM post-processing",
                    TimeController.getInstance()::isThereStillTimeInThisPhase);
            if (skipCause == PhaseSkipCause.TIMEOUT) {
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Skipping unified LLM post-processing because not enough time is left");
                postProcessor.publishSkippedPostProcessingMetrics(
                        postProcessingContext, "timeout", minimizationResult);
            } else if (skipCause == PhaseSkipCause.LOW_MEMORY) {
                postProcessor.publishSkippedPostProcessingMetrics(
                        postProcessingContext, "low_memory", minimizationResult);
            } else {
                try {
                    llmPostProcessingAssertionsApplied =
                            postProcessor.runUnifiedPostProcessing(
                                    testSuite, minimizationResult, postProcessingContext);
                } catch (RuntimeException e) {
                    LoggingUtils.getEvoLogger().warn(
                            "Unified LLM post-processing failed; continuing without changes", e);
                    postProcessor.publishSkippedPostProcessingMetrics(
                            postProcessingContext, "phase_failure", minimizationResult);
                }
            }
        } else {
            postProcessor.publishSkippedPostProcessingMetrics(
                    postProcessingContext, "disabled", minimizationResult);
        }

        if (!generateStandardAssertions
                && Properties.ASSERTIONS
                && postProcessingContext.requiresStandardAssertionFallback()) {
            addStandardAssertionsForTestsWithoutOracle(testSuite,
                    postProcessingContext);
        }

        if (Properties.NO_RUNTIME_DEPENDENCY) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Property NO_RUNTIME_DEPENDENCY is set to true - skipping JUnit compile check");
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "WARNING: Not including the runtime dependencies is likely to lead to flaky tests!");
        } else if (Properties.JUNIT_TESTS && (Properties.JUNIT_CHECK == Properties.JUnitCheckValues.TRUE
                || Properties.JUNIT_CHECK == Properties.JUnitCheckValues.OPTIONAL)) {
            if (ClassPathHacker.isJunitCheckAvailable()) {
                int beforeJUnitCheck = testSuite.size();
                compileAndCheckTests(testSuite, postProcessor, postProcessingContext);
                logger.info("JUnit compile-check changed suite size {} -> {}",
                        beforeJUnitCheck, testSuite.size());
                if (beforeJUnitCheck > 0 && testSuite.size() == 0) {
                    logger.warn("JUnit compile-check removed all tests ({} -> 0).", beforeJUnitCheck);
                }
                TestSuiteGenerator.logSuiteDiagnostics("postProcess:afterJUnitCheck", testSuite);
            } else {
                logger.warn("Cannot run Junit test. Cause {}", ClassPathHacker.getCause());
            }
        }
        if (replayStructure != null) {
            // Restore at the final oracle boundary. Assertions from tests retained
            // by JUnit validation are transferred to pristine structural clones.
            // If validation removed a test because its oracle did not compile or
            // was unstable, pure arms restore it without an oracle while the
            // hybrid arm restores its validated mutation-assertion baseline.
            replayStructure.restore(testSuite);
        }
        postProcessor.publishFinalAssertionReconciliation(
                postProcessingContext, testSuite, llmPostProcessingAssertionsApplied);
    }

    static boolean shouldGenerateStandardAssertions(boolean llmServiceReady) {
        return Properties.ASSERTIONS
                && (isMutationLlmReplay()
                || !llmServiceReady
                || !(LlmPostProcessor.isAnyFeatureEnabled() && Properties.LLM_POSTPROCESSING_ASSERTIONS));
    }

    private static void addStandardAssertionsForTestsWithoutOracle(TestSuiteChromosome suite,
                                                                   LlmPostProcessingPhaseContext context) {
        if (suite == null || suite.size() == 0) {
            return;
        }
        String reason = context == null ? "unknown" : context.getTerminalReason();
        PhaseSkipCause skipCause = TestSuiteGenerator.checkPhase(
                "standard assertion fallback after LLM " + reason,
                TimeController.getInstance()::isThereStillTimeInThisPhase);
        if (skipCause == PhaseSkipCause.TIMEOUT) {
            logger.warn("Cannot apply standard assertion fallback after LLM {}: no phase time remains", reason);
        }
        if (skipCause != PhaseSkipCause.NONE) {
            return;
        }
        TestSuiteChromosome missingOracles = new TestSuiteChromosome();
        for (TestChromosome chromosome : suite.getTestChromosomes()) {
            if (chromosome != null && chromosome.getTestCase() != null
                    && chromosome.getTestCase().getAssertions().isEmpty()
                    && context != null
                    && context.shouldGenerateStandardAssertionsFor(chromosome.getTestCase())) {
                // addTest(TestCase) deliberately shares the live TestCase, so
                // generated assertions are attached to the original suite.
                missingOracles.addTest(chromosome.getTestCase());
            }
        }
        if (missingOracles.size() == 0) {
            return;
        }
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Applying standard assertions to " + missingOracles.size()
                + " test(s) after LLM post-processing stopped: " + reason);
        TestSuiteGeneratorHelper.addAssertions(missingOracles);
    }

    private static boolean isMutationLlmReplay() {
        return TestSuiteGenerator.isConfigured(Properties.ORACLE_REPLAY_INPUT)
                && Properties.ORACLE_REPLAY_STRATEGY
                == Properties.OracleReplayStrategy.MUTATION_LLM;
    }

    /**
     * Compile and run the given tests. Remove from input list all tests that do
     * not compile, and handle the cases of instability (either remove tests or
     * comment out failing assertions).
     *
     * @param chromosome the test suite to compile and check
     */
    private static void compileAndCheckTests(TestSuiteChromosome chromosome,
                                             LlmPostProcessor postProcessor,
                                             LlmPostProcessingPhaseContext postProcessingContext) {
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Compiling and checking tests");
        final int originalSuiteSize = chromosome.size();

        if (!JUnitAnalyzer.isJavaCompilerAvailable()) {
            String msg = "No Java compiler is available. Make sure to run EvoSuite with the JDK and not the JRE. "
                    + "You can try to setup the JAVA_HOME system variable to point to it, "
                    + "as well as to make sure that the PATH variable points to the JDK before any JRE.";
            logger.error(msg);
            throw new RuntimeException(msg);
        }

        // Establish this run's validation metadata before the preflight below can return: a
        // reused JVM (e.g. many checks within one run, or many tests within one JVM) would
        // otherwise leave whatever a previous check round recorded in place, so a low-memory skip
        // here could be masked by, or wrongly blamed on, stale incompleteness from earlier.
        JUnitCheckState.resetForPhase();

        if (TestSuiteGenerator.isMemoryTooLowForPhase("JUnit check")) {
            JUnitCheckState.recordIncomplete(
                    "skipped the whole JUnit check phase due to low memory");
            return;
        }

        ClientServices.getInstance().getClientNode().changeState(ClientState.JUNIT_CHECK);

        // Store this value; if this option is true then the JUnit check
        // would not succeed, as the JUnit classloader wouldn't find the class
        boolean junitSeparateClassLoader = Properties.USE_SEPARATE_CLASSLOADER;
        Properties.USE_SEPARATE_CLASSLOADER = false;

        int numUnstable = 0;

        // note: compiling and running JUnit tests can be very time consuming
        if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
            JUnitCheckState.recordIncomplete(
                    "JUnit check phase budget expired before any check could run");
            Properties.USE_SEPARATE_CLASSLOADER = junitSeparateClassLoader;
            return;
        }

        List<TestCase> testCases = chromosome.getTests(); // make copy of
        // current tests
        List<TestCase> testsBeforeCompileFilter = new ArrayList<>(testCases);
        int beforeCompileFilter = testCases.size();

        // first, let's just get rid of all the tests that do not compile
        JUnitAnalyzer.removeTestsThatDoNotCompile(testCases);
        int removedByCompileFilter = beforeCompileFilter - testCases.size();
        if (removedByCompileFilter > 0) {
            Set<TestCase> retainedAfterCompile = Collections.newSetFromMap(new IdentityHashMap<TestCase, Boolean>());
            retainedAfterCompile.addAll(testCases);
            List<TestCase> compileRemovedTests = new ArrayList<>();
            for (TestCase testCase : testsBeforeCompileFilter) {
                if (!retainedAfterCompile.contains(testCase)) {
                    compileRemovedTests.add(testCase);
                }
            }
            postProcessor.recordAssertionsRemovedByCompileFilter(
                    postProcessingContext, compileRemovedTests);
            logger.warn("JUnit compile filter removed {} test(s) out of {} before runtime checks",
                    removedByCompileFilter, beforeCompileFilter);
        }

        JUnitCheckBudget budget = new JUnitCheckBudget(new PhaseMemoryGuard());

        // compile and run each test one at a time. and keep track of total time
        long start = java.lang.System.currentTimeMillis();
        Iterator<TestCase> iter = testCases.iterator();
        int removedInSingleTestChecks = 0;
        int checkedInSingleTestChecks = 0;
        while (iter.hasNext()) {
            if (!budget.mayRun(JUnitCheckBudget.Step.PER_TEST,
                    testCases.size() - checkedInSingleTestChecks + removedInSingleTestChecks)) {
                break;
            }
            checkedInSingleTestChecks++;
            TestCase tc = iter.next();
            List<TestCase> list = new ArrayList<>();
            list.add(tc);
            numUnstable += JUnitAnalyzer.handleTestsThatAreUnstable(list);
            if (list.isEmpty()) {
                // if the test was unstable and deleted, need to remove it from
                // final testSuite
                iter.remove();
                removedInSingleTestChecks++;
            }
        }
        if (removedInSingleTestChecks > 0) {
            logger.warn("JUnit per-test instability checks removed {} test(s)", removedInSingleTestChecks);
        }
        /*
         * compiling and running each single test individually will take more
         * than compiling/running everything in on single suite. so it can be
         * used as an upper bound
         */
        long delta = java.lang.System.currentTimeMillis() - start;

        // What running the whole suite once costs; the per-test total is an upper bound
        // until the whole-suite check has actually been timed.
        long suiteRunMs = delta;
        budget.setPerTestChecksMs(delta);
        if (budget.mayRun(JUnitCheckBudget.Step.WHOLE_SUITE, 0)) {
            long suiteCheckStart = java.lang.System.currentTimeMillis();
            numUnstable += JUnitAnalyzer.handleTestsThatAreUnstable(testCases);
            suiteRunMs = Math.min(suiteRunMs, java.lang.System.currentTimeMillis() - suiteCheckStart);
        }

        // second passage on reverse order, this is to spot dependencies among
        // tests
        if (testCases.size() > 1 && budget.mayRun(JUnitCheckBudget.Step.REVERSE_ORDER, 0)) {
            Collections.reverse(testCases);
            try {
                numUnstable += JUnitAnalyzer.handleTestsThatAreUnstable(testCases);
            } finally {
                // The reverse traversal is only a probe for order sensitivity;
                // it must not silently become the emitted suite order.
                Collections.reverse(testCases);
            }
        }

        chromosome.setExtensionInitializationOrder(
                budget.mayRun(JUnitCheckBudget.Step.ORDER_SENSITIVITY, 0)
                        ? resolveExtensionInitializationOrderIfNeeded(testCases, budget, suiteRunMs)
                        : Collections.<String>emptyList());

        chromosome.clearTests(); // remove all tests
        for (TestCase testCase : testCases) {
            chromosome.addTest(testCase); // add back the filtered tests
        }
        logger.info("JUnit check suite reduction: {} -> {}", originalSuiteSize, chromosome.size());
        if (originalSuiteSize > 0 && chromosome.size() == 0) {
            logger.warn("JUnit check removed all tests from suite");
        }

        boolean unstable = (numUnstable > 0);

        if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
            logger.warn("JUnit checking timed out");
        }

        ClientServices.track(RuntimeVariable.HadUnstableTests, unstable);
        ClientServices.track(RuntimeVariable.NumUnstableTests, numUnstable);
        Properties.USE_SEPARATE_CLASSLOADER = junitSeparateClassLoader;

    }

    private static List<String> resolveExtensionInitializationOrderIfNeeded(List<TestCase> testCases,
                                                                            JUnitCheckBudget budget,
                                                                            long suiteRunMs) {
        if (Properties.TEST_FORMAT != Properties.OutputFormat.JUNIT5
                || !Properties.TEST_EXTENSION_MODE
                || !Properties.RESET_STATIC_FIELDS
                || testCases == null
                || testCases.size() < 2) {
            return Collections.emptyList();
        }

        if (!budget.hasTimeForWholeSuiteCheck()) {
            JUnitCheckState.recordIncomplete(
                    "ran out of time before the extension initialization order could be resolved");
            return Collections.emptyList();
        }

        List<String> observedInitializationOrder = ClassReInitializer.getInstance().getInitializedClasses();
        if (Properties.TEST_EXTENSION_PRELOAD_INITIALIZED_CLASSES) {
            List<String> preloadOrder = JUnitAnalyzer.buildExtensionInitializationOrder(observedInitializationOrder);
            if (!preloadOrder.isEmpty()) {
                logger.info("Extension preloading enabled. Emitting explicit initialization order with {} classes.",
                        preloadOrder.size());
            }
            return preloadOrder;
        }

        if (!hasTimeForOrderSensitivityAnalysis(suiteRunMs)) {
            logger.warn("Skipping order-sensitivity analysis: less than twice its expected cost of {} ms "
                    + "left in the JUnit check phase", orderSensitivityAnalysisCostMs(suiteRunMs));
            JUnitCheckState.recordIncomplete(
                    "skipped the order-sensitivity analysis for lack of time");
            return Collections.emptyList();
        }
        JUnitAnalyzer.OrderSensitivityAnalysis analysis = JUnitAnalyzer.analyzeOrderSensitivity(testCases);
        if (!analysis.isOrderSensitive()) {
            return Collections.emptyList();
        }

        if (observedInitializationOrder.size() < 2) {
            return Collections.emptyList();
        }

        List<String> explicitOrder = JUnitAnalyzer.buildExtensionInitializationOrder(observedInitializationOrder);
        if (explicitOrder.size() < 2) {
            return Collections.emptyList();
        }

        logger.info("Detected order-sensitive suite under extension mode. "
                        + "Forward failures: {}, reverse failures: {}. "
                        + "Emitting explicit initialization order with {} classes.",
                analysis.getForwardFailures(),
                analysis.getReverseFailures(),
                explicitOrder.size());
        return explicitOrder;
    }

    /**
     * The order-sensitivity analysis compiles and runs the whole suite twice, forward and in
     * reverse.
     */
    static long orderSensitivityAnalysisCostMs(long suiteRunMs) {
        return 2L * Math.max(0L, suiteRunMs);
    }

    /**
     * Returns whether the current phase has at least twice the expected cost of the
     * order-sensitivity analysis left, so that starting it does not run the phase over time.
     */
    static boolean hasTimeForOrderSensitivityAnalysis(long suiteRunMs) {
        return TimeController.getInstance().isThereStillTimeInThisPhase(
                2L * orderSensitivityAnalysisCostMs(suiteRunMs));
    }
}
