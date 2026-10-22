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
package org.evosuite.llm.response;

import org.apache.commons.io.FileUtils;
import org.evosuite.llm.search.LlmPrivilegedThreads;
import org.evosuite.Properties;
import org.evosuite.junit.GeneratedTestCompiler;
import org.evosuite.junit.UnitTestAdapter;
import org.evosuite.junit.writer.TestSuiteWriterUtils;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmRequest;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.PhaseDeadline;
import org.evosuite.llm.prompt.ClusterGrowthDelta;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.SystemPromptProvider;
import org.evosuite.llm.prompt.TestClusterSummarizer;
import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.evosuite.utils.ReplaceableWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.evosuite.llm.response.ExecutionFailureFormatter.EXPLICIT_REPAIR_ACTION_MARKER;
import static org.evosuite.llm.response.ExecutionFailureFormatter.MAX_TEST_CODE_EXCERPT_CHARS;
import static org.evosuite.llm.response.ExecutionFailureFormatter.formatThrowable;
import static org.evosuite.llm.response.RepairText.getSutPackage;
import static org.evosuite.llm.response.RepairText.safeMethodName;
import static org.evosuite.llm.response.RepairText.simpleName;
import static org.evosuite.llm.response.RepairText.truncate;

/**
 * Applies parse -> validate -> execute and iterative LLM repair.
 *
 * <p>An instance holds configuration only; the state of one {@link #attemptParse}
 * call lives in a {@link RepairConversation}, so threads may share a loop.
 */
public class TestRepairLoop {

    private static final Logger logger = LoggerFactory.getLogger(TestRepairLoop.class);
    static final long NO_REPAIR_DEADLINE = PhaseDeadline.NONE;
    static final long REPAIR_DEADLINE_RETURN_MARGIN_NANOS = TimeUnit.SECONDS.toNanos(2);

    private static final long DEFAULT_PARSE_COMPILE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final ReplaceableWorker PARSE_COMPILE_WORKER =
            new ReplaceableWorker(r -> LlmPrivilegedThreads.newThread(r, "parse-compile"));
    private static volatile JavaCompiler parseCompileCompilerForTesting;

    /** Error patterns that the LLM cannot fix (native libs, sandbox, etc.). */
    private static final Set<String> UNFIXABLE_ERROR_PATTERNS = new HashSet<>(Arrays.asList(
            "UnsatisfiedLinkError",
            "AccessControlException",
            "java.security.AccessControlException"
    ));
    private static final Set<String> DEPENDENCY_MISSING_MARKERS = new HashSet<>(Arrays.asList(
            "NoClassDefFoundError",
            "ClassNotFoundException",
            "ClassNotFoundError",
            "Could not initialize class",
            "NoClassDefFoundError:"
    ));
    /**
     * A javac "cannot find symbol" error names a missing class only when its symbol line says so;
     * an invented method or variable is an ordinary source error that the model can fix.
     */
    private static final Pattern MISSING_CLASS_SYMBOL_PATTERN = Pattern.compile(
            "cannot find symbol[^\\n]*\\n(?:[^\\n]*\\n){0,3}?\\s*symbol:\\s+class\\b");

    private final LlmService llmService;
    private final TestParser testParser;
    private final LlmResponseParser responseParser;
    private final ClusterExpansionManager clusterExpansionManager;
    private final TestExecutor testExecutor;
    private final int maxAttempts;
    private final String systemPrompt;
    /** The dependency summary, built on first use; see {@link #sutContextSummary()}. */
    private final Supplier<String> sutContextSummary;
    private final RepairOptions repairOptions;
    private final ExecutionFailureFormatter failureFormatter;
    private final ParsedTestExecutionCheck executionCheck;
    private final LlmTestSalvager salvager;
    private final RepairHintComposer hintComposer;
    /** Signature summarizer for the cluster-growth delta of a repair conversation. */
    private final TestClusterSummarizer dependencyGrowthSummarizer = new TestClusterSummarizer();
    /**
     * Test-only seam: overrides the {@code TestCluster.getInstance()} used to render
     * the cluster-growth signature delta, since the real test cluster is a global
     * singleton that is awkward to control from a unit test.
     */
    private Supplier<TestCluster> testClusterSupplier = TestCluster::getInstance;

    /**
     * Options controlling parse-repair behavior.
     */
    public static class RepairOptions {
        private final boolean keepAssertionsInParsedTests;
        private final boolean repairOnAssertionFailures;
        private final boolean instructRepairToAvoidAssertions;
        private final boolean executeParsedTests;
        private final boolean expandClusterOnDemand;

        public static RepairOptions defaults() {
            return new RepairOptions(true, true, false, true, true);
        }

        public static RepairOptions forAssertionPolicy(boolean keepAssertions) {
            return new RepairOptions(keepAssertions, keepAssertions, !keepAssertions, true, true);
        }

        /**
         * Options for callers that will evaluate the parsed tests themselves
         * (e.g. a genetic-algorithm operator running on a worker thread) and
         * must not touch {@link org.evosuite.testcase.execution.TestCaseExecutor}
         * from the repair loop. With this option, {@link #attemptParse} never
         * executes a parsed test: it only compile-checks candidates, and later
         * repair turns react to parse/compile diagnostics rather than runtime
         * failures. The execution-based brute-force salvage is skipped as well,
         * since it would otherwise execute the failed test directly.
         */
        public static RepairOptions parseOnly(boolean keepAssertions) {
            return parseOnly(keepAssertions, true);
        }

        /**
         * Same as {@link #parseOnly(boolean)}, but also controls whether the
         * loop may expand the test cluster itself. A worker thread must pass
         * {@code false}: {@link TestCluster} is process-wide and the search
         * thread reads it concurrently, so expansion has to happen on the
         * search thread instead. With expansion disallowed, {@link
         * #attemptParse} stops as soon as it would otherwise expand and
         * returns a failure flagged {@link RepairResult#needsClusterExpansion()}.
         */
        public static RepairOptions parseOnly(boolean keepAssertions, boolean expandClusterOnDemand) {
            return new RepairOptions(keepAssertions, keepAssertions, !keepAssertions, false,
                    expandClusterOnDemand);
        }

        /**
         * Creates repair options.
         *
         * @param keepAssertionsInParsedTests whether parsed tests keep their assertions
         * @param repairOnAssertionFailures whether assertion failures trigger a repair
         * @param instructRepairToAvoidAssertions whether the repair prompt asks the LLM to avoid assertions
         */
        public RepairOptions(boolean keepAssertionsInParsedTests,
                             boolean repairOnAssertionFailures,
                             boolean instructRepairToAvoidAssertions) {
            this(keepAssertionsInParsedTests, repairOnAssertionFailures,
                    instructRepairToAvoidAssertions, true, true);
        }

        public RepairOptions(boolean keepAssertionsInParsedTests,
                             boolean repairOnAssertionFailures,
                             boolean instructRepairToAvoidAssertions,
                             boolean executeParsedTests,
                             boolean expandClusterOnDemand) {
            this.keepAssertionsInParsedTests = keepAssertionsInParsedTests;
            this.repairOnAssertionFailures = repairOnAssertionFailures;
            this.instructRepairToAvoidAssertions = instructRepairToAvoidAssertions;
            this.executeParsedTests = executeParsedTests;
            this.expandClusterOnDemand = expandClusterOnDemand;
        }

        public boolean isKeepAssertionsInParsedTests() {
            return keepAssertionsInParsedTests;
        }

        public boolean isRepairOnAssertionFailures() {
            return repairOnAssertionFailures;
        }

        public boolean isInstructRepairToAvoidAssertions() {
            return instructRepairToAvoidAssertions;
        }

        /**
         * Whether {@link #attemptParse} is allowed to execute parsed tests via
         * {@link org.evosuite.testcase.execution.TestCaseExecutor}. When {@code false},
         * the caller is responsible for evaluating the returned tests itself, and the
         * repair loop only reacts to parse/compile diagnostics on later repair turns.
         */
        public boolean isExecuteParsedTests() {
            return executeParsedTests;
        }

        /**
         * Whether {@link #attemptParse} may expand the test cluster itself when
         * parsing hits unresolved symbols. When {@code false}, it stops instead
         * of expanding and returns a failure flagged {@link
         * RepairResult#needsClusterExpansion()} so the caller can expand and
         * re-parse on a thread that is allowed to touch the cluster.
         */
        public boolean isExpandClusterOnDemand() {
            return expandClusterOnDemand;
        }
    }

    /**
     * Creates a standard repair loop wired to the given LLM service, using
     * the SUT-aware parser, default response parser, and cluster expansion manager.
     */
    public static TestRepairLoop createDefault(LlmService llmService) {
        return createDefault(llmService, RepairOptions.defaults());
    }

    /**
     * Creates a standard repair loop wired to the given LLM service and options,
     * allowing {@link Properties#LLM_REPAIR_ATTEMPTS} repair round-trips.
     */
    public static TestRepairLoop createDefault(LlmService llmService, RepairOptions options) {
        return createDefault(llmService, options, Properties.LLM_REPAIR_ATTEMPTS);
    }

    /**
     * Creates a standard repair loop wired to the given LLM service and options
     * with an explicit cap on repair round-trips.
     */
    public static TestRepairLoop createDefault(LlmService llmService, RepairOptions options,
                                               int maxAttempts) {
        return createDefault(llmService, options, maxAttempts, TestCluster::getInstance);
    }

    /** As above, reading the test cluster for the dependency summary from {@code clusterSupplier}. */
    static TestRepairLoop createDefault(LlmService llmService, RepairOptions options,
                                        int maxAttempts, Supplier<TestCluster> clusterSupplier) {
        String systemPrompt = null;
        try {
            systemPrompt = new SystemPromptProvider().getSystemPrompt();
        } catch (Throwable t) {
            logger.debug("Could not obtain system prompt for repair context", t);
        }
        return new TestRepairLoop(
                llmService,
                TestParser.forSUTWithLlmProvenance(),
                new LlmResponseParser(),
                new ClusterExpansionManager(),
                new DefaultExecutor(),
                maxAttempts,
                systemPrompt,
                new LazySutContextSummary(clusterSupplier),
                options == null ? RepairOptions.defaults() : options);
    }

    /**
     * Summarises the test cluster's dependencies on the first repair turn
     * that needs them. Most responses parse without a repair turn, so most
     * calls never pay for the summary.
     */
    private static final class LazySutContextSummary implements Supplier<String> {
        private final Supplier<TestCluster> clusterSupplier;
        private boolean computed;
        private String summary;

        LazySutContextSummary(Supplier<TestCluster> clusterSupplier) {
            this.clusterSupplier = clusterSupplier;
        }

        @Override
        public synchronized String get() {
            if (!computed) {
                computed = true;
                summary = summarizeSutContext();
            }
            return summary;
        }

        private String summarizeSutContext() {
            try {
                TestCluster cluster = clusterSupplier.get();
                if (cluster != null) {
                    TestClusterSummarizer.DependencySummaryResult deps =
                            new TestClusterSummarizer().summarizeDependencies(
                                    cluster, Properties.TARGET_CLASS, 4000);
                    return deps.getText();
                }
            } catch (Throwable t) {
                logger.debug("Could not obtain SUT context summary for repair context", t);
            }
            return null;
        }
    }

    /** Creates a repair loop using Properties-configured max attempts and a default test executor. */
    public TestRepairLoop(LlmService llmService,
                          TestParser testParser,
                          LlmResponseParser responseParser,
                          ClusterExpansionManager clusterExpansionManager) {
        this(llmService,
                testParser,
                responseParser,
                clusterExpansionManager,
                new DefaultExecutor(),
                Properties.LLM_REPAIR_ATTEMPTS,
                null,
                (String) null,
                RepairOptions.defaults());
    }

    /** Creates a repair loop with explicit executor and attempt count (no system prompt/SUT context). */
    public TestRepairLoop(LlmService llmService,
                          TestParser testParser,
                          LlmResponseParser responseParser,
                          ClusterExpansionManager clusterExpansionManager,
                          TestExecutor testExecutor,
                          int maxAttempts) {
        this(llmService, testParser, responseParser, clusterExpansionManager,
                testExecutor, maxAttempts, null, (String) null, RepairOptions.defaults());
    }

    /** Creates a repair loop with explicit executor, attempt count, and optional repair context. */
    public TestRepairLoop(LlmService llmService,
                          TestParser testParser,
                          LlmResponseParser responseParser,
                          ClusterExpansionManager clusterExpansionManager,
                          TestExecutor testExecutor,
                          int maxAttempts,
                          String systemPrompt,
                          String sutContextSummary) {
        this(llmService, testParser, responseParser, clusterExpansionManager, testExecutor,
                maxAttempts, systemPrompt, sutContextSummary, RepairOptions.defaults());
    }

    /** Creates a repair loop with explicit options controlling assertion handling. */
    public TestRepairLoop(LlmService llmService,
                          TestParser testParser,
                          LlmResponseParser responseParser,
                          ClusterExpansionManager clusterExpansionManager,
                          TestExecutor testExecutor,
                          int maxAttempts,
                          String systemPrompt,
                          String sutContextSummary,
                          RepairOptions repairOptions) {
        this(llmService, testParser, responseParser, clusterExpansionManager, testExecutor,
                maxAttempts, systemPrompt, () -> sutContextSummary, repairOptions);
    }

    /** Creates a repair loop whose dependency summary {@code sutContextSummary} supplies on demand. */
    private TestRepairLoop(LlmService llmService,
                           TestParser testParser,
                           LlmResponseParser responseParser,
                           ClusterExpansionManager clusterExpansionManager,
                           TestExecutor testExecutor,
                           int maxAttempts,
                           String systemPrompt,
                           Supplier<String> sutContextSummary,
                           RepairOptions repairOptions) {
        this.llmService = llmService;
        this.testParser = testParser;
        this.responseParser = responseParser;
        this.clusterExpansionManager = clusterExpansionManager;
        this.testExecutor = testExecutor;
        this.maxAttempts = Math.max(0, maxAttempts);
        this.systemPrompt = systemPrompt;
        this.sutContextSummary = sutContextSummary;
        this.repairOptions = repairOptions == null ? RepairOptions.defaults() : repairOptions;
        this.failureFormatter = new ExecutionFailureFormatter(sutContextSummary, testParser);
        this.hintComposer = new RepairHintComposer(sutContextSummary, failureFormatter);
        this.executionCheck = new ParsedTestExecutionCheck(testExecutor,
                this.repairOptions.isRepairOnAssertionFailures(), failureFormatter);
        this.salvager = new LlmTestSalvager(testExecutor,
                this.repairOptions.isKeepAssertionsInParsedTests(), executionCheck);
    }

    /** Returns the dependency summary for repair prompts, or null when there is none. */
    private String sutContextSummary() {
        return sutContextSummary.get();
    }

    /** Returns the {@link RepairOptions} this loop was configured with. */
    public RepairOptions getRepairOptions() {
        return repairOptions;
    }

    /** Attempts to parse the LLM response and repair it iteratively if parsing fails. */
    public RepairResult attemptParse(String llmResponse,
                                     List<LlmMessage> conversationHistory,
                                     LlmFeature feature) {
        return attemptParse(llmResponse, conversationHistory, feature, NO_REPAIR_DEADLINE);
    }

    /**
     * Attempts to parse the LLM response and repair it iteratively if parsing
     * fails, stopping before {@code repairDeadlineNanos} when another repair
     * request would risk losing already parsed or salvaged tests to the caller's
     * outer timeout.
     */
    public RepairResult attemptParse(String llmResponse,
                                     List<LlmMessage> conversationHistory,
                                     LlmFeature feature,
                                     long repairDeadlineNanos) {
        return attemptParse(llmResponse, conversationHistory, feature, repairDeadlineNanos, null);
    }

    /**
     * Variant of {@link #attemptParse(String, List, LlmFeature, long)} that
     * publishes newly validated executable tests as soon as they are salvaged.
     * This lets async orchestrators retain partial results if an outer future
     * timeout interrupts a later repair turn before the final result returns.
     */
    public RepairResult attemptParse(String llmResponse,
                                     List<LlmMessage> conversationHistory,
                                     LlmFeature feature,
                                     long repairDeadlineNanos,
                                     Consumer<List<ParseResult>> salvagedTestsConsumer) {
        return attemptParse(llmResponse, conversationHistory, null, feature,
                repairDeadlineNanos, salvagedTestsConsumer);
    }

    /** Parses and repairs a response to {@code prompt}, which also supplies the starting conversation. */
    public RepairResult attemptParse(String llmResponse,
                                     PromptResult prompt,
                                     LlmFeature feature) {
        return attemptParse(llmResponse, prompt, feature, NO_REPAIR_DEADLINE);
    }

    /** Deadline-aware variant of {@link #attemptParse(String, PromptResult, LlmFeature)}. */
    public RepairResult attemptParse(String llmResponse,
                                     PromptResult prompt,
                                     LlmFeature feature,
                                     long repairDeadlineNanos) {
        return attemptParse(llmResponse, prompt, feature, repairDeadlineNanos, null);
    }

    /**
     * Variant of {@link #attemptParse(String, List, LlmFeature, long, Consumer)} that takes the
     * conversation from {@code prompt}, which also says whether the dependency-type catalogue is
     * already part of it so a repair turn does not send it a second time. Repair turns are traced
     * with the context metadata of {@code prompt}.
     */
    public RepairResult attemptParse(String llmResponse,
                                     PromptResult prompt,
                                     LlmFeature feature,
                                     long repairDeadlineNanos,
                                     Consumer<List<ParseResult>> salvagedTestsConsumer) {
        return attemptParse(llmResponse, prompt.getMessages(), prompt,
                feature, repairDeadlineNanos, salvagedTestsConsumer);
    }

    private RepairResult attemptParse(String llmResponse,
                                      List<LlmMessage> conversationHistory,
                                      PromptResult prompt,
                                      LlmFeature feature,
                                      long repairDeadlineNanos,
                                      Consumer<List<ParseResult>> salvagedTestsConsumer) {
        List<String> diagnostics = new ArrayList<>();
        List<String> expandedClasses = new ArrayList<>();
        Map<String, ParseResult> salvagedExecutableTests = new LinkedHashMap<>();
        String currentResponse = llmResponse;
        int attemptsUsed = 0;
        RepairConversation state = new RepairConversation();
        state.setPrompt(prompt);
        boolean observedExecutionDrop = false;
        boolean strictContractReaskUsed = false;
        // Candidates that failed at execution in any attempt. The LLM often
        // rewrites or deletes a failing test in a later turn, so salvage must
        // not be limited to the final attempt's failures.
        Map<String, ParseResult> executionDropsForSalvage = new LinkedHashMap<>();
        boolean salvageAttempted = false;
        String previousError = null;

        // Accumulate the full conversation so repair requests include prior turns
        List<LlmMessage> conversation = new ArrayList<>();
        if (conversationHistory != null) {
            conversation.addAll(conversationHistory);
        }

        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            attemptsUsed = attempt + 1;
            List<ParseResult> parseResults;
            String extractedClassForDiagnostics;
            boolean wrapperNormalizationApplied = false;
            try {
                String sutPackage = getSutPackage();
                List<LlmResponseParser.ExtractionResult> extractions =
                        responseParser.extractAllTestClassesWithMetadata(
                                currentResponse, "GeneratedLlmTest", sutPackage);
                parseResults = new ArrayList<>();
                List<String> extractedSources = new ArrayList<>();
                List<String> parserFailures = new ArrayList<>();
                int blockIndex = 0;
                for (LlmResponseParser.ExtractionResult extraction : extractions) {
                    blockIndex++;
                    if (extraction.isEmptyFallback()) {
                        // A reply without code is not an empty test; let the no-test-methods
                        // path ask the model again.
                        diagnostics.add("No Java code found in block #" + blockIndex);
                        continue;
                    }
                    String extractedClass = extraction.getSource();
                    if (extraction.isRecoveryApplied()) {
                        String recoveryReason = extraction.getRecoveryReason() == null
                                ? "" : extraction.getRecoveryReason();
                        if (recoveryReason.contains("wrapper-normalized")) {
                            wrapperNormalizationApplied = true;
                            diagnostics.add("Applied wrapper normalization (block #" + blockIndex + "): "
                                    + recoveryReason);
                        } else {
                            diagnostics.add("Applied truncation recovery (block #" + blockIndex + "): "
                                    + recoveryReason);
                        }
                        try {
                            LlmResponseParser.validateRecoveredSource(extractedClass);
                        } catch (IllegalArgumentException recoveryFailure) {
                            parserFailures.add("Parser failure in code block #" + blockIndex + ": "
                                    + formatThrowable(recoveryFailure));
                            continue;
                        }
                    }
                    LlmResponseParser.ReflectiveAssertThrowsRewriteResult rewriteResult =
                            LlmResponseParser.rewriteReflectiveAssertThrowsAssertions(extractedClass);
                    extractedClass = rewriteResult.source;
                    if (rewriteResult.rewrites > 0) {
                        diagnostics.add("Normalized " + rewriteResult.rewrites
                                + " reflective assertThrows invocation(s) to unwrap "
                                + "InvocationTargetException (block #" + blockIndex + ")");
                    }
                    extractedSources.add(extractedClass);
                    try {
                        List<ParseResult> parsedBlock = testParser.parseTestClass(extractedClass);
                        if (parsedBlock != null && !parsedBlock.isEmpty()) {
                            parseResults.addAll(parsedBlock);
                        }
                    } catch (Throwable blockParserFailure) {
                        parserFailures.add("Parser failure in code block #" + blockIndex + ": "
                                + formatThrowable(blockParserFailure));
                    }
                }
                extractedClassForDiagnostics = joinExtractedSources(extractedSources);
                if (parseResults.isEmpty() && !parserFailures.isEmpty()) {
                    String parserFailureText = String.join("\n", parserFailures);
                    diagnostics.add(parserFailureText);
                    String next = tryRepair(state, parserFailureText, attempt, previousError, diagnostics,
                            conversation, currentResponse, feature, expandedClasses,
                            Collections.<ParseResult, ExecutionFailureContext>emptyMap(),
                            repairDeadlineNanos);
                    if (next == null) {
                        break;
                    }
                    previousError = parserFailureText;
                    currentResponse = next;
                    continue;
                }
            } catch (Throwable parserFailure) {
                String parserFailureText = "Parser failure: " + formatThrowable(parserFailure);
                diagnostics.add(parserFailureText);
                String next = tryRepair(state, parserFailureText, attempt, previousError, diagnostics,
                        conversation, currentResponse, feature, expandedClasses,
                        Collections.<ParseResult, ExecutionFailureContext>emptyMap(),
                        repairDeadlineNanos);
                if (next == null) {
                    break;
                }
                previousError = parserFailureText;
                currentResponse = next;
                continue;
            }

            if (parseResults == null || parseResults.isEmpty()) {
                String parseErrorText = buildNoTestMethodsParseError(extractedClassForDiagnostics);
                diagnostics.add(parseErrorText);
                OutputContractStatus contractStatus = classifyOutputContractStatus(
                        currentResponse, extractedClassForDiagnostics, wrapperNormalizationApplied);
                if (contractStatus == OutputContractStatus.HARD_REJECT && !strictContractReaskUsed) {
                    strictContractReaskUsed = true;
                    String strictContractError = buildStrictContractRepairRequest(
                            parseErrorText, extractedClassForDiagnostics);
                    diagnostics.add("Detected hard output-contract violation; issuing strict contract re-ask.");
                    String next = tryStrictContractRepair(state, strictContractError, attempt, diagnostics,
                            conversation, currentResponse, feature, expandedClasses,
                            repairDeadlineNanos);
                    if (next == null) {
                        break;
                    }
                    previousError = strictContractError;
                    currentResponse = next;
                    continue;
                }
                String next = tryRepair(state, parseErrorText, attempt, previousError, diagnostics,
                        conversation, currentResponse, feature, expandedClasses,
                        Collections.<ParseResult, ExecutionFailureContext>emptyMap(),
                        repairDeadlineNanos);
                if (next == null) {
                    break;
                }
                previousError = parseErrorText;
                currentResponse = next;
                continue;
            }

            // Partition parse-time outcomes: tests that produced no ERROR diagnostics
            // are candidates for execution; the rest are dropped and reported back
            // to the LLM in the combined repair message.
            List<ParseResult> validTests = new ArrayList<>();
            List<ParseResult> droppedAtParse = new ArrayList<>();
            for (ParseResult pr : parseResults) {
                if (pr.hasErrors()) {
                    droppedAtParse.add(pr);
                } else {
                    validTests.add(pr);
                }
            }

            // Cluster-expansion retry only makes sense when nothing parsed; otherwise
            // we would re-expand even after already obtaining usable tests.
            if (validTests.isEmpty()
                    && hasResolutionErrors(parseResults)
                    && Properties.LLM_EXPAND_CLUSTER_ON_DEMAND) {
                if (!repairOptions.isExpandClusterOnDemand()) {
                    // This loop must not touch the shared TestCluster (it runs on a
                    // worker thread); hand the response back unparsed instead of
                    // burning a repair round-trip that expansion might make moot.
                    Set<String> unresolved = clusterExpansionManager.extractUnresolvedSymbols(parseResults);
                    diagnostics.add("Deferred cluster expansion: " + unresolved);
                    return RepairResult.needsClusterExpansion(diagnostics, attemptsUsed, expandedClasses);
                }
                boolean expanded = false;
                state.markExpansionAttempted();
                try {
                    expanded = clusterExpansionManager.tryExpandFrom(parseResults);
                } catch (Throwable expansionFailure) {
                    diagnostics.add("Cluster expansion failure: " + formatThrowable(expansionFailure));
                }
                if (expanded) {
                    boolean addedNew = false;
                    for (String cls : clusterExpansionManager.getLastExpandedClasses()) {
                        if (!expandedClasses.contains(cls)) {
                            expandedClasses.add(cls);
                            addedNew = true;
                        }
                    }
                    if (addedNew) {
                        diagnostics.add("Expanded cluster with: " + expandedClasses);
                        // Re-parse the SAME response against the now-larger cluster
                        // without spending an LLM attempt: no request was sent, so
                        // this must not advance attemptsUsed (and, with
                        // maxAttempts == 0, must not be skipped by loop
                        // termination either). Decrementing attempt here cancels
                        // out the for-loop's attempt++, so the next iteration
                        // recomputes the same attemptsUsed. This cannot loop
                        // forever: addedNew is only true when at least one new
                        // class was appended to expandedClasses, and the set of
                        // classes the cluster can still expand into is finite, so
                        // eventually expansion adds nothing new and falls through.
                        attempt--;
                        continue;
                    }
                    logger.info("Cluster expansion produced no new classes; stopping expansion loop");
                    diagnostics.add("Skipped expansion: no new classes resolved");
                }
            }

            if (!validTests.isEmpty() && !repairOptions.isKeepAssertionsInParsedTests()) {
                int removed = 0;
                for (ParseResult pr : validTests) {
                    removed += LlmAssertionSanitizer.sanitize(pr.getTestCase());
                }
                if (removed > 0) {
                    diagnostics.add("Removed " + removed + " LLM assertion artifact(s) by policy");
                }
            }

            // Parse-phase compile gate: render parsed tests back to Java and run javac.
            // This catches source-level issues (imports/symbol visibility/name resolution)
            // before compile&rerun.
            if (!validTests.isEmpty()) {
                List<ParseResult> compileChecked = new ArrayList<>();
                for (ParseResult pr : validTests) {
                    CompileCheck compileCheck = checkRenderedCompilation(pr, repairDeadlineNanos);
                    if (compileCheck.isRejected()) {
                        String compileError = compileCheck.getRejection();
                        pr.addDiagnostic(new org.evosuite.testparser.ParseDiagnostic(
                                org.evosuite.testparser.ParseDiagnostic.Severity.ERROR, compileError, -1, ""));
                        droppedAtParse.add(pr);
                        diagnostics.add(compileError);
                    } else {
                        if (compileCheck.isNotJudged()) {
                            // The check itself could not run: keep the test, as the
                            // JUnit analyzer does, rather than spend a repair turn on it.
                            logger.warn("Parse-phase compile check did not run for '{}': {}",
                                    safeMethodName(pr), compileCheck.getCause());
                            diagnostics.add("Compile check skipped for parsed test '" + safeMethodName(pr)
                                    + "': " + compileCheck.getCause());
                        }
                        compileChecked.add(pr);
                    }
                }
                validTests = compileChecked;
            }

            // Execute valid tests and split into executable (kept) and dropped-at-execution.
            // When execution is disabled, every compile-checked test is trusted as-is:
            // the caller (e.g. a GA operator evaluating candidates itself) is responsible
            // for running it, and the repair loop must not touch TestCaseExecutor.
            List<ParseResult> finalTests = new ArrayList<>();
            List<ParseResult> droppedAtExecution = new ArrayList<>();
            Map<ParseResult, ExecutionFailureContext> executionErrorByTest = new LinkedHashMap<>();
            if (repairOptions.isExecuteParsedTests()) {
                for (ParseResult pr : validTests) {
                    ExecutionFailureContext failure = executionCheck.checkExecution(pr);
                    if (failure == null) {
                        finalTests.add(pr);
                    } else {
                        droppedAtExecution.add(pr);
                        executionErrorByTest.put(pr, failure);
                        diagnostics.add(failure.errorMessage);
                        executionDropsForSalvage.putIfAbsent(salvager.buildSalvageDedupKey(pr), pr);
                    }
                }
            } else {
                finalTests.addAll(validTests);
            }

            // Record any executable tests we have so far so they survive later repair turns.
            salvager.publishSalvagedTests(
                    salvager.mergeSalvagedTests(salvagedExecutableTests, finalTests),
                    salvagedTestsConsumer);

            boolean hasDropped = !droppedAtParse.isEmpty() || !droppedAtExecution.isEmpty();
            if (!hasDropped) {
                return RepairResult.success(new ArrayList<>(salvagedExecutableTests.values()),
                        diagnostics, attemptsUsed, expandedClasses);
            }

            // At least one test was dropped. Build a repair prompt that covers every
            // test: kept + dropped-at-parse + dropped-at-execution, so the LLM has
            // full context for the whole batch rather than just the first failure.
            String combinedError = buildCombinedRepairMessage(finalTests, droppedAtParse,
                    droppedAtExecution, executionErrorByTest);
            diagnostics.add("Partial success: kept " + finalTests.size()
                    + " executable test(s), " + droppedAtParse.size()
                    + " failed to parse, " + droppedAtExecution.size()
                    + " failed to execute. Requesting repair with combined diagnostics.");
            if (!droppedAtExecution.isEmpty()) {
                observedExecutionDrop = true;
            }

            String next = tryRepair(state, combinedError, attempt, previousError, diagnostics,
                    conversation, currentResponse, feature, expandedClasses,
                    executionErrorByTest, repairDeadlineNanos);
            if (next == null) {
                if (!repairOptions.isExecuteParsedTests()) {
                    diagnostics.add("Skipped execution-based salvage: parse-only mode");
                } else {
                    salvager.salvageFailedCandidates(droppedAtParse, executionDropsForSalvage.values(),
                            diagnostics, salvagedExecutableTests, salvagedTestsConsumer,
                            repairDeadlineNanos);
                    salvageAttempted = true;
                }
                if (!salvagedExecutableTests.isEmpty()) {
                    return RepairResult.success(new ArrayList<>(salvagedExecutableTests.values()),
                            diagnostics, attemptsUsed, expandedClasses);
                }
                break;
            }
            previousError = combinedError;
            currentResponse = next;
        }

        if (!salvageAttempted && !executionDropsForSalvage.isEmpty()) {
            // The loop ended on a parse failure after earlier attempts had
            // produced executable-but-failing candidates.
            salvager.salvageFailedCandidates(Collections.<ParseResult>emptyList(),
                    executionDropsForSalvage.values(), diagnostics, salvagedExecutableTests,
                    salvagedTestsConsumer, repairDeadlineNanos);
        }
        if (!salvagedExecutableTests.isEmpty()) {
            diagnostics.add("Returned partial success from previously executable tests.");
            return RepairResult.success(new ArrayList<>(salvagedExecutableTests.values()),
                    diagnostics, attemptsUsed, expandedClasses);
        }
        if (observedExecutionDrop) {
            // Distinct, easily greppable signal: every parsed candidate was
            // dropped at execution and no covering chromosome was salvaged.
            // Useful for triaging classes where the LLM "succeeds" but the
            // population is left empty.
            diagnostics.add("All LLM candidate tests dropped at execution; "
                    + "no covering chromosome obtained.");
            org.evosuite.rmi.ClientServices.trackQuietly(
                    org.evosuite.statistics.RuntimeVariable.LLM_All_Candidates_Dropped_Execution, 1);
            logger.warn("LLM seeding for {} dropped every candidate at execution after {} attempt(s)",
                    Properties.TARGET_CLASS, attemptsUsed);
        }
        return RepairResult.failure(diagnostics, attemptsUsed, expandedClasses, observedExecutionDrop);
    }

    private String joinExtractedSources(List<String> extractedSources) {
        if (extractedSources == null || extractedSources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < extractedSources.size(); i++) {
            if (i > 0) {
                sb.append(System.lineSeparator())
                        .append(System.lineSeparator())
                        .append("// --- extracted code block #")
                        .append(i + 1)
                        .append(" ---")
                        .append(System.lineSeparator());
            }
            sb.append(extractedSources.get(i));
        }
        return sb.toString();
    }

    /** Runs a salvage candidate through the injected executor; null when it could not be executed. */

    /**
     * Build a repair prompt body that tells the LLM about every test in the batch:
     * which ones are executable (and must stay verbatim), which ones were dropped
     * at parse time (with their ERROR diagnostics), and which ones parsed but
     * failed at execution (with their execution error and code excerpt). Ending
     * with an explicit instruction that the corrected class must reproduce the
     * kept tests and repair or regenerate only the failing ones.
     */
    private String buildCombinedRepairMessage(List<ParseResult> keptExecutable,
                                              List<ParseResult> droppedAtParse,
                                              List<ParseResult> droppedAtExecution,
                                              Map<ParseResult, ExecutionFailureContext> executionErrorByTest) {
        StringBuilder sb = new StringBuilder();
        appendAttritionSummary(sb, keptExecutable, droppedAtParse, droppedAtExecution, executionErrorByTest);

        if (keptExecutable != null && !keptExecutable.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(System.lineSeparator()).append(System.lineSeparator());
            }
            sb.append("The following generated tests already parse and execute cleanly; "
                    + "keep them unchanged in the corrected class:");
            for (ParseResult pr : keptExecutable) {
                sb.append(System.lineSeparator()).append("- ").append(safeMethodName(pr));
            }
        }

        if (droppedAtParse != null && !droppedAtParse.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(System.lineSeparator()).append(System.lineSeparator());
            }
            sb.append("The following tests were dropped because they failed to parse; "
                    + "please rewrite them:");
            for (ParseResult pr : droppedAtParse) {
                sb.append(System.lineSeparator()).append("* ").append(safeMethodName(pr)).append(":");
                boolean anyErrorEmitted = false;
                for (ParseDiagnostic d : pr.getDiagnostics()) {
                    if (shouldIncludeDroppedParseDiagnostic(d)) {
                        sb.append(System.lineSeparator()).append("  - ").append(d.toString());
                        anyErrorEmitted = true;
                    }
                }
                if (!anyErrorEmitted) {
                    logger.warn("Dropped parse result {} had no ERROR or LLM_REPAIR_ACTION_REQUIRED diagnostic: {}",
                            safeMethodName(pr), pr.getDiagnostics());
                    sb.append(System.lineSeparator()).append("  - (no ERROR diagnostic recorded)");
                }
            }
        }

        if (droppedAtExecution != null && !droppedAtExecution.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(System.lineSeparator()).append(System.lineSeparator());
            }
            sb.append("The following tests parsed but failed at execution; please repair them:");
            for (ParseResult pr : droppedAtExecution) {
                ExecutionFailureContext context =
                        executionErrorByTest == null ? null : executionErrorByTest.get(pr);
                String error = context == null ? null : context.errorMessage;
                sb.append(System.lineSeparator()).append("* ").append(safeMethodName(pr)).append(":")
                        .append(System.lineSeparator()).append("  - ")
                        .append(error == null ? "(execution error)" : error);
            }
        }

        List<ParseResult> failingForHints = new ArrayList<>();
        if (droppedAtParse != null) {
            failingForHints.addAll(droppedAtParse);
        }
        if (droppedAtExecution != null) {
            failingForHints.addAll(droppedAtExecution);
        }
        String enriched = hintComposer.enrichParseErrorWithRepairHints(failingForHints, sb.toString());

        StringBuilder out = new StringBuilder(enriched);
        if (out.length() > 0) {
            out.append(System.lineSeparator()).append(System.lineSeparator());
        }
        out.append("Return the complete corrected test class. "
                + "Keep the executable tests verbatim and fix or regenerate only the failing ones.");
        return out.toString();
    }

    private void appendAttritionSummary(StringBuilder sb,
                                        List<ParseResult> keptExecutable,
                                        List<ParseResult> droppedAtParse,
                                        List<ParseResult> droppedAtExecution,
                                        Map<ParseResult, ExecutionFailureContext> executionErrorByTest) {
        int keptCount = keptExecutable == null ? 0 : keptExecutable.size();
        int droppedAtParseCount = droppedAtParse == null ? 0 : droppedAtParse.size();
        int droppedAtExecutionCount = droppedAtExecution == null ? 0 : droppedAtExecution.size();
        int droppedCount = droppedAtParseCount + droppedAtExecutionCount;
        int totalCount = keptCount + droppedCount;
        if (droppedCount == 0 || totalCount < 2) {
            return;
        }

        sb.append("Batch attrition summary:");
        sb.append(System.lineSeparator()).append("- Started from ").append(totalCount)
                .append(" parsed candidate test(s); only ").append(keptCount)
                .append(" currently parse and execute cleanly.");
        sb.append(System.lineSeparator()).append("- Dropped ").append(droppedCount)
                .append(" test(s) so far (").append(droppedAtParseCount)
                .append(" parse-time, ").append(droppedAtExecutionCount)
                .append(" execution-time).");
        sb.append(System.lineSeparator()).append("- Do not solve this by returning only the surviving test(s) or by "
                + "silently deleting most failing scenarios.");
        sb.append(System.lineSeparator()).append("- Return a complete corrected class that preserves the kept tests "
                + "and repairs or regenerates the dropped ones so the batch still exercises the intended behaviors.");

        String sharedExecutionHint = hintComposer.buildSharedExecutionFailureHint(executionErrorByTest);
        if (sharedExecutionHint != null && !sharedExecutionHint.isEmpty()) {
            sb.append(System.lineSeparator()).append("- ").append(sharedExecutionHint);
        }
    }

    /**
     * Sends a repair request and accumulates the conversation with the assistant's
     * previous response and the error feedback, so subsequent repairs have full context.
     */
    private String requestRepair(RepairConversation state,
                                 List<LlmMessage> conversation,
                                 String previousResponse,
                                 String error,
                                 LlmFeature feature,
                                 List<String> expandedClasses,
                                 int repairAttempt,
                                 Map<ParseResult, ExecutionFailureContext> executionFailures,
                                 long repairDeadlineNanos) {
        // Ensure a system message is at the front of the conversation. The
        // conversation seeded from the original prompt already carries one
        // (possibly longer than `systemPrompt`, e.g. with the cached static
        // SUT/dependency context appended) — only prepend when none is there.
        if (systemPrompt != null && !systemPrompt.isEmpty()
                && (conversation.isEmpty()
                    || conversation.get(0).getRole() != LlmMessage.Role.SYSTEM)) {
            conversation.add(0, LlmMessage.system(systemPrompt));
        }

        // Add the assistant's last response to the conversation
        if (previousResponse != null && !previousResponse.isEmpty()) {
            conversation.add(LlmMessage.assistant(previousResponse));
        }
        // Build repair message with error + SUT context reminder
        StringBuilder repairMessage = new StringBuilder();
        String priorityDirective = state.takePriorityDirective();
        if (priorityDirective != null && !priorityDirective.isEmpty()) {
            repairMessage.append("=== PRIORITY DIRECTIVE ===\n")
                         .append(priorityDirective)
                         .append("\n=== END PRIORITY DIRECTIVE ===\n\n");
        }
        if (!expandedClasses.isEmpty()) {
            repairMessage.append("Note: The test cluster has been expanded with newly resolved classes: ")
                         .append(expandedClasses).append("\n\n");
            // The names above are not enough for the model to actually use the
            // new classes (no constructors/methods). When the dependency
            // catalogue lives in the cached system message, render a bounded
            // signature delta here instead — once per class per conversation.
            if (Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT) {
                Set<String> newlyDescribed = state.takeUndescribedDependencyClasses(expandedClasses);
                if (!newlyDescribed.isEmpty()) {
                    String growthDelta = ClusterGrowthDelta.render(dependencyGrowthSummarizer,
                            testClusterSupplier.get(), Properties.TARGET_CLASS, newlyDescribed);
                    if (growthDelta != null) {
                        repairMessage.append(growthDelta).append("\n\n");
                    }
                }
            }
        }
        repairMessage.append("The following issue was found in the generated tests:\n")
                     .append(error);
        hintComposer.appendFailureHints(state, error, previousResponse, executionFailures,
                repairAttempt, repairMessage);
        // Include the bulk dependency-types catalog only on the first repair
        // turn (repairAttempt == 2 in the conversation numbering: 1 = initial
        // seed, 2 = first repair). On later turns the LLM already has it in
        // its conversation history, and re-emitting tens of KB of catalog text
        // dilutes the failure-specific hints that should drive the repair. The
        // same holds when the original prompt carried the catalogue itself
        // (in the system message with static caching, in the user message
        // without), which the prompt reports.
        String summary = sutContextSummary();
        if (summary != null && !summary.isEmpty() && repairAttempt <= 2
                && !state.isDependencyContextInPrompt()) {
            repairMessage.append("\n\nFor reference, here are the available constructors and methods:\n")
                         .append(summary);
        }
        if (repairOptions.isInstructRepairToAvoidAssertions()) {
            repairMessage.append("\n\nIMPORTANT: Do NOT include assertions "
                    + "(no assert* calls or Java assert statements).");
        }
        hintComposer.appendFallbackRepairInstructions(error, repairMessage);
        repairMessage.append("\n\nPlease provide the corrected complete test class with all test methods.");

        conversation.add(LlmMessage.user(repairMessage.toString()));

        boolean expanded = state.takeExpansionAttempted();
        return llmService.query(LlmRequest.of(conversation, feature)
                .withContext(state.getPrompt())
                .withRepairAttempt(repairAttempt)
                .withExpansion(expanded, expandedClasses)
                .withDeadlineNanos(repairDeadlineNanos)).getText();
    }

    private String buildNoTestMethodsParseError(String extractedClass) {
        StringBuilder error = new StringBuilder("Parser produced no test methods.");
        List<String> malformedImports = RepairHintComposer.extractMalformedImportLines(extractedClass);
        if (!malformedImports.isEmpty()) {
            error.append("\nDetected malformed import statement(s):");
            for (String importLine : malformedImports.subList(0, Math.min(2, malformedImports.size()))) {
                error.append("\n- ").append(importLine);
            }
            error.append("\nRepair action: Java import declarations must use package dots, not file-path slashes, and "
                    + "do NOT support 'as' aliases. ");
            error.append("Example: import net.sourceforge.beanbin.query.Query; (not 'import ... as ...'). ");
            error.append("To resolve naming conflicts, use fully qualified names at the call site.");
        } else if (extractedClass == null || extractedClass.trim().isEmpty()) {
            error.append("\nThe LLM response was empty or contained no Java code. This can happen when:");
            error.append("\n- The LLM hit a token limit and returned only context/documentation");
            error.append("\n- The response was formatted as prose or explanation instead of code");
            error.append("\n- The LLM failed silently or returned only context restatement");
            error.append("\nRepair action: Return a complete valid Java test class with @Test methods.");
        } else if (extractedClass.contains("BYTECODE_DISASSEMBLED") || extractedClass.contains("access flags") 
                   || (extractedClass.contains("public class ") && extractedClass.contains(".java"))) {
            error.append("\nThe LLM response contained only bytecode disassembly or context, not test code.");
            error.append("\nRepair action: Generate actual Java test code with @Test methods, not context "
                    + "documentation.");
        } else if (extractedClass.contains("@Test")) {
            error.append("\nThe extracted source appears to contain @Test annotations, so malformed Java syntax "
                    + "earlier in the class likely prevented method extraction.");
        } else {
            error.append("\nThe extracted source did not contain any detectable @Test methods.");
        }
        return error.toString();
    }

    private enum OutputContractStatus {
        OK,
        RECOVERED,
        HARD_REJECT
    }

    private OutputContractStatus classifyOutputContractStatus(String rawResponse,
                                                              String extractedClass,
                                                              boolean wrapperRecovered) {
        if (wrapperRecovered) {
            return OutputContractStatus.RECOVERED;
        }
        String raw = rawResponse == null ? "" : rawResponse.trim();
        String extracted = extractedClass == null ? "" : extractedClass.trim();
        if (raw.isEmpty() || extracted.isEmpty()) {
            return OutputContractStatus.HARD_REJECT;
        }
        boolean mentionsTest = raw.contains("@Test")
                || raw.contains("@org.junit.Test")
                || raw.contains("@org.junit.jupiter.api.Test");
        boolean mentionsClass = raw.contains(" class ") || raw.startsWith("class ");
        boolean extractedHasTests = extracted.contains("@Test")
                || extracted.contains("@org.junit.Test")
                || extracted.contains("@org.junit.jupiter.api.Test");
        if (!extractedHasTests && !mentionsTest && !mentionsClass) {
            return OutputContractStatus.HARD_REJECT;
        }
        return OutputContractStatus.OK;
    }

    private String buildStrictContractRepairRequest(String parseErrorText, String extractedClass) {
        StringBuilder strict = new StringBuilder();
        strict.append("OUTPUT CONTRACT VIOLATION (hard reject).\n");
        strict.append("Return ONLY a complete Java test class containing @Test methods.\n");
        strict.append("Do NOT return explanations, bytecode context, or prose.\n");
        strict.append("Required shape:\n");
        strict.append("- one Java class\n");
        strict.append("- one or more methods annotated with @Test\n");
        strict.append("- compilable Java source\n\n");
        strict.append("Previous parser diagnostics:\n").append(parseErrorText);
        if (extractedClass != null && !extractedClass.trim().isEmpty()) {
            strict.append("\n\nExtracted source snippet that failed contract:\n```java\n")
                    .append(truncate(extractedClass, MAX_TEST_CODE_EXCERPT_CHARS))
                    .append("\n```");
        }
        return strict.toString();
    }

    private boolean shouldIncludeDroppedParseDiagnostic(ParseDiagnostic diagnostic) {
        if (diagnostic == null) {
            return false;
        }
        if (diagnostic.getSeverity() == ParseDiagnostic.Severity.ERROR) {
            return true;
        }
        return diagnostic.getSeverity() == ParseDiagnostic.Severity.WARNING
                && diagnostic.getMessage() != null
                && diagnostic.getMessage().contains(EXPLICIT_REPAIR_ACTION_MARKER);
    }

    /**
     * Returns the LLM's repair response, or {@code null} if the repair loop
     * should stop. Stop conditions: the attempt budget is exhausted, the error
     * is unfixable / a repeat, or the repair request itself failed.
     */
    private String tryRepair(RepairConversation state,
                             String errorText,
                             int attempt,
                             String previousError,
                             List<String> diagnostics,
                             List<LlmMessage> conversation,
                             String currentResponse,
                             LlmFeature feature,
                             List<String> expandedClasses,
                             Map<ParseResult, ExecutionFailureContext> executionFailures,
                             long repairDeadlineNanos) {
        if (attempt == maxAttempts || state.shouldSkipRepair(errorText, previousError, diagnostics)) {
            return null;
        }
        if (!hasTimeForAnotherRepairRequest(repairDeadlineNanos)) {
            diagnostics.add("Skipped repair: sync deadline too close; returning partial/salvaged tests if available");
            return null;
        }
        return requestRepairSafely(state, conversation, currentResponse, errorText,
                feature, expandedClasses, diagnostics, attempt + 2,
                executionFailures, repairDeadlineNanos);
    }

    private String tryStrictContractRepair(RepairConversation state,
                                           String errorText,
                                           int attempt,
                                           List<String> diagnostics,
                                           List<LlmMessage> conversation,
                                           String currentResponse,
                                           LlmFeature feature,
                                           List<String> expandedClasses,
                                           long repairDeadlineNanos) {
        if (attempt == maxAttempts) {
            return null;
        }
        if (!hasTimeForAnotherRepairRequest(repairDeadlineNanos)) {
            diagnostics.add("Skipped strict contract repair: sync deadline too close; returning partial/salvaged tests "
                    + "if available");
            return null;
        }
        return requestRepairSafely(state, conversation, currentResponse, errorText,
                feature, expandedClasses, diagnostics, attempt + 2,
                Collections.<ParseResult, ExecutionFailureContext>emptyMap(),
                repairDeadlineNanos);
    }

    private boolean hasTimeForAnotherRepairRequest(long repairDeadlineNanos) {
        if (repairDeadlineNanos == NO_REPAIR_DEADLINE || repairDeadlineNanos <= 0L) {
            return true;
        }
        long remainingNanos = PhaseDeadline.remainingNanos(repairDeadlineNanos);
        long minRepairCallNanos = TimeUnit.SECONDS.toNanos(
                Math.max(1L, Properties.LLM_TIMEOUT_SECONDS));
        return remainingNanos > minRepairCallNanos + REPAIR_DEADLINE_RETURN_MARGIN_NANOS;
    }

    private String requestRepairSafely(RepairConversation state,
                                       List<LlmMessage> conversation,
                                       String previousResponse,
                                       String error,
                                       LlmFeature feature,
                                       List<String> expandedClasses,
                                       List<String> diagnostics,
                                       int repairAttempt,
                                       Map<ParseResult, ExecutionFailureContext> executionFailures,
                                       long repairDeadlineNanos) {
        try {
            return requestRepair(state, conversation, previousResponse, error, feature,
                    expandedClasses, repairAttempt, executionFailures,
                    repairDeadlineNanos);
        } catch (Throwable repairFailure) {
            diagnostics.add("Repair request failure: " + formatThrowable(repairFailure));
            return null;
        }
    }

    private boolean hasResolutionErrors(List<ParseResult> parseResults) {
        for (ParseResult parseResult : parseResults) {
            for (ParseDiagnostic diagnostic : parseResult.getDiagnostics()) {
                if (diagnostic.getSeverity() != ParseDiagnostic.Severity.ERROR) {
                    continue;
                }
                String message = diagnostic.getMessage() == null
                        ? ""
                        : diagnostic.getMessage().toLowerCase();
                if (message.contains("cannot") || message.contains("unresolved") || message.contains("not found")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the error is an environment-level problem that the LLM
     * cannot fix (missing native libraries, sandbox restrictions, etc.).
     */
    static boolean isUnfixableError(String error) {
        if (error == null) {
            return false;
        }
        if (isDependencyMissingError(error)) {
            return false;
        }
        if (isSnippetCompilerInfrastructureError(error)) {
            return true;
        }
        for (String pattern : UNFIXABLE_ERROR_PATTERNS) {
            if (error.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSnippetCompilerInfrastructureError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        return lower.contains("snippet compilation failed")
                && lower.contains("error reading")
                && lower.contains("urisyntaxexception");
    }

    static boolean isDependencyMissingError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        for (String marker : DEPENDENCY_MISSING_MARKERS) {
            if (error.contains(marker)) {
                return true;
            }
        }
        return MISSING_CLASS_SYMBOL_PATTERN.matcher(error).find();
    }

    /**
     * Outcome of the parse-phase compile probe: the rendered test compiled, javac rejected it, or
     * the probe could not run (no compiler, timeout, sandbox restriction, internal failure) and
     * therefore says nothing about the test.
     */
    static final class CompileCheck {
        private static final CompileCheck COMPILED = new CompileCheck(null, null);

        private final String rejection;
        private final String cause;

        private CompileCheck(String rejection, String cause) {
            this.rejection = rejection;
            this.cause = cause;
        }

        static CompileCheck rejected(String rejection) {
            return new CompileCheck(rejection, null);
        }

        static CompileCheck notJudged(String cause) {
            return new CompileCheck(null, cause);
        }

        boolean isRejected() {
            return rejection != null;
        }

        boolean isNotJudged() {
            return cause != null;
        }

        String getRejection() {
            return rejection;
        }

        String getCause() {
            return cause;
        }
    }

    private CompileCheck checkRenderedCompilation(ParseResult parseResult, long repairDeadlineNanos) {
        if (parseResult == null || parseResult.getTestCase() == null) {
            return CompileCheck.rejected(
                    "Compilation error in parsed test '<unnamed>': missing parsed test case");
        }
        if (parseCompileCompiler() == null) {
            return CompileCheck.notJudged("No Java compiler available in current runtime");
        }

        org.evosuite.testcase.TestCodeVisitor visitor = new org.evosuite.testcase.TestCodeVisitor();
        try {
            parseResult.getTestCase().accept(visitor);
        } catch (Throwable t) {
            return CompileCheck.notJudged("failed to render parsed test code: " + formatThrowable(t));
        }
        String methodBody = visitor.getCode() == null ? "" : visitor.getCode();
        String source = buildCompilationProbeSource(parseResult, methodBody, visitor.getImports());

        try {
            final String method = safeMethodName(parseResult);
            final String finalSource = source;
            return PARSE_COMPILE_WORKER.call(() -> runRenderedCompilationCheck(method, finalSource),
                    parseCompileTimeoutNanos(repairDeadlineNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            return CompileCheck.notJudged("compile check timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted during parse-phase compile check");
        } catch (SecurityException securityException) {
            return CompileCheck.notJudged("compile check failed due to sandbox/security restrictions: "
                    + securityException.getMessage());
        } catch (Throwable t) {
            if (isSecurityInfrastructureError(t)) {
                return CompileCheck.notJudged(
                        "compile check failed due to security infrastructure error: " + t);
            }
            return CompileCheck.notJudged("compile check failed: " + formatThrowable(t));
        }
    }

    /**
     * Registers the parse-phase compilation worker thread as privileged with
     * the EvoSuite sandbox. Must be invoked by a privileged EvoSuite thread
     * after sandbox initialization. A worker thread started while the sandbox
     * is up registers itself on creation; this covers a thread that already
     * existed when the sandbox was (re)initialized.
     */
    public static void registerParseCompileThreadAsPrivileged() {
        if (!Sandbox.isSecurityManagerInitialized()) {
            return;
        }
        try {
            Thread worker = PARSE_COMPILE_WORKER.call(Thread::currentThread, 2L, TimeUnit.SECONDS);
            Sandbox.addPrivilegedThread(worker);
        } catch (TimeoutException timeout) {
            logger.warn("Timed out registering parse-compile worker; replaced the blocked executor");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (SecurityException se) {
            // Caller is not privileged right now; another privileged caller may register later.
            logger.debug("Could not register parse-compile worker as privileged from thread '{}': {}",
                    Thread.currentThread().getName(), se.getMessage());
        } catch (Throwable t) {
            logger.debug("Could not bootstrap parse-compile worker privilege registration: {}", t.toString());
        }
    }

    private static long parseCompileTimeoutNanos(long repairDeadlineNanos) {
        if (repairDeadlineNanos == NO_REPAIR_DEADLINE) {
            return DEFAULT_PARSE_COMPILE_TIMEOUT_NANOS;
        }
        long remaining = PhaseDeadline.remainingNanos(repairDeadlineNanos);
        // The repair deadline governs whether another provider request may be
        // started. If it has already elapsed, still validate locally salvaged
        // tests; caller cancellation remains able to interrupt this bounded
        // compile task.
        if (remaining <= 0L) {
            return DEFAULT_PARSE_COMPILE_TIMEOUT_NANOS;
        }
        return Math.min(DEFAULT_PARSE_COMPILE_TIMEOUT_NANOS, remaining);
    }

    /** Cancel compile probes and provision a clean worker for the next EvoSuite run. */
    public static void resetForRunCompletion() {
        PARSE_COMPILE_WORKER.reset();
    }

    static void setParseCompileExecutorForTesting(ExecutorService executor) {
        PARSE_COMPILE_WORKER.setForTesting(executor);
    }

    /** Test-only seam: see {@link #testClusterSupplier}. */
    void setTestClusterSupplierForTesting(Supplier<TestCluster> supplier) {
        this.testClusterSupplier = supplier == null ? TestCluster::getInstance : supplier;
    }

    private CompileCheck runRenderedCompilationCheck(String methodName, String source) throws Exception {
        JavaCompiler compiler = parseCompileCompiler();
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("evosuite-llm-parse-compile-");
            String packageName = getSutPackage();
            String className = compilationProbeClassName(methodName);
            Path pkgDir = tmpDir;
            if (packageName != null && !packageName.trim().isEmpty()) {
                pkgDir = tmpDir.resolve(packageName.replace('.', File.separatorChar));
                Files.createDirectories(pkgDir);
            }
            Path sourceFile = pkgDir.resolve(className + ".java");
            Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

            String classPath = GeneratedTestCompiler.compilerClasspathForGeneratedTests();
            int maxPasses = 3;
            for (int pass = 0; ; pass++) {
                GeneratedTestCompiler.CompilerAttemptResult attempt = GeneratedTestCompiler
                        .compileWithPrintedDiagnostics(compiler, Collections.singletonList(sourceFile.toFile()),
                                classPath);
                if (attempt.hasInfrastructureFailure()) {
                    Throwable failure = attempt.getInfrastructureFailure();
                    if (failure instanceof Error) {
                        throw (Error) failure;
                    }
                    throw (RuntimeException) failure;
                }
                if (attempt.isCompiled()) {
                    return CompileCheck.COMPILED;
                }
                String diagnostics = attempt.getCompilerOutput().trim();
                String crash = javacCrashLine(diagnostics);
                if (crash != null) {
                    // javac died or ran out of heap instead of judging the test, as the JUnit
                    // check also recognises: nothing the model could repair.
                    return CompileCheck.notJudged("javac crashed: " + crash);
                }

                boolean repaired = false;
                String repairedClasspath = org.evosuite.testcase.execution.ExecutableSnippetEngine.INSTANCE
                        .sanitizeClasspathForKnownCompileIssues(classPath, diagnostics);
                if (repairedClasspath != null && !repairedClasspath.equals(classPath)) {
                    classPath = repairedClasspath;
                    repaired = true;
                }

                if (!repaired || pass == maxPasses) {
                    return CompileCheck.rejected("Compilation error in parsed test '" + methodName
                            + "': " + diagnostics
                            + "\nNote: this error comes from EvoSuite-rendered synthesized test source (after "
                                    + "parsing), not directly from raw LLM text."
                            + "\nRendered compile-check class excerpt:\n```java\n"
                            + truncate(source, MAX_TEST_CODE_EXCERPT_CHARS)
                            + "\n```");
                }
            }
        } finally {
            if (tmpDir != null) {
                FileUtils.deleteQuietly(tmpDir.toFile());
            }
        }
    }

    /**
     * The line with which javac reported a crash of its own (an internal exception, a stack
     * overflow or an exhausted heap) instead of a verdict on the source, or {@code null}. Only
     * lines that start with the marker count: javac quotes source lines as they stand in the
     * probe class, where test code is indented, so a test mentioning these errors cannot match.
     */
    static String javacCrashLine(String compilerOutput) {
        if (compilerOutput == null) {
            return null;
        }
        for (String line : compilerOutput.split("\\R")) {
            if (line.startsWith(GeneratedTestCompiler.JAVAC_INTERNAL_ERROR_MARKER)
                    || line.startsWith(GeneratedTestCompiler.JAVAC_STACK_OVERFLOW_MARKER)
                    || line.startsWith(GeneratedTestCompiler.JAVAC_OUT_OF_MEMORY_MARKER)) {
                return line.trim();
            }
        }
        return null;
    }

    private static JavaCompiler parseCompileCompiler() {
        JavaCompiler override = parseCompileCompilerForTesting;
        return override != null ? override : ToolProvider.getSystemJavaCompiler();
    }

    /** Test-only seam: the compiler the parse-phase probe runs, {@code null} for the system one. */
    static void setParseCompileCompilerForTesting(JavaCompiler compiler) {
        parseCompileCompilerForTesting = compiler;
    }

    private boolean isSecurityInfrastructureError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8) {
            if (current instanceof SecurityException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("unable to create temporary file")
                        || lower.contains("unable to create temporary directory")
                        || lower.contains("access denied")
                        || lower.contains("permission denied")) {
                    return true;
                }
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /** The probe class's name, which must match between its source file and its declaration. */
    private static String compilationProbeClassName(String methodName) {
        return "__ParseCompileProbe_" + Integer.toUnsignedString(methodName.hashCode());
    }

    private String buildCompilationProbeSource(ParseResult parseResult,
                                               String methodBody,
                                               Set<Class<?>> imports) {
        String packageName = getSutPackage();
        String className = compilationProbeClassName(safeMethodName(parseResult));
        StringBuilder sb = new StringBuilder();
        if (packageName != null && !packageName.trim().isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        UnitTestAdapter adapter = TestSuiteWriterUtils.getAdapter();
        ImportCollector importCollector = new ImportCollector(sb);
        appendUniqueImportLines(importCollector, adapter.getImports());

        // The names as this build of EvoSuite sees them, relocated in the shaded jar.
        String mockitoCanonical = org.mockito.Mockito.class.getCanonicalName();
        importCollector.append("import " + mockitoCanonical + ";");
        importCollector.append("import static " + mockitoCanonical + ".*;");
        String matchersCanonical = org.mockito.ArgumentMatchers.class.getCanonicalName();
        importCollector.append("import " + matchersCanonical + ";");
        importCollector.append("import static " + matchersCanonical + ".*;");
        importCollector.append("import static "
                + org.evosuite.runtime.EvoAssertions.class.getCanonicalName() + ".*;");
        importCollector.append("import "
                + org.evosuite.runtime.ViolatedAssumptionAnswer.class.getCanonicalName() + ";");
        
        sb.append("import java.lang.reflect.Field;\n");
        sb.append("import java.lang.reflect.Method;\n");
        sb.append("import java.lang.reflect.Modifier;\n");
        sb.append("import java.lang.reflect.Array;\n");
        if (imports != null) {
            for (Class<?> clazz : imports) {
                if (clazz == null) {
                    continue;
                }
                String canonical = clazz.getCanonicalName();
                if (canonical == null || canonical.isEmpty() || canonical.startsWith("java.lang.")) {
                    continue;
                }
                if (canonical.startsWith("shaded.org.evosuite.shaded.org.mockito.")) {
                    canonical = canonical.replace("shaded.org.evosuite.shaded.org.mockito.", "org.mockito.");
                }
                importCollector.append("import " + canonical.replace('$', '.') + ";");
            }
        }
        sb.append("\npublic class ").append(className).append(" {\n");
        sb.append("  @Test\n");
        sb.append("  public void ").append(sanitizeMethodName(safeMethodName(parseResult)))
                .append("() throws Throwable {\n");
        if (methodBody != null && !methodBody.isEmpty()) {
            String[] lines = methodBody.split("\\R", -1);
            for (String line : lines) {
                sb.append("    ").append(line).append('\n');
            }
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private void appendUniqueImportLines(ImportCollector collector, String rawImports) {
        if (collector == null || rawImports == null || rawImports.trim().isEmpty()) {
            return;
        }
        for (String line : rawImports.split("\\R")) {
            collector.append(line);
        }
    }

    private static final class ImportCollector {
        private final StringBuilder sb;
        private final Set<String> seenExactImports = new LinkedHashSet<>();
        private final Set<String> seenNormalSimpleNames = new LinkedHashSet<>();
        private final Set<String> seenStaticSimpleNames = new LinkedHashSet<>();

        private ImportCollector(StringBuilder sb) {
            this.sb = sb;
        }

        private void append(String rawLine) {
            String trimmed = rawLine == null ? "" : rawLine.trim();
            if (!trimmed.startsWith("import ")) {
                return;
            }
            if (!trimmed.endsWith(";")) {
                trimmed = trimmed + ";";
            }
            if (!seenExactImports.add(trimmed)) {
                return;
            }
            String simpleName = TestSuiteWriterUtils.importSimpleName(trimmed);
            if (simpleName != null) {
                Set<String> seen = trimmed.startsWith("import static ")
                        ? seenStaticSimpleNames
                        : seenNormalSimpleNames;
                if (!seen.add(simpleName)) {
                    seenExactImports.remove(trimmed);
                    return;
                }
            }
            sb.append(trimmed).append('\n');
        }
    }

    private String sanitizeMethodName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "test0";
        }
        String trimmed = name.trim();
        if (!Character.isJavaIdentifierStart(trimmed.charAt(0))) {
            trimmed = "test_" + trimmed;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            out.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return out.toString();
    }

    public interface TestExecutor {
        ExecutionResult execute(org.evosuite.testcase.TestCase testCase);
    }

    private static class DefaultExecutor implements TestExecutor {
        @Override
        public ExecutionResult execute(org.evosuite.testcase.TestCase testCase) {
            if (!TestCaseExecutor.isAvailable()) {
                // Keep parse-phase execution checks aligned with compile&rerun:
                // if a prior phase called TestCaseExecutor.pullDown(), eagerly
                // recreate the singleton executor instead of silently skipping
                // runtime/assertion validation for this parsed test.
                logger.info("TestCaseExecutor unavailable during parse-phase execution check; reinitializing.");
                try {
                    TestCaseExecutor.getInstance();
                    if (!TestCaseExecutor.isAvailable()) {
                        logger.warn("TestCaseExecutor reinitialization did not restore availability; "
                                + "execution check will be skipped for this parsed test.");
                        return null;
                    }
                } catch (Throwable t) {
                    logger.warn("Could not reinitialize TestCaseExecutor during parse-phase execution check: {}",
                            t.toString());
                    return null;
                }
            }
            // Hold the executor's lock around the whole prep+execute+cleanup so
            // class-reset and headless/mock toggles cannot race with a concurrent
            // GA fitness evaluation on the search thread.
            synchronized (TestCaseExecutor.getInstance().getExecutionLock()) {
                if (Properties.RESET_STATIC_FIELDS) {
                    // Align repair-time execution with later JUnit rerun behavior by
                    // resetting known initialized classes before execution as well.
                    // Otherwise stale static state from previous checks can mask
                    // failures that only appear during compile/rerun.
                    ClassReInitializer.getInstance().resetAllInitializedClasses(1);
                }
                boolean shouldEnableMockFramework =
                        RuntimeSettings.mockJVMNonDeterminism || Properties.REPLACE_GUI || RuntimeSettings.mockGUI;
                boolean guiGuardEnabled = Properties.REPLACE_GUI || RuntimeSettings.mockGUI;
                if (!shouldEnableMockFramework && !guiGuardEnabled) {
                    return TestCaseExecutor.runTest(testCase);
                }

                boolean wasMockFrameworkEnabled = MockFramework.isEnabled();
                boolean disabledHeadless = false;
                try {
                    if (shouldEnableMockFramework) {
                        MockFramework.enable();
                    }
                    if (guiGuardEnabled) {
                        GuiSupport.disableHeadlessForMockConstruction();
                        disabledHeadless = true;
                    }
                    return TestCaseExecutor.runTest(testCase);
                } finally {
                    if (disabledHeadless) {
                        GuiSupport.restoreHeadlessAfterMockConstruction();
                    }
                    if (!wasMockFrameworkEnabled) {
                        MockFramework.disable();
                    }
                }
            }
        }
    }
}
