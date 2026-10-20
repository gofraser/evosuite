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

import org.evosuite.llm.search.LlmPrivilegedThreads;
import org.evosuite.Properties;
import org.evosuite.junit.UnitTestAdapter;
import org.evosuite.junit.writer.TestSuiteWriterUtils;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.ClusterGrowthDelta;
import org.evosuite.llm.prompt.StaticPromptContext;
import org.evosuite.llm.prompt.SystemPromptProvider;
import org.evosuite.llm.prompt.TestClusterSummarizer;
import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.evosuite.llm.response.ExecutionFailureFormatter.EXPLICIT_REPAIR_ACTION_MARKER;
import static org.evosuite.llm.response.ExecutionFailureFormatter.MAX_TEST_CODE_EXCERPT_CHARS;
import static org.evosuite.llm.response.ExecutionFailureFormatter.formatThrowable;
import static org.evosuite.llm.response.MockRuntimeExceptions.MOCK_RUNTIME_PACKAGE_PREFIX;
import static org.evosuite.llm.response.MockRuntimeExceptions.errorMentionsMockRuntimeException;
import static org.evosuite.llm.response.MockRuntimeExceptions.isRaisedByEvoSuiteRuntime;
import static org.evosuite.llm.response.MockRuntimeExceptions.reportedExceptionName;
import static org.evosuite.llm.response.ParsedTestExecutionCheck.matchesExpectedExceptionName;
import static org.evosuite.llm.response.RepairText.MISSING_METHOD_DIAGNOSTIC_PATTERN;
import static org.evosuite.llm.response.RepairText.NEW_CALL_PATTERN;
import static org.evosuite.llm.response.RepairText.NULL_DECLARATION_PATTERN;
import static org.evosuite.llm.response.RepairText.extractMissingMethodName;
import static org.evosuite.llm.response.RepairText.extractTypedFallbackDeclaredType;
import static org.evosuite.llm.response.RepairText.getSutPackage;
import static org.evosuite.llm.response.RepairText.isUnresolvedTypeDiagnostic;
import static org.evosuite.llm.response.RepairText.joinTop;
import static org.evosuite.llm.response.RepairText.safeMethodName;
import static org.evosuite.llm.response.RepairText.simpleName;
import static org.evosuite.llm.response.RepairText.stripTypeDecorators;
import static org.evosuite.llm.response.RepairText.truncate;

/**
 * Applies parse -> validate -> execute and iterative LLM repair.
 *
 * <p>An instance holds configuration only; the state of one {@link #attemptParse}
 * call lives in a {@link RepairConversation}, so threads may share a loop.
 */
public class TestRepairLoop {

    private static final Logger logger = LoggerFactory.getLogger(TestRepairLoop.class);
    static final long NO_REPAIR_DEADLINE = Long.MAX_VALUE;
    static final long REPAIR_DEADLINE_RETURN_MARGIN_NANOS = TimeUnit.SECONDS.toNanos(2);

    private static final Pattern NPE_NULL_VARIABLE_PATTERN = Pattern.compile(
            "because\\s+\"([^\"]+)\"\\s+is null");
    private static final Pattern NPE_CANNOT_INVOKE_PATTERN = Pattern.compile(
            "Cannot invoke\\s+\"([^\"]+)\"\\s+because\\s+\"([^\"]+)\"\\s+is null");
    private static final Pattern SYNTHETIC_LOCAL_VARIABLE_PATTERN = Pattern.compile(
            "<local\\d+>");
    private static final Pattern INIT_FAILURE_CLASS_PATTERN = Pattern.compile(
            "Could not initialize class\\s+([A-Za-z0-9_.$]+)");
    private static final Pattern NEW_DECLARATION_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_$.<>]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new"
                    + "\\s+([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\(");
    private static final Pattern PARSED_TEST_CODE_EXCERPT_PATTERN = Pattern.compile(
            "Parsed test code excerpt:\\n```java\\n(.*?)\\n```", Pattern.DOTALL);
    private static final Pattern ANONYMOUS_TYPE_CREATION_PATTERN = Pattern.compile(
            "\\bnew\\s+([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\([^)]*\\)\\s*\\{");
    private static final Pattern MISSING_SYMBOL_CLASS_PATTERN = Pattern.compile(
            "symbol:\\s+class\\s+([A-Za-z_][A-Za-z0-9_$]*)", Pattern.MULTILINE);
    private static final Pattern MISSING_PACKAGE_PATTERN = Pattern.compile(
            "package\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s+does\\s+not\\s+exist", Pattern.MULTILINE);
    private static final Pattern MISSING_SYMBOL_METHOD_WITH_LOCATION_PATTERN = Pattern.compile(
            "symbol:\\s+method\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*\\([^)]*\\)\\s*"
                    + "location:\\s+variable\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s+of\\s+type\\s+([A-Za-z_][A-Za-z0-9_$.]*)",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern MISSING_SYMBOL_VARIABLE_WITH_LOCATION_PATTERN = Pattern.compile(
            "symbol:\\s+variable\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*"
                    + "location:\\s+variable\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s+of\\s+type\\s+([A-Za-z_][A-Za-z0-9_$.]*)",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern MALFORMED_IMPORT_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?[^;]*([/\\\\]|\\s+as\\s+)[^;]*;\\s*$");

    private static final Pattern ALIASED_IMPORT_PATTERN = Pattern.compile(
            "(?m)^\\s*import\\s+[^;]+\\s+as\\s+([^;]+);\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Object PARSE_COMPILE_EXECUTOR_LOCK = new Object();
    private static final long DEFAULT_PARSE_COMPILE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static volatile ExecutorService parseCompileExecutor = createParseCompileExecutor();

    private static ExecutorService createParseCompileExecutor() {
        return Executors.newSingleThreadExecutor(r -> LlmPrivilegedThreads.newThread(r, "parse-compile"));
    }

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
    private static final Set<String> INITIALIZATION_FAILURE_MARKERS = new HashSet<>(Arrays.asList(
            "ExceptionInInitializerError",
            "NoClassDefFoundError",
            "Could not initialize class",
            "ClassNotFoundException",
            "UnsatisfiedLinkError",
            "LinkageError",
            "java.awt.AWTError",
            "HeadlessException",
            "Local GraphicsEnvironment must not be null"
    ));
    private static final Set<String> INSTANTIATION_FAILURE_MARKERS = new HashSet<>(Arrays.asList(
            "InstantiationException",
            "is abstract; cannot be instantiated",
            "cannot instantiate"
    ));
    private static final Set<String> MOCKITO_MISUSE_MARKERS = new HashSet<>(Arrays.asList(
            "NotAMockException",
            "UnfinishedStubbingException",
            "MissingMethodInvocationException",
            "org.mockito.exceptions.misusing"
    ));
    private static final Pattern FALSE_POSITIVE_MOCK_CALL_PATTERN = Pattern.compile(
            "Mock call to\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s+which was not presented when the test was generated");
    private static final Pattern FALSE_POSITIVE_MOCK_SIGNATURE_PATTERN = Pattern.compile(
            "(?:\\[Dependency Stack\\]\\s+)?([A-Za-z_$][A-Za-z0-9_.$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\(");
    private static final Set<String> INDEXED_FIXTURE_FAILURE_MARKERS = new HashSet<>(Arrays.asList(
            "ArrayIndexOutOfBoundsException",
            "IndexOutOfBoundsException",
            "NegativeArraySizeException"
    ));

    private final LlmService llmService;
    private final TestParser testParser;
    private final LlmResponseParser responseParser;
    private final ClusterExpansionManager clusterExpansionManager;
    private final TestExecutor testExecutor;
    private final int maxAttempts;
    private final String systemPrompt;
    private final String sutContextSummary;
    private final RepairOptions repairOptions;
    private final ExecutionFailureFormatter failureFormatter;
    private final ParsedTestExecutionCheck executionCheck;
    private final LlmTestSalvager salvager;
    private final DependencyCodeContextResolver dependencyCodeResolver = new DependencyCodeContextResolver();
    private final RepairHintResolver repairHintResolver = new RepairHintResolver();
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
        String systemPrompt = null;
        String sutContext = null;
        try {
            systemPrompt = new SystemPromptProvider().getSystemPrompt();
        } catch (Throwable t) {
            logger.debug("Could not obtain system prompt for repair context", t);
        }
        try {
            TestCluster cluster = TestCluster.getInstance();
            if (cluster != null) {
                TestClusterSummarizer.DependencySummaryResult deps =
                        new TestClusterSummarizer().summarizeDependencies(
                                cluster, Properties.TARGET_CLASS, 4000);
                sutContext = deps.getText();
            }
        } catch (Throwable t) {
            logger.debug("Could not obtain SUT context summary for repair context", t);
        }
        return new TestRepairLoop(
                llmService,
                TestParser.forSUTWithLlmProvenance(),
                new LlmResponseParser(),
                new ClusterExpansionManager(),
                new DefaultExecutor(),
                maxAttempts,
                systemPrompt,
                sutContext,
                options == null ? RepairOptions.defaults() : options);
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
                null,
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
                testExecutor, maxAttempts, null, null, RepairOptions.defaults());
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
        this.executionCheck = new ParsedTestExecutionCheck(testExecutor,
                this.repairOptions.isRepairOnAssertionFailures(), failureFormatter);
        this.salvager = new LlmTestSalvager(testExecutor,
                this.repairOptions.isKeepAssertionsInParsedTests(), executionCheck);
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
        List<String> diagnostics = new ArrayList<>();
        List<String> expandedClasses = new ArrayList<>();
        Map<String, ParseResult> salvagedExecutableTests = new LinkedHashMap<>();
        String currentResponse = llmResponse;
        int attemptsUsed = 0;
        RepairConversation state = new RepairConversation();
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
            try {
                org.evosuite.rmi.ClientServices.track(
                        org.evosuite.statistics.RuntimeVariable.LLM_All_Candidates_Dropped_Execution,
                        1);
            } catch (Throwable trackingFailure) {
                logger.debug("Could not publish LLM_All_Candidates_Dropped_Execution stat",
                        trackingFailure);
            }
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
        String enriched = enrichParseErrorWithRepairHints(failingForHints, sb.toString());

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

        String sharedExecutionHint = buildSharedExecutionFailureHint(executionErrorByTest);
        if (sharedExecutionHint != null && !sharedExecutionHint.isEmpty()) {
            sb.append(System.lineSeparator()).append("- ").append(sharedExecutionHint);
        }
    }

    private String buildSharedExecutionFailureHint(Map<ParseResult, ExecutionFailureContext> executionErrorByTest) {
        if (executionErrorByTest == null || executionErrorByTest.size() < 2) {
            return null;
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> hints = new LinkedHashMap<>();
        for (ExecutionFailureContext failureContext : executionErrorByTest.values()) {
            if (failureContext == null) {
                continue;
            }
            String executionError = failureContext.errorMessage;
            if (executionError == null || executionError.trim().isEmpty()) {
                continue;
            }

            String anonymousConstructedType = extractAnonymousConstructedType(executionError);
            if (anonymousConstructedType != null
                    && !anonymousConstructedType.isEmpty()
                    && isMalformedAnonymousSnippetSyntaxError(executionError)) {
                String key = "anon:" + anonymousConstructedType;
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
                hints.putIfAbsent(key, buildSharedAnonymousSnippetFailureHint(anonymousConstructedType));
                continue;
            }

            NpeDereferenceInfo dereferenceInfo = extractNpeDereferenceInfo(executionError);
            if (dereferenceInfo != null) {
                String key = "npe:" + dereferenceInfo.memberSignature + "::" + dereferenceInfo.nullVariable;
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
                hints.putIfAbsent(key, buildSharedNpeExecutionFailureHint(dereferenceInfo));
                continue;
            }

            String initializationFailureClass = extractInitializationFailureClassName(executionError);
            if (initializationFailureClass != null && !initializationFailureClass.isEmpty()) {
                String key = "init:" + initializationFailureClass;
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
                hints.putIfAbsent(key, "Multiple dropped tests share the same initialization failure in '"
                        + initializationFailureClass
                        + "'. Repair the shared environment/setup once or avoid that path instead of deleting those "
                                + "tests individually.");
                continue;
            }

            if (isIndexedFixtureShapeError(executionError)) {
                String key = "shape:indexed-fixture";
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
                hints.putIfAbsent(key, buildSharedIndexedFixtureFailureHint());
                continue;
            }

            if (isFalsePositiveMockError(executionError)) {
                String mockMethodName = pickFalsePositiveMockName(executionError);
                String key = mockMethodName == null || mockMethodName.isEmpty()
                        ? "mockfp:generic"
                        : "mockfp:" + mockMethodName;
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
                hints.putIfAbsent(key, buildSharedFalsePositiveMockFailureHint(mockMethodName));
            }
        }

        String bestKey = null;
        int bestCount = 1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestKey = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        if (bestKey == null) {
            return null;
        }
        return hints.get(bestKey) + " (" + bestCount + " tests)";
    }

    private String buildSharedAnonymousSnippetFailureHint(String constructedType) {
        String simpleType = simpleName(constructedType);
        return "Multiple dropped tests share the same anonymous-class snippet failure around '"
                + simpleType
                + "'. This is not a parser/import issue. Do not keep writing `new "
                + simpleType
                + "() { ... }`; replace it with Mockito.mock("
                + constructedType
                + ".class, new ViolatedAssumptionAnswer()) or a real concrete collaborator setup.";
    }

    private String buildSharedFalsePositiveMockFailureHint(String mockMethodName) {
        if (mockMethodName == null || mockMethodName.isEmpty()) {
            return "Multiple dropped tests fail because a ViolatedAssumptionAnswer mock path is missing one or more "
                    + "collaborator stubs. "
                    + "Repair the shared mock setup once by adding the minimal stub(s) the SUT actually calls instead "
                            + "of deleting those tests one-by-one.";
        }
        return "Multiple dropped tests fail because the SUT reaches an unstubbed mock call to `"
                + mockMethodName
                + "(...)` on a ViolatedAssumptionAnswer collaborator. Repair the shared mock setup once by stubbing "
                        + "that collaborator method (and any returned nested collaborator) instead of deleting those "
                        + "tests one-by-one.";
    }

    private String buildSharedNpeExecutionFailureHint(NpeDereferenceInfo dereferenceInfo) {
        StringBuilder hint = new StringBuilder();
        hint.append("Multiple dropped tests share the same execution root cause: null receiver '")
                .append(dereferenceInfo.nullVariable)
                .append("' inside SUT call '")
                .append(dereferenceInfo.memberSignature)
                .append("'.");
        if (dereferenceInfo.receiverType != null && !dereferenceInfo.receiverType.isEmpty()) {
            hint.append(" Repair the shared object/collaborator setup that should initialize this ")
                    .append(simpleName(dereferenceInfo.receiverType))
                    .append(" instance before that call, instead of deleting those tests one-by-one.");
        } else {
            hint.append(" Repair the shared object/collaborator setup before that call, instead of deleting those "
                    + "tests one-by-one.");
        }
        return hint.toString();
    }

    private String buildSharedIndexedFixtureFailureHint() {
        return "Multiple dropped tests share the same bounds/shape failure while constructing or populating indexed "
                + "fixtures. "
                + "Repair the shared setup pattern once: make constructor size/count arguments consistent with every "
                        + "later indexed get/set call, "
                + "do not assume every helper/container stores all slots exactly 1:1 with its constructor arguments, "
                        + "and regenerate those fixtures instead of deleting the tests one-by-one.";
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

        // When the conversation's system message already carries the cached
        // dependency-type catalogue (rendered once into the system message by
        // PromptBuilder), don't re-append the bulk catalogue below — the model
        // already has it in the cacheable prefix.
        boolean sutContextSummaryAlreadyInSystemMessage = Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT
                && !conversation.isEmpty()
                && conversation.get(0).getRole() == LlmMessage.Role.SYSTEM
                && StaticPromptContext.containsDependencyContext(conversation.get(0).getContent());

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
        if (isDependencyMissingError(error)) {
            repairMessage.append("\n\nDependency-missing repair instructions:")
                    .append("\n- Do NOT reference or instantiate missing external/framework classes.")
                    .append("\n- Avoid code paths that trigger static initialization of missing dependencies.")
                    .append("\n- Replace such tests with alternatives that only use available SUT/JDK types.")
                    .append("\n- Focus on pure constructors/methods that do not require "
                            + "GUI/network/plugin containers.");
        }
        appendInitializationFailureRepairInstructions(error, repairMessage);
        appendHeadlessEscalationRepairInstructions(state, error, repairMessage);
        appendInstantiationFailureRepairInstructions(error, repairMessage);
        appendExpectationMismatchRepairInstructions(executionFailures, repairMessage);
        appendNpePreconditionRepairInstructions(error, executionFailures, repairMessage);
        appendMockingRepairInstructions(error, repairMessage);
        appendStreamPreconditionRepairInstructions(error, repairMessage);
        appendArgumentPreconditionRepairInstructions(error, repairMessage);
        appendIndexedFixtureShapeRepairInstructions(error, repairMessage);
        appendReflectiveInvocationRepairInstructions(error, repairMessage);
        appendReflectiveAssertThrowsFallbackRepairInstructions(error, repairMessage);
        appendMissingMethodOnVariableRepairInstructions(error, repairMessage);
        appendMemberAccessRepairInstructions(state, error, repairMessage);
        appendFalsePositiveMockRepairInstructions(error, repairMessage);
        appendNoTestMethodsRepairInstructions(error, previousResponse, repairMessage);
        appendSpyUnsupportedRepairInstructions(error, repairMessage);
        appendCollaboratorFallbackRepairInstructions(error, repairMessage);
        appendSyntheticFallbackUsageRepairInstructions(error, repairMessage);
        appendNotAMockRepairInstructions(error, repairMessage);
        appendAnonymousImplementationRepairInstructions(error, previousResponse, repairMessage);
        appendContextSpecificRepairFacts(error, previousResponse, repairMessage);
        appendDependencyCodeContext(state, executionFailures, repairMessage);
        appendRuleBasedRepairHints(state, error, executionFailures, repairAttempt, repairMessage);
        // Include the bulk dependency-types catalog only on the first repair
        // turn (repairAttempt == 2 in the conversation numbering: 1 = initial
        // seed, 2 = first repair). On later turns the LLM already has it in
        // its conversation history, and re-emitting tens of KB of catalog text
        // dilutes the failure-specific hints that should drive the repair.
        if (sutContextSummary != null && !sutContextSummary.isEmpty() && repairAttempt <= 2
                && !sutContextSummaryAlreadyInSystemMessage) {
            repairMessage.append("\n\nFor reference, here are the available constructors and methods:\n")
                         .append(sutContextSummary);
        }
        if (repairOptions.isInstructRepairToAvoidAssertions()) {
            repairMessage.append("\n\nIMPORTANT: Do NOT include assertions "
                    + "(no assert* calls or Java assert statements).");
        }
        appendFallbackRepairInstructions(error, repairMessage);
        repairMessage.append("\n\nPlease provide the corrected complete test class with all test methods.");

        conversation.add(LlmMessage.user(repairMessage.toString()));

        boolean expanded = state.takeExpansionAttempted();
        if (repairDeadlineNanos == NO_REPAIR_DEADLINE
                || repairDeadlineNanos <= 0L) {
            return llmService.query(conversation, feature, repairAttempt,
                    expanded, expandedClasses);
        }
        return llmService.query(conversation, feature, repairAttempt,
                expanded, expandedClasses, repairDeadlineNanos);
    }

    private void appendNpePreconditionRepairInstructions(String error,
                                                         Map<ParseResult, ExecutionFailureContext> executionFailures,
                                                         StringBuilder repairMessage) {
        if (error == null || error.isEmpty()) {
            return;
        }
        String lower = error.toLowerCase();
        if (!lower.contains("nullpointerexception") && !lower.contains("is null")) {
            return;
        }

        // Scope hint to the specific tests whose actual exception is an NPE.
        // Without this, an NPE in one test would emit "fix the receiver" hints
        // for sibling tests that failed for an unrelated reason (e.g. shaded
        // mock-runtime exceptions), pushing the LLM toward changes that cannot
        // resolve the non-NPE failures.
        FailureScopes scopes = classifyExecutionFailures(executionFailures);
        if (scopes.npeFailingTests.isEmpty() && !scopes.executionFailuresAvailable) {
            // Best-effort: when no per-failure context is available, fall through
            // and emit the hint based on the aggregate error string only.
        } else if (scopes.npeFailingTests.isEmpty()) {
            return;
        }

        String nullVariable = extractNullVariableName(error);
        NpeDereferenceInfo dereferenceInfo = extractNpeDereferenceInfo(error);
        boolean syntheticLocal = isSyntheticLocalVariable(nullVariable);
        boolean directTestVariable = appearsInParsedTestCodeExcerpt(error, nullVariable);
        repairMessage.append("\n\nNPE precondition hint:");
        appendFailureScopeQualifier(repairMessage, scopes, scopes.npeFailingTests,
                "These NPE-precondition hints apply ONLY to the test(s) above; do not "
                        + "apply them to sibling failing tests whose actual exception is "
                        + "different (those are listed separately and require different fixes).");
        repairMessage.append("\n- The failing test violates required non-null preconditions.");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        if (nullVariable != null && !nullVariable.isEmpty() && !syntheticLocal && directTestVariable) {
            repairMessage.append("\n- For failing tests only, ensure the object named '")
                    .append(nullVariable)
                    .append("' is initialized before invoking the SUT.");
        } else if (nullVariable != null && !nullVariable.isEmpty() && !syntheticLocal) {
            repairMessage.append("\n- The JVM-reported null receiver name '")
                    .append(nullVariable)
                    .append("' comes from inside the failing SUT call path and is not necessarily a direct test "
                            + "variable; ")
                    .append("it is likely a parameter or local variable inside the SUT.");
            repairMessage.append("\n- Repair the upstream collaborator setup so the SUT receives a non-null value "
                    + "there before dereferencing it.");
        } else {
            repairMessage.append("\n- For failing tests only, initialize required provider/session objects "
                    + "before invoking the SUT.");
        }
        if (dereferenceInfo != null) {
            repairMessage.append("\n- Detected null dereference: '")
                    .append(dereferenceInfo.nullVariable)
                    .append("' was used in call '")
                    .append(dereferenceInfo.memberSignature)
                    .append("'.");
            if (dereferenceInfo.receiverType != null && !dereferenceInfo.receiverType.isEmpty()) {
                if (directTestVariable) {
                    repairMessage.append("\n- Initialize '")
                            .append(dereferenceInfo.nullVariable)
                            .append("' with a non-null value compatible with ")
                            .append(dereferenceInfo.receiverType)
                            .append(" before invoking the SUT.");
                } else {
                    repairMessage.append("\n- Here, '")
                            .append(dereferenceInfo.nullVariable)
                            .append("' is the null receiver inside the SUT call '")
                            .append(dereferenceInfo.memberSignature)
                            .append("', likely as a parameter or local variable there, not necessarily a same-named "
                                    + "test variable.");
                    repairMessage.append("\n- Stub or initialize the upstream collaborator/return value so that "
                            + "receiver is a non-null ")
                            .append(dereferenceInfo.receiverType)
                            .append(" before the SUT reaches that call.");
                }
                if (isDomElementType(dereferenceInfo.receiverType)) {
                    repairMessage.append("\n- For DOM collaborators, build a minimal in-memory element "
                            + "(DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()"
                            + ".createElement(\"model\")) "
                            + "instead of null.");
                }
            } else {
                repairMessage.append("\n- Ensure the null receiver is initialized before dereferencing it.");
            }
            repairMessage.append("\n- If this collaborator is required by the constructor, pass a concrete non-null "
                    + "argument during object construction (do not rely on later null-state assertions).");
        }
        if (syntheticLocal) {
            repairMessage.append("\n- The null receiver name is compiler-synthetic (for example '<local1>'); "
                    + "explicitly initialize each call receiver right before invoking the failing method.");
        }
        repairMessage.append("\n- Prefer minimal straight-line setup with concrete objects "
                + "over anonymous classes.");
        repairMessage.append("\n- Do not call the target method with null for mandatory "
                + "provider/session arguments.");
        repairMessage.append("\n- If the test is intentionally checking null-input behavior, "
                + "wrap the call in assertThrows(NullPointerException.class, ...) instead of "
                + "making a plain invocation with a known-null collaborator.");
        repairMessage.append("\n- If a required collaborator type is an interface/abstract type, "
                + "do NOT use new on that type; use a listed concrete subtype or Mockito.mock(Type.class).");
        repairMessage.append("\n- Do not assume complex SUT methods succeed on minimally initialized objects: "
                + "either construct the required internal state first, or assertThrows for the observed failure path.");
        repairMessage.append("\n- If a branch requires null, assert that behavior only after "
                + "satisfying earlier mandatory preconditions.");
    }

    private boolean isSyntheticLocalVariable(String variableName) {
        if (variableName == null || variableName.isEmpty()) {
            return false;
        }
        return SYNTHETIC_LOCAL_VARIABLE_PATTERN.matcher(variableName).matches();
    }

    /**
     * Per-failure classification of an execution-failure batch. Used to scope
     * hint blocks (e.g. NPE-precondition hints) to the tests they actually
     * apply to, instead of letting one failure's signal pollute the prompt for
     * sibling tests that failed for different reasons.
     */
    private static final class FailureScopes {
        final boolean executionFailuresAvailable;
        final List<String> npeFailingTests = new ArrayList<>();
        final List<String> shadedMockFailingTests = new ArrayList<>();
        final List<String> expectationMismatchFailingTests = new ArrayList<>();
        /** Maps a test method name to "expected=X, actual=Y". */
        final Map<String, String> expectationMismatchDetails = new LinkedHashMap<>();

        FailureScopes(boolean executionFailuresAvailable) {
            this.executionFailuresAvailable = executionFailuresAvailable;
        }
    }

    private FailureScopes classifyExecutionFailures(
            Map<ParseResult, ExecutionFailureContext> executionFailures) {
        boolean available = executionFailures != null && !executionFailures.isEmpty();
        FailureScopes scopes = new FailureScopes(available);
        if (!available) {
            return scopes;
        }
        for (Map.Entry<ParseResult, ExecutionFailureContext> entry : executionFailures.entrySet()) {
            ParseResult pr = entry.getKey();
            ExecutionFailureContext ctx = entry.getValue();
            if (pr == null || ctx == null) {
                continue;
            }
            String name = safeMethodName(pr);
            Throwable thrown = ctx.throwable;
            if (thrown instanceof NullPointerException
                    || (ctx.errorMessage != null
                            && (ctx.errorMessage.contains("NullPointerException")
                                    || ctx.errorMessage.contains("is null")))) {
                scopes.npeFailingTests.add(name);
            }
            if (isRaisedByEvoSuiteRuntime(thrown)
                    || errorMentionsMockRuntimeException(ctx.errorMessage)) {
                scopes.shadedMockFailingTests.add(name);
            }
            String expected = pr.getExpectedExceptionClass();
            if (expected != null && !expected.trim().isEmpty()) {
                String actualName = thrown != null ? reportedExceptionName(thrown) : null;
                if (actualName != null && !matchesExpectedExceptionName(thrown.getClass(), expected)) {
                    scopes.expectationMismatchFailingTests.add(name);
                    scopes.expectationMismatchDetails.put(name,
                            "expected=" + expected + ", actual=" + actualName);
                }
            }
        }
        return scopes;
    }

    /**
     * Appends a one-line scope qualifier that names the specific tests a hint
     * block applies to. Skipped when no per-failure context is available, so
     * hints emitted purely from the aggregate error text remain unchanged.
     */
    private void appendFailureScopeQualifier(StringBuilder repairMessage,
                                             FailureScopes scopes,
                                             List<String> applicableTests,
                                             String exclusivityNote) {
        if (scopes == null || !scopes.executionFailuresAvailable) {
            return;
        }
        if (applicableTests == null || applicableTests.isEmpty()) {
            return;
        }
        // Only add the scope qualifier when there is at least one sibling failure
        // that this hint block does NOT apply to; otherwise the hint already
        // implicitly applies to every failure in the batch.
        boolean hasNonApplicableSibling =
                scopes.npeFailingTests.size() + scopes.shadedMockFailingTests.size()
                        + scopes.expectationMismatchFailingTests.size()
                        > applicableTests.size()
                        || scopes.shadedMockFailingTests.stream()
                                .anyMatch(name -> !applicableTests.contains(name));
        if (!hasNonApplicableSibling) {
            return;
        }
        repairMessage.append("\n- Applies to test(s): ").append(applicableTests).append(".");
        if (exclusivityNote != null && !exclusivityNote.isEmpty()) {
            repairMessage.append("\n- ").append(exclusivityNote);
        }
    }

    /**
     * Surfaces a single, prioritized line near the top of the repair message
     * when the LLM's <code>assertThrows(Y.class, ...)</code> expectation does
     * not match the actually observed exception type. The bytecode signature
     * tells the LLM what the SUT <em>declares</em> it can throw; the runtime
     * tells us what actually happens. Trust the runtime.
     */
    private void appendExpectationMismatchRepairInstructions(
            Map<ParseResult, ExecutionFailureContext> executionFailures,
            StringBuilder repairMessage) {
        if (executionFailures == null || executionFailures.isEmpty()) {
            return;
        }
        FailureScopes scopes = classifyExecutionFailures(executionFailures);
        if (scopes.expectationMismatchFailingTests.isEmpty()) {
            return;
        }
        repairMessage.append("\n\nExpected-vs-actual exception mismatch:");
        for (String name : scopes.expectationMismatchFailingTests) {
            String detail = scopes.expectationMismatchDetails.get(name);
            repairMessage.append("\n- ").append(name).append(": ")
                    .append(detail == null ? "(details unavailable)" : detail);
        }
        repairMessage.append("\n- The actual runtime exception is what executes; the expected class");
        repairMessage.append(" came from the method's declared `throws` clause but the SUT does not");
        repairMessage.append(" actually translate the runtime failure into that type in this environment.");
        repairMessage.append("\n- For each test above, EITHER change the assertThrows expectation to the");
        repairMessage.append(" actually observed class, OR replace the test with one that does not");
        repairMessage.append(" reach the failing call. Do NOT keep the same expected class hoping a");
        repairMessage.append(" different setup will produce it.");
        if (!scopes.shadedMockFailingTests.isEmpty()) {
            repairMessage.append("\n- Note: tests ").append(scopes.shadedMockFailingTests)
                    .append(" failed with an exception under ").append(MOCK_RUNTIME_PACKAGE_PREFIX).append("*;");
            repairMessage.append(" that is the EvoSuite test harness, not the SUT, and you cannot");
            repairMessage.append(" eliminate it by changing test inputs. Either accept it via");
            repairMessage.append(" assertThrows on that exact class or drop those tests.");
        }
    }

    private void appendMockingRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isMockingMisuseError(error)) {
            return;
        }
        repairMessage.append("\n\nMockito usage hint:");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- For failing tests only, ensure objects passed to Mockito.when(...), "
                + "doThrow(...).when(...), and verify(...) are actual mocks/spies.");
        repairMessage.append("\n- Do NOT stub or verify real objects created with constructors.");
        repairMessage.append("\n- If a collaborator type is interface/abstract, use Mockito.mock(Type.class) "
                + "or a concrete subtype; never use new on the interface/abstract type.");
        repairMessage.append("\n- Do NOT use Mockito.spy(...) or doReturn(...).when(spy)...; regenerate failing tests "
                + "using real instances or Mockito.mock(...) collaborators.");
        repairMessage.append("\n- Complete each stubbing expression fully (avoid unfinished stubbing chains).");
    }

    private void appendSpyUnsupportedRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isSpyUnsupportedPattern(error)) {
            return;
        }
        repairMessage.append("\n\nSpy-unsupported repair instructions:");
        repairMessage.append("\n- Do NOT use Mockito.spy(...) in EvoSuite-generated parsed tests.");
        repairMessage.append("\n- Do NOT use spy-based stubbing such as doReturn(...).when(spy)... or "
                + "when(spy.method(...)).thenReturn(...).");
        repairMessage.append("\n- Replace spy-based setup with one of: (1) direct calls on real SUT instances without "
                + "spy stubbing, or (2) Mockito.mock(...) collaborators that are stubbed minimally.");
        repairMessage.append("\n- Never alias or stub through parser-generated '__llm_fallback...' variables.");
    }

    private void appendFalsePositiveMockRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isFalsePositiveMockError(error)) {
            return;
        }

        String mockMethodName = pickFalsePositiveMockName(error);
        repairMessage.append("\n\nMock-stubbing hint:");
        repairMessage.append("\n- The rerun failed because a mocked collaborator used ViolatedAssumptionAnswer and the "
                + "SUT called an unstubbed method.");
        if (mockMethodName != null && !mockMethodName.isEmpty()) {
            repairMessage.append("\n- Unstubbed mock call: `").append(mockMethodName).append("(...)`.");
        } else {
            repairMessage.append("\n- Unstubbed mock call: the runtime reported a false-positive mock-policy failure, "
                    + "but the exact method name was not captured.");
        }
        repairMessage.append("\n- Add the minimal stub for the collaborator method the SUT actually calls, instead of "
                + "leaving that mock on a bare ViolatedAssumptionAnswer path.");
        repairMessage.append("\n- Keep the rest of the test unchanged unless it depends on the missing stub.");
        repairMessage.append("\n- Do not replace the collaborator with null; make the mock return the value or nested "
                + "collaborator the SUT needs.");
    }

    private void appendStreamPreconditionRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isStreamRelatedError(error)) {
            return;
        }
        repairMessage.append("\n\nStream precondition hint:");
        repairMessage.append("\n- The failing test violates stream preconditions or stream lifecycle constraints.");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- For failing tests only, avoid null InputStream/OutputStream/Reader/Writer arguments "
                + "unless the method contract explicitly expects null.");
        repairMessage.append("\n- Prefer in-memory data sources over filesystem/network I/O in tests:");
        repairMessage.append("\n  ByteArrayInputStream for bytes, StringReader for text, "
                + "ByteArrayOutputStream/StringWriter for outputs.");
        repairMessage.append("\n- In EvoSuite sandbox, avoid direct file-writer constructors "
                + "(e.g., FileOutputStream/FileWriter/PrintWriter on real files); use in-memory outputs or "
                + "EvoSuite mock I/O types (MockFile*, MockPrint*) when file-like APIs are required.");
        repairMessage.append("\n- Build minimally valid non-empty test payloads before invoking parser/configuration "
                + "methods.");
        repairMessage.append("\n- If the expected behavior is argument validation, assert the concrete thrown "
                + "exception for null stream arguments.");
    }

    private void appendArgumentPreconditionRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isArgumentPreconditionError(error)) {
            return;
        }
        String lower = error == null ? "" : error.toLowerCase();
        boolean eventObjectNullSource = lower.contains("null source")
                && (lower.contains("eventobject") || lower.contains("actionevent"));
        repairMessage.append("\n\nArgument precondition hint:");
        repairMessage.append("\n- The failing test violates method preconditions for input arguments.");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- For failing tests only, replace invalid empty/default inputs with minimally valid "
                + "non-empty values before invoking the SUT.");
        repairMessage.append("\n- Typical fixes: provide at least one required line/entry/token/element instead of "
                + "empty structures or empty strings.");
        repairMessage.append("\n- If testing validation behavior, use assertThrows(...) for the invalid-input case and "
                + "add a separate valid-input smoke test.");
        if (eventObjectNullSource) {
            repairMessage.append("\n- Detected `IllegalArgumentException: null source` from EventObject/ActionEvent "
                    + "construction.");
            repairMessage.append("\n- Never build `new ActionEvent(null, ...)`: the first constructor argument (event "
                    + "source) must be non-null.");
            repairMessage.append("\n- If receiver construction collapsed to a typed null placeholder "
                    + "(`__llm_fallback...`), do not keep emitting event-driven tests on that null receiver; repair "
                    + "upstream construction or rewrite/drop those tests.");
        }
    }

    private void appendIndexedFixtureShapeRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isIndexedFixtureShapeError(error)) {
            return;
        }
        repairMessage.append("\n\nIndexed fixture/bounds hint:");
        repairMessage.append("\n- The failing test uses inconsistent dimensions, counts, or indices while building an "
                + "indexed fixture or invoking an indexed API.");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- For failing tests only, re-check each constructor size/count argument against every "
                + "later indexed read/write in that test.");
        repairMessage.append("\n- Do not assume constructor counts map 1:1 to all later accessible "
                + "rows/elements/slots; some helpers reserve header or metadata positions, or derive internal storage "
                + "differently.");
        repairMessage.append("\n- Rebuild the fixture so every set/get index stays within the actual bounds created by "
                + "the constructor and setup calls.");
        repairMessage.append("\n- If the behavior under test is rejection of invalid sizes or indices, stop the test "
                + "at the offending constructor/mutator with assertThrows(...) instead of continuing with normal-path "
                + "assertions.");
    }

    private void appendReflectiveInvocationRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isReflectiveInvocationWrapperError(error)) {
            return;
        }
        repairMessage.append("\n\nReflection wrapper hint:");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- Prefer direct invocation of accessible SUT methods over reflection.");
        repairMessage.append("\n- If using Method.invoke(...), expect InvocationTargetException from assertThrows.");
        repairMessage.append("\n- Then assert the wrapped cause type/message (e.getCause()) matches the expected SUT "
                + "exception.");
        repairMessage.append("\n- Avoid Class.forName/getDeclaredMethod/setAccessible when the target method is public "
                + "or package-private.");
    }

    private void appendReflectiveAssertThrowsFallbackRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isReflectiveAssertThrowsFallbackError(error)) {
            return;
        }
        repairMessage.append("\n\nReflective assertThrows fallback hint:");
        repairMessage.append("\n- The shown InvocationTargetException/__llm_fallback line is a parser-generated "
                + "fallback, not valid intended test logic.");
        repairMessage.append("\n- Replace the entire failing reflective assertion fragment instead of keeping the "
                + "synthetic fallback variable or aliasing it.");
        repairMessage.append("\n- Prefer direct invocation of accessible SUT methods over reflection.");
        repairMessage.append("\n- If reflection is unavoidable, capture InvocationTargetException with "
                + "Assertions.assertThrows(...), then cast/check invocationTargetException.getCause() as the expected "
                + "SUT exception type.");
        repairMessage.append("\n- Do not assign assertThrows(...) results to synthetic null/fallback placeholders.");
    }

    private void appendMissingMethodOnVariableRepairInstructions(String error, StringBuilder repairMessage) {
        if (error == null || error.isEmpty()) {
            return;
        }
        java.util.regex.Matcher missingMethodMatcher = MISSING_SYMBOL_METHOD_WITH_LOCATION_PATTERN.matcher(error);
        boolean emitted = false;
        while (missingMethodMatcher.find()) {
            String methodName = missingMethodMatcher.group(1);
            String receiverVar = missingMethodMatcher.group(2);
            String receiverType = missingMethodMatcher.group(3);
            if (!emitted) {
                repairMessage.append("\n\nMissing-method repair instructions:");
                emitted = true;
            }
            if (methodName != null && methodName.equals(receiverVar)) {
                repairMessage.append("\n- Invalid self-call typo detected: `")
                        .append(receiverVar).append(".").append(methodName).append("(...)`.")
                        .append(" Remove this call entirely or replace it with a real existing API call on `")
                        .append(receiverType).append("` from provided context.")
                        .append(" Do not invent methods from variable names.");
            } else {
                repairMessage.append("\n- Cannot resolve method `").append(methodName)
                        .append("(...)` on variable `").append(receiverVar)
                        .append("` of type `").append(receiverType)
                        .append("`. Replace with an existing method from context or remove this call/assertion.");
                repairMessage.append("\n- Do not call subtype-only methods on a supertype reference.")
                        .append(" If the runtime object is a subtype, add an `instanceof` guard and cast before "
                                + "calling subtype APIs")
                        .append(" (for example `Component` -> `JLabel` before `getText()`).");
                String closest = failureFormatter.findClosestAccessibleApiSuggestion(methodName);
                if (closest != null && !closest.isEmpty()) {
                    repairMessage.append("\n- Closest available API: ").append(closest);
                }
            }
        }

        java.util.regex.Matcher directMissingMethodMatcher = MISSING_METHOD_DIAGNOSTIC_PATTERN.matcher(error);
        while (directMissingMethodMatcher.find()) {
            if (!emitted) {
                repairMessage.append("\n\nMissing-method repair instructions:");
                emitted = true;
            }
            String methodName = directMissingMethodMatcher.group(1);
            String targetClass = directMissingMethodMatcher.group(2);
            repairMessage.append("\n- No method named `").append(methodName)
                    .append("` exists in `").append(targetClass)
                    .append("`. Replace the invented call with a real API from context, or remove the call entirely.");
            String closest = failureFormatter.findClosestAccessibleApiSuggestion(methodName);
            if (closest != null && !closest.isEmpty()) {
                repairMessage.append("\n- Closest available API: ").append(closest);
            }
        }
    }

    private void appendMemberAccessRepairInstructions(RepairConversation state, String error,
                                                      StringBuilder repairMessage) {
        if (error == null || error.isEmpty()) {
            return;
        }
        String lower = error.toLowerCase();
        if (!(lower.contains("has private access")
                || lower.contains("has protected access")
                || lower.contains("cannot be accessed from outside package"))) {
            return;
        }

        // Parse specific inaccessible members from diagnostics and track them
        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse(error);
        Set<String> currentKeys = new LinkedHashSet<>();
        for (AccessViolationDiagnosticParser.AccessViolation v : violations) {
            currentKeys.add(v.toTrackingKey());
        }
        boolean hasRecurrent = state.recordAccessViolations(currentKeys);

        repairMessage.append("\n\nMember-access repair hint:");
        repairMessage.append("\n- The failing test directly accesses private or protected members (which are not "
                + "allowed). Note: Package-private members ARE allowed as tests are in the same package.");
        repairMessage.append("\n- Keep all already executable tests unchanged.");

        // Emit specific inaccessible member names so the LLM knows exactly what to avoid
        if (!violations.isEmpty()) {
            repairMessage.append("\n- Specifically inaccessible members detected:");
            for (AccessViolationDiagnosticParser.AccessViolation v : violations) {
                if (hasRecurrent && state.hasSeenAccessViolation(v.toTrackingKey())) {
                    // Escalation language for members that recurred across retries
                    repairMessage.append("\n  * CRITICAL (recurrent): You MUST NOT call `")
                            .append(v.getMemberName()).append("()` — it has ")
                            .append(v.getAccessLevel()).append(" access in `")
                            .append(v.getDeclaringClass()).append("`. ")
                            .append("Remove ALL calls to this member immediately.");
                } else {
                    repairMessage.append("\n  * Do NOT call `")
                            .append(v.getMemberName()).append("()` — it has ")
                            .append(v.getAccessLevel()).append(" access in `")
                            .append(v.getDeclaringClass()).append("`.");
                }
            }
        }

        repairMessage.append("\n- For failing tests only, remove direct field/member access assertions and replace "
                + "them with checks via accessible public or package-visible methods.");
        repairMessage.append("\n- Do not assert internal fields such as receiver.superclassField; assert externally "
                + "visible behavior instead.");
        repairMessage.append("\n- If no accessible API exposes the same state, drop that assertion rather than forcing "
                + "illegal member access.");
        repairMessage.append("\n- Also avoid non-public cross-package types in imports/declarations/constructors; use "
                + "only public accessible types/paths from context.");
        if (lower.contains("baserecognizer") || lower.contains(".state.")) {
            repairMessage.append("\n- ANTLR-specific: never access `lexer.state` / `BaseRecognizer.state` directly.")
                    .append(" Remove assertions like `lexer.state.type` and assert behavior via public lexer APIs or "
                            + "tokenization results.");
        }
    }

    static boolean isReflectiveAssertThrowsFallbackError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        return lower.contains("snippet compilation")
                && lower.contains("__llm_fallback")
                && lower.contains("invocationtargetexception");
    }

    static boolean isPartialAnonymousImplementationError(String error, String previousResponse) {
        if ((error == null || error.isEmpty())
                && (previousResponse == null || previousResponse.isEmpty())) {
            return false;
        }
        String sourceText = error != null && !error.isEmpty() ? error : previousResponse;
        if (extractAnonymousConstructedTypeStatic(sourceText) == null) {
            return false;
        }
        String lower = error == null ? "" : error.toLowerCase();
        if (lower.contains("is not abstract and does not override abstract method")) {
            return true;
        }
        return lower.contains("snippet compilation")
                && (lower.contains("illegal start of type")
                || lower.contains("class, interface, enum, or record expected")
                || lower.contains("';' expected")
                || lower.contains("is abstract; cannot be instantiated")
                || lower.contains("has protected access")
                || (lower.contains("constructor") && lower.contains("cannot be applied to given types")));
    }

    private void appendCollaboratorFallbackRepairInstructions(String error, StringBuilder repairMessage) {
        String fallbackType = extractTypedFallbackDeclaredType(error);
        if (fallbackType == null || fallbackType.isEmpty()) {
            return;
        }
        String simpleType = simpleName(fallbackType);
        repairMessage.append("\n\nCollaborator fallback hint:");
        repairMessage.append("\n- The shown '")
                .append(fallbackType)
                .append(" __llm_fallback... = null;' line is a parser-generated placeholder, not a usable collaborator "
                        + "setup.");
        repairMessage.append("\n- Do not alias that synthetic fallback, call methods on it, stub through it, or keep "
                + "passing null instead of a real ")
                .append(simpleType)
                .append(" value.");
        repairMessage.append("\n- Replace it with a real collaborator before invoking the SUT.");
        repairMessage.append("\n- If ")
                .append(simpleType)
                .append(" is interface/abstract-like in context, prefer Mockito.mock(")
                .append(simpleType)
                .append(".class, new ViolatedAssumptionAnswer()) and stub only the minimal methods the SUT call "
                        + "actually uses.");
        repairMessage.append("\n- If that collaborator cannot be constructed or mocked with available SUT/JDK types, "
                + "rewrite the failing test to avoid that path instead of keeping the synthetic fallback.");
    }

    private void appendNotAMockRepairInstructions(String error, StringBuilder repairMessage) {
        if (error.contains("NotAMockException")) {
            repairMessage.append("\n\nNot-a-mock repair instructions:");
            repairMessage.append("\n- The failing test attempted to use Mockito.when() or Mockito.doReturn() on a real "
                    + "object.");
            repairMessage.append("\n- Only use Mockito.when() or Mockito.doReturn() on objects created via "
                    + "Mockito.mock().");
            repairMessage.append("\n- If the object was created using a 'new' constructor, it is a real instance. "
                    + "Remove the Mockito stubbing for this object.");
        }
    }

    private void appendSyntheticFallbackUsageRepairInstructions(String error, StringBuilder repairMessage) {
        if (error.contains("NullPointerException") && error.contains("__llm_fallback")) {
            repairMessage.append("\n\nSynthetic fallback usage error:");
            repairMessage.append("\n- You are attempting to call a method on a variable named '__llm_fallback...' "
                    + "which is 'null'.");
            repairMessage.append("\n- This variable is a placeholder and should NEVER be used as a valid object "
                    + "instance.");
            repairMessage.append("\n- You MUST fix the constructor call that led to this fallback by providing valid "
                    + "data (e.g., a valid byte array header like {0xCA, 0xFE, 0xBA, 0xBE} for ASM ClassReader).");
        }
    }

    private void appendAnonymousImplementationRepairInstructions(String error,
                                                                 String previousResponse,
                                                                 StringBuilder repairMessage) {
        if (!isPartialAnonymousImplementationError(error, previousResponse)) {
            return;
        }
        String constructedType = extractAnonymousConstructedType(error);
        if (constructedType == null || constructedType.isEmpty()) {
            constructedType = extractAnonymousConstructedType(previousResponse);
        }
        if (constructedType == null || constructedType.isEmpty()) {
            return;
        }

        String simpleType = simpleName(constructedType);
        Class<?> resolvedType = failureFormatter.tryResolveRepairType(constructedType);
        repairMessage.append("\n\nAnonymous implementation repair hint:");
        repairMessage.append("\n- The failing test created an anonymous implementation/subclass of '")
                .append(constructedType)
                .append("', and that anonymous `new ")
                .append(simpleType)
                .append("() { ... }` fragment is non-compilable in EvoSuite snippet execution here.");
        repairMessage.append("\n- This is not a parser/import issue: the anonymous `new ")
                .append(simpleType)
                .append("() { ... }` fragment itself is not a valid repair for this type.");
        if (resolvedType != null) {
            if (resolvedType.isInterface()) {
                repairMessage.append("\n- '")
                        .append(simpleType)
                        .append("' is an interface, so the one-off anonymous body still has to implement every "
                                + "required method.");
            } else if (Modifier.isAbstract(resolvedType.getModifiers()) && hasOnlyNonPublicConstructors(resolvedType)) {
                repairMessage.append("\n- '")
                        .append(simpleType)
                        .append("' is abstract and its constructors are not directly public, so a one-method anonymous "
                                + "subclass is especially brittle here.");
            } else if (Modifier.isAbstract(resolvedType.getModifiers())) {
                repairMessage.append("\n- '")
                        .append(simpleType)
                        .append("' is abstract, so overriding one visible method is still not enough to make the "
                                + "anonymous subtype valid.");
            } else if (hasOnlyNonPublicConstructors(resolvedType)) {
                repairMessage.append("\n- '")
                        .append(simpleType)
                        .append("' does not expose a directly public constructor, so `new ")
                        .append(simpleType)
                        .append("() { ... }` is not a safe repair strategy.");
            } else {
                repairMessage.append("\n- Even though '")
                        .append(simpleType)
                        .append("' is concrete, keep avoiding `new ")
                        .append(simpleType)
                        .append("() { ... }` here; the anonymous override body is the failing pattern.");
            }
        }
        if (isMalformedAnonymousSnippetSyntaxError(error)) {
            repairMessage.append("\n- The reported javac syntax errors (for example `illegal start of type`, `';' "
                    + "expected`, or stray `return` lines) are downstream effects of the malformed anonymous class, "
                    + "not evidence that EvoSuite parsed the body incorrectly.");
        }
        repairMessage.append("\n- Do NOT write new ")
                .append(simpleType)
                .append("() { ... } just to override one method.");
        repairMessage.append("\n- Replace it with Mockito.mock(")
                .append(constructedType)
                .append(".class, new ViolatedAssumptionAnswer()) or a concrete existing subtype from context.");
        repairMessage.append("\n- Stub or verify only the minimal method actually relevant to the failing path instead "
                + "of hand-writing an anonymous class body.");
        repairMessage.append("\n- If the intended assertion is that a callback like showErrorDialog(...) must not be "
                + "reached, keep the collaborator as a mock and verify the method is never invoked instead of calling "
                + "fail(...) inside an anonymous override.");
    }

    private void appendNoTestMethodsRepairInstructions(String error,
                                                       String previousResponse,
                                                       StringBuilder repairMessage) {
        if (error == null || !error.contains("Parser produced no test methods")) {
            return;
        }
        repairMessage.append("\n\nNo-test-method parsing hint:");
        repairMessage.append("\n- Return a complete compilable Java test class that contains one or more concrete "
                + "methods annotated with @Test inside the class body.");
        
        List<String> malformedImports =
                extractMalformedImportLines(error + "\n" + (previousResponse == null ? "" : previousResponse));
        if (!malformedImports.isEmpty()) {
            boolean hasAlias = malformedImports.stream().anyMatch(l -> l.toLowerCase().contains(" as "));
            if (hasAlias) {
                repairMessage.append("\n- The previous response used invalid 'import ... as ...' alias syntax, which "
                        + "Java does not support.");
                repairMessage.append("\n- To resolve naming conflicts (e.g., between two classes named 'Query'), use "
                        + "the fully qualified name at the call site instead of aliasing the import.");
                repairMessage.append("\n- Example: `net.sourceforge.beanbin.query.Query beanBinQuery = new "
                        + "net.sourceforge.beanbin.query.Query();`.");
            } else {
                repairMessage.append("\n- The previous response used invalid Java import syntax with file-path "
                        + "separators instead of package dots.");
                for (String importLine : malformedImports.subList(0, Math.min(2, malformedImports.size()))) {
                    repairMessage.append("\n- Invalid import from previous response: ").append(importLine);
                }
                repairMessage.append("\n- Java imports must use dotted package names, for example `import "
                        + "br.com.jnfe.base.service.SimpleSecurityHandlerBean;`.");
            }
        } else if (error.contains("LLM response was empty") || error.contains("hit a token limit")
                || error.contains("contained only bytecode")) {
            repairMessage.append("\n- Do not return only context, documentation, or bytecode disassembly.");
            repairMessage.append("\n- Generate actual test code with concrete @Test methods that instantiate and "
                    + "interact with the target class.");
            repairMessage.append("\n- If your previous response was cut off or hit a token limit, generate fewer test "
                    + "cases but include actual test code.");
        } else if (previousResponse != null && previousResponse.contains("@Test")) {
            repairMessage.append("\n- The previous response already looked like a test class, so malformed Java syntax "
                    + "likely prevented method extraction. Recheck imports, class declaration, braces, and method "
                    + "headers.");
        } else {
            repairMessage.append("\n- Do not return prose or helper snippets only; include actual @Test methods in the "
                    + "final class.");
        }
    }

    private String buildNoTestMethodsParseError(String extractedClass) {
        StringBuilder error = new StringBuilder("Parser produced no test methods.");
        List<String> malformedImports = extractMalformedImportLines(extractedClass);
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

    private List<String> extractMalformedImportLines(String sourceText) {
        if (sourceText == null || sourceText.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> malformed = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = MALFORMED_IMPORT_LINE_PATTERN.matcher(sourceText);
        while (matcher.find()) {
            String line = matcher.group();
            if (line != null) {
                malformed.add(line.trim());
            }
        }
        return new ArrayList<>(malformed);
    }

    private String extractAnonymousConstructedType(String sourceText) {
        return extractAnonymousConstructedTypeStatic(sourceText);
    }

    private static String extractAnonymousConstructedTypeStatic(String sourceText) {
        if (sourceText == null || sourceText.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = ANONYMOUS_TYPE_CREATION_PATTERN.matcher(sourceText);
        if (!matcher.find()) {
            return null;
        }
        return stripTypeDecorators(matcher.group(1));
    }

    private boolean isMalformedAnonymousSnippetSyntaxError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        return lower.contains("snippet compilation")
                && (lower.contains("illegal start of type")
                || lower.contains("class, interface, enum, or record expected")
                || lower.contains("';' expected"));
    }

    private boolean hasOnlyNonPublicConstructors(Class<?> type) {
        if (type == null) {
            return false;
        }
        java.lang.reflect.Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length == 0) {
            return false;
        }
        for (java.lang.reflect.Constructor<?> constructor : constructors) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private boolean isArgumentPreconditionError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        boolean illegalArgument = lower.contains("illegalargumentexception")
                || lower.contains("mockillegalargumentexception");
        if (!illegalArgument) {
            return false;
        }
        if (lower.contains("null source")
                && (lower.contains("eventobject") || lower.contains("actionevent"))) {
            return true;
        }
        return lower.contains("no ")
                || lower.contains("empty")
                || lower.contains("must ")
                || lower.contains("required")
                || lower.contains("cannot be ")
                || lower.contains("at least");
    }

    private boolean isIndexedFixtureShapeError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        for (String marker : INDEXED_FIXTURE_FAILURE_MARKERS) {
            if (error.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean isReflectiveInvocationWrapperError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        boolean mentionsInvocationTarget = lower.contains("invocationtargetexception");
        if (!mentionsInvocationTarget) {
            return false;
        }
        return lower.contains("method.invoke")
                || lower.contains("getdeclaredmethod")
                || lower.contains("setaccessible")
                || lower.contains("unexpected exception type thrown");
    }

    private boolean isStreamRelatedError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        boolean mentionsStreamType = lower.contains("inputstream")
                || lower.contains("outputstream")
                || lower.contains("reader")
                || lower.contains("writer")
                || lower.contains("stream");
        if (!mentionsStreamType) {
            return false;
        }
        return lower.contains("null")
                || lower.contains("cannot be null")
                || lower.contains("illegalargumentexception")
                || lower.contains("stream closed")
                || lower.contains("eofexception")
                || lower.contains("malformedinputexception")
                || lower.contains("premature end of file");
    }

    private String extractNullVariableName(String error) {
        if (error == null) {
            return null;
        }
        java.util.regex.Matcher matcher = NPE_NULL_VARIABLE_PATTERN.matcher(error);
        if (matcher.find()) {
            String variable = matcher.group(1);
            if (variable != null && !variable.trim().isEmpty()) {
                return variable.trim();
            }
        }
        return null;
    }

    private static final class NpeDereferenceInfo {
        private final String memberSignature;
        private final String nullVariable;
        private final String receiverType;

        private NpeDereferenceInfo(String memberSignature, String nullVariable, String receiverType) {
            this.memberSignature = memberSignature;
            this.nullVariable = nullVariable;
            this.receiverType = receiverType;
        }
    }

    private NpeDereferenceInfo extractNpeDereferenceInfo(String error) {
        if (error == null || error.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = NPE_CANNOT_INVOKE_PATTERN.matcher(error);
        if (!matcher.find()) {
            return null;
        }

        String memberSignature = matcher.group(1) == null ? "" : matcher.group(1).trim();
        String nullVariable = matcher.group(2) == null ? "" : matcher.group(2).trim();
        if (memberSignature.isEmpty() || nullVariable.isEmpty()) {
            return null;
        }

        String receiverType = extractReceiverTypeFromMemberSignature(memberSignature);
        return new NpeDereferenceInfo(memberSignature, nullVariable, receiverType);
    }

    private String extractReceiverTypeFromMemberSignature(String memberSignature) {
        if (memberSignature == null || memberSignature.isEmpty()) {
            return null;
        }
        int paren = memberSignature.indexOf('(');
        String withoutArgs = paren >= 0 ? memberSignature.substring(0, paren) : memberSignature;
        int lastDot = withoutArgs.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return withoutArgs.substring(0, lastDot);
    }

    private boolean appearsInParsedTestCodeExcerpt(String error, String variableName) {
        if (error == null || error.isEmpty() || variableName == null || variableName.isEmpty()) {
            return false;
        }
        java.util.regex.Matcher matcher = PARSED_TEST_CODE_EXCERPT_PATTERN.matcher(error);
        if (!matcher.find()) {
            return false;
        }
        String excerpt = matcher.group(1);
        if (excerpt == null || excerpt.isEmpty()) {
            return false;
        }
        return Pattern.compile("\\b" + Pattern.quote(variableName) + "\\b").matcher(excerpt).find();
    }

    private boolean isDomElementType(String receiverType) {
        return "org.w3c.dom.Element".equals(receiverType)
                || "org.w3c.dom.Node".equals(receiverType)
                || receiverType.endsWith(".Element");
    }

    /**
     * Appends two kinds of dep-code-driven repair guidance.
     * <ol>
     *   <li>For exceptions raised inside a non-CUT instance method or constructor body,
     *       inline a focused excerpt of that member's source/decompiled code so the
     *       LLM can satisfy the missing precondition.</li>
     *   <li>For {@code <clinit>} failures and follow-on {@code NoClassDefFoundError},
     *       record the poisoned class on the conversation-scoped set and emit a
     *       strong avoidance instruction. The class is permanently unusable for the
     *       rest of this run, so showing its source would only mislead the LLM.</li>
     * </ol>
     */
    private void appendDependencyCodeContext(RepairConversation state,
                                             Map<ParseResult, ExecutionFailureContext> executionFailures,
                                             StringBuilder repairMessage) {
        if (!Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR) {
            return;
        }
        String targetClass = Properties.TARGET_CLASS;
        String targetPackage = getSutPackage();

        DependencyFailureAnalysis.Frame depFrame = null;

        if (executionFailures != null) {
            for (ExecutionFailureContext context : executionFailures.values()) {
                if (context == null || context.throwable == null) {
                    continue;
                }
                DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(
                        context.throwable, targetClass, targetPackage);
                switch (analysis.getKind()) {
                    case CLASS_INIT_FAILURE:
                    case CLASS_INIT_AFTERSHOCK:
                        if (analysis.getPoisonedClass() != null) {
                            state.recordPoisonedClass(analysis.getPoisonedClass());
                        }
                        break;
                    case DEP_MEMBER_FAILURE:
                        if (depFrame == null) {
                            depFrame = analysis.getFrame();
                        }
                        break;
                    default:
                        // No-op
                        break;
                }
            }
        }

        appendPoisonedClassAvoidance(state.getPoisonedClasses(), repairMessage);
        appendDependencyCodeExcerpt(depFrame, repairMessage);
    }

    private void appendPoisonedClassAvoidance(Set<String> poisonedClasses, StringBuilder repairMessage) {
        if (poisonedClasses.isEmpty()) {
            return;
        }
        repairMessage.append("\n\nPoisoned-class avoidance:");
        repairMessage.append("\n- The following non-CUT class(es) failed to initialize during this run "
                + "and are now permanently unusable in this JVM. Any test that touches them — directly "
                + "or transitively — will fail with NoClassDefFoundError no matter how it is rewritten.");
        for (String poisoned : poisonedClasses) {
            repairMessage.append("\n  * ").append(poisoned);
        }
        repairMessage.append("\n- Do NOT reference these classes from any test in the corrected class, "
                + "including via fields, parameters, return types, factories, or methods that internally "
                + "construct or load them. Choose alternative SUT/JDK types from the dependency summary instead.");
    }

    private void appendDependencyCodeExcerpt(DependencyFailureAnalysis.Frame depFrame,
                                             StringBuilder repairMessage) {
        if (depFrame == null) {
            return;
        }
        if (Properties.LLM_DEPENDENCY_CODE_MAX_CLASSES <= 0) {
            return;
        }
        int budget = Math.max(0, Properties.LLM_DEPENDENCY_CODE_MAX_CHARS);
        if (budget <= 0) {
            return;
        }
        Optional<String> excerpt = dependencyCodeResolver.resolveExcerpt(depFrame, budget);
        if (!excerpt.isPresent()) {
            return;
        }
        repairMessage.append("\n\nDependency code excerpt (non-CUT, source unavailable to LLM otherwise):\n")
                .append(excerpt.get())
                .append("\n- Use this excerpt to understand the precondition the failing call requires.");
        if (depFrame.isConstructor()) {
            repairMessage.append("\n- Provide constructor arguments that satisfy the body's null/range checks before "
                    + "invoking it.");
        } else {
            repairMessage.append("\n- Initialize collaborators or arguments so the method body's branches succeed "
                    + "instead of throwing.");
        }
        repairMessage.append("\n- Do not assert on the dependency's internals; only adjust how your test sets up "
                + "inputs to it.");
    }

    private void appendRuleBasedRepairHints(RepairConversation state,
                                            String error,
                                            Map<ParseResult, ExecutionFailureContext> executionFailures,
                                            int repairAttempt,
                                            StringBuilder repairMessage) {
        if (!Properties.LLM_REPAIR_HINTS_ENABLED) {
            return;
        }
        List<Throwable> throwables = collectExecutionThrowables(executionFailures);
        RepairHintResolver.Resolution resolution = repairHintResolver.resolve(
                error,
                throwables,
                state.getPoisonedClasses(),
                repairAttempt,
                state.getHintLastShownAttempts(),
                Properties.LLM_REPAIR_HINTS_MAX_PER_ATTEMPT,
                Properties.LLM_REPAIR_HINTS_COOLDOWN_ATTEMPTS,
                Properties.LLM_REPAIR_HINTS_ALWAYS_ON);
        if (resolution.isEmpty()) {
            return;
        }

        List<RepairFailureSignal> signals = resolution.getSignals();
        if (signals != null && !signals.isEmpty()) {
            repairMessage.append("\n\nObserved failure signals:");
            int maxSignals = Math.min(4, signals.size());
            for (int i = 0; i < maxSignals; i++) {
                repairMessage.append("\n- ").append(signals.get(i).getSummary());
            }
        }

        repairMessage.append("\n\nTargeted repair hints:");
        for (RepairHintRule hint : resolution.getHints()) {
            repairMessage.append("\n- ").append(hint.getText());
            state.recordHintShown(hint.getId(), repairAttempt);
        }
    }

    private List<Throwable> collectExecutionThrowables(
            Map<ParseResult, ExecutionFailureContext> executionFailures) {
        if (executionFailures == null || executionFailures.isEmpty()) {
            return Collections.emptyList();
        }
        List<Throwable> throwables = new ArrayList<>();
        for (ExecutionFailureContext context : executionFailures.values()) {
            if (context != null && context.throwable != null) {
                throwables.add(context.throwable);
            }
        }
        return throwables;
    }

    private void appendInitializationFailureRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isInitializationFailureError(error)) {
            return;
        }
        String failingClass = extractInitializationFailureClassName(error);
        repairMessage.append("\n\nInitialization/dependency failure hint:");
        if (failingClass != null && !failingClass.isEmpty()) {
            repairMessage.append("\n- Detected failing initialization class: ").append(failingClass);
        }
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- For failing tests only, avoid code paths that load or initialize unavailable "
                + "framework/native classes.");
        repairMessage.append("\n- Avoid static initialization triggers of missing dependencies.");
        repairMessage.append("\n- Replace failing tests with alternatives that only use available SUT/JDK types.");
        repairMessage.append("\n- Prefer pure constructors/methods and precondition checks that do not require "
                + "GUI/native/plugin containers.");
        if (containsHeadlessGuiSignal(error)) {
            repairMessage.append("\n- AWT/Swing initialization failed in headless execution: avoid UI construction "
                    + "paths that require a real display toolkit.");
            repairMessage.append("\n- Prefer non-visual SUT logic and model-level methods; only create GUI objects "
                    + "when constructor preconditions are fully initialized.");
        }
    }

    /**
     * Strong, last-chance hint emitted exactly once per conversation when a
     * headless/AWT failure repeats unchanged. By the time we reach this branch,
     * gentler guidance has already failed; the LLM is stuck on a SUT it cannot
     * construct in this JVM. Tell it explicitly to drop the constructor.
     */
    private void appendHeadlessEscalationRepairInstructions(RepairConversation state, String error,
                                                            StringBuilder repairMessage) {
        if (!state.isHeadlessRepairEscalated() || !containsHeadlessGuiSignal(error)) {
            return;
        }
        String sut = Properties.TARGET_CLASS;
        String simpleSut = sut == null ? "<SUT>"
                : sut.substring(sut.lastIndexOf('.') + 1);
        repairMessage.append("\n\nHEADLESS ESCALATION (final attempt for this failure mode):");
        repairMessage.append("\n- The previous repair attempt produced the same headless/AWT failure. The SUT cannot "
                + "be constructed in this JVM.");
        repairMessage.append("\n- Do NOT call `new ").append(simpleSut).append("(...)` anywhere in the test class — "
                + "every call path through the constructor will hit `Local GraphicsEnvironment must not be null`.");
        repairMessage.append("\n- Replace SUT instantiation with `").append(simpleSut)
                .append(" sut = Mockito.mock(").append(simpleSut).append(".class);` (or `mock(")
                .append(simpleSut).append(".class)` when static Mockito imports are present), then exercise only "
                        + "methods that do not require real GUI state.");
        repairMessage.append("\n- For tests that need real behavior, restrict them to static methods or utility entry "
                + "points reachable without instantiation.");
        repairMessage.append("\n- Do NOT extract construction into a private helper method; the parser cannot inline "
                + "GUI-touching helpers and the SUT will be elided to a typed null.");
        repairMessage.append("\n- If a test fundamentally requires a real ").append(simpleSut)
                .append(", remove that test from the class — partial coverage is preferable to a class that yields "
                        + "zero executable tests.");
    }

    static boolean containsHeadlessGuiSignal(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        return lower.contains("awterror")
                || lower.contains("headlessexception")
                || lower.contains("graphicsenvironment")
                || lower.contains("local graphicsenvironment must not be null");
    }

    private String extractInitializationFailureClassName(String error) {
        if (error == null) {
            return null;
        }
        java.util.regex.Matcher matcher = INIT_FAILURE_CLASS_PATTERN.matcher(error);
        if (matcher.find()) {
            String className = matcher.group(1);
            if (className != null && !className.trim().isEmpty()) {
                return className.trim();
            }
        }
        return null;
    }

    static boolean isInitializationFailureError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        for (String marker : INITIALIZATION_FAILURE_MARKERS) {
            if (error.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private void appendInstantiationFailureRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isInstantiationFailureError(error)) {
            return;
        }
        repairMessage.append("\n\nInstantiation failure hint:");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- For failing tests only, do NOT instantiate abstract classes or interfaces.");
        repairMessage.append("\n- Prefer concrete implementations listed in the dependency summary (use concrete "
                + "subtype constructors).");
        repairMessage.append("\n- If only an abstract/interface type is available at the call site, use a known "
                + "concrete subtype or a mock.");
        repairMessage.append("\n- Avoid reflective instantiation or helper patterns that hide non-instantiable types.");
    }

    static boolean isInstantiationFailureError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        for (String marker : INSTANTIATION_FAILURE_MARKERS) {
            if (error.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static boolean isMockingMisuseError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        for (String marker : MOCKITO_MISUSE_MARKERS) {
            if (error.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSpyUnsupportedPattern(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        boolean hasSpySignal = lower.contains("mockito.spy(")
                || lower.contains(".when(spy)")
                || lower.contains("when(spy.")
                || lower.contains("spy).");
        if (!hasSpySignal) {
            return false;
        }
        return lower.contains("__llm_fallback")
                || lower.contains("cannot find symbol")
                || lower.contains("notamockexception")
                || lower.contains("missingmethodinvocationexception")
                || lower.contains("llm_repair_action_required");
    }

    static boolean isFalsePositiveMockError(String error) {
        return extractFalsePositiveMockMethod(error) != null
                || (error != null && !error.isEmpty()
                && error.contains("FalsePositiveException")
                && error.contains("Mock call to"));
    }

    private static String extractFalsePositiveMockMethod(String error) {
        if (error == null || error.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = FALSE_POSITIVE_MOCK_CALL_PATTERN.matcher(error);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    /**
     * Picks the most useful name to surface for a false-positive mock failure.
     * The canonical signal is the {@code "Mock call to X which was not presented"}
     * phrase produced by the runtime; that yields the bare method name. The
     * SIGNATURE pattern can additionally enrich it with a {@code Class.method}
     * form when the runtime has captured the actual collaborator type — but
     * only when the matched method name agrees with the canonical hint;
     * otherwise the signature match is just noise from the test runner's own
     * stack frames (e.g. {@code java.util.ArrayList.forEach}) and must be
     * rejected so the canonical method name wins.
     */
    private static String pickFalsePositiveMockName(String error) {
        String simpleMethod = extractFalsePositiveMockMethod(error);
        String signature = extractFalsePositiveMockSignature(error);
        if (signature == null || signature.isEmpty()) {
            return simpleMethod;
        }
        if (simpleMethod == null || simpleMethod.isEmpty()) {
            return signature;
        }
        if (signature.endsWith("." + simpleMethod) || signature.equals(simpleMethod)) {
            return signature;
        }
        return simpleMethod;
    }

    private static String extractFalsePositiveMockSignature(String error) {
        if (error == null || error.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = FALSE_POSITIVE_MOCK_SIGNATURE_PATTERN.matcher(error);
        while (matcher.find()) {
            String className = matcher.group(1);
            String methodName = matcher.group(2);
            if (className == null || className.isEmpty() || methodName == null || methodName.isEmpty()) {
                continue;
            }
            String lowerClass = className.toLowerCase();
            if (lowerClass.startsWith("org.evosuite.")
                    || lowerClass.startsWith("org.mockito.")
                    || lowerClass.startsWith("java.lang.reflect.")) {
                continue;
            }
            return className + "." + methodName;
        }
        return null;
    }

    private void appendFallbackRepairInstructions(String error, StringBuilder repairMessage) {
        if (error == null || error.isEmpty()) {
            return;
        }
        String lower = error.toLowerCase();
        boolean fallbackArtifactDetected =
                lower.contains("__llm_fallback")
                        || lower.contains("preserved as uninterpretedstatement")
                        || lower.contains("cannot resolve unscoped method call")
                        || lower.contains("cannot resolve method scope")
                        || lower.contains("setfield(")
                        || lower.contains("setstaticfield(");
        if (!fallbackArtifactDetected) {
            return;
        }

        LinkedHashSet<String> hints = new LinkedHashSet<>();
        hints.add("__llm_fallback variables are parser-generated placeholders inserted because the previous "
                + "LLM output contained unresolved or unsupported code that had to be replaced with a compilable "
                        + "fallback. "
                + "Do not trigger synthetic __llm_fallback variables by reusing unsupported helper patterns.");
        hints.add("Do NOT call methods on these fallback variables; they are 'null' placeholders and will throw "
                + "NullPointerException at runtime.");
        hints.add("Instead of using a fallback, fix the object instantiation that made the parser fail by using a "
                + "valid SUT/JDK type or a proper Mockito.mock().");
        hints.add("Use only existing SUT/JDK types and exact type names; never derive package/type names from variable "
                + "names.");
        hints.add("Do not reference non-public dependency types from other packages "
                + "(diagnostics like 'X is not public in package Y; cannot be accessed from outside package').");
        hints.add("If a constructor/method path requires a non-public cross-package type, do not force it with "
                + "fallbacks; "
                + "use a public alternative setup/factory/path from context or rewrite the test to avoid that path.");
        hints.add("Do not use helper wrappers like setField/setStaticField unless those methods are explicitly "
                + "available in the test class.");
        hints.add("Do not define anonymous classes; replace them with concrete existing types or null "
                + "when acceptable.");

        repairMessage.append("\n\nFallback-strategy repair instructions:");
        for (String hint : hints) {
            repairMessage.append("\n- ").append(hint);
        }
    }

    private void appendContextSpecificRepairFacts(String error,
                                                  String previousResponse,
                                                  StringBuilder repairMessage) {
        if ((error == null || error.isEmpty())
                && (previousResponse == null || previousResponse.isEmpty())) {
            return;
        }
        ContextTypeIndex context = ContextTypeIndex.fromSummary(sutContextSummary);
        if (context.isEmpty()) {
            return;
        }

        LinkedHashSet<String> facts = new LinkedHashSet<>();
        addNonInstantiableTypeFacts(previousResponse, context, facts);
        addNpeConstructionOriginFacts(error, previousResponse, context, facts);
        addSymbolConsistencyFacts(error, context, facts);

        if (facts.isEmpty()) {
            return;
        }
        repairMessage.append("\n\nContext-specific repair facts:");
        for (String fact : facts) {
            repairMessage.append("\n- ").append(fact);
        }
    }

    private void addNonInstantiableTypeFacts(String previousResponse,
                                             ContextTypeIndex context,
                                             LinkedHashSet<String> facts) {
        if (previousResponse == null || previousResponse.isEmpty()) {
            return;
        }
        java.util.regex.Matcher matcher = NEW_CALL_PATTERN.matcher(previousResponse);
        while (matcher.find()) {
            String constructedType = matcher.group(1);
            String simple = simpleName(constructedType);
            if (!context.isNonInstantiable(simple)) {
                continue;
            }
            List<String> concrete = context.getConcreteSubtypes(simple);
            if (concrete.isEmpty()) {
                facts.add("Detected construction of non-instantiable type '" + simple
                        + "'. Use a concrete subtype from context or Mockito.mock(" + simple + ".class).");
            } else {
                facts.add("Detected construction of non-instantiable type '" + simple
                        + "'. Prefer one of these concrete subtypes from context: "
                        + joinTop(concrete, 3) + ".");
            }
        }
    }

    private void addNpeConstructionOriginFacts(String error,
                                               String previousResponse,
                                               ContextTypeIndex context,
                                               LinkedHashSet<String> facts) {
        String nullVariable = extractNullVariableName(error);
        if (nullVariable == null || nullVariable.isEmpty()
                || previousResponse == null || previousResponse.isEmpty()) {
            return;
        }

        Map<String, String> declaredTypeByVar = new LinkedHashMap<>();
        Map<String, String> constructedTypeByVar = new LinkedHashMap<>();
        java.util.regex.Matcher newDecl = NEW_DECLARATION_PATTERN.matcher(previousResponse);
        while (newDecl.find()) {
            String declaredType = stripTypeDecorators(newDecl.group(1));
            String var = newDecl.group(2);
            String constructedType = stripTypeDecorators(newDecl.group(3));
            declaredTypeByVar.put(var, declaredType);
            constructedTypeByVar.put(var, constructedType);
        }
        java.util.regex.Matcher nullDecl = NULL_DECLARATION_PATTERN.matcher(previousResponse);
        while (nullDecl.find()) {
            String declaredType = stripTypeDecorators(nullDecl.group(1));
            String var = nullDecl.group(2);
            declaredTypeByVar.put(var, declaredType);
        }

        if (!declaredTypeByVar.containsKey(nullVariable)) {
            return;
        }
        String declaredType = declaredTypeByVar.get(nullVariable);
        String declaredSimple = simpleName(declaredType);
        if (context.isNonInstantiable(declaredSimple)) {
            List<String> concrete = context.getConcreteSubtypes(declaredSimple);
            if (concrete.isEmpty()) {
                facts.add("Null variable '" + nullVariable + "' has non-instantiable declared type '"
                        + declaredSimple + "'. Repair construction first (concrete subtype or Mockito.mock).");
            } else {
                facts.add("Null variable '" + nullVariable + "' has non-instantiable declared type '"
                        + declaredSimple + "'. Repair construction first using a concrete subtype such as "
                        + joinTop(concrete, 2) + ".");
            }
            return;
        }

        String constructedType = constructedTypeByVar.get(nullVariable);
        if (constructedType != null) {
            String constructedSimple = simpleName(constructedType);
            if (context.isKnownType(declaredSimple) && context.isKnownType(constructedSimple)
                    && !declaredSimple.equals(constructedSimple)) {
                facts.add("Null variable '" + nullVariable + "' has declared type '" + declaredSimple
                        + "' but is constructed as '" + constructedSimple
                        + "'. Repair type consistency before adding downstream null checks.");
            }
        } else {
            facts.add("Null variable '" + nullVariable + "' is explicitly assigned null. "
                    + "Repair by initializing required precondition objects at construction site first.");
        }
    }

    private void addSymbolConsistencyFacts(String error,
                                           ContextTypeIndex context,
                                           LinkedHashSet<String> facts) {
        if (error == null || error.isEmpty()) {
            return;
        }
        String lower = error.toLowerCase();
        if (!(lower.contains("cannot find symbol")
                || lower.contains("package ") && lower.contains(" does not exist"))) {
            return;
        }
        java.util.regex.Matcher symbolMatcher = MISSING_SYMBOL_CLASS_PATTERN.matcher(error);
        LinkedHashSet<String> missingSymbols = new LinkedHashSet<>();
        while (symbolMatcher.find()) {
            missingSymbols.add(symbolMatcher.group(1));
        }
        java.util.regex.Matcher missingPackageMatcher = MISSING_PACKAGE_PATTERN.matcher(error);
        boolean hasMissingPackage = false;
        while (missingPackageMatcher.find()) {
            hasMissingPackage = true;
            String missingPackage = missingPackageMatcher.group(1);
            facts.add("Unresolved package/type prefix '" + missingPackage
                    + "' often means an unqualified nested type/enum constant (for example Foo.BAR). "
                    + "Use the declaring type qualifier from context (for example OuterType."
                    + missingPackage + ".BAR) or an already declared variable.");
        }
        java.util.regex.Matcher missingMethodMatcher = MISSING_SYMBOL_METHOD_WITH_LOCATION_PATTERN.matcher(error);
        boolean hasMissingMethodWithLocation = false;
        while (missingMethodMatcher.find()) {
            hasMissingMethodWithLocation = true;
            String methodName = missingMethodMatcher.group(1);
            String receiverVar = missingMethodMatcher.group(2);
            String receiverType = missingMethodMatcher.group(3);
            if (methodName != null && methodName.equals(receiverVar)) {
                facts.add("Method call '" + receiverVar + "." + methodName
                        + "(...)' is invalid: method name equals receiver variable name. "
                        + "Replace/remove this self-call typo and invoke a real method on '" + receiverType + "'.");
            } else {
                facts.add("Cannot resolve method '" + methodName + "(...)' on variable '" + receiverVar
                        + "' of type '" + receiverType + "'. Use an existing method from the provided context.");
            }
        }
        java.util.regex.Matcher missingVariableMatcher = MISSING_SYMBOL_VARIABLE_WITH_LOCATION_PATTERN.matcher(error);
        boolean hasMissingVariableWithLocation = false;
        while (missingVariableMatcher.find()) {
            hasMissingVariableWithLocation = true;
            String missingField = missingVariableMatcher.group(1);
            String receiverVar = missingVariableMatcher.group(2);
            String receiverType = missingVariableMatcher.group(3);
            if ("java.lang.Object".equals(receiverType)) {
                facts.add("Invalid member access '" + receiverVar + "." + missingField
                        + "': receiver is typed as Object (often a __llm_fallback alias). "
                        + "Remove this assertion/access and replace with assertions over real typed collaborators.");
            } else {
                facts.add("Cannot resolve member '" + missingField + "' on variable '" + receiverVar
                        + "' of type '" + receiverType + "'. Use accessible members that actually exist.");
            }
        }
        if (missingSymbols.isEmpty()) {
            if (!hasMissingMethodWithLocation && !hasMissingVariableWithLocation && !hasMissingPackage) {
                facts.add("Resolve symbol/package errors by using exact type names/packages from the provided context; "
                        + "do not invent FQCNs.");
            }
            return;
        }
        for (String symbol : missingSymbols) {
            if (error.contains("__llm_fallback")) {
                facts.add("Unresolved class '" + symbol + "' appears in a __llm_fallback declaration. "
                        + "Do not introduce short unresolved type aliases (e.g., '" + symbol
                        + " __llm_fallback...'); use a resolvable fully-qualified type or Object.");
            }
            if (error.contains("new " + symbol + "(")) {
                facts.add("Unresolved class '" + symbol + "' is being instantiated (`new "
                        + symbol + "(...)`). Do not invent helper/stub classes. "
                        + "Replace with an existing SUT/JDK type, or use Mockito.mock(ExpectedType.class, new "
                                + "ViolatedAssumptionAnswer()) "
                        + "for collaborator parameters.");
            }
            if ("Node".equals(symbol)
                    && error.contains("org.dom4j")
                    && error.contains("NodeComparator")) {
                facts.add("For dom4j tests, unqualified cast type `Node` is unresolved here. "
                        + "Use `org.dom4j.Node` (or an already imported compatible type such as `org.dom4j.Element`) "
                        + "instead of bare `Node`.");
            }
            if (context.isKnownType(symbol)) {
                facts.add("Symbol '" + symbol + "' exists in provided context; use its exact context form/package and "
                        + "avoid invented qualifiers.");
            } else {
                facts.add("Symbol '" + symbol + "' is not present in provided context; replace it with a "
                        + "context-listed SUT/JDK type.");
            }
        }
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
        long remainingNanos = repairDeadlineNanos - System.nanoTime();
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

    private String enrichParseErrorWithRepairHints(List<ParseResult> parseResults, String parseErrorText) {
        if (parseResults == null || parseResults.isEmpty()) {
            return parseErrorText;
        }

        LinkedHashSet<String> hints = new LinkedHashSet<>();
        for (ParseResult parseResult : parseResults) {
            if (parseResult == null) {
                continue;
            }
            for (ParseDiagnostic diagnostic : parseResult.getDiagnostics()) {
                if (diagnostic == null || diagnostic.getMessage() == null) {
                    continue;
                }
                String message = diagnostic.getMessage();
                int explicitActionIdx = message.indexOf("LLM_REPAIR_ACTION_REQUIRED:");
                if (explicitActionIdx >= 0) {
                    String explicit = message.substring(
                            explicitActionIdx + "LLM_REPAIR_ACTION_REQUIRED:".length()).trim();
                    if (!explicit.isEmpty()) {
                        hints.add(explicit);
                    }
                }

                String lower = message.toLowerCase();
                if (lower.contains("cannot resolve method scope")) {
                    hints.add("Declare the receiver variable earlier, or use a real static call "
                            + "in ClassName.method(...) form.");
                } else if (lower.contains("cannot resolve unscoped method call")) {
                    hints.add("Avoid bare helper calls; inline helper logic or call an existing SUT/JDK method.");
                } else if (lower.contains("no method named") || lower.contains("no matching method")) {
                    String missingMethodName = extractMissingMethodName(message);
                    if (missingMethodName != null && !missingMethodName.isEmpty()) {
                        hints.add("The parser rejected invented method `" + missingMethodName
                                + "`; keep the raw failing line visible in the prompt instead of collapsing it into "
                                        + "generic `llm_feedback`.");
                        String snippet = diagnostic.getSourceSnippet();
                        if (snippet != null && !snippet.trim().isEmpty()) {
                            hints.add("Source expression: " + snippet.trim());
                        }
                        String closest = failureFormatter.findClosestAccessibleApiSuggestion(missingMethodName);
                        if (closest != null && !closest.isEmpty()) {
                            hints.add("Closest available API: " + closest);
                        }
                    } else {
                        hints.add("Replace invented method calls with real APIs from the supplied context; keep the "
                                + "offending line visible in the repair prompt.");
                    }
                } else if (lower.contains("unknown array variable")) {
                    hints.add("Declare the array before indexing it, including full rank/dimensions.");
                } else if (lower.contains("unresolved variable")) {
                    hints.add("Declare the variable earlier and keep names consistent across statements.");
                } else if (isUnresolvedTypeDiagnostic(diagnostic)) {
                    hints.add("Replace invented/unknown types with existing SUT or JDK types.");
                }
            }
        }

        if (hints.isEmpty()) {
            return parseErrorText;
        }

        StringBuilder enriched = new StringBuilder(parseErrorText == null ? "" : parseErrorText);
        if (enriched.length() > 0) {
            enriched.append(System.lineSeparator());
        }
        enriched.append("Repair hints:");
        for (String hint : hints) {
            enriched.append(System.lineSeparator()).append("- ").append(hint);
        }
        return enriched.toString();
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
        ensureParseCompileThreadPrivileged();
        if (ToolProvider.getSystemJavaCompiler() == null) {
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

        ExecutorService executor = parseCompileExecutor;
        Future<String> future = null;
        try {
            final String method = safeMethodName(parseResult);
            final String finalSource = source;
            future = executor.submit(() -> runRenderedCompilationCheck(method, finalSource));
            String rejection = future.get(parseCompileTimeoutNanos(repairDeadlineNanos), TimeUnit.NANOSECONDS);
            return rejection == null ? CompileCheck.COMPILED : CompileCheck.rejected(rejection);
        } catch (TimeoutException timeout) {
            if (future != null) {
                future.cancel(true);
            }
            replaceParseCompileExecutor(executor);
            return CompileCheck.notJudged("compile check timed out");
        } catch (InterruptedException interrupted) {
            if (future != null) {
                future.cancel(true);
            }
            replaceParseCompileExecutor(executor);
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

    private void ensureParseCompileThreadPrivileged() {
        registerParseCompileThreadAsPrivileged();
    }

    /**
     * Registers the parse-phase compilation worker thread as privileged with
     * the EvoSuite sandbox. Must be invoked by a privileged EvoSuite thread
     * after sandbox initialization.
     */
    public static void registerParseCompileThreadAsPrivileged() {
        if (!Sandbox.isSecurityManagerInitialized()) {
            return;
        }
        ExecutorService executor = parseCompileExecutor;
        Future<Thread> future = null;
        try {
            future = executor.submit(Thread::currentThread);
            Thread worker = future.get(2L, TimeUnit.SECONDS);
            Sandbox.addPrivilegedThread(worker);
        } catch (TimeoutException timeout) {
            if (future != null) {
                future.cancel(true);
            }
            replaceParseCompileExecutor(executor);
            logger.warn("Timed out registering parse-compile worker; replaced the blocked executor");
        } catch (InterruptedException interrupted) {
            if (future != null) {
                future.cancel(true);
            }
            replaceParseCompileExecutor(executor);
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
        long remaining = repairDeadlineNanos - System.nanoTime();
        // The repair deadline governs whether another provider request may be
        // started. If it has already elapsed, still validate locally salvaged
        // tests; caller cancellation remains able to interrupt this bounded
        // compile task.
        if (remaining <= 0L) {
            return DEFAULT_PARSE_COMPILE_TIMEOUT_NANOS;
        }
        return Math.min(DEFAULT_PARSE_COMPILE_TIMEOUT_NANOS, remaining);
    }

    private static void replaceParseCompileExecutor(ExecutorService expected) {
        synchronized (PARSE_COMPILE_EXECUTOR_LOCK) {
            if (parseCompileExecutor != expected) {
                return;
            }
            try {
                expected.shutdownNow();
            } finally {
                parseCompileExecutor = createParseCompileExecutor();
            }
        }
    }

    /** Cancel compile probes and provision a clean worker for the next EvoSuite run. */
    public static void resetForRunCompletion() {
        replaceParseCompileExecutor(parseCompileExecutor);
    }

    static void setParseCompileExecutorForTesting(ExecutorService executor) {
        if (executor == null) {
            throw new IllegalArgumentException("Parse compile executor must not be null");
        }
        synchronized (PARSE_COMPILE_EXECUTOR_LOCK) {
            ExecutorService previous = parseCompileExecutor;
            parseCompileExecutor = executor;
            previous.shutdownNow();
        }
    }

    /** Test-only seam: see {@link #testClusterSupplier}. */
    void setTestClusterSupplierForTesting(Supplier<TestCluster> supplier) {
        this.testClusterSupplier = supplier == null ? TestCluster::getInstance : supplier;
    }

    private String runRenderedCompilationCheck(String methodName, String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
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

            String classPath = System.getProperty("java.class.path", "");
            int maxPasses = 3;
            for (int pass = 0; ; pass++) {
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                List<String> args = new ArrayList<>();
                args.add("-proc:none");
                args.add("-classpath");
                args.add(classPath);
                args.add("-d");
                args.add(tmpDir.toString());
                args.add(sourceFile.toString());
                int result = compiler.run(null, null, err, args.toArray(new String[0]));
                if (result == 0) {
                    return null;
                }
                String diagnostics = err.toString(StandardCharsets.UTF_8.name()).trim();
                
                boolean repaired = false;
                String repairedClasspath = org.evosuite.testcase.execution.ExecutableSnippetEngine.INSTANCE
                        .sanitizeClasspathForKnownCompileIssues(classPath, diagnostics);
                if (repairedClasspath != null && !repairedClasspath.equals(classPath)) {
                    classPath = repairedClasspath;
                    repaired = true;
                }
                
                if (!repaired || pass == maxPasses) {
                    return "Compilation error in parsed test '" + methodName
                            + "': " + diagnostics
                            + "\nNote: this error comes from EvoSuite-rendered synthesized test source (after "
                                    + "parsing), not directly from raw LLM text."
                            + "\nRendered compile-check class excerpt:\n```java\n"
                            + truncate(source, MAX_TEST_CODE_EXCERPT_CHARS)
                            + "\n```";
                }
            }
        } finally {
            if (tmpDir != null) {
                try {
                    deleteRecursively(tmpDir.toFile());
                } catch (Throwable ignored) {
                    // best-effort cleanup
                }
            }
        }
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

        String mockitoCanonical = org.mockito.Mockito.class.getCanonicalName();
        if (mockitoCanonical != null) {
            importCollector.append("import " + mockitoCanonical + ";");
            importCollector.append("import static " + mockitoCanonical + ".*;");
        } else {
            importCollector.append("import org.mockito.Mockito;");
            importCollector.append("import static org.mockito.Mockito.*;");
        }

        String matchersCanonical = org.mockito.ArgumentMatchers.class.getCanonicalName();
        if (matchersCanonical != null) {
            importCollector.append("import " + matchersCanonical + ";");
            importCollector.append("import static " + matchersCanonical + ".*;");
        } else {
            importCollector.append("import org.mockito.ArgumentMatchers;");
            importCollector.append("import static org.mockito.ArgumentMatchers.*;");
        }

        String evoAssertionsCanonical = org.evosuite.runtime.EvoAssertions.class.getCanonicalName();
        if (evoAssertionsCanonical != null && evoAssertionsCanonical.startsWith("shaded.")) {
            importCollector.append("import static " + evoAssertionsCanonical + ".*;");
        } else {
            importCollector.append("import static org.evosuite.runtime.EvoAssertions.*;");
        }

        String vaaCanonical = org.evosuite.runtime.ViolatedAssumptionAnswer.class.getCanonicalName();
        if (vaaCanonical != null && vaaCanonical.startsWith("shaded.")) {
            importCollector.append("import " + vaaCanonical + ";");
        } else {
            importCollector.append("import org.evosuite.runtime.ViolatedAssumptionAnswer;");
        }
        
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
            String simpleName = extractImportSimpleName(trimmed);
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

        private String extractImportSimpleName(String importLine) {
            String body;
            if (importLine.startsWith("import static ")) {
                body = importLine.substring("import static ".length(), importLine.length() - 1).trim();
            } else {
                body = importLine.substring("import ".length(), importLine.length() - 1).trim();
            }
            if (body.endsWith(".*")) {
                body = body.substring(0, body.length() - 2);
            }
            int lastDot = body.lastIndexOf('.');
            if (lastDot < 0 || lastDot == body.length() - 1) {
                return null;
            }
            return body.substring(lastDot + 1);
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

    private void deleteRecursively(File root) throws IOException {
        if (root == null || !root.exists()) {
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!root.delete() && root.exists()) {
            throw new IOException("Could not delete " + root.getAbsolutePath());
        }
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
