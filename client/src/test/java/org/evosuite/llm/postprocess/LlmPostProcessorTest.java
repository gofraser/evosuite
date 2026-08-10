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
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.CompleteAssertionGenerator;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.assertion.TemplateCodeAssertion;
import org.evosuite.llm.LlmBudgetCoordinator;
import org.evosuite.llm.LlmConfiguration;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.LlmTraceRecorder;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestPresentationMetadata;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.MinimizationResult;
import org.evosuite.testsuite.MinimizationStatus;
import org.evosuite.testsuite.MinimizationStopCause;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmPostProcessorTest {

    private static int run(LlmPostProcessor processor, TestSuiteChromosome suite) {
        return run(processor, suite, MinimizationResult.disabled(suite));
    }

    private static int run(LlmPostProcessor processor, TestSuiteChromosome suite,
                           MinimizationResult minimizationResult) {
        return processor.runUnifiedPostProcessing(suite, minimizationResult,
                processor.createPhaseContext());
    }

    private ClientNodeImpl<?> previousClientNode;

    @BeforeEach
    void setUp() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        Properties.LLM_POSTPROCESSING_ENABLED = false;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = true;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = true;
        Properties.LLM_POSTPROCESSING_COMMENTS = true;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = true;
        Properties.LLM_POSTPROCESSING_MAX_TESTS = 0;
        Properties.LLM_POSTPROCESSING_MAX_TOTAL_STATEMENTS = 0;
        Properties.LLM_POSTPROCESSING_MAX_CALLS = 0;
        Properties.LLM_POSTPROCESSING_TIMEOUT = 0;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TESTS = 20;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TOTAL_STATEMENTS = 400;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_CALLS = 40;
        Properties.LLM_POSTPROCESSING_ASSERTION_EVAL_TIMEOUT_MS = 2000;
        Properties.LLM_POSTPROCESSING_ASSERTION_COMPILE_TIMEOUT_MS = 10000;
        Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS = 1;
        Properties.LLM_POSTPROCESSING_REPAIR_POLICY =
                Properties.LlmPostProcessingRepairPolicy.TARGETED_ONE;
        Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION =
                Properties.LlmPostProcessingOnIncompleteMinimization.SKIP;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK =
                Properties.LlmPostProcessingAssertionFallback.NONE;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK_STRATEGY =
                Properties.LlmPostProcessingAssertionFallbackStrategy.ALL;
        Properties.LLM_POSTPROCESSING_SCOPE = Properties.LlmPostProcessingScope.ALL_TESTS;
        Properties.TARGET_CLASS = "com.example.Foo";
        LlmService.resetInstanceForTesting();
        previousClientNode = null;
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        Properties.LLM_POSTPROCESSING_ENABLED = false;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = true;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = true;
        Properties.LLM_POSTPROCESSING_COMMENTS = true;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = true;
        Properties.LLM_POSTPROCESSING_MAX_TESTS = 0;
        Properties.LLM_POSTPROCESSING_MAX_TOTAL_STATEMENTS = 0;
        Properties.LLM_POSTPROCESSING_MAX_CALLS = 40;
        Properties.LLM_POSTPROCESSING_TIMEOUT = 120;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TESTS = 20;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TOTAL_STATEMENTS = 400;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_CALLS = 40;
        Properties.LLM_POSTPROCESSING_ASSERTION_EVAL_TIMEOUT_MS = 2000;
        Properties.LLM_POSTPROCESSING_ASSERTION_COMPILE_TIMEOUT_MS = 10000;
        Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS = 1;
        Properties.LLM_POSTPROCESSING_REPAIR_POLICY =
                Properties.LlmPostProcessingRepairPolicy.TARGETED_ONE;
        Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION =
                Properties.LlmPostProcessingOnIncompleteMinimization.SKIP;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK =
                Properties.LlmPostProcessingAssertionFallback.NONE;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK_STRATEGY =
                Properties.LlmPostProcessingAssertionFallbackStrategy.ALL;
        Properties.LLM_POSTPROCESSING_SCOPE = Properties.LlmPostProcessingScope.ALL_TESTS;
        Properties.TEST_NAMING_STRATEGY = Properties.TestNamingStrategy.NUMBERED;
        Properties.VARIABLE_NAMING_STRATEGY = Properties.VariableNamingStrategy.TYPE_BASED;
        LlmService.resetInstanceForTesting();
        restoreClientNode();
    }

    // ---- Feature toggle tests ----

    @Test
    void isAnyFeatureEnabled_allDisabled() {
        assertFalse(LlmPostProcessor.isAnyFeatureEnabled());
    }

    @Test
    void isAnyFeatureEnabled_providerNone_alwaysFalse() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        assertFalse(LlmPostProcessor.isAnyFeatureEnabled());
    }

    @Test
    void isAnyFeatureEnabled_unifiedEnabledWithProvider() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertTrue(LlmPostProcessor.isAnyFeatureEnabled());
    }

    @Test
    void isAnyFeatureEnabled_topLevelDisabled() {
        Properties.LLM_POSTPROCESSING_ENABLED = false;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertFalse(LlmPostProcessor.isAnyFeatureEnabled());
    }

    @Test
    void isAnyFeatureEnabled_noResponseFeaturesEnabled() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = false;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = false;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = false;
        Properties.LLM_POSTPROCESSING_COMMENTS = false;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = false;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertFalse(LlmPostProcessor.isAnyFeatureEnabled());
    }

    @Test
    void isAnyResponseFeatureEnabled_noFeaturesEnabled() {
        Properties.LLM_POSTPROCESSING_ASSERTIONS = false;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = false;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = false;
        Properties.LLM_POSTPROCESSING_COMMENTS = false;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = false;
        assertFalse(LlmPostProcessor.isAnyResponseFeatureEnabled());
    }

    @Test
    void runUnifiedPostProcessing_enabledButNoLlmProvider_gracefulNoop() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        LlmPostProcessor processor = new LlmPostProcessor();
        assertDoesNotThrow(() -> run(processor, null));
    }

    @Test
    void runUnifiedPostProcessing_unavailableLlmServiceSkipsWithoutCallingModel() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,\"testName\":\"shouldNotApply\"}");

        run(processor(new UnavailableTestLlmService(model)), suite);

        assertNoAppliedPostProcessing(test);
        assertEquals(0, model.messages.size());
    }

    @Test
    void runUnifiedPostProcessing_noBudgetBeforeSuiteSkipsWithoutCallingModel() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,\"testName\":\"shouldNotApply\"}");
        LlmService service = new NoBudgetTestLlmService(model);

        run(processor(service), suite);

        assertNoAppliedPostProcessing(test);
        assertEquals(0, model.messages.size());
    }

    @Test
    void infrastructureSkipMarksStandardAssertionFallbackRequired() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        LlmPostProcessor phaseProcessor = processor(new NoBudgetTestLlmService(new QueueCapturingModel()));
        LlmPostProcessingPhaseContext context = phaseProcessor.createPhaseContext();

        phaseProcessor.runUnifiedPostProcessing(singleTestSuite(7),
                MinimizationResult.disabled(null), context);

        assertEquals("service_unavailable_or_no_budget", context.getTerminalReason());
        assertTrue(context.requiresStandardAssertionFallback());
    }

    @Test
    void assertionFallbackTargetsOnlyUnattemptedOrFailedTests() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        LlmPostProcessingPhaseContext context = processor(
                new NoBudgetTestLlmService(new QueueCapturingModel())).createPhaseContext();
        DefaultTestCase explicitNoOracle = singleIntTest(1);
        DefaultTestCase failed = singleIntTest(2);
        DefaultTestCase unattempted = singleIntTest(3);
        context.recordAttemptedTest(explicitNoOracle);
        context.recordAttemptedTest(failed);
        context.recordFailedAssertionTest(failed);
        context.recordTerminalReason("infrastructure_failure");

        assertFalse(context.shouldGenerateStandardAssertionsFor(explicitNoOracle));
        assertTrue(context.shouldGenerateStandardAssertionsFor(failed));
        assertTrue(context.shouldGenerateStandardAssertionsFor(unattempted));
    }

    @Test
    void runUnifiedPostProcessing_noFeaturesEnabledNoopsWithoutCallingModel() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = false;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = false;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = false;
        Properties.LLM_POSTPROCESSING_COMMENTS = false;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = false;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,\"testName\":\"shouldNotApply\"}");
        LlmService service = createService(model, 1);

        run(processor(service), suite);

        assertNoAppliedPostProcessing(test);
        assertEquals(0, model.messages.size());
    }

    @Test
    void runUnifiedPostProcessing_skipPathsPublishZeroMetricsAndReasons() {
        CapturingClientNode node = installCapturingClientNode();

        Properties.LLM_POSTPROCESSING_ENABLED = false;
        run(processor(createService(new QueueCapturingModel(), 1)), singleTestSuite(7));
        assertEquals("disabled", node.value(RuntimeVariable.LLM_PostProcessing_Skip_Reason));
        assertZeroPostProcessingOutcomeMetrics(node);

        node.clear();
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        run(new LlmPostProcessor(), singleTestSuite(7));
        assertEquals("no_provider", node.value(RuntimeVariable.LLM_PostProcessing_Skip_Reason));
        assertZeroPostProcessingOutcomeMetrics(node);

        node.clear();
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = false;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = false;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = false;
        Properties.LLM_POSTPROCESSING_COMMENTS = false;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = false;
        run(processor(createService(new QueueCapturingModel(), 1)), singleTestSuite(7));
        assertEquals("no_features_enabled", node.value(RuntimeVariable.LLM_PostProcessing_Skip_Reason));
        assertZeroPostProcessingOutcomeMetrics(node);

        node.clear();
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = true;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = true;
        Properties.LLM_POSTPROCESSING_COMMENTS = true;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = true;
        TestSuiteChromosome suite = singleTestSuite(7);
        run(processor(createService(new QueueCapturingModel(), 1)), suite,
                incompleteMinimizationResult(suite, MinimizationStatus.TIMED_OUT,
                        MinimizationStopCause.TIMEOUT));
        assertEquals("incomplete_minimization_TIMED_OUT",
                node.value(RuntimeVariable.LLM_PostProcessing_Skip_Reason));
        assertEquals(MinimizationStatus.TIMED_OUT.name(),
                node.value(RuntimeVariable.LLM_PostProcessing_Minimization_Status));
        assertEquals(MinimizationStopCause.TIMEOUT.name(),
                node.value(RuntimeVariable.LLM_PostProcessing_Minimization_Stop_Cause));
        assertZeroPostProcessingOutcomeMetrics(node);

        node.clear();
        LlmPostProcessor skippedProcessor = new LlmPostProcessor();
        LlmPostProcessingPhaseContext skippedContext = skippedProcessor.createPhaseContext();
        skippedProcessor.publishSkippedPostProcessingMetrics(skippedContext, "low_memory",
                incompleteMinimizationResult(suite, MinimizationStatus.COMPLETED,
                        MinimizationStopCause.NONE));
        assertEquals("low_memory", node.value(RuntimeVariable.LLM_PostProcessing_Skip_Reason));
        assertEquals(MinimizationStatus.COMPLETED.name(),
                node.value(RuntimeVariable.LLM_PostProcessing_Minimization_Status));
        assertEquals(MinimizationStopCause.NONE.name(),
                node.value(RuntimeVariable.LLM_PostProcessing_Minimization_Stop_Cause));
        assertZeroPostProcessingOutcomeMetrics(node);
    }

    @Test
    void runUnifiedPostProcessing_metricsReconcileAcceptedRejectedFallbackAndRepairCalls() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK =
                Properties.LlmPostProcessingAssertionFallback.ON_NO_ACCEPTED_ASSERTIONS;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK_STRATEGY =
                Properties.LlmPostProcessingAssertionFallbackStrategy.ALL;
        CapturingClientNode node = installCapturingClientNode();
        DefaultTestCase first = uninterpretedIntReturnTest();
        DefaultTestCase second = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(first);
        suite.addTest(second);
        markAllTestsExecutedNormally(suite);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("```json\n{\"schemaVersion\":2,"
                + "\"testName\":\"partial\","
                + "\"variableNames\":{\"v0\":\"count\",\"v9\":\"missing\"},"
                + "\"comments\":[{\"afterStatementId\":\"s9\",\"text\":\"invalid\"}]}\n```");
        model.enqueue("{\"schemaVersion\":2,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"8\",\"actual\":\"v1\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v1\"}"
                + "]}");
        model.enqueue("[{\"assertionId\":\"a0\",\"kind\":\"NOT_EQUALS\",\"expected\":\"8\",\"actual\":\"v1\"}]");
        LlmService service = createService(model, 3);
        RecordingFallbackRunner fallbackRunner = new RecordingFallbackRunner(1);

        LlmPostProcessor phaseProcessor =
                processor(service, fallbackRunner, new FinalScopeCandidateRunner(7));
        LlmPostProcessingPhaseContext phaseContext = phaseProcessor.createPhaseContext();
        int assertionsAppliedByPhase = phaseProcessor.runUnifiedPostProcessing(suite,
                MinimizationResult.disabled(suite), phaseContext);

        assertEquals("", node.value(RuntimeVariable.LLM_PostProcessing_Skip_Reason));
        assertEquals(2, node.value(RuntimeVariable.LLM_PostProcessing_Requested_Tests));
        assertEquals(2, node.value(RuntimeVariable.LLM_PostProcessing_Accepted_Responses));
        assertEquals(3, model.messages.size());
        assertEquals(2, node.value(RuntimeVariable.LLM_PostProcessing_Initial_Calls));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Repair_Calls));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Repair_Calls_Skipped_Budget));
        assertEquals(3, node.value(RuntimeVariable.LLM_PostProcessing_Rejected_Edits));
        assertEquals(2, node.value(RuntimeVariable.LLM_PostProcessing_Rejected_Unknown_Ids));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Rejected_Observed_Execution));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_No_Accepted));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_All));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Fallback_Assertions_Applied));
        assertEquals(2, node.value(RuntimeVariable.LLM_PostProcessing_Variable_Names_Proposed));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Variable_Names_Applied));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Comments_Proposed));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Comments_Applied));
        assertEquals(2, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Proposed));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_Initial));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Repair_Requested));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Proposed_After_Repair));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_After_Repair));
        assertEquals(2, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Applied));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Compile));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Shipped));
        assertEquals(2, assertionsAppliedByPhase);
        assertEquals(2, LlmPostProcessor.countUnifiedTemplateAssertions(suite));

        Assertion noLongerPresent = second.getAssertions().get(0);
        noLongerPresent.getStatement().removeAssertion(noLongerPresent);
        assertEquals(1, LlmPostProcessor.countUnifiedTemplateAssertions(suite));

        phaseProcessor.publishFinalAssertionReconciliation(phaseContext, suite, assertionsAppliedByPhase);
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Compile));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Shipped));
    }

    @Test
    void runUnifiedPostProcessing_validEmptyResponseCountsAsProcessed() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = false;
        CapturingClientNode node = installCapturingClientNode();
        TestSuiteChromosome suite = singleTestSuite(7);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING, "{\"schemaVersion\":2}");

        run(processor(createService(model, 1)), suite);

        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Processed_Tests));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Partially_Processed_Tests));
    }

    @Test
    void runUnifiedPostProcessing_recordsFallbackAttemptEvenWhenItAddsNothing() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK =
                Properties.LlmPostProcessingAssertionFallback.ON_NO_ACCEPTED_ASSERTIONS;
        CapturingClientNode node = installCapturingClientNode();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(uninterpretedIntReturnTest());
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING, "{\"schemaVersion\":2}");

        run(processor(createService(model, 1), new RecordingFallbackRunner(0), new FinalScopeCandidateRunner(7)), suite);

        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Fallback_Assertions_Applied));
    }

    @Test
    void runUnifiedPostProcessing_appliesStructuredLlmResponse() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,"
                        + "\"testName\":\"usesPostProcessing\","
                        + "\"variableNames\":{\"v0\":\"count\"},"
                        + "\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"Prepare the value.\"}],"
                        + "\"sectionBreaksAfter\":[\"s0\"]}");
        LlmService service = createService(model, 1);

        run(processor(service), suite);

        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        assertNotNull(metadata);
        assertEquals("usesPostProcessing", metadata.getTestName());
        assertEquals("count", metadata.getVariableName(0));
        assertEquals("Prepare the value.", metadata.getCommentsAfter(0).get(0));
        assertTrue(metadata.hasSectionBreakAfter(0));
    }

    @Test
    void runUnifiedPostProcessing_honorsMaxTestsLimit() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_MAX_TESTS = 1;
        DefaultTestCase first = singleIntTest(1);
        DefaultTestCase second = singleIntTest(2);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(first);
        suite.addTest(second);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"first\"}}");
        LlmService service = createService(model, 2);

        run(processor(service), suite);

        assertEquals("first", TestPresentationMetadata.get(first).getVariableName(0));
        assertNull(TestPresentationMetadata.get(second));
    }

    @Test
    void runUnifiedPostProcessing_honorsMaxTotalStatementsLimit() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_MAX_TOTAL_STATEMENTS = 1;
        DefaultTestCase first = singleIntTest(1);
        DefaultTestCase second = singleIntTest(2);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(first);
        suite.addTest(second);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"first\"}}");
        LlmService service = createService(model, 2);

        run(processor(service), suite);

        assertEquals("first", TestPresentationMetadata.get(first).getVariableName(0));
        assertNull(TestPresentationMetadata.get(second));
    }

    @Test
    void runUnifiedPostProcessing_stopsWhenBudgetExhaustedDuringSuite() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        DefaultTestCase first = singleIntTest(1);
        DefaultTestCase second = singleIntTest(2);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(first);
        suite.addTest(second);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"first\"}}");
        model.enqueue("{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"second\"}}");
        LlmService service = createService(model, 1);

        run(processor(service), suite);

        assertEquals("first", TestPresentationMetadata.get(first).getVariableName(0));
        assertNull(TestPresentationMetadata.get(second));
        assertEquals(1, model.messages.size());
    }

    @Test
    void runUnifiedPostProcessing_incompleteMinimizationSkipPolicyNoops() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION =
                Properties.LlmPostProcessingOnIncompleteMinimization.SKIP;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING, "{\"schemaVersion\":2,\"testName\":\"shouldNotApply\"}");
        LlmService service = createService(model, 1);

        run(processor(service), suite,
                incompleteMinimizationResult(suite, MinimizationStatus.TIMED_OUT,
                        MinimizationStopCause.TIMEOUT));

        assertNull(TestPresentationMetadata.get(test));
    }

    @Test
    void runUnifiedPostProcessing_maxCallsCapsCompleteMinimizationRun() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_MAX_CALLS = 1;
        DefaultTestCase first = singleIntTest(1);
        DefaultTestCase second = singleIntTest(2);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(first);
        suite.addTest(second);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"first\"}}");
        model.enqueue("{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"second\"}}");
        LlmService service = createService(model, 10);

        run(processor(service), suite);

        assertEquals("first", TestPresentationMetadata.get(first).getVariableName(0));
        assertNull(TestPresentationMetadata.get(second));
        assertEquals(1, model.messages.size());
    }

    @Test
    void runUnifiedPostProcessing_incompleteMinimizationLimitedPolicyUsesDedicatedCallCap() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION =
                Properties.LlmPostProcessingOnIncompleteMinimization.LIMITED;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TESTS = 20;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TOTAL_STATEMENTS = 400;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_CALLS = 1;
        DefaultTestCase first = singleIntTest(1);
        DefaultTestCase second = singleIntTest(2);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(first);
        suite.addTest(second);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"first\"}}");
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"second\"}}");
        LlmService service = createService(model, 10);

        run(processor(service), suite,
                incompleteMinimizationResult(suite, MinimizationStatus.LOW_MEMORY,
                        MinimizationStopCause.LOW_MEMORY));

        assertEquals("first", TestPresentationMetadata.get(first).getVariableName(0));
        assertNull(TestPresentationMetadata.get(second));
    }

    @Test
    void runUnifiedPostProcessing_incompleteMinimizationLimitedPolicyPrioritizesUniqueCoverage() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION =
                Properties.LlmPostProcessingOnIncompleteMinimization.LIMITED;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TESTS = 20;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TOTAL_STATEMENTS = 400;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_CALLS = 1;
        DefaultTestCase commonOnly = singleIntTest(1);
        DefaultTestCase uniqueCoverage = singleIntTest(2);
        TestFitnessFunction commonGoal = new DummyFitnessFunction("common");
        TestFitnessFunction uniqueGoal = new DummyFitnessFunction("unique");
        commonOnly.addCoveredGoal(commonGoal);
        uniqueCoverage.addCoveredGoal(commonGoal);
        uniqueCoverage.addCoveredGoal(uniqueGoal);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(commonOnly);
        suite.addTest(uniqueCoverage);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"selected\"}}");
        LlmService service = createService(model, 10);

        run(processor(service), suite,
                incompleteMinimizationResult(suite, MinimizationStatus.LOW_MEMORY,
                        MinimizationStopCause.LOW_MEMORY));

        assertNull(TestPresentationMetadata.get(commonOnly));
        assertEquals("selected", TestPresentationMetadata.get(uniqueCoverage).getVariableName(0));
    }

    @Test
    void runUnifiedPostProcessing_incompleteMinimizationLimitedZeroCapsUseDefaults() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION =
                Properties.LlmPostProcessingOnIncompleteMinimization.LIMITED;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TESTS = 0;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TOTAL_STATEMENTS = 0;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_CALLS = 0;
        DefaultTestCase first = singleIntTest(1);
        DefaultTestCase second = singleIntTest(2);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(first);
        suite.addTest(second);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"first\"}}");
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"second\"}}");
        LlmService service = createService(model, 10);

        run(processor(service), suite,
                incompleteMinimizationResult(suite, MinimizationStatus.TIMED_OUT,
                        MinimizationStopCause.TIMEOUT));

        assertEquals("first", TestPresentationMetadata.get(first).getVariableName(0));
        assertEquals("second", TestPresentationMetadata.get(second).getVariableName(0));
    }

    @Test
    void runUnifiedPostProcessing_incompleteMinimizationFullPolicyIgnoresLimitedCaps() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION =
                Properties.LlmPostProcessingOnIncompleteMinimization.FULL;
        Properties.LLM_POSTPROCESSING_LIMITED_MAX_CALLS = 1;
        DefaultTestCase first = singleIntTest(1);
        DefaultTestCase second = singleIntTest(2);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(first);
        suite.addTest(second);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"first\"}}");
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"second\"}}");
        LlmService service = createService(model, 10);

        run(processor(service), suite,
                incompleteMinimizationResult(suite, MinimizationStatus.FAILED,
                        MinimizationStopCause.NONE));

        assertEquals("first", TestPresentationMetadata.get(first).getVariableName(0));
        assertEquals("second", TestPresentationMetadata.get(second).getVariableName(0));
    }

    @Test
    void runUnifiedPostProcessing_gracefullySkipsMalformedResponse() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING, "not json");
        LlmService service = createService(model, 1);

        assertDoesNotThrow(() -> run(processor(service), suite));
        assertNull(TestPresentationMetadata.get(test));
    }

    @Test
    void runUnifiedPostProcessing_acceptsValidPartsOfPartiallyInvalidResponse() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,"
                        + "\"testName\":\"partialAccepted\","
                        + "\"variableNames\":{\"v0\":\"count\",\"v9\":\"missing\"},"
                        + "\"comments\":["
                        + "{\"afterStatementId\":\"s0\",\"text\":\"Valid comment.\"},"
                        + "{\"afterStatementId\":\"s9\",\"text\":\"Invalid comment.\"}],"
                        + "\"sectionBreaksAfter\":[\"s0\",\"s9\"]}");
        LlmService service = createService(model, 1);

        run(processor(service), suite);

        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        assertNotNull(metadata);
        assertEquals("partialAccepted", metadata.getTestName());
        assertEquals("count", metadata.getVariableName(0));
        assertNull(metadata.getVariableName(9));
        assertEquals("Valid comment.", metadata.getCommentsAfter(0).get(0));
        assertTrue(metadata.hasSectionBreakAfter(0));
        assertFalse(metadata.hasSectionBreakAfter(9));
    }

    @Test
    void runUnifiedPostProcessing_noAcceptedAssertionsCanUseTraceFallback() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK =
                Properties.LlmPostProcessingAssertionFallback.ON_NO_ACCEPTED_ASSERTIONS;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK_STRATEGY =
                Properties.LlmPostProcessingAssertionFallbackStrategy.ALL;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING, "{\"schemaVersion\":2,\"testName\":\"readableOnly\"}");
        LlmService service = createService(model, 1);
        RecordingFallbackRunner fallbackRunner = new RecordingFallbackRunner(1);

        run(processor(service, fallbackRunner, new FinalScopeCandidateRunner(7)), suite);

        assertEquals("readableOnly", TestPresentationMetadata.get(test).getTestName());
        assertTrue(test.hasAssertions());
        assertEquals(1, fallbackRunner.calls);
        assertEquals(Properties.LlmPostProcessingAssertionFallbackStrategy.ALL, fallbackRunner.strategy);
    }

    @Test
    void runUnifiedPostProcessing_candidatePreparationFailureIsIsolatedPerTest() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase first = singleIntTest(1);
        DefaultTestCase second = singleIntTest(2);
        suite.addTest(first);
        suite.addTest(second);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"testName\":\"secondProcessed\"}");
        LlmService service = createService(model, 1);

        run(processor(service, new RecordingFallbackRunner(0), new FailOnceCandidateRunner()), suite);

        assertNull(TestPresentationMetadata.get(first));
        assertEquals("secondProcessed", TestPresentationMetadata.get(second).getTestName());
    }

    @Test
    void runUnifiedPostProcessing_rejectsAssertionsFromAbnormalOriginalExecution() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS = 0;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"assertions\":[{\"assertionId\":\"a0\","
                        + "\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v1\"}]}");
        LlmService service = createService(model, 1);

        run(processor(service, new RecordingFallbackRunner(0), new AbnormalFinalScopeCandidateRunner(7)), suite);

        assertFalse(test.hasAssertions());
    }

    @Test
    void runUnifiedPostProcessing_assertionFallbackDisabledWhenAssertionFeatureDisabled() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = false;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK =
                Properties.LlmPostProcessingAssertionFallback.ON_NO_ACCEPTED_ASSERTIONS;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING, "{\"schemaVersion\":2}");
        LlmService service = createService(model, 1);
        RecordingFallbackRunner fallbackRunner = new RecordingFallbackRunner(1);

        run(processor(service, fallbackRunner), suite);

        assertFalse(test.hasAssertions());
        assertEquals(0, fallbackRunner.calls);
    }

    @Test
    void runUnifiedPostProcessing_usesUnifiedPromptAndFeatureTag() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        CapturingModel model = new CapturingModel();
        LlmService service = createService(model, 1);

        run(processor(service), suite);

        assertEquals(LlmFeature.POST_PROCESSING, model.feature);
        assertNotNull(model.messages);
        assertEquals(2, model.messages.size());
        assertTrue(model.messages.get(1).getContent().contains("s0 v0"));
        assertTrue(model.messages.get(1).getContent().contains("\"schemaVersion\":2"));
    }

    @Test
    void runUnifiedPostProcessing_assertionEligibleScopeSkipsThrowingTests() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_SCOPE = Properties.LlmPostProcessingScope.ASSERTION_ELIGIBLE_TESTS;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        TestChromosome chromosome = suite.addTest(test);
        chromosome.setLastExecutionResult(throwingResult(test));
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING, "{\"schemaVersion\":2,\"testName\":\"shouldNotApply\"}");
        LlmService service = createService(model, 1);

        run(processor(service), suite);

        assertNull(TestPresentationMetadata.get(test));
    }

    @Test
    void runUnifiedPostProcessing_allTestsKeepsReadabilityButSuppressesAssertionsForThrowingTests() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_SCOPE = Properties.LlmPostProcessingScope.ALL_TESTS;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        TestChromosome chromosome = suite.addTest(test);
        chromosome.setLastExecutionResult(throwingResult(test));
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,"
                        + "\"testName\":\"readableThrowingTest\","
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v0\"}]}");
        LlmService service = createService(model, 1);

        run(processor(service), suite);

        assertEquals("readableThrowingTest", TestPresentationMetadata.get(test).getTestName());
        assertFalse(test.getStatement(0).hasAssertions());
    }

    @Test
    void runUnifiedPostProcessing_allTestsPromptDisablesAssertionsForThrowingTests() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_SCOPE = Properties.LlmPostProcessingScope.ALL_TESTS;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        TestChromosome chromosome = suite.addTest(test);
        chromosome.setLastExecutionResult(throwingResult(test));
        CapturingModel model = new CapturingModel();
        LlmService service = createService(model, 1);

        run(processor(service), suite);

        assertNotNull(model.messages);
        String userPrompt = model.messages.get(1).getContent();
        assertTrue(userPrompt.contains("Assertions are disabled for this test"));
        assertFalse(userPrompt.contains("\"assertions\""));
    }

    @Test
    void runUnifiedPostProcessing_filtersAssertionsAgainstObservedFinalScope() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        String rawResponse = "{\"schemaVersion\":2,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v1\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\",\"expected\":\"8\",\"actual\":\"v1\"}"
                + "]}";
        CompleteAssertionGenerator.CandidateCollection candidates =
                new FinalScopeCandidateRunner(7).collectCandidates(test);
        OracleContext context = OracleContext.from(
                test, candidates.getExecutionResult(), candidates.getAssertions(),
                PostProcessingOptions.fromProperties());
        LlmPostProcessingParseResult parseResult = LlmPostProcessingResponseParser.parse(
                rawResponse, context.toParseContext());
        assertEquals(2, parseResult.getResponse().getAssertions().size());
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING, rawResponse);
        LlmService service = createService(model, 1);

        run(processor(service, new RecordingFallbackRunner(0), new FinalScopeCandidateRunner(7)), suite);

        assertEquals(1, test.getAssertions().size());
    }

    @Test
    void runUnifiedPostProcessing_tracesAcceptedAppliedAndShippedAssertionLifecycle() throws Exception {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS = 0;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"assertions\":["
                        + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v1\"}]}");
        Path traceDir = Files.createTempDirectory("llm-lifecycle");
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE, "mock", "", "", 0.0,
                1024, 2, 0, 1, true, traceDir, "test-lifecycle");
        LlmService service = new LlmService(
                model, new LlmBudgetCoordinator.Local(1), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));

        LlmPostProcessor phaseProcessor = processor(service, new RecordingFallbackRunner(0),
                new FinalScopeCandidateRunner(7));
        LlmPostProcessingPhaseContext phaseContext = phaseProcessor.createPhaseContext();
        int applied = phaseProcessor.runUnifiedPostProcessing(suite,
                MinimizationResult.disabled(suite), phaseContext);
        phaseProcessor.publishFinalAssertionReconciliation(phaseContext, suite, applied);

        String trace = new String(Files.readAllBytes(traceDir.resolve("llm-trace.jsonl")),
                StandardCharsets.UTF_8);
        assertTrue(trace.contains("\"lifecycle_state\":\"accepted_final\""));
        assertTrue(trace.contains("\"lifecycle_state\":\"applied\""));
        assertTrue(trace.contains("\"lifecycle_state\":\"shipped\""));
        assertFalse(trace.contains("\"lifecycle_state\":\"removed_unstable\""));
    }

    @Test
    void finalReconciliation_classifiesCompileFilteredAssertionsSeparately() throws Exception {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS = 0;
        CapturingClientNode node = installCapturingClientNode();
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING,
                "{\"schemaVersion\":2,\"assertions\":["
                        + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v1\"}]}");
        Path traceDir = Files.createTempDirectory("llm-compile-lifecycle");
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE, "mock", "", "", 0.0,
                1024, 2, 0, 1, true, traceDir, "test-compile-lifecycle");
        LlmService service = new LlmService(
                model, new LlmBudgetCoordinator.Local(1), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));

        LlmPostProcessor phaseProcessor = processor(service, new RecordingFallbackRunner(0),
                new FinalScopeCandidateRunner(7));
        LlmPostProcessingPhaseContext phaseContext = phaseProcessor.createPhaseContext();
        int applied = phaseProcessor.runUnifiedPostProcessing(suite,
                MinimizationResult.disabled(suite), phaseContext);
        phaseProcessor.recordAssertionsRemovedByCompileFilter(phaseContext,
                Collections.singletonList(test));
        suite.clearTests();
        phaseProcessor.publishFinalAssertionReconciliation(phaseContext, suite, applied);

        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Compile));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Shipped));
        String trace = new String(Files.readAllBytes(traceDir.resolve("llm-trace.jsonl")),
                StandardCharsets.UTF_8);
        assertTrue(trace.contains("\"lifecycle_state\":\"removed_compile\""));
        assertFalse(trace.contains("\"lifecycle_state\":\"removed_unstable\""));
    }

    @Test
    void runUnifiedPostProcessing_buildsPromptFromExecutedCandidateClone() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        CapturingModel model = new CapturingModel();
        LlmService service = createService(model, 1);

        run(processor(service, new RecordingFallbackRunner(0), new ClonedFinalScopeCandidateRunner(7)), suite);

        assertNotNull(model.messages);
        String userPrompt = model.messages.get(1).getContent();
        assertTrue(userPrompt.contains("provenance=SUT_RETURN complete=true value=7"));
    }

    @Test
    void runUnifiedPostProcessing_enablesAssertionsForCandidateFactsWithoutDuplicateObservations() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        CapturingModel model = new CapturingModel();
        LlmService service = createService(model, 1);

        run(processor(service, new RecordingFallbackRunner(0), new CandidateAssertionCloneRunner(7)), suite);

        assertNotNull(model.messages);
        String userPrompt = model.messages.get(1).getContent();
        String generatedStatements = userPrompt.substring(
                userPrompt.indexOf("Generated test statements:"), userPrompt.indexOf("\nExceptions:"));
        assertFalse(generatedStatements.contains("assertEquals("));
        assertTrue(userPrompt.contains("kind=PrimitiveAssertion observed=7"));
        assertTrue(userPrompt.contains("candidateId=c0"));
        assertTrue(userPrompt.contains("\"candidateId\":\"c0\""));
        assertTrue(userPrompt.contains("Enabled fields: testName variableNames comments sectionBreaksAfter assertions"));
        assertTrue(userPrompt.contains("\"assertions\""));
        assertFalse(userPrompt.contains("Assertions are disabled for this test"));
    }

    @Test
    void runUnifiedPostProcessing_appliesCandidateSelectedByStableId() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS = 0;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,\"assertions\":["
                + "{\"assertionId\":\"a0\",\"candidateId\":\"c0\"}]}");

        int applied = run(processor(createService(model, 1), new RecordingFallbackRunner(0),
                new CandidateAssertionCloneRunner(7)), suite);

        assertEquals(1, applied);
        assertEquals(1, test.getAssertions().size());
    }

    @Test
    void runUnifiedPostProcessing_refreshesMissingExecutionResultBeforeAssertionEligibility() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        TestChromosome chromosome = suite.addTest(test);
        assertNull(chromosome.getLastExecutionResult());
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"7\",\"actual\":\"v1\"}]}");
        LlmService service = createService(model, 1);

        run(processor(service, new RecordingFallbackRunner(0), new FinalScopeCandidateRunner(7)), suite);

        assertNotNull(chromosome.getLastExecutionResult());
        assertEquals(1, model.messages.size());
        assertTrue(model.messages.get(0).get(1).getContent().contains("\"assertions\""));
        assertEquals(1, test.getAssertions().size());
    }

    @Test
    void runUnifiedPostProcessing_rejectsAssertionsThatFailStabilityScope() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING, "{\"schemaVersion\":2,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"7\",\"actual\":\"v1\"}]}");
        LlmService service = createService(model, 1);

        run(new LlmPostProcessor(service, new RecordingFallbackRunner(0), new FinalScopeCandidateRunner(7),
                new FinalScopeStabilityRunner(8), new IntegerScopeAssertionEvaluationRunner(),
                null, null, new RuntimePostProcessingTelemetry()), suite);

        assertTrue(test.getAssertions().isEmpty());
    }

    @Test
    void runUnifiedPostProcessing_preservesBaselineMutationAssertionsWhenAllProposalsRejected() {
        // Workstream G: the executable MUTATION_LLM arm ships a mutation oracle
        // plus accepted LLM assertions. When every LLM proposal is rejected the
        // pre-existing (mutation) assertions must survive byte-for-byte -- the
        // hybrid must never silently weaken its own baseline oracle.
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        DefaultTestCase test = uninterpretedIntReturnTest();
        PrimitiveAssertion baseline = new PrimitiveAssertion();
        baseline.setSource(test.getStatement(0).getReturnValue());
        baseline.setValue(7);
        baseline.setStatement(test.getStatement(0));
        test.getStatement(0).addAssertion(baseline);
        String baselineCode = baseline.getCode();

        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        // Proposes an LLM assertion whose expected value disagrees with the
        // stability scope, so it is rejected during validation.
        model.enqueue(LlmFeature.POST_PROCESSING, "{\"schemaVersion\":2,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"7\",\"actual\":\"v1\"}]}");
        LlmService service = createService(model, 1);

        // The production candidate runner (TraceAssertionCandidateRunner) collects
        // candidates on a clone; use the cloning double so the real test's
        // baseline assertions are not detached by candidate collection.
        run(new LlmPostProcessor(service, new RecordingFallbackRunner(0), new ClonedFinalScopeCandidateRunner(7),
                new FinalScopeStabilityRunner(8), new IntegerScopeAssertionEvaluationRunner(),
                null, null, new RuntimePostProcessingTelemetry()), suite);

        // No LLM template assertion shipped, and the mutation baseline is intact:
        // same instance, same rendered code, and it is still the test's only assertion.
        assertEquals(0, LlmPostProcessor.countUnifiedTemplateAssertions(suite));
        assertEquals(1, test.getAssertions().size());
        assertSame(baseline, test.getAssertions().get(0));
        assertEquals(baselineCode, test.getAssertions().get(0).getCode());
    }

    @Test
    void runUnifiedPostProcessing_rejectsAssertionsThatFailCompilationStage() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.POST_PROCESSING, "{\"schemaVersion\":2,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"7\",\"actual\":\"v1\"}]}");
        LlmService service = createService(model, 1);

        run(new LlmPostProcessor(service, new RecordingFallbackRunner(0), new FinalScopeCandidateRunner(7),
                new FinalScopeStabilityRunner(7),
                (proposal, validationTest, references, finalScope, options) ->
                        LlmPostProcessor.EvaluationOutcome.compileFailure("compile rejected"),
                null, null, new RuntimePostProcessingTelemetry()), suite);

        assertTrue(test.getAssertions().isEmpty());
    }


    @Test
    void runUnifiedPostProcessing_repairsRejectedAssertionAgainstObservedFinalScope() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,"
                + "\"testName\":\"keepsReadability\","
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"8\",\"actual\":\"v1\"}"
                + "]}");
        model.enqueue("[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v1\"}]");
        LlmService service = createService(model, 2);

        run(processor(service, new RecordingFallbackRunner(0), new FinalScopeCandidateRunner(7)), suite);

        assertEquals("keepsReadability", TestPresentationMetadata.get(test).getTestName());
        assertEquals(1, test.getAssertions().size());
        assertEquals(2, model.messages.size());
        assertTrue(model.messages.get(1).get(1).getContent().contains("Repair only the rejected assertion"));
        assertTrue(model.messages.get(1).get(1).getContent().contains("a0"));
    }

    @Test
    void runUnifiedPostProcessing_budgetExhaustedBetweenValidationAndRepairKeepsReadabilityOnly() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS = 1;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        QueueCapturingModel model = new QueueCapturingModel();
        CapturingClientNode node = installCapturingClientNode();
        model.enqueue("{\"schemaVersion\":2,"
                + "\"testName\":\"noBudgetForRepair\","
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"8\",\"actual\":\"v1\"}"
                + "]}");
        model.enqueue("[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v1\"}]");
        LlmService service = createService(model, 1);

        run(processor(service, new RecordingFallbackRunner(0), new FinalScopeCandidateRunner(7)), suite);

        assertEquals("noBudgetForRepair", TestPresentationMetadata.get(test).getTestName());
        assertTrue(test.getAssertions().isEmpty());
        assertEquals(1, model.messages.size());
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Initial_Calls));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Repair_Calls));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Repair_Calls_Skipped_Budget));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_Initial));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Repair_Requested));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Proposed_After_Repair));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_After_Repair));
        assertEquals(0, node.value(RuntimeVariable.LLM_PostProcessing_Processed_Tests));
        assertEquals(1, node.value(RuntimeVariable.LLM_PostProcessing_Partially_Processed_Tests));
    }

    @Test
    void runUnifiedPostProcessing_repairFailureKeepsAcceptedReadabilityAndDropsRejectedAssertion() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS = 1;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,"
                + "\"testName\":\"repairFailureKeepsReadability\","
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"8\",\"actual\":\"v1\"}"
                + "]}");
        model.enqueue("not json");
        LlmService service = createService(model, 2);

        run(processor(service, new RecordingFallbackRunner(0), new FinalScopeCandidateRunner(7)), suite);

        assertEquals("repairFailureKeepsReadability", TestPresentationMetadata.get(test).getTestName());
        assertTrue(test.getAssertions().isEmpty());
        assertEquals(2, model.messages.size());
    }

    @Test
    void runUnifiedPostProcessing_repairsParserRejectedAssertionKind() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"ASSERT_EQUALS\",\"expected\":\"7\",\"actual\":\"v1\"}"
                + "]}");
        model.enqueue("[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v1\"}]");
        LlmService service = createService(model, 2);

        run(processor(service, new RecordingFallbackRunner(0), new FinalScopeCandidateRunner(7)), suite);

        assertEquals(1, test.getAssertions().size());
        assertEquals(2, model.messages.size());
        assertTrue(model.messages.get(1).get(1).getContent().contains("Unsupported assertion kind"));
    }

    @Test
    void runUnifiedPostProcessing_doesNotRepairNonRepairableDuplicateAssertion() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        markAllTestsExecutedNormally(suite);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v1\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v1\"}"
                + "]}");
        model.enqueue("[{\"assertionId\":\"a1\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v1\"}]");
        LlmService service = createService(model, 2);

        run(processor(service, new RecordingFallbackRunner(0), new FinalScopeCandidateRunner(7)), suite);

        assertEquals(1, test.getAssertions().size());
        assertEquals(1, model.messages.size());
    }

    @Test
    void runUnifiedPostProcessing_lowMemoryBeforeTestSkipsRemainingTests() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        QueueCapturingModel model = new QueueCapturingModel();
        LlmService service = createService(model, 1);

        run(new LlmPostProcessor(service, new RecordingFallbackRunner(0), new NoOpCandidateRunner(),
                new FinalScopeStabilityRunner(7), new IntegerScopeAssertionEvaluationRunner(),
                new SequenceResourceGuard(true), null, new RuntimePostProcessingTelemetry()), suite);

        assertNull(TestPresentationMetadata.get(test));
        assertEquals(0, model.messages.size());
    }

    @Test
    void runUnifiedPostProcessing_timeoutBeforeTestSkipsRemainingTests() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_TIMEOUT = 1;
        DefaultTestCase test = singleIntTest(7);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        QueueCapturingModel model = new QueueCapturingModel();
        LlmService service = createService(model, 1);

        run(new LlmPostProcessor(service, new RecordingFallbackRunner(0), new NoOpCandidateRunner(),
                new FinalScopeStabilityRunner(7), new IntegerScopeAssertionEvaluationRunner(),
                new SequenceResourceGuard(false), new SequencePhaseClock(0L, 1000L),
                new RuntimePostProcessingTelemetry()), suite);

        assertNull(TestPresentationMetadata.get(test));
        assertEquals(0, model.messages.size());
    }

    @Test
    void runUnifiedPostProcessing_lowMemoryAfterResponsePreservesReadabilityAndSkipsValidationRepairFallback() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.TARGET_CLASS = null;
        Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS = 1;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK =
                Properties.LlmPostProcessingAssertionFallback.ON_NO_ACCEPTED_ASSERTIONS;
        DefaultTestCase test = uninterpretedIntReturnTest();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        QueueCapturingModel model = new QueueCapturingModel();
        model.enqueue("{\"schemaVersion\":2,"
                + "\"testName\":\"readabilitySurvivesLowMemory\","
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"7\",\"actual\":\"v1\"}]}");
        LlmService service = createService(model, 2);
        RecordingFallbackRunner fallbackRunner = new RecordingFallbackRunner(1);

        run(new LlmPostProcessor(service, fallbackRunner, new FinalScopeCandidateRunner(7),
                testCase -> fail("stability execution must not start after low memory"),
                (proposal, validationTest, references, finalScope, options) ->
                        fail("assertion evaluation must not start after low memory"),
                new SequenceResourceGuard(false, true), null,
                new RuntimePostProcessingTelemetry()), suite);

        assertEquals("readabilitySurvivesLowMemory", TestPresentationMetadata.get(test).getTestName());
        assertTrue(test.getAssertions().isEmpty());
        assertEquals(0, fallbackRunner.calls);
        assertEquals(1, model.messages.size());
    }

    @Test
    void finalAssertionReconciliationCountsShippedAndRemovedUnstableTemplateAssertions() {
        DefaultTestCase stable = singleIntTest(7);
        addTemplateAssertion(stable, "a0");
        DefaultTestCase unstable = singleIntTest(8);
        addTemplateAssertion(unstable, "a1");
        unstable.setUnstable(true);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(stable);
        suite.addTest(unstable);

        PostProcessingAssertionReconciler.Reconciliation reconciliation =
                PostProcessingAssertionReconciler.reconcile(suite, 3);

        assertEquals(1, reconciliation.shipped());
        assertEquals(2, reconciliation.removedUnstable());
    }

    private static DefaultTestCase singleIntTest(int value) {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, value));
        return test;
    }

    private static TestSuiteChromosome singleTestSuite(int value) {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(singleIntTest(value));
        return suite;
    }

    private static void markAllTestsExecutedNormally(TestSuiteChromosome suite) {
        for (TestChromosome chromosome : suite.getTestChromosomes()) {
            chromosome.setLastExecutionResult(new ExecutionResult(chromosome.getTestCase()));
        }
    }

    private static void assertNoAppliedPostProcessing(DefaultTestCase test) {
        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        if (metadata != null) {
            assertNull(metadata.getTestName());
            assertNull(metadata.getVariableName(0));
            assertTrue(metadata.getCommentsAfter(0).isEmpty());
            assertFalse(metadata.hasSectionBreakAfter(0));
        }
        assertFalse(test.hasAssertions());
    }

    private CapturingClientNode installCapturingClientNode() {
        try {
            Field field = ClientServices.class.getDeclaredField("clientNode");
            field.setAccessible(true);
            previousClientNode = (ClientNodeImpl<?>) field.get(ClientServices.getInstance());
            CapturingClientNode node = new CapturingClientNode();
            field.set(ClientServices.getInstance(), node);
            return node;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to install capturing client node", e);
        }
    }

    private void restoreClientNode() {
        if (previousClientNode == null) {
            return;
        }
        try {
            Field field = ClientServices.class.getDeclaredField("clientNode");
            field.setAccessible(true);
            field.set(ClientServices.getInstance(), previousClientNode);
            previousClientNode = null;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to restore client node", e);
        }
    }

    private static void assertZeroPostProcessingOutcomeMetrics(CapturingClientNode node) {
        for (RuntimeVariable variable : ZERO_ON_SKIP_POSTPROCESSING_METRICS) {
            assertEquals(0, node.value(variable), variable.name());
        }
    }

    private static final RuntimeVariable[] ZERO_ON_SKIP_POSTPROCESSING_METRICS = {
            RuntimeVariable.LLM_PostProcessing_Requested_Tests,
            RuntimeVariable.LLM_PostProcessing_Requested_Statements,
            RuntimeVariable.LLM_PostProcessing_Initial_Calls,
            RuntimeVariable.LLM_PostProcessing_Repair_Calls,
            RuntimeVariable.LLM_PostProcessing_Repair_Calls_Skipped_Budget,
            RuntimeVariable.LLM_PostProcessing_Accepted_Responses,
            RuntimeVariable.LLM_PostProcessing_Skipped_Tests,
            RuntimeVariable.LLM_PostProcessing_Cap_Skipped_Tests,
            RuntimeVariable.LLM_PostProcessing_Infrastructure_Failures,
            RuntimeVariable.LLM_PostProcessing_Rejected_Edits,
            RuntimeVariable.LLM_PostProcessing_Rejected_Unknown_Ids,
            RuntimeVariable.LLM_PostProcessing_Rejected_Duplicates,
            RuntimeVariable.LLM_PostProcessing_Rejected_Invalid_Fields,
            RuntimeVariable.LLM_PostProcessing_Rejected_Unsupported_Kinds,
            RuntimeVariable.LLM_PostProcessing_Rejected_Limit_Exceeded,
            RuntimeVariable.LLM_PostProcessing_Rejected_Compile,
            RuntimeVariable.LLM_PostProcessing_Rejected_Observed_Execution,
            RuntimeVariable.LLM_PostProcessing_Rejected_Stability_Execution,
            RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks,
            RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Infrastructure,
            RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_No_Accepted,
            RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_All,
            RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Mutation,
            RuntimeVariable.LLM_PostProcessing_Fallback_Assertions_Applied,
            RuntimeVariable.LLM_PostProcessing_Processed_Tests,
            RuntimeVariable.LLM_PostProcessing_Partially_Processed_Tests,
            RuntimeVariable.LLM_PostProcessing_Test_Names_Proposed,
            RuntimeVariable.LLM_PostProcessing_Test_Names_Applied,
            RuntimeVariable.LLM_PostProcessing_Variable_Names_Proposed,
            RuntimeVariable.LLM_PostProcessing_Variable_Names_Applied,
            RuntimeVariable.LLM_PostProcessing_Comments_Proposed,
            RuntimeVariable.LLM_PostProcessing_Comments_Applied,
            RuntimeVariable.LLM_PostProcessing_Section_Breaks_Proposed,
            RuntimeVariable.LLM_PostProcessing_Section_Breaks_Applied,
            RuntimeVariable.LLM_PostProcessing_Assertions_Proposed,
            RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_Initial,
            RuntimeVariable.LLM_PostProcessing_Assertions_Repair_Requested,
            RuntimeVariable.LLM_PostProcessing_Assertions_Proposed_After_Repair,
            RuntimeVariable.LLM_PostProcessing_Assertions_Accepted_After_Repair,
            RuntimeVariable.LLM_PostProcessing_Assertions_Applied,
            RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable,
            RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Compile,
            RuntimeVariable.LLM_PostProcessing_Assertions_Shipped
    };

    private static void addTemplateAssertion(DefaultTestCase test, String assertionId) {
        TemplateCodeAssertion assertion = new TemplateCodeAssertion(assertionId,
                LlmPostProcessingResponse.AssertionKind.EQUALS, "7", "v0", null,
                Collections.singletonMap("v0", 0), "");
        assertion.setStatement(test.getStatement(0));
        test.getStatement(0).addAssertion(assertion);
    }

    private static DefaultTestCase uninterpretedIntReturnTest() {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference input = test.addStatement(new IntPrimitiveStatement(test, 7));
        Map<String, VariableReference> bindings = new LinkedHashMap<>();
        bindings.put("int0", input);
        test.addStatement(new UninterpretedStatement(test, int.class,
                "int __tmp = int0;", bindings, "__tmp"));
        return test;
    }

    private static ExecutionResult throwingResult(DefaultTestCase test) {
        ExecutionResult result = new ExecutionResult(test);
        result.reportNewThrownException(0, new RuntimeException("boom"));
        return result;
    }

    private static LlmPostProcessor processor(LlmService service) {
        return processor(service, new RecordingFallbackRunner(0));
    }

    private static LlmPostProcessor processor(LlmService service,
                                              LlmPostProcessor.AssertionFallbackRunner fallbackRunner) {
        return processor(service, fallbackRunner, new NoOpCandidateRunner());
    }

    private static LlmPostProcessor processor(LlmService service,
                                              LlmPostProcessor.AssertionFallbackRunner fallbackRunner,
                                              LlmPostProcessor.AssertionCandidateRunner candidateRunner) {
        int stabilityValue = candidateRunner instanceof FinalScopeCandidateRunner
                ? ((FinalScopeCandidateRunner) candidateRunner).value
                : 7;
        return new LlmPostProcessor(service, fallbackRunner, candidateRunner,
                new FinalScopeStabilityRunner(stabilityValue),
                new IntegerScopeAssertionEvaluationRunner(), null,
                null, new RuntimePostProcessingTelemetry());
    }

    private static LlmService createService(LlmService.ChatLanguageModel model, int budget) {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE,
                "mock",
                "",
                "",
                0.0,
                1024,
                2,
                0,
                1,
                false,
                Paths.get("target/llm-test-traces"),
                "test-post-processing");
        return new LlmService(
                model,
                new LlmBudgetCoordinator.Local(budget),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }

    private static LlmConfiguration testConfiguration() {
        return new LlmConfiguration(
                Properties.LlmProvider.NONE,
                "mock",
                "",
                "",
                0.0,
                1024,
                2,
                0,
                1,
                false,
                Paths.get("target/llm-test-traces"),
                "test-post-processing");
    }

    private static MinimizationResult incompleteMinimizationResult(TestSuiteChromosome suite,
                                                                   MinimizationStatus status,
                                                                   MinimizationStopCause cause) {
        return new MinimizationResult(status, cause, suite.size(), suite.totalLengthOfTestCases(),
                suite.size(), suite.totalLengthOfTestCases(), 123L);
    }

    private static final class NoOpCandidateRunner implements LlmPostProcessor.AssertionCandidateRunner {
        @Override
        public CompleteAssertionGenerator.CandidateCollection collectCandidates(TestCase test) {
            return null;
        }
    }

    private static final class FinalScopeCandidateRunner implements LlmPostProcessor.AssertionCandidateRunner {
        private final int value;

        private FinalScopeCandidateRunner(int value) {
            this.value = value;
        }

        @Override
        public CompleteAssertionGenerator.CandidateCollection collectCandidates(TestCase test) {
            ExecutionResult result = new ExecutionResult(test);
            Scope scope = new Scope();
            scope.setObject(test.getStatement(0).getReturnValue(), value);
            if (test.size() > 1) {
                scope.setObject(test.getStatement(1).getReturnValue(), value);
            }
            result.setFinalScope(scope);
            return new CompleteAssertionGenerator.CandidateCollection(test, result,
                    Collections.emptyList());
        }
    }

    private static final class ClonedFinalScopeCandidateRunner implements LlmPostProcessor.AssertionCandidateRunner {
        private final int value;

        private ClonedFinalScopeCandidateRunner(int value) {
            this.value = value;
        }

        @Override
        public CompleteAssertionGenerator.CandidateCollection collectCandidates(TestCase test) {
            TestCase clone = test.clone();
            return new FinalScopeCandidateRunner(value).collectCandidates(clone);
        }
    }

    private static final class CandidateAssertionCloneRunner implements LlmPostProcessor.AssertionCandidateRunner {
        private final int value;

        private CandidateAssertionCloneRunner(int value) {
            this.value = value;
        }

        @Override
        public CompleteAssertionGenerator.CandidateCollection collectCandidates(TestCase test) {
            TestCase clone = test.clone();
            CompleteAssertionGenerator.CandidateCollection base =
                    new FinalScopeCandidateRunner(value).collectCandidates(clone);
            PrimitiveAssertion assertion = new PrimitiveAssertion();
            assertion.setSource(clone.getStatement(clone.size() - 1).getReturnValue());
            assertion.setValue(value);
            clone.getStatement(clone.size() - 1).addAssertion(assertion);
            return new CompleteAssertionGenerator.CandidateCollection(
                    clone, base.getExecutionResult(), Collections.singletonList(assertion));
        }
    }

    private static final class FailOnceCandidateRunner implements LlmPostProcessor.AssertionCandidateRunner {
        private boolean failed;

        @Override
        public CompleteAssertionGenerator.CandidateCollection collectCandidates(TestCase test) {
            if (!failed) {
                failed = true;
                throw new IllegalStateException("candidate collection failed");
            }
            return new FinalScopeCandidateRunner(2).collectCandidates(test);
        }
    }

    private static final class AbnormalFinalScopeCandidateRunner
            implements LlmPostProcessor.AssertionCandidateRunner {
        private final int value;

        private AbnormalFinalScopeCandidateRunner(int value) {
            this.value = value;
        }

        @Override
        public CompleteAssertionGenerator.CandidateCollection collectCandidates(TestCase test) {
            CompleteAssertionGenerator.CandidateCollection collection =
                    new FinalScopeCandidateRunner(value).collectCandidates(test);
            collection.getExecutionResult().reportNewThrownException(test.size() - 1,
                    new IllegalStateException("abnormal original execution"));
            return collection;
        }
    }

    private static final class FinalScopeStabilityRunner implements LlmPostProcessor.StabilityExecutionRunner {
        private final int value;

        private FinalScopeStabilityRunner(int value) {
            this.value = value;
        }

        @Override
        public ExecutionResult execute(TestCase test) {
            ExecutionResult result = new ExecutionResult(test);
            Scope scope = new Scope();
            for (int position = 0; position < test.size(); position++) {
                VariableReference returnValue = test.getStatement(position).getReturnValue();
                if (returnValue != null && !returnValue.isVoid()) {
                    scope.setObject(returnValue, value);
                }
            }
            result.setFinalScope(scope);
            return result;
        }
    }

    private static final class IntegerScopeAssertionEvaluationRunner
            implements LlmPostProcessor.AssertionEvaluationRunner {
        @Override
        public LlmPostProcessor.EvaluationOutcome evaluate(LlmPostProcessingResponse.AssertionProposal proposal,
                                                           TestCase validationTest,
                                                           LlmPostProcessingReferences references,
                                                           Scope finalScope,
                                                           PostProcessingOptions options) {
            if (proposal == null || finalScope == null || proposal.getActual() == null) {
                return LlmPostProcessor.EvaluationOutcome.observedFailure("missing proposal or scope");
            }
            Object actual = valueOf(proposal.getActual(), validationTest, references, finalScope);
            Object expected = valueOf(proposal.getExpected(), validationTest, references, finalScope);
            boolean accepted;
            switch (proposal.getKind()) {
                case EQUALS:
                    accepted = java.util.Objects.equals(expected, actual);
                    break;
                case NOT_EQUALS:
                    accepted = !java.util.Objects.equals(expected, actual);
                    break;
                case TRUE:
                    accepted = Boolean.TRUE.equals(actual);
                    break;
                case FALSE:
                    accepted = Boolean.FALSE.equals(actual);
                    break;
                case NULL:
                    accepted = actual == null;
                    break;
                case NOT_NULL:
                    accepted = actual != null;
                    break;
                case SAME:
                    accepted = expected == actual;
                    break;
                case NOT_SAME:
                    accepted = expected != actual;
                    break;
                default:
                    accepted = false;
                    break;
            }
            return accepted
                    ? LlmPostProcessor.EvaluationOutcome.accepted()
                    : LlmPostProcessor.EvaluationOutcome.observedFailure("assertion rejected");
        }

        private Object valueOf(String expression, TestCase validationTest,
                               LlmPostProcessingReferences references, Scope finalScope) {
            if (expression == null) {
                return null;
            }
            String trimmed = expression.trim();
            if (references.hasVariableId(trimmed)) {
                try {
                    return references.resolveVariable(validationTest, trimmed).getObject(finalScope);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return trimmed;
            }
        }
    }

    private static final class CapturingModel implements LlmService.ChatLanguageModel {
        private List<LlmMessage> messages;
        private LlmFeature feature;

        @Override
        public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
            this.messages = messages;
            this.feature = feature;
            return new LlmService.LlmResponse("{\"schemaVersion\":2}", 0, 0);
        }
    }

    private static final class QueueCapturingModel implements LlmService.ChatLanguageModel {
        private final List<String> responses = new ArrayList<>();
        private final List<List<LlmMessage>> messages = new ArrayList<>();

        private void enqueue(String response) {
            responses.add(response);
        }

        @Override
        public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
            this.messages.add(messages);
            if (responses.isEmpty()) {
                throw new IllegalStateException("No queued response");
            }
            return new LlmService.LlmResponse(responses.remove(0), 0, 0);
        }
    }

    private static final class UnavailableTestLlmService extends LlmService {
        private UnavailableTestLlmService(LlmService.ChatLanguageModel model) {
            super(model,
                    new LlmBudgetCoordinator.Local(1),
                    testConfiguration(),
                    new LlmStatistics(),
                    new LlmTraceRecorder(testConfiguration()));
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public boolean hasBudget() {
            return false;
        }

        @Override
        public String query(PromptResult promptResult, LlmFeature feature) {
            throw new AssertionError("Unavailable service must be skipped before query");
        }
    }

    private static final class NoBudgetTestLlmService extends LlmService {
        private NoBudgetTestLlmService(LlmService.ChatLanguageModel model) {
            super(model,
                    new LlmBudgetCoordinator.Local(1),
                    testConfiguration(),
                    new LlmStatistics(),
                    new LlmTraceRecorder(testConfiguration()));
        }

        @Override
        public boolean hasBudget() {
            return false;
        }

        @Override
        public String query(PromptResult promptResult, LlmFeature feature) {
            throw new AssertionError("No-budget service must be skipped before query");
        }
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

        private void clear() {
            values.clear();
        }
    }

    private static final class RecordingFallbackRunner implements LlmPostProcessor.AssertionFallbackRunner {
        private final int assertionsToApply;
        private int calls;
        private Properties.LlmPostProcessingAssertionFallbackStrategy strategy;

        private RecordingFallbackRunner(int assertionsToApply) {
            this.assertionsToApply = assertionsToApply;
        }

        @Override
        public int applyFallbackAssertions(TestCase test,
                                           Properties.LlmPostProcessingAssertionFallbackStrategy strategy) {
            this.calls++;
            this.strategy = strategy;
            for (int i = 0; i < assertionsToApply; i++) {
                PrimitiveAssertion assertion = new PrimitiveAssertion();
                assertion.setSource(test.getStatement(0).getReturnValue());
                assertion.setValue(7);
                test.getStatement(0).addAssertion(assertion);
            }
            return assertionsToApply;
        }
    }

    private static final class SequenceResourceGuard implements LlmPostProcessor.ResourceGuard {
        private final boolean[] values;
        private int index;

        private SequenceResourceGuard(boolean... values) {
            this.values = values == null ? new boolean[0] : values;
        }

        @Override
        public boolean isLowMemory() {
            if (values.length == 0) {
                return false;
            }
            int current = Math.min(index, values.length - 1);
            index++;
            return values[current];
        }
    }

    private static final class SequencePhaseClock implements LlmPostProcessor.PhaseClock {
        private final long[] values;
        private int index;

        private SequencePhaseClock(long... values) {
            this.values = values == null ? new long[0] : values;
        }

        @Override
        public long currentTimeMillis() {
            if (values.length == 0) {
                return 0L;
            }
            int current = Math.min(index, values.length - 1);
            index++;
            return values[current];
        }
    }

    private static final class DummyFitnessFunction extends TestFitnessFunction {
        private final String id;

        private DummyFitnessFunction(String id) {
            this.id = id;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return 0.0;
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            if (other instanceof DummyFitnessFunction) {
                return id.compareTo(((DummyFitnessFunction) other).id);
            }
            return getClass().getName().compareTo(other.getClass().getName());
        }

        @Override
        public String getTargetClass() {
            return "Dummy";
        }

        @Override
        public String getTargetMethod() {
            return id;
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof DummyFitnessFunction
                    && id.equals(((DummyFitnessFunction) obj).id);
        }
    }
}
