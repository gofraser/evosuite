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

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.SystemPromptProvider;
import org.evosuite.llm.prompt.TestClusterSummarizer;
import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.junit.UnitTestAdapter;
import org.evosuite.junit.writer.TestSuiteWriterUtils;
import org.evosuite.assertion.Assertion;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.ExecutionResult;
import java.util.Optional;
import java.util.Collections;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.evosuite.runtime.sandbox.Sandbox;

/**
 * Applies parse -> validate -> execute and iterative LLM repair.
 */
public class TestRepairLoop {

    private static final Logger logger = LoggerFactory.getLogger(TestRepairLoop.class);
    private static final long NO_REPAIR_DEADLINE = Long.MAX_VALUE;
    private static final long REPAIR_DEADLINE_RETURN_MARGIN_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final int MAX_STACK_EXCERPT_FRAMES = 4;
    private static final int MAX_TEST_CODE_EXCERPT_CHARS = 1600;
    private static final int MAX_FALLBACK_DIAGNOSTIC_CHARS = 1200;
    private static final int MAX_FALLBACK_DIAGNOSTICS = 2;

    /** Pattern to normalize line numbers for fuzzy error comparison. */
    private static final Pattern LINE_NUMBER_PATTERN = Pattern.compile(
            "(?<=\\(line |line |Line )\\d+|(?<=position )\\d+");
    private static final Pattern NPE_NULL_VARIABLE_PATTERN = Pattern.compile(
            "because\\s+\"([^\"]+)\"\\s+is null");
    private static final Pattern NPE_CANNOT_INVOKE_PATTERN = Pattern.compile(
            "Cannot invoke\\s+\"([^\"]+)\"\\s+because\\s+\"([^\"]+)\"\\s+is null");
    private static final Pattern SYNTHETIC_LOCAL_VARIABLE_PATTERN = Pattern.compile(
            "<local\\d+>");
    private static final Pattern INIT_FAILURE_CLASS_PATTERN = Pattern.compile(
            "Could not initialize class\\s+([A-Za-z0-9_.$]+)");
    private static final Pattern CONTEXT_TYPE_HEADER_PATTERN = Pattern.compile(
            "^\\s*//\\s*([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\[(interface|abstract|class|enum)\\].*$");
    private static final Pattern CONTEXT_CONCRETE_SUBTYPES_PATTERN = Pattern.compile(
            "^\\s*concrete subtypes:\\s*(.*)$");
    private static final Pattern NEW_DECLARATION_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_$.<>]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new\\s+([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\(");
    private static final Pattern NULL_DECLARATION_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_$.<>]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*null\\s*;");
    private static final Pattern FALLBACK_NULL_DECLARATION_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_$.<>]*)\\s+(__llm_fallback\\d+)\\s*=\\s*null\\s*;");
    private static final Pattern PARSED_TEST_CODE_EXCERPT_PATTERN = Pattern.compile(
            "Parsed test code excerpt:\\n```java\\n(.*?)\\n```", Pattern.DOTALL);
    private static final String EXCERPT_INDEX_MARKER_PREFIX = "__EVOSUITE_REPAIR_INDEX__";
    private static final Pattern EXCERPT_INDEX_MARKER_PATTERN = Pattern.compile(
            "^\\s*//\\s*" + Pattern.quote(EXCERPT_INDEX_MARKER_PREFIX) + "(\\d+)\\s*$");
    private static final Pattern NEW_CALL_PATTERN = Pattern.compile(
            "\\bnew\\s+([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\(");
    private static final Pattern CONSTRUCTOR_DIAGNOSTIC_TYPE_PATTERN = Pattern.compile(
            "No matching constructor found for\\s+([A-Za-z_][A-Za-z0-9_$.]+)");
    private static final String EXPLICIT_REPAIR_ACTION_MARKER = "LLM_REPAIR_ACTION_REQUIRED:";
    private static final Pattern EXPLICIT_REPAIR_ACTION_PATTERN = Pattern.compile(
            EXPLICIT_REPAIR_ACTION_MARKER + "\\s*(.*)$");
    private static final Pattern MISSING_METHOD_DIAGNOSTIC_PATTERN = Pattern.compile(
            "No method named\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s+in\\s+([A-Za-z_][A-Za-z0-9_$.]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_SIGNATURE_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_$]*)\\s*\\(([^\\)]*)\\)");
    private static final Pattern INVENTED_UNKNOWN_TYPE_PATTERN = Pattern.compile(
            "replace invented/unknown type '([A-Za-z_][A-Za-z0-9_$.]*)'",
            Pattern.CASE_INSENSITIVE);
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
    private static final ExecutorService PARSE_COMPILE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "evosuite-llm-parse-compile");
        t.setDaemon(true);
        return t;
    });

    /** Error patterns that the LLM cannot fix (native libs, sandbox, etc.). */
    private static final Set<String> UNFIXABLE_ERROR_PATTERNS = new HashSet<>(Arrays.asList(
            "UnsatisfiedLinkError",
            "AccessControlException",
            "java.security.AccessControlException"
    ));
    /**
     * FQCN prefix for exceptions thrown by EvoSuite's mock runtime. Anything
     * under this package is produced deterministically by the harness, not by
     * the SUT, so no test can avoid it: instead of dropping such tests as
     * failures, we accept them for coverage purposes.
     */
    static final String SHADED_MOCK_RUNTIME_PACKAGE_PREFIX =
            "shaded.org.evosuite.runtime.mock.";
    private static final Set<String> DEPENDENCY_MISSING_MARKERS = new HashSet<>(Arrays.asList(
            "NoClassDefFoundError",
            "ClassNotFoundException",
            "ClassNotFoundError",
            "Could not initialize class",
            "cannot find symbol",
            "NoClassDefFoundError:"
    ));
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
    private final DependencyCodeContextResolver dependencyCodeResolver = new DependencyCodeContextResolver();
    private final RepairHintResolver repairHintResolver = new RepairHintResolver();
    private boolean expansionAttempted;
    /** FQCNs whose static initializer has failed during this repair conversation. */
    private final Set<String> poisonedClasses = new LinkedHashSet<>();
    /** Hint-id -> last repair attempt where it was emitted, for cooldown/dedup. */
    private final Map<String, Integer> hintLastShownAttemptById = new LinkedHashMap<>();
    /**
     * Access-violation tracking keys ("memberName:declaringClass") accumulated
     * across repair attempts. Used to detect recurrent inaccessible-member
     * errors and escalate to early termination.
     */
    private final Set<String> seenAccessViolations = new LinkedHashSet<>();

    /**
     * One-shot flag: set when the loop encounters an identical-error retry on
     * a headless/AWT failure and decides to escalate (do not skip; instead
     * inject a stronger "use Mockito.mock(SUT.class)" instruction). The next
     * repair message includes the escalation hint, after which this flag stays
     * true so we do not bypass the identical-error skip again in the same
     * conversation.
     */
    private boolean headlessRepairEscalated;

    /**
     * One-shot flag: set when the loop encounters an identical-error retry
     * that does not match any of the more specific escalation paths (headless,
     * access-violation, dependency-missing). Allows exactly one more repair
     * turn with a generic "the environment cannot satisfy this assumption,
     * change the expected exception or drop the test" nudge prepended to the
     * user message before falling back to abort.
     */
    private boolean identicalErrorEscalated;

    /**
     * Transient text injected at the top of the next repair user message. Used
     * by escalation paths to communicate a single high-priority directive that
     * must not be diluted by the rest of the rule-based hint blocks. Cleared
     * after it is consumed so the same hint is not repeated on later turns.
     */
    private String pendingTopOfMessageEscalationHint;

    /**
     * Per-test execution failure: the formatted error string, the originating
     * throwable (used for dependency-code lookup), and the failing statement
     * position when known.
     */
    private static final class ExecutionFailureContext {
        final String errorMessage;
        final Throwable throwable;
        final Integer failingPosition;

        ExecutionFailureContext(String errorMessage, Throwable throwable, Integer failingPosition) {
            this.errorMessage = errorMessage;
            this.throwable = throwable;
            this.failingPosition = failingPosition;
        }
    }

    /**
     * Options controlling parse-repair behavior.
     */
    public static class RepairOptions {
        private final boolean keepAssertionsInParsedTests;
        private final boolean repairOnAssertionFailures;
        private final boolean instructRepairToAvoidAssertions;

        public static RepairOptions defaults() {
            return new RepairOptions(true, true, false);
        }

        public static RepairOptions forAssertionPolicy(boolean keepAssertions) {
            return new RepairOptions(keepAssertions, keepAssertions, !keepAssertions);
        }

        public RepairOptions(boolean keepAssertionsInParsedTests,
                             boolean repairOnAssertionFailures,
                             boolean instructRepairToAvoidAssertions) {
            this.keepAssertionsInParsedTests = keepAssertionsInParsedTests;
            this.repairOnAssertionFailures = repairOnAssertionFailures;
            this.instructRepairToAvoidAssertions = instructRepairToAvoidAssertions;
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
    }

    /**
     * Creates a standard repair loop wired to the given LLM service, using
     * the SUT-aware parser, default response parser, and cluster expansion manager.
     */
    public static TestRepairLoop createDefault(LlmService llmService) {
        return createDefault(llmService, RepairOptions.defaults());
    }

    /**
     * Creates a standard repair loop wired to the given LLM service and options.
     */
    public static TestRepairLoop createDefault(LlmService llmService, RepairOptions options) {
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
                Properties.LLM_REPAIR_ATTEMPTS,
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
        // Conversation-scoped state: any class poisoned in a previous turn must
        // remain in the avoidance instructions, even when later turns surface
        // different errors.
        poisonedClasses.clear();
        hintLastShownAttemptById.clear();
        seenAccessViolations.clear();
        headlessRepairEscalated = false;
        identicalErrorEscalated = false;
        pendingTopOfMessageEscalationHint = null;
        boolean observedExecutionDrop = false;
        boolean strictContractReaskUsed = false;
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
                        validateRecoveredSource(extractedClass);
                    }
                    ReflectiveAssertThrowsRewriteResult rewriteResult =
                            rewriteReflectiveAssertThrowsAssertions(extractedClass);
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
                    String next = tryRepair(parserFailureText, attempt, previousError, diagnostics,
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
                String next = tryRepair(parserFailureText, attempt, previousError, diagnostics,
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
                    String next = tryStrictContractRepair(strictContractError, attempt, diagnostics,
                            conversation, currentResponse, feature, expandedClasses,
                            repairDeadlineNanos);
                    if (next == null) {
                        break;
                    }
                    previousError = strictContractError;
                    currentResponse = next;
                    continue;
                }
                String next = tryRepair(parseErrorText, attempt, previousError, diagnostics,
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
                boolean expanded = false;
                this.expansionAttempted = true;
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
                    String compileError = checkRenderedCompilation(pr);
                    if (compileError == null) {
                        compileChecked.add(pr);
                    } else {
                        pr.addDiagnostic(new org.evosuite.testparser.ParseDiagnostic(org.evosuite.testparser.ParseDiagnostic.Severity.ERROR, compileError, -1, ""));
                        droppedAtParse.add(pr);
                        diagnostics.add(compileError);
                    }
                }
                validTests = compileChecked;
            }

            // Execute valid tests and split into executable (kept) and dropped-at-execution.
            List<ParseResult> finalTests = new ArrayList<>();
            List<ParseResult> droppedAtExecution = new ArrayList<>();
            Map<ParseResult, ExecutionFailureContext> executionErrorByTest = new LinkedHashMap<>();
            for (ParseResult pr : validTests) {
                ExecutionFailureContext failure = checkExecution(pr, repairOptions);
                if (failure == null) {
                    finalTests.add(pr);
                } else {
                    droppedAtExecution.add(pr);
                    executionErrorByTest.put(pr, failure);
                    diagnostics.add(failure.errorMessage);
                }
            }

            // Record any executable tests we have so far so they survive later repair turns.
            publishSalvagedTests(
                    mergeSalvagedTests(salvagedExecutableTests, finalTests),
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

            String next = tryRepair(combinedError, attempt, previousError, diagnostics,
                    conversation, currentResponse, feature, expandedClasses,
                    executionErrorByTest, repairDeadlineNanos);
            if (next == null) {
                if (repairOptions.isKeepAssertionsInParsedTests()) {
                    diagnostics.add("Skipped assertion-stripping brute-force salvage "
                            + "because assertions must be preserved.");
                } else {
                    // Final-resort salvage is only valid for integrations whose
                    // policy explicitly permits assertions to be removed.
                    List<ParseResult> allFailedTests = new ArrayList<>(droppedAtParse);
                    allFailedTests.addAll(droppedAtExecution);
                    for (ParseResult pr : allFailedTests) {
                        Optional<TestCase> salvaged = performBruteForceSalvage(pr.getTestCase());
                        if (salvaged.isPresent()) {
                            ParseResult salvagedResult = new ParseResult(salvaged.get(),
                                                                         pr.getOriginalMethodName(),
                                                                         pr.getDiagnostics());
                            publishSalvagedTests(
                                    mergeSalvagedTests(salvagedExecutableTests,
                                            Collections.singletonList(salvagedResult)),
                                    salvagedTestsConsumer);
                        }
                    }
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
        return RepairResult.failure(diagnostics, attemptsUsed, expandedClasses);
    }

    private List<ParseResult> mergeSalvagedTests(Map<String, ParseResult> salvaged, List<ParseResult> newResults) {
        List<ParseResult> accepted = new ArrayList<>();
        if (newResults == null || newResults.isEmpty()) {
            return accepted;
        }
        for (ParseResult parseResult : newResults) {
            if (parseResult == null) {
                continue;
            }
            if (hasOrphanedVariableReferences(parseResult.getTestCase(),
                    "LLM-parsed test '" + parseResult.getOriginalMethodName() + "'")) {
                continue;
            }
            String key = buildSalvageDedupKey(parseResult);
            if (!salvaged.containsKey(key)) {
                salvaged.put(key, parseResult);
                accepted.add(parseResult);
            }
        }
        return accepted;
    }

    private void publishSalvagedTests(List<ParseResult> newlySalvaged,
                                      Consumer<List<ParseResult>> salvagedTestsConsumer) {
        if (salvagedTestsConsumer == null || newlySalvaged == null || newlySalvaged.isEmpty()) {
            return;
        }
        try {
            salvagedTestsConsumer.accept(Collections.unmodifiableList(new ArrayList<>(newlySalvaged)));
        } catch (RuntimeException e) {
            logger.debug("LLM salvage publication failed; continuing repair loop", e);
        }
    }

    private String buildSalvageDedupKey(ParseResult parseResult) {
        String method = parseResult.getOriginalMethodName();
        String methodPart = (method == null || method.trim().isEmpty())
                ? "unknown"
                : method.trim();
        String codeFingerprint = fingerprintTestCode(parseResult.getTestCase());
        return methodPart + "|" + codeFingerprint;
    }

    private String fingerprintTestCode(TestCase testCase) {
        if (testCase == null) {
            return "null";
        }
        try {
            String normalized = normalizeForDedup(testCase.toCode());
            if (!normalized.isEmpty()) {
                return Integer.toHexString(normalized.hashCode());
            }
        } catch (Throwable ignored) {
            // Fall through to identity-based key if rendering fails.
        }
        return "id_" + System.identityHashCode(testCase);
    }

    private String normalizeForDedup(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        return source.replaceAll("\\s+", " ").trim();
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

    /**
     * Reject test cases whose clone() would throw because some
     * {@link org.evosuite.testcase.variable.VariableReference} no longer resolves
     * to a defining statement. Logs at WARN so the next occurrence is
     * traceable to its parse path, then returns true to signal the caller to
     * drop the test. Returns false (silently) on a well-formed test case.
     */
    private boolean hasOrphanedVariableReferences(TestCase testCase, String context) {
        if (testCase == null) {
            return false;
        }
        List<String> orphans;
        try {
            orphans = TestParser.findOrphanedVariableReferences(testCase);
        } catch (Throwable t) {
            logger.warn("Orphan-reference validation crashed for {}; dropping test ({})",
                    context, t.toString());
            return true;
        }
        if (orphans.isEmpty()) {
            return false;
        }
        logger.warn("Dropping {} due to {} orphaned variable reference(s); details: {}",
                context, orphans.size(), orphans);
        return true;
    }

    /**
     * Reject test cases that contain {@code AssignmentStatement}s whose
     * positional copy would crash with "wrong position N, total N" — the
     * symptom is invisible to {@link #hasOrphanedVariableReferences} because
     * the AssignmentStatement is itself the only matching definition for its
     * retval. ObjectPool sequence insertion and certain crossover-driven
     * paths trigger {@code Statement.copy(newTestCase, offset)} and would
     * kill the master process; dropping the salvaged test here keeps the
     * search alive.
     */
    private boolean hasUnsafelyCopyableStatements(TestCase testCase, String context) {
        if (testCase == null) {
            return false;
        }
        List<String> issues;
        try {
            issues = TestParser.findUnsafelyCopyableStatements(testCase);
        } catch (Throwable t) {
            logger.warn("Copy-safety validation crashed for {}; dropping test ({})",
                    context, t.toString());
            return true;
        }
        if (issues.isEmpty()) {
            return false;
        }
        logger.warn("Dropping {} due to {} unsafe-to-copy statement(s); details: {}",
                context, issues.size(), issues);
        return true;
    }

    private Optional<TestCase> performBruteForceSalvage(TestCase failedTestCase) {
        logger.warn("Attempting brute-force salvage for test case: {}", failedTestCase.getID());
        if (hasOrphanedVariableReferences(failedTestCase,
                "salvage candidate test " + failedTestCase.getID())) {
            return Optional.empty();
        }
        TestCase salvaged = failedTestCase.clone();

        // 1. Initial attempt: remove assertions and assertion-only raw snippets.
        LlmAssertionSanitizer.sanitize(salvaged);

        // Force reset to align with strict JUnitAnalyzer check
        ClassReInitializer.getInstance().resetAllInitializedClasses(1);
        ExecutionResult result = TestCaseExecutor.runTest(salvaged);

        if (result.noThrownExceptions()) {
            if (isMeaningful(salvaged)) {
                if (hasUnsafelyCopyableStatements(salvaged,
                        "salvaged-no-assertions test " + failedTestCase.getID())) {
                    return Optional.empty();
                }
                logger.warn("Successfully salvaged test {} by removing assertions.", failedTestCase.getID());
                return Optional.of(salvaged);
            }
            logger.warn("Discarded salvaged test {} as it is not meaningful.", failedTestCase.getID());
            return Optional.empty();
        }

        // 1b. If the throwing statement IS the SUT's meaningful entry call,
        // chopping it would remove the only line worth keeping. Instead, drop
        // everything AFTER the failing call and keep the test as a "covering
        // throw" — the chromosome will exercise the SUT up to the throw point,
        // and the test writer wraps it in a try/fail block at render time.
        Optional<TestCase> coveringThrow = trySalvageAsCoveringSutThrow(salvaged, result, failedTestCase);
        if (coveringThrow.isPresent()) {
            return coveringThrow;
        }

        // 2. Iterative truncation at failure site
        while (!result.noThrownExceptions()) {
            Map<Integer, Throwable> exceptionMapping = result.getCopyOfExceptionMapping();
            Integer failIndex = exceptionMapping.keySet().stream().min(Integer::compareTo).orElse(-1);
            if (failIndex == null || failIndex <= 0) break;

            salvaged.chop(failIndex);
            LlmAssertionSanitizer.sanitize(salvaged);

            // Force reset again for the truncated test
            ClassReInitializer.getInstance().resetAllInitializedClasses(1);
            result = TestCaseExecutor.runTest(salvaged);

            if (result.noThrownExceptions()) {
                if (isMeaningful(salvaged)) {
                    if (hasUnsafelyCopyableStatements(salvaged,
                            "salvaged-truncated test " + failedTestCase.getID()
                                    + " (chop@" + failIndex + ")")) {
                        return Optional.empty();
                    }
                    logger.warn("Successfully salvaged test {} by truncation at index {}.", failedTestCase.getID(), failIndex);
                    return Optional.of(salvaged);
                }
                logger.warn("Discarded salvaged test {} after truncation as it is not meaningful.", failedTestCase.getID());
                return Optional.empty();
            }
        }

        logger.warn("Failed to salvage test case: {}", failedTestCase.getID());
        return Optional.empty();
    }

    /**
     * If the failing index of <code>salvaged</code> points at a statement that
     * invokes the SUT directly (a method call on the target class, or a
     * constructor of the target class), keep the test as a covering-throw
     * chromosome. The throwing statement stays; everything after it is
     * removed. The test still throws when re-run, but it now genuinely
     * exercises the SUT and contributes coverage up to the throw point.
     */
    private Optional<TestCase> trySalvageAsCoveringSutThrow(TestCase salvaged,
                                                            ExecutionResult result,
                                                            TestCase failedTestCase) {
        if (salvaged == null || result == null || result.noThrownExceptions()) {
            return Optional.empty();
        }
        Map<Integer, Throwable> exceptionMapping = result.getCopyOfExceptionMapping();
        Integer failIndex = exceptionMapping.keySet().stream().min(Integer::compareTo).orElse(-1);
        if (failIndex == null || failIndex < 0 || failIndex >= salvaged.size()) {
            return Optional.empty();
        }
        org.evosuite.testcase.statements.Statement failStmt = salvaged.getStatement(failIndex);
        if (!isSutCallStatement(failStmt)) {
            return Optional.empty();
        }
        // Trim everything after the failing statement (keep [0..failIndex]).
        if (salvaged.size() > failIndex + 1) {
            salvaged.chop(failIndex + 1);
        }
        // Strip any assertions that may now be dangling.
        LlmAssertionSanitizer.sanitize(salvaged);
        if (!isMeaningful(salvaged)) {
            return Optional.empty();
        }
        if (hasUnsafelyCopyableStatements(salvaged,
                "covering-throw salvage test " + failedTestCase.getID()
                        + " (chop@" + (failIndex + 1) + ")")) {
            return Optional.empty();
        }
        Throwable thrown = exceptionMapping.get(failIndex);
        String thrownName = thrown == null ? "<unknown>" : thrown.getClass().getName();
        logger.warn("Salvaged test {} as covering-throw at SUT call (index {}, exception {}).",
                failedTestCase.getID(), failIndex, thrownName);
        return Optional.of(salvaged);
    }

    /**
     * True when the statement is a direct invocation of the SUT (a method
     * declared by the target class, or a constructor of the target class).
     * Used by the salvage path to recognize the only line worth keeping when
     * the LLM-generated test throws at the SUT entry point.
     */
    private boolean isSutCallStatement(org.evosuite.testcase.statements.Statement statement) {
        if (statement == null || Properties.TARGET_CLASS == null) {
            return false;
        }
        if (statement instanceof org.evosuite.testcase.statements.MethodStatement) {
            org.evosuite.testcase.statements.MethodStatement ms =
                    (org.evosuite.testcase.statements.MethodStatement) statement;
            Class<?> declaring = ms.getMethod() == null ? null
                    : ms.getMethod().getDeclaringClass();
            return declaring != null
                    && Properties.TARGET_CLASS.equals(declaring.getName());
        }
        if (statement instanceof org.evosuite.testcase.statements.ConstructorStatement) {
            org.evosuite.testcase.statements.ConstructorStatement cs =
                    (org.evosuite.testcase.statements.ConstructorStatement) statement;
            if (cs.getReturnValue() == null
                    || cs.getReturnValue().getVariableClass() == null) {
                return false;
            }
            return Properties.TARGET_CLASS.equals(
                    cs.getReturnValue().getVariableClass().getName());
        }
        return false;
    }

    private boolean isMeaningful(TestCase testCase) {
        return TestInteractionChecker.invokesTarget(testCase, Properties.TARGET_CLASS);
    }


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
        sb.append(System.lineSeparator()).append("- Do not solve this by returning only the surviving test(s) or by silently deleting most failing scenarios.");
        sb.append(System.lineSeparator()).append("- Return a complete corrected class that preserves the kept tests and repairs or regenerates the dropped ones so the batch still exercises the intended behaviors.");

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
                        + "'. Repair the shared environment/setup once or avoid that path instead of deleting those tests individually.");
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
            return "Multiple dropped tests fail because a ViolatedAssumptionAnswer mock path is missing one or more collaborator stubs. "
                    + "Repair the shared mock setup once by adding the minimal stub(s) the SUT actually calls instead of deleting those tests one-by-one.";
        }
        return "Multiple dropped tests fail because the SUT reaches an unstubbed mock call to `"
                + mockMethodName
                + "(...)` on a ViolatedAssumptionAnswer collaborator. Repair the shared mock setup once by stubbing that collaborator method (and any returned nested collaborator) instead of deleting those tests one-by-one.";
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
            hint.append(" Repair the shared object/collaborator setup before that call, instead of deleting those tests one-by-one.");
        }
        return hint.toString();
    }

    private String buildSharedIndexedFixtureFailureHint() {
        return "Multiple dropped tests share the same bounds/shape failure while constructing or populating indexed fixtures. "
                + "Repair the shared setup pattern once: make constructor size/count arguments consistent with every later indexed get/set call, "
                + "do not assume every helper/container stores all slots exactly 1:1 with its constructor arguments, and regenerate those fixtures instead of deleting the tests one-by-one.";
    }

    private String safeMethodName(ParseResult parseResult) {
        if (parseResult == null) {
            return "<unnamed>";
        }
        String name = parseResult.getOriginalMethodName();
        return (name == null || name.isEmpty()) ? "<unnamed>" : name;
    }

    private ExecutionFailureContext checkExecution(ParseResult parseResult, RepairOptions options) {
        ExecutionResult executionResult;
        try {
            executionResult = testExecutor.execute(parseResult.getTestCase());
            if (executionResult != null) {
                for (Integer position : executionResult.getPositionsWhereExceptionsWereThrown()) {
                    Throwable thrown = executionResult.getExceptionThrownAtPosition(position);
                    if (!isUndeclaredException(parseResult, position, thrown)) {
                        continue;
                    }
                    if (!options.isRepairOnAssertionFailures() && thrown instanceof AssertionError) {
                        continue;
                    }
                    Integer diagnosticPosition = resolveDiagnosticStatementPosition(parseResult, position, thrown);
                    String message = "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': " + formatThrowableWithStackExcerpt(thrown, parseResult, diagnosticPosition)
                            + buildExecutionContext(parseResult, diagnosticPosition, thrown);
                    return new ExecutionFailureContext(message, thrown, diagnosticPosition);
                }
            }
        } catch (Throwable executionFailure) {
            String message = "Execution failure in test '" + parseResult.getOriginalMethodName()
                    + "': " + formatThrowableWithStackExcerpt(executionFailure, parseResult, null)
                    + buildExecutionContext(parseResult, null, executionFailure);
            return new ExecutionFailureContext(message, executionFailure, null);
        }
        if (options.isRepairOnAssertionFailures()) {
            ExecutionFailureContext assertionError = checkAssertions(parseResult, executionResult);
            if (assertionError != null) {
                return assertionError;
            }
        }
        return null;
    }

    private ExecutionFailureContext checkAssertions(ParseResult parseResult, ExecutionResult executionResult) {
        if (parseResult == null || parseResult.getTestCase() == null || executionResult == null) {
            return null;
        }
        if (executionResult.getFinalScope() == null) {
            return null;
        }
        for (int i = 0; i < parseResult.getTestCase().size(); i++) {
            org.evosuite.testcase.statements.Statement stmt = parseResult.getTestCase().getStatement(i);
            if (stmt == null || stmt.getAssertions() == null || stmt.getAssertions().isEmpty()) {
                continue;
            }
            for (Assertion assertion : stmt.getAssertions()) {
                if (assertion == null) {
                    continue;
                }
                final boolean holds;
                try {
                    holds = assertion.evaluate(executionResult.getFinalScope());
                } catch (Throwable assertionFailure) {
                    String message = "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': Assertion evaluation error at statement " + i + " - "
                            + formatThrowableWithStackExcerpt(assertionFailure, parseResult, i)
                            + buildExecutionContext(parseResult, i, assertionFailure);
                    return new ExecutionFailureContext(message, assertionFailure, i);
                }
                if (!holds) {
                    String message = "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': Assertion failed at statement " + i + " - "
                            + assertion.getCode()
                            + buildExecutionContext(parseResult, i, null);
                    return new ExecutionFailureContext(message, null, i);
                }
            }
        }
        return null;
    }

    private boolean isUndeclaredException(ParseResult parseResult, Integer position, Throwable thrown) {
        if (parseResult == null || parseResult.getTestCase() == null || position == null || thrown == null) {
            return false;
        }
        if (matchesExpectedException(parseResult, thrown)) {
            return false;
        }
        // Exceptions raised by the EvoSuite mock runtime (shaded.org.evosuite.runtime.mock.*)
        // are environment-induced, deterministic, and unavoidable for any test that
        // reaches the mocked call site. Accept them as if they were declared so the
        // test is kept as a covering chromosome instead of dropped.
        if (isShadedRuntimeMockException(thrown)) {
            return false;
        }
        if (position < 0 || position >= parseResult.getTestCase().size()) {
            // If execution reports an exception at an invalid position, treat it as undeclared.
            // Silently ignoring it can incorrectly mark a failing candidate as successful.
            return true;
        }
        return !parseResult.getTestCase().getStatement(position)
                .getDeclaredExceptions().contains(thrown.getClass());
    }

    /**
     * Returns true when the throwable (or any of its superclasses) is a member
     * of EvoSuite's shaded mock runtime package. Used by the executor and the
     * repair-loop hint logic to recognize environment-induced exceptions that
     * the LLM cannot eliminate by changing the test.
     */
    static boolean isShadedRuntimeMockException(Throwable thrown) {
        if (thrown == null) {
            return false;
        }
        Class<?> current = thrown.getClass();
        while (current != null) {
            String name = current.getName();
            if (name != null && name.startsWith(SHADED_MOCK_RUNTIME_PACKAGE_PREFIX)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    /**
     * Returns true when the diagnostic error string contains a reference to
     * an exception under the shaded mock runtime package, suggesting the
     * underlying failure is environment-induced rather than test-fixable.
     */
    static boolean errorMentionsShadedRuntimeMockException(String error) {
        return error != null && error.contains(SHADED_MOCK_RUNTIME_PACKAGE_PREFIX);
    }

    private boolean matchesExpectedException(ParseResult parseResult, Throwable thrown) {
        if (parseResult == null || thrown == null) {
            return false;
        }
        String expected = parseResult.getExpectedExceptionClass();
        if (expected == null || expected.trim().isEmpty()) {
            return false;
        }
        Class<?> current = thrown.getClass();
        while (current != null) {
            if (matchesExpectedExceptionName(current, expected)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private boolean matchesExpectedExceptionName(Class<?> actualClass, String expected) {
        if (actualClass == null || expected == null) {
            return false;
        }
        String normalized = expected.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return actualClass.getName().equals(normalized)
                || actualClass.getSimpleName().equals(normalized)
                || actualClass.getName().endsWith("." + normalized);
    }

    private void validateRecoveredSource(String source) {
        try {
            StaticJavaParser.parse(source);
        } catch (ParseProblemException parseProblemException) {
            throw new IllegalArgumentException("Recovered source is still not valid Java: "
                    + parseProblemException.getMessage(), parseProblemException);
        }
    }

    private static final class ReflectiveAssertThrowsRewriteResult {
        private final String source;
        private final int rewrites;

        private ReflectiveAssertThrowsRewriteResult(String source, int rewrites) {
            this.source = source;
            this.rewrites = rewrites;
        }
    }

    private ReflectiveAssertThrowsRewriteResult rewriteReflectiveAssertThrowsAssertions(String source) {
        if (source == null || source.isEmpty()) {
            return new ReflectiveAssertThrowsRewriteResult(source, 0);
        }
        final CompilationUnit compilationUnit;
        try {
            compilationUnit = StaticJavaParser.parse(source);
        } catch (Throwable ignored) {
            // Best-effort normalization only; keep original source if parsing fails.
            return new ReflectiveAssertThrowsRewriteResult(source, 0);
        }

        int rewrites = 0;
        List<MethodCallExpr> assertThrowsCalls = new ArrayList<>(compilationUnit.findAll(MethodCallExpr.class));
        for (MethodCallExpr call : assertThrowsCalls) {
            if (!"assertThrows".equals(call.getNameAsString()) || call.getArguments().size() != 2) {
                continue;
            }

            Expression expectedArg = call.getArgument(0);
            if (!(expectedArg instanceof ClassExpr)) {
                continue;
            }
            String expectedType = ((ClassExpr) expectedArg).getType().toString();
            if (expectedType.endsWith("InvocationTargetException")
                    || "java.lang.reflect.InvocationTargetException".equals(expectedType)) {
                continue;
            }

            Expression executableArg = call.getArgument(1);
            if (!(executableArg instanceof LambdaExpr)) {
                continue;
            }
            LambdaExpr lambdaExpr = (LambdaExpr) executableArg;

            MethodCallExpr invokeCall = extractSingleReflectiveInvokeCall(lambdaExpr);
            if (invokeCall == null) {
                continue;
            }

            Node parent = call.getParentNode().orElse(null);
            if (!(parent instanceof ExpressionStmt)) {
                continue;
            }
            ExpressionStmt expressionStmt = (ExpressionStmt) parent;
            BlockStmt blockStmt = expressionStmt.findAncestor(BlockStmt.class).orElse(null);
            if (blockStmt == null) {
                continue;
            }

            int statementIndex = blockStmt.getStatements().indexOf(expressionStmt);
            if (statementIndex < 0) {
                continue;
            }
            if (!isReflectiveMethodInvokeReceiver(invokeCall, blockStmt, statementIndex)) {
                continue;
            }

            String variableName = "invocationTargetException" + rewrites;
            String causeVariableName = "expectedCause" + rewrites;
            String rewrittenThrow = "java.lang.reflect.InvocationTargetException " + variableName
                    + " = org.junit.jupiter.api.Assertions.assertThrows("
                    + "java.lang.reflect.InvocationTargetException.class, "
                    + lambdaExpr.toString() + ");";
            String rewrittenCauseAssert = expectedType + " " + causeVariableName
                    + " = (" + expectedType + ") " + variableName + ".getCause();";

            Statement rewrittenThrowStmt;
            Statement rewrittenCauseAssertStmt;
            try {
                rewrittenThrowStmt = StaticJavaParser.parseStatement(rewrittenThrow);
                rewrittenCauseAssertStmt = StaticJavaParser.parseStatement(rewrittenCauseAssert);
            } catch (Throwable ignored) {
                continue;
            }

            blockStmt.getStatements().set(statementIndex, rewrittenThrowStmt);
            blockStmt.addStatement(statementIndex + 1, rewrittenCauseAssertStmt);
            rewrites++;
        }

        if (rewrites == 0) {
            return new ReflectiveAssertThrowsRewriteResult(source, 0);
        }
        return new ReflectiveAssertThrowsRewriteResult(compilationUnit.toString(), rewrites);
    }

    private MethodCallExpr extractSingleReflectiveInvokeCall(LambdaExpr lambdaExpr) {
        if (lambdaExpr == null) {
            return null;
        }
        if (lambdaExpr.getBody() instanceof ExpressionStmt) {
            Expression expression = ((ExpressionStmt) lambdaExpr.getBody()).getExpression();
            if (expression instanceof MethodCallExpr) {
                MethodCallExpr callExpr = (MethodCallExpr) expression;
                return "invoke".equals(callExpr.getNameAsString()) ? callExpr : null;
            }
            return null;
        }
        if (lambdaExpr.getBody() instanceof BlockStmt) {
            BlockStmt blockStmt = (BlockStmt) lambdaExpr.getBody();
            if (blockStmt.getStatements().size() != 1) {
                return null;
            }
            Statement single = blockStmt.getStatement(0);
            if (!(single instanceof ExpressionStmt)) {
                return null;
            }
            Expression expression = ((ExpressionStmt) single).getExpression();
            if (expression instanceof MethodCallExpr) {
                MethodCallExpr callExpr = (MethodCallExpr) expression;
                return "invoke".equals(callExpr.getNameAsString()) ? callExpr : null;
            }
        }
        return null;
    }

    private boolean isReflectiveMethodInvokeReceiver(MethodCallExpr invokeCall,
                                                     BlockStmt blockStmt,
                                                     int statementIndex) {
        if (invokeCall == null || blockStmt == null || statementIndex < 0) {
            return false;
        }
        if (!invokeCall.getScope().isPresent()) {
            return false;
        }
        Expression scope = invokeCall.getScope().get();
        if (!(scope instanceof NameExpr)) {
            return false;
        }
        String receiverName = ((NameExpr) scope).getNameAsString();
        if (receiverName == null || receiverName.isEmpty()) {
            return false;
        }

        // Only treat invoke(...) as reflective when we can find a prior local declaration
        // of that receiver as java.lang.reflect.Method (or Method via import).
        for (int i = statementIndex - 1; i >= 0; i--) {
            Statement statement = blockStmt.getStatement(i);
            if (!(statement instanceof ExpressionStmt)) {
                continue;
            }
            Expression expression = ((ExpressionStmt) statement).getExpression();
            if (!(expression instanceof VariableDeclarationExpr)) {
                continue;
            }
            VariableDeclarationExpr declarationExpr = (VariableDeclarationExpr) expression;
            for (com.github.javaparser.ast.body.VariableDeclarator variable : declarationExpr.getVariables()) {
                if (!receiverName.equals(variable.getNameAsString())) {
                    continue;
                }
                String typeName = variable.getType().asString();
                return "Method".equals(typeName)
                        || "java.lang.reflect.Method".equals(typeName)
                        || typeName.endsWith(".Method");
            }
        }
        return false;
    }

    /**
     * Sends a repair request and accumulates the conversation with the assistant's
     * previous response and the error feedback, so subsequent repairs have full context.
     */
    private String requestRepair(List<LlmMessage> conversation,
                                 String previousResponse,
                                 String error,
                                 LlmFeature feature,
                                 List<String> expandedClasses,
                                 int repairAttempt,
                                 Map<ParseResult, ExecutionFailureContext> executionFailures,
                                 long repairDeadlineNanos) {
        // Ensure system prompt is at the front of the conversation
        if (systemPrompt != null && !systemPrompt.isEmpty()
                && (conversation.isEmpty()
                    || !conversation.get(0).getContent().equals(systemPrompt))) {
            conversation.add(0, LlmMessage.system(systemPrompt));
        }

        // Add the assistant's last response to the conversation
        if (previousResponse != null && !previousResponse.isEmpty()) {
            conversation.add(LlmMessage.assistant(previousResponse));
        }
        // Build repair message with error + SUT context reminder
        StringBuilder repairMessage = new StringBuilder();
        if (pendingTopOfMessageEscalationHint != null && !pendingTopOfMessageEscalationHint.isEmpty()) {
            repairMessage.append("=== PRIORITY DIRECTIVE ===\n")
                         .append(pendingTopOfMessageEscalationHint)
                         .append("\n=== END PRIORITY DIRECTIVE ===\n\n");
            pendingTopOfMessageEscalationHint = null;
        }
        if (!expandedClasses.isEmpty()) {
            repairMessage.append("Note: The test cluster has been expanded with newly resolved classes: ")
                         .append(expandedClasses).append("\n\n");
        }
        repairMessage.append("The following issue was found in the generated tests:\n")
                     .append(error);
        if (isDependencyMissingError(error)) {
            repairMessage.append("\n\nDependency-missing repair instructions:")
                    .append("\n- Do NOT reference or instantiate missing external/framework classes.")
                    .append("\n- Avoid code paths that trigger static initialization of missing dependencies.")
                    .append("\n- Replace such tests with alternatives that only use available SUT/JDK types.")
                    .append("\n- Focus on pure constructors/methods that do not require GUI/network/plugin containers.");
        }
        appendInitializationFailureRepairInstructions(error, repairMessage);
        appendHeadlessEscalationRepairInstructions(error, repairMessage);
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
        appendMemberAccessRepairInstructions(error, repairMessage);
        appendFalsePositiveMockRepairInstructions(error, repairMessage);
        appendNoTestMethodsRepairInstructions(error, previousResponse, repairMessage);
        appendSpyUnsupportedRepairInstructions(error, repairMessage);
        appendCollaboratorFallbackRepairInstructions(error, repairMessage);
        appendSyntheticFallbackUsageRepairInstructions(error, repairMessage);
        appendNotAMockRepairInstructions(error, repairMessage);
        appendAnonymousImplementationRepairInstructions(error, previousResponse, repairMessage);
        appendContextSpecificRepairFacts(error, previousResponse, repairMessage);
        appendDependencyCodeContext(executionFailures, repairMessage);
        appendRuleBasedRepairHints(error, executionFailures, repairAttempt, repairMessage);
        // Include the bulk dependency-types catalog only on the first repair
        // turn (repairAttempt == 2 in the conversation numbering: 1 = initial
        // seed, 2 = first repair). On later turns the LLM already has it in
        // its conversation history, and re-emitting tens of KB of catalog text
        // dilutes the failure-specific hints that should drive the repair.
        if (sutContextSummary != null && !sutContextSummary.isEmpty() && repairAttempt <= 2) {
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

        boolean expanded = this.expansionAttempted;
        this.expansionAttempted = false;
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
                    .append("' comes from inside the failing SUT call path and is not necessarily a direct test variable; ")
                    .append("it is likely a parameter or local variable inside the SUT.");
            repairMessage.append("\n- Repair the upstream collaborator setup so the SUT receives a non-null value there before dereferencing it.");
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
                            .append("', likely as a parameter or local variable there, not necessarily a same-named test variable.");
                    repairMessage.append("\n- Stub or initialize the upstream collaborator/return value so that receiver is a non-null ")
                            .append(dereferenceInfo.receiverType)
                            .append(" before the SUT reaches that call.");
                }
                if (isDomElementType(dereferenceInfo.receiverType)) {
                    repairMessage.append("\n- For DOM collaborators, build a minimal in-memory element "
                            + "(DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument().createElement(\"model\")) "
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
        /** test method name -> "expected=X, actual=Y" */
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
            if (isShadedRuntimeMockException(thrown)
                    || (ctx.errorMessage != null
                            && errorMentionsShadedRuntimeMockException(ctx.errorMessage))) {
                scopes.shadedMockFailingTests.add(name);
            }
            String expected = pr.getExpectedExceptionClass();
            if (expected != null && !expected.trim().isEmpty()) {
                String actualName = thrown != null ? thrown.getClass().getName() : null;
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
                    .append(" failed with an exception under shaded.org.evosuite.runtime.mock.*;");
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
        repairMessage.append("\n- Do NOT use Mockito.spy(...) or doReturn(...).when(spy)...; regenerate failing tests using real instances or Mockito.mock(...) collaborators.");
        repairMessage.append("\n- Complete each stubbing expression fully (avoid unfinished stubbing chains).");
    }

    private void appendSpyUnsupportedRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isSpyUnsupportedPattern(error)) {
            return;
        }
        repairMessage.append("\n\nSpy-unsupported repair instructions:");
        repairMessage.append("\n- Do NOT use Mockito.spy(...) in EvoSuite-generated parsed tests.");
        repairMessage.append("\n- Do NOT use spy-based stubbing such as doReturn(...).when(spy)... or when(spy.method(...)).thenReturn(...).");
        repairMessage.append("\n- Replace spy-based setup with one of: (1) direct calls on real SUT instances without spy stubbing, or (2) Mockito.mock(...) collaborators that are stubbed minimally.");
        repairMessage.append("\n- Never alias or stub through parser-generated '__llm_fallback...' variables.");
    }

    private void appendFalsePositiveMockRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isFalsePositiveMockError(error)) {
            return;
        }

        String mockMethodName = pickFalsePositiveMockName(error);
        repairMessage.append("\n\nMock-stubbing hint:");
        repairMessage.append("\n- The rerun failed because a mocked collaborator used ViolatedAssumptionAnswer and the SUT called an unstubbed method.");
        if (mockMethodName != null && !mockMethodName.isEmpty()) {
            repairMessage.append("\n- Unstubbed mock call: `").append(mockMethodName).append("(...)`.");
        } else {
            repairMessage.append("\n- Unstubbed mock call: the runtime reported a false-positive mock-policy failure, but the exact method name was not captured.");
        }
        repairMessage.append("\n- Add the minimal stub for the collaborator method the SUT actually calls, instead of leaving that mock on a bare ViolatedAssumptionAnswer path.");
        repairMessage.append("\n- Keep the rest of the test unchanged unless it depends on the missing stub.");
        repairMessage.append("\n- Do not replace the collaborator with null; make the mock return the value or nested collaborator the SUT needs.");
    }

    private void appendStreamPreconditionRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isStreamRelatedError(error)) {
            return;
        }
        repairMessage.append("\n\nStream precondition hint:");
        repairMessage.append("\n- The failing test violates stream preconditions or stream lifecycle constraints.");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- For failing tests only, avoid null InputStream/OutputStream/Reader/Writer arguments unless the method contract explicitly expects null.");
        repairMessage.append("\n- Prefer in-memory data sources over filesystem/network I/O in tests:");
        repairMessage.append("\n  ByteArrayInputStream for bytes, StringReader for text, ByteArrayOutputStream/StringWriter for outputs.");
        repairMessage.append("\n- In EvoSuite sandbox, avoid direct file-writer constructors "
                + "(e.g., FileOutputStream/FileWriter/PrintWriter on real files); use in-memory outputs or "
                + "EvoSuite mock I/O types (MockFile*, MockPrint*) when file-like APIs are required.");
        repairMessage.append("\n- Build minimally valid non-empty test payloads before invoking parser/configuration methods.");
        repairMessage.append("\n- If the expected behavior is argument validation, assert the concrete thrown exception for null stream arguments.");
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
        repairMessage.append("\n- For failing tests only, replace invalid empty/default inputs with minimally valid non-empty values before invoking the SUT.");
        repairMessage.append("\n- Typical fixes: provide at least one required line/entry/token/element instead of empty structures or empty strings.");
        repairMessage.append("\n- If testing validation behavior, use assertThrows(...) for the invalid-input case and add a separate valid-input smoke test.");
        if (eventObjectNullSource) {
            repairMessage.append("\n- Detected `IllegalArgumentException: null source` from EventObject/ActionEvent construction.");
            repairMessage.append("\n- Never build `new ActionEvent(null, ...)`: the first constructor argument (event source) must be non-null.");
            repairMessage.append("\n- If receiver construction collapsed to a typed null placeholder (`__llm_fallback...`), do not keep emitting event-driven tests on that null receiver; repair upstream construction or rewrite/drop those tests.");
        }
    }

    private void appendIndexedFixtureShapeRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isIndexedFixtureShapeError(error)) {
            return;
        }
        repairMessage.append("\n\nIndexed fixture/bounds hint:");
        repairMessage.append("\n- The failing test uses inconsistent dimensions, counts, or indices while building an indexed fixture or invoking an indexed API.");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- For failing tests only, re-check each constructor size/count argument against every later indexed read/write in that test.");
        repairMessage.append("\n- Do not assume constructor counts map 1:1 to all later accessible rows/elements/slots; some helpers reserve header or metadata positions, or derive internal storage differently.");
        repairMessage.append("\n- Rebuild the fixture so every set/get index stays within the actual bounds created by the constructor and setup calls.");
        repairMessage.append("\n- If the behavior under test is rejection of invalid sizes or indices, stop the test at the offending constructor/mutator with assertThrows(...) instead of continuing with normal-path assertions.");
    }

    private void appendReflectiveInvocationRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isReflectiveInvocationWrapperError(error)) {
            return;
        }
        repairMessage.append("\n\nReflection wrapper hint:");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- Prefer direct invocation of accessible SUT methods over reflection.");
        repairMessage.append("\n- If using Method.invoke(...), expect InvocationTargetException from assertThrows.");
        repairMessage.append("\n- Then assert the wrapped cause type/message (e.getCause()) matches the expected SUT exception.");
        repairMessage.append("\n- Avoid Class.forName/getDeclaredMethod/setAccessible when the target method is public or package-private.");
    }

    private void appendReflectiveAssertThrowsFallbackRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isReflectiveAssertThrowsFallbackError(error)) {
            return;
        }
        repairMessage.append("\n\nReflective assertThrows fallback hint:");
        repairMessage.append("\n- The shown InvocationTargetException/__llm_fallback line is a parser-generated fallback, not valid intended test logic.");
        repairMessage.append("\n- Replace the entire failing reflective assertion fragment instead of keeping the synthetic fallback variable or aliasing it.");
        repairMessage.append("\n- Prefer direct invocation of accessible SUT methods over reflection.");
        repairMessage.append("\n- If reflection is unavoidable, capture InvocationTargetException with Assertions.assertThrows(...), then cast/check invocationTargetException.getCause() as the expected SUT exception type.");
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
                        .append(" If the runtime object is a subtype, add an `instanceof` guard and cast before calling subtype APIs")
                        .append(" (for example `Component` -> `JLabel` before `getText()`).");
                String closest = findClosestAccessibleApiSuggestion(methodName);
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
            String closest = findClosestAccessibleApiSuggestion(methodName);
            if (closest != null && !closest.isEmpty()) {
                repairMessage.append("\n- Closest available API: ").append(closest);
            }
        }
    }

    private void appendMemberAccessRepairInstructions(String error, StringBuilder repairMessage) {
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
        boolean hasRecurrent = false;
        for (String key : currentKeys) {
            if (seenAccessViolations.contains(key)) {
                hasRecurrent = true;
                break;
            }
        }
        seenAccessViolations.addAll(currentKeys);

        repairMessage.append("\n\nMember-access repair hint:");
        repairMessage.append("\n- The failing test directly accesses private or protected members (which are not allowed). Note: Package-private members ARE allowed as tests are in the same package.");
        repairMessage.append("\n- Keep all already executable tests unchanged.");

        // Emit specific inaccessible member names so the LLM knows exactly what to avoid
        if (!violations.isEmpty()) {
            repairMessage.append("\n- Specifically inaccessible members detected:");
            for (AccessViolationDiagnosticParser.AccessViolation v : violations) {
                if (hasRecurrent && seenAccessViolations.contains(v.toTrackingKey())) {
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

        repairMessage.append("\n- For failing tests only, remove direct field/member access assertions and replace them with checks via accessible public or package-visible methods.");
        repairMessage.append("\n- Do not assert internal fields such as receiver.superclassField; assert externally visible behavior instead.");
        repairMessage.append("\n- If no accessible API exposes the same state, drop that assertion rather than forcing illegal member access.");
        repairMessage.append("\n- Also avoid non-public cross-package types in imports/declarations/constructors; use only public accessible types/paths from context.");
        if (lower.contains("baserecognizer") || lower.contains(".state.")) {
            repairMessage.append("\n- ANTLR-specific: never access `lexer.state` / `BaseRecognizer.state` directly.")
                    .append(" Remove assertions like `lexer.state.type` and assert behavior via public lexer APIs or tokenization results.");
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
                .append(" __llm_fallback... = null;' line is a parser-generated placeholder, not a usable collaborator setup.");
        repairMessage.append("\n- Do not alias that synthetic fallback, call methods on it, stub through it, or keep passing null instead of a real ")
                .append(simpleType)
                .append(" value.");
        repairMessage.append("\n- Replace it with a real collaborator before invoking the SUT.");
        repairMessage.append("\n- If ")
                .append(simpleType)
                .append(" is interface/abstract-like in context, prefer Mockito.mock(")
                .append(simpleType)
                .append(".class, new ViolatedAssumptionAnswer()) and stub only the minimal methods the SUT call actually uses.");
        repairMessage.append("\n- If that collaborator cannot be constructed or mocked with available SUT/JDK types, rewrite the failing test to avoid that path instead of keeping the synthetic fallback.");
    }

    private void appendNotAMockRepairInstructions(String error, StringBuilder repairMessage) {
        if (error.contains("NotAMockException")) {
            repairMessage.append("\n\nNot-a-mock repair instructions:");
            repairMessage.append("\n- The failing test attempted to use Mockito.when() or Mockito.doReturn() on a real object.");
            repairMessage.append("\n- Only use Mockito.when() or Mockito.doReturn() on objects created via Mockito.mock().");
            repairMessage.append("\n- If the object was created using a 'new' constructor, it is a real instance. Remove the Mockito stubbing for this object.");
        }
    }

    private void appendSyntheticFallbackUsageRepairInstructions(String error, StringBuilder repairMessage) {
        if (error.contains("NullPointerException") && error.contains("__llm_fallback")) {
            repairMessage.append("\n\nSynthetic fallback usage error:");
            repairMessage.append("\n- You are attempting to call a method on a variable named '__llm_fallback...' which is 'null'.");
            repairMessage.append("\n- This variable is a placeholder and should NEVER be used as a valid object instance.");
            repairMessage.append("\n- You MUST fix the constructor call that led to this fallback by providing valid data (e.g., a valid byte array header like {0xCA, 0xFE, 0xBA, 0xBE} for ASM ClassReader).");
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
        Class<?> resolvedType = tryResolveRepairType(constructedType);
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
                        .append("' is an interface, so the one-off anonymous body still has to implement every required method.");
            } else if (Modifier.isAbstract(resolvedType.getModifiers()) && hasOnlyNonPublicConstructors(resolvedType)) {
                repairMessage.append("\n- '")
                        .append(simpleType)
                        .append("' is abstract and its constructors are not directly public, so a one-method anonymous subclass is especially brittle here.");
            } else if (Modifier.isAbstract(resolvedType.getModifiers())) {
                repairMessage.append("\n- '")
                        .append(simpleType)
                        .append("' is abstract, so overriding one visible method is still not enough to make the anonymous subtype valid.");
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
            repairMessage.append("\n- The reported javac syntax errors (for example `illegal start of type`, `';' expected`, or stray `return` lines) are downstream effects of the malformed anonymous class, not evidence that EvoSuite parsed the body incorrectly.");
        }
        repairMessage.append("\n- Do NOT write new ")
                .append(simpleType)
                .append("() { ... } just to override one method.");
        repairMessage.append("\n- Replace it with Mockito.mock(")
                .append(constructedType)
                .append(".class, new ViolatedAssumptionAnswer()) or a concrete existing subtype from context.");
        repairMessage.append("\n- Stub or verify only the minimal method actually relevant to the failing path instead of hand-writing an anonymous class body.");
        repairMessage.append("\n- If the intended assertion is that a callback like showErrorDialog(...) must not be reached, keep the collaborator as a mock and verify the method is never invoked instead of calling fail(...) inside an anonymous override.");
    }

    private void appendNoTestMethodsRepairInstructions(String error,
                                                       String previousResponse,
                                                       StringBuilder repairMessage) {
        if (error == null || !error.contains("Parser produced no test methods")) {
            return;
        }
        repairMessage.append("\n\nNo-test-method parsing hint:");
        repairMessage.append("\n- Return a complete compilable Java test class that contains one or more concrete methods annotated with @Test inside the class body.");
        
        List<String> malformedImports = extractMalformedImportLines(error + "\n" + (previousResponse == null ? "" : previousResponse));
        if (!malformedImports.isEmpty()) {
            boolean hasAlias = malformedImports.stream().anyMatch(l -> l.toLowerCase().contains(" as "));
            if (hasAlias) {
                repairMessage.append("\n- The previous response used invalid 'import ... as ...' alias syntax, which Java does not support.");
                repairMessage.append("\n- To resolve naming conflicts (e.g., between two classes named 'Query'), use the fully qualified name at the call site instead of aliasing the import.");
                repairMessage.append("\n- Example: `net.sourceforge.beanbin.query.Query beanBinQuery = new net.sourceforge.beanbin.query.Query();`.");
            } else {
                repairMessage.append("\n- The previous response used invalid Java import syntax with file-path separators instead of package dots.");
                for (String importLine : malformedImports.subList(0, Math.min(2, malformedImports.size()))) {
                    repairMessage.append("\n- Invalid import from previous response: ").append(importLine);
                }
                repairMessage.append("\n- Java imports must use dotted package names, for example `import br.com.jnfe.base.service.SimpleSecurityHandlerBean;`.");
            }
        } else if (error.contains("LLM response was empty") || error.contains("hit a token limit") || error.contains("contained only bytecode")) {
            repairMessage.append("\n- Do not return only context, documentation, or bytecode disassembly.");
            repairMessage.append("\n- Generate actual test code with concrete @Test methods that instantiate and interact with the target class.");
            repairMessage.append("\n- If your previous response was cut off or hit a token limit, generate fewer test cases but include actual test code.");
        } else if (previousResponse != null && previousResponse.contains("@Test")) {
            repairMessage.append("\n- The previous response already looked like a test class, so malformed Java syntax likely prevented method extraction. Recheck imports, class declaration, braces, and method headers.");
        } else {
            repairMessage.append("\n- Do not return prose or helper snippets only; include actual @Test methods in the final class.");
        }
    }

    private String extractTypedFallbackDeclaredType(String error) {
        if (error == null || error.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = FALLBACK_NULL_DECLARATION_PATTERN.matcher(error);
        while (matcher.find()) {
            String declaredType = stripTypeDecorators(matcher.group(1));
            String simpleType = simpleName(declaredType);
            if (declaredType.isEmpty()
                    || "Object".equals(simpleType)
                    || "InvocationTargetException".equals(simpleType)) {
                continue;
            }
            return declaredType;
        }
        return null;
    }

    private String buildNoTestMethodsParseError(String extractedClass) {
        StringBuilder error = new StringBuilder("Parser produced no test methods.");
        List<String> malformedImports = extractMalformedImportLines(extractedClass);
        if (!malformedImports.isEmpty()) {
            error.append("\nDetected malformed import statement(s):");
            for (String importLine : malformedImports.subList(0, Math.min(2, malformedImports.size()))) {
                error.append("\n- ").append(importLine);
            }
            error.append("\nRepair action: Java import declarations must use package dots, not file-path slashes, and do NOT support 'as' aliases. ");
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
            error.append("\nRepair action: Generate actual Java test code with @Test methods, not context documentation.");
        } else if (extractedClass.contains("@Test")) {
            error.append("\nThe extracted source appears to contain @Test annotations, so malformed Java syntax earlier in the class likely prevented method extraction.");
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

    private List<String> extractTypedNullDeclarationTypes(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> declaredTypes = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = NULL_DECLARATION_PATTERN.matcher(text);
        while (matcher.find()) {
            String declaredType = stripTypeDecorators(matcher.group(1));
            String simpleType = simpleName(declaredType);
            if (declaredType.isEmpty()
                    || "Object".equals(simpleType)
                    || "InvocationTargetException".equals(simpleType)) {
                continue;
            }
            declaredTypes.add(declaredType);
        }
        return new ArrayList<>(declaredTypes);
    }

    private String extractFallbackTypeFromDiagnostic(ParseDiagnostic diagnostic) {
        if (diagnostic == null) {
            return null;
        }
        String message = diagnostic.getMessage() == null ? "" : diagnostic.getMessage();
        java.util.regex.Matcher constructorMatcher = CONSTRUCTOR_DIAGNOSTIC_TYPE_PATTERN.matcher(message);
        if (constructorMatcher.find()) {
            return stripTypeDecorators(constructorMatcher.group(1));
        }

        String sourceSnippet = diagnostic.getSourceSnippet();
        if (sourceSnippet == null || sourceSnippet.trim().isEmpty()) {
            return null;
        }
        java.util.regex.Matcher constructorSourceMatcher = NEW_CALL_PATTERN.matcher(sourceSnippet);
        if (constructorSourceMatcher.find()) {
            return stripTypeDecorators(constructorSourceMatcher.group(1));
        }
        return null;
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
     * Append two kinds of dep-code-driven repair guidance:
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
    private void appendDependencyCodeContext(Map<ParseResult, ExecutionFailureContext> executionFailures,
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
                            poisonedClasses.add(analysis.getPoisonedClass());
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

        appendPoisonedClassAvoidance(repairMessage);
        appendDependencyCodeExcerpt(depFrame, repairMessage);
    }

    private void appendPoisonedClassAvoidance(StringBuilder repairMessage) {
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
            repairMessage.append("\n- Provide constructor arguments that satisfy the body's null/range checks before invoking it.");
        } else {
            repairMessage.append("\n- Initialize collaborators or arguments so the method body's branches succeed instead of throwing.");
        }
        repairMessage.append("\n- Do not assert on the dependency's internals; only adjust how your test sets up inputs to it.");
    }

    private void appendRuleBasedRepairHints(String error,
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
                poisonedClasses,
                repairAttempt,
                hintLastShownAttemptById,
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
            hintLastShownAttemptById.put(hint.getId(), repairAttempt);
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
        repairMessage.append("\n- For failing tests only, avoid code paths that load or initialize unavailable framework/native classes.");
        repairMessage.append("\n- Avoid static initialization triggers of missing dependencies.");
        repairMessage.append("\n- Replace failing tests with alternatives that only use available SUT/JDK types.");
        repairMessage.append("\n- Prefer pure constructors/methods and precondition checks that do not require GUI/native/plugin containers.");
        if (containsHeadlessGuiSignal(error)) {
            repairMessage.append("\n- AWT/Swing initialization failed in headless execution: avoid UI construction paths that require a real display toolkit.");
            repairMessage.append("\n- Prefer non-visual SUT logic and model-level methods; only create GUI objects when constructor preconditions are fully initialized.");
        }
    }

    /**
     * Strong, last-chance hint emitted exactly once per conversation when a
     * headless/AWT failure repeats unchanged. By the time we reach this branch,
     * gentler guidance has already failed; the LLM is stuck on a SUT it cannot
     * construct in this JVM. Tell it explicitly to drop the constructor.
     */
    private void appendHeadlessEscalationRepairInstructions(String error, StringBuilder repairMessage) {
        if (!headlessRepairEscalated || !containsHeadlessGuiSignal(error)) {
            return;
        }
        String sut = Properties.TARGET_CLASS;
        String simpleSut = sut == null ? "<SUT>"
                : sut.substring(sut.lastIndexOf('.') + 1);
        repairMessage.append("\n\nHEADLESS ESCALATION (final attempt for this failure mode):");
        repairMessage.append("\n- The previous repair attempt produced the same headless/AWT failure. The SUT cannot be constructed in this JVM.");
        repairMessage.append("\n- Do NOT call `new ").append(simpleSut).append("(...)` anywhere in the test class — every call path through the constructor will hit `Local GraphicsEnvironment must not be null`.");
        repairMessage.append("\n- Replace SUT instantiation with `").append(simpleSut)
                .append(" sut = Mockito.mock(").append(simpleSut).append(".class);` (or `mock(")
                .append(simpleSut).append(".class)` when static Mockito imports are present), then exercise only methods that do not require real GUI state.");
        repairMessage.append("\n- For tests that need real behavior, restrict them to static methods or utility entry points reachable without instantiation.");
        repairMessage.append("\n- Do NOT extract construction into a private helper method; the parser cannot inline GUI-touching helpers and the SUT will be elided to a typed null.");
        repairMessage.append("\n- If a test fundamentally requires a real ").append(simpleSut)
                .append(", remove that test from the class — partial coverage is preferable to a class that yields zero executable tests.");
    }

    private boolean containsHeadlessGuiSignal(String error) {
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
        repairMessage.append("\n- Prefer concrete implementations listed in the dependency summary (use concrete subtype constructors).");
        repairMessage.append("\n- If only an abstract/interface type is available at the call site, use a known concrete subtype or a mock.");
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
                + "LLM output contained unresolved or unsupported code that had to be replaced with a compilable fallback. "
                + "Do not trigger synthetic __llm_fallback variables by reusing unsupported helper patterns.");
        hints.add("Do NOT call methods on these fallback variables; they are 'null' placeholders and will throw NullPointerException at runtime.");
        hints.add("Instead of using a fallback, fix the object instantiation that made the parser fail by using a valid SUT/JDK type or a proper Mockito.mock().");
        hints.add("Use only existing SUT/JDK types and exact type names; never derive package/type names from variable names.");
        hints.add("Do not reference non-public dependency types from other packages "
                + "(diagnostics like 'X is not public in package Y; cannot be accessed from outside package').");
        hints.add("If a constructor/method path requires a non-public cross-package type, do not force it with fallbacks; "
                + "use a public alternative setup/factory/path from context or rewrite the test to avoid that path.");
        hints.add("Do not use helper wrappers like setField/setStaticField unless those methods are explicitly available in the test class.");
        hints.add("Do not define anonymous classes; replace them with concrete existing types or null when acceptable.");

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
                facts.add("Resolve symbol/package errors by using exact type names/packages from the provided context; do not invent FQCNs.");
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
                        + "Replace with an existing SUT/JDK type, or use Mockito.mock(ExpectedType.class, new ViolatedAssumptionAnswer()) "
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
                facts.add("Symbol '" + symbol + "' exists in provided context; use its exact context form/package and avoid invented qualifiers.");
            } else {
                facts.add("Symbol '" + symbol + "' is not present in provided context; replace it with a context-listed SUT/JDK type.");
            }
        }
    }

    private static String stripTypeDecorators(String typeName) {
        if (typeName == null) {
            return "";
        }
        String base = typeName.trim();
        int generic = base.indexOf('<');
        if (generic >= 0) {
            base = base.substring(0, generic);
        }
        return base;
    }

    private String simpleName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "";
        }
        String clean = stripTypeDecorators(typeName);
        int lastDot = clean.lastIndexOf('.');
        return lastDot >= 0 ? clean.substring(lastDot + 1) : clean;
    }

    private String joinTop(List<String> values, int maxItems) {
        if (values == null || values.isEmpty() || maxItems <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(values.size(), maxItems);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        if (values.size() > limit) {
            sb.append(", ...");
        }
        return sb.toString();
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

    private static final class ContextTypeIndex {
        private final Map<String, String> typeKind;
        private final Map<String, List<String>> concreteSubtypes;

        private ContextTypeIndex(Map<String, String> typeKind,
                                 Map<String, List<String>> concreteSubtypes) {
            this.typeKind = typeKind;
            this.concreteSubtypes = concreteSubtypes;
        }

        static ContextTypeIndex fromSummary(String summary) {
            Map<String, String> typeKind = new LinkedHashMap<>();
            Map<String, List<String>> concreteSubtypes = new LinkedHashMap<>();
            if (summary == null || summary.trim().isEmpty()) {
                return new ContextTypeIndex(typeKind, concreteSubtypes);
            }

            String currentType = null;
            String[] lines = summary.split("\\r?\\n");
            for (String raw : lines) {
                if (raw == null) {
                    continue;
                }
                String line = raw.trim();
                java.util.regex.Matcher typeHeader = CONTEXT_TYPE_HEADER_PATTERN.matcher(line);
                if (typeHeader.matches()) {
                    currentType = typeHeader.group(1);
                    String kind = typeHeader.group(2);
                    String simple = simpleOf(currentType);
                    typeKind.put(simple, kind);
                    continue;
                }
                if (currentType == null) {
                    continue;
                }
                java.util.regex.Matcher concrete = CONTEXT_CONCRETE_SUBTYPES_PATTERN.matcher(line);
                if (!concrete.matches()) {
                    continue;
                }
                String payload = concrete.group(1);
                List<String> values = parseSubtypeList(payload);
                if (!values.isEmpty()) {
                    concreteSubtypes.put(simpleOf(currentType), values);
                }
            }
            return new ContextTypeIndex(typeKind, concreteSubtypes);
        }

        boolean isEmpty() {
            return typeKind.isEmpty();
        }

        boolean isKnownType(String simple) {
            return simple != null && typeKind.containsKey(simple);
        }

        boolean isNonInstantiable(String simple) {
            if (simple == null) {
                return false;
            }
            String kind = typeKind.get(simple);
            return "interface".equals(kind) || "abstract".equals(kind);
        }

        String getKind(String simple) {
            return typeKind.get(simple);
        }

        List<String> getConcreteSubtypes(String simple) {
            List<String> values = concreteSubtypes.get(simple);
            return values == null ? Collections.<String>emptyList() : values;
        }

        private static String simpleOf(String name) {
            if (name == null || name.isEmpty()) {
                return "";
            }
            int dot = name.lastIndexOf('.');
            return dot >= 0 ? name.substring(dot + 1) : name;
        }

        private static List<String> parseSubtypeList(String payload) {
            List<String> out = new ArrayList<>();
            if (payload == null || payload.trim().isEmpty()) {
                return out;
            }
            String[] tokens = payload.split(",");
            for (String token : tokens) {
                String t = token == null ? "" : token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                out.add(t);
            }
            return out;
        }
    }

    /**
     * Returns true if the error should not be sent to the LLM for repair
     * (either because it is an environment-level error that the LLM cannot fix,
     * or because it is identical to the previous error, indicating a stuck loop).
     */
    private boolean shouldSkipRepair(String error, String previousError, List<String> diagnostics) {
        if (isUnfixableError(error)) {
            logger.info("Aborting repair: unfixable environment error detected: {}", error);
            diagnostics.add("Skipped repair: unfixable environment error");
            return true;
        }
        if (previousError != null && normalizeError(previousError).equals(normalizeError(error))) {
            if (isDependencyMissingError(error)) {
                logger.info("Aborting repair: dependency-missing error persisted after targeted guidance: {}", error);
                diagnostics.add("Skipped repair: dependency-missing error persisted");
                return true;
            }
            // Headless escalation: when the LLM keeps producing tests that crash on
            // AWT/Swing initialization, the diagnostics fed back to it usually
            // describe downstream NPEs (because the parser elided the GUI ctor with
            // a typed null), not the original headless error. Give the LLM one more
            // turn with an explicit "use Mockito.mock(SUT.class)" instruction
            // before bailing out.
            if (!headlessRepairEscalated && containsHeadlessGuiSignal(error)) {
                headlessRepairEscalated = true;
                logger.info("Continuing repair with headless escalation hint after identical-error retry: {}", error);
                diagnostics.add("Continuing repair: headless escalation hint injected for identical-error retry");
                return false;
            }
            // Generic identical-error escalation: the LLM has produced essentially
            // the same source as last turn (same normalized error). Before
            // bailing out, give it exactly one more turn with a strong, top-of-
            // message directive to either change the expected exception class
            // to what was actually observed or drop the test entirely.
            if (!identicalErrorEscalated) {
                identicalErrorEscalated = true;
                pendingTopOfMessageEscalationHint = buildIdenticalErrorEscalationHint(error);
                logger.info("Continuing repair with identical-error escalation hint: {}", error);
                diagnostics.add("Continuing repair: identical-error escalation hint injected");
                return false;
            }
            logger.info("Aborting repair: equivalent error on consecutive attempts: {}", error);
            diagnostics.add("Skipped repair: identical error repeated");
            return true;
        }
        // Escalation: if every access violation in the current error has already
        // been reported in a previous attempt, the LLM is stuck retrying the same
        // inaccessible members — stop the loop.
        if (hasOnlyRecurrentAccessViolations(error)) {
            logger.info("Aborting repair: all access violations are recurrent: {}", error);
            diagnostics.add("Skipped repair: recurrent access violations");
            return true;
        }
        return false;
    }

    /**
     * Returns true when the error contains access-violation diagnostics and
     * every single one has already been seen in a previous repair attempt.
     * This detects the pattern where the LLM keeps retrying the same
     * inaccessible members despite being told they are off-limits.
     */
    private boolean hasOnlyRecurrentAccessViolations(String error) {
        if (!AccessViolationDiagnosticParser.containsAccessViolation(error)) {
            return false;
        }
        Set<String> currentKeys = AccessViolationDiagnosticParser.extractTrackingKeys(error);
        if (currentKeys.isEmpty()) {
            return false;
        }
        // All current violations must have been seen before
        return seenAccessViolations.containsAll(currentKeys);
    }

    /**
     * Builds the one-shot top-of-message escalation hint sent on the bonus
     * turn after the LLM produced an identical normalized error. The text is
     * intentionally generic: it tells the LLM that the previous repair did not
     * change the failure, so the previous response was effectively the same
     * source, and that on this turn it must either change the assertThrows
     * expectation to whatever was actually observed or drop the offending
     * tests entirely.
     */
    static String buildIdenticalErrorEscalationHint(String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("STOP: identical failure repeated.\n");
        sb.append("Your previous repair produced the same normalized error as the turn before.\n");
        sb.append("That means the test source for the failing tests is effectively unchanged.\n");
        sb.append("Do NOT submit another response that tweaks the same tests in the same shape.\n");
        sb.append("On this turn you MUST do one of the following for every failing test:\n");
        sb.append("  1) Change the assertThrows expectation to the EXACT exception class observed\n");
        sb.append("     at runtime (look at \"actual=...\" in the failure list above).\n");
        sb.append("  2) Replace the test with a different one that does NOT reach the failing call\n");
        sb.append("     (e.g. exercise a different SUT method, constructor, or pure-logic path).\n");
        sb.append("  3) Remove the test from the class entirely.\n");
        if (errorMentionsShadedRuntimeMockException(error)) {
            sb.append("Note: the failure list mentions an exception under shaded.org.evosuite.runtime.mock.*.\n");
            sb.append("That is the EvoSuite mock harness, not the SUT, and it is unavoidable in this\n");
            sb.append("environment for any test reaching that call site. Treat it as ground truth.\n");
        }
        sb.append("Returning the same tests with cosmetic changes (renamed locals, reordered imports)\n");
        sb.append("will be rejected as no progress.");
        return sb.toString();
    }

    /**
     * Normalizes an error string for fuzzy comparison by replacing line numbers
     * with a placeholder, so that the same logical error at different positions
     * is recognized as a stuck loop.
     */
    static String normalizeError(String error) {
        if (error == null) {
            return "";
        }
        return LINE_NUMBER_PATTERN.matcher(error).replaceAll("N");
    }

    /**
     * Returns the LLM's repair response, or {@code null} if the repair loop
     * should stop. Stop conditions: the attempt budget is exhausted, the error
     * is unfixable / a repeat, or the repair request itself failed.
     */
    private String tryRepair(String errorText,
                             int attempt,
                             String previousError,
                             List<String> diagnostics,
                             List<LlmMessage> conversation,
                             String currentResponse,
                             LlmFeature feature,
                             List<String> expandedClasses,
                             Map<ParseResult, ExecutionFailureContext> executionFailures,
                             long repairDeadlineNanos) {
        if (attempt == maxAttempts || shouldSkipRepair(errorText, previousError, diagnostics)) {
            return null;
        }
        if (!hasTimeForAnotherRepairRequest(repairDeadlineNanos)) {
            diagnostics.add("Skipped repair: sync deadline too close; returning partial/salvaged tests if available");
            return null;
        }
        return requestRepairSafely(conversation, currentResponse, errorText,
                feature, expandedClasses, diagnostics, attempt + 2,
                executionFailures, repairDeadlineNanos);
    }

    private String tryStrictContractRepair(String errorText,
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
            diagnostics.add("Skipped strict contract repair: sync deadline too close; returning partial/salvaged tests if available");
            return null;
        }
        return requestRepairSafely(conversation, currentResponse, errorText,
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

    private String requestRepairSafely(List<LlmMessage> conversation,
                                       String previousResponse,
                                       String error,
                                       LlmFeature feature,
                                       List<String> expandedClasses,
                                       List<String> diagnostics,
                                       int repairAttempt,
                                       Map<ParseResult, ExecutionFailureContext> executionFailures,
                                       long repairDeadlineNanos) {
        try {
            return requestRepair(conversation, previousResponse, error, feature,
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
                                + "`; keep the raw failing line visible in the prompt instead of collapsing it into generic `llm_feedback`.");
                        String snippet = diagnostic.getSourceSnippet();
                        if (snippet != null && !snippet.trim().isEmpty()) {
                            hints.add("Source expression: " + snippet.trim());
                        }
                        String closest = findClosestAccessibleApiSuggestion(missingMethodName);
                        if (closest != null && !closest.isEmpty()) {
                            hints.add("Closest available API: " + closest);
                        }
                    } else {
                        hints.add("Replace invented method calls with real APIs from the supplied context; keep the offending line visible in the repair prompt.");
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
     * Derives the SUT's package name from {@link Properties#TARGET_CLASS}
     * so that the generated test class resides in the same package, enabling
     * access to package-private members.
     */
    private static String getSutPackage() {
        String target = Properties.TARGET_CLASS;
        if (target == null || target.isEmpty()) {
            return null;
        }
        int lastDot = target.lastIndexOf('.');
        return lastDot > 0 ? target.substring(0, lastDot) : null;
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
        return false;
    }

    private String formatThrowable(Throwable throwable) {
        String message = throwable.getMessage() == null ? "" : throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message.isEmpty() ? "" : (": " + message));
    }

    private String formatThrowableWithStackExcerpt(Throwable throwable,
                                                  ParseResult parseResult,
                                                  Integer failingPosition) {
        if (throwable == null) {
            return "UnknownException";
        }
        Throwable rootCause = unwrapDiagnosticCause(throwable);
        StringBuilder message = new StringBuilder();
        message.append(throwable.getClass().getName())
                .append(" - ")
                .append(throwable.getMessage() == null ? "" : throwable.getMessage());
        if (rootCause != null && rootCause != throwable) {
            message.append("\nRoot cause: ")
                    .append(rootCause.getClass().getName())
                    .append(" - ")
                    .append(rootCause.getMessage() == null ? "" : rootCause.getMessage());
        }
        appendSnippetCompilationDetails(message, throwable, rootCause);
        message.append(buildFailureStackExcerpt(rootCause != null ? rootCause : throwable,
                parseResult, failingPosition));
        return message.toString();
    }

    private void appendSnippetCompilationDetails(StringBuilder message,
                                                 Throwable throwable,
                                                 Throwable rootCause) {
        if (!isSnippetCompilationThrowable(throwable) && !isSnippetCompilationThrowable(rootCause)) {
            return;
        }

        String wrapperMessage = throwable == null ? null : throwable.getMessage();
        String causeMessage = rootCause == null ? null : rootCause.getMessage();
        String compilerDiagnostics = extractSnippetCompilerDiagnostics(wrapperMessage, causeMessage);

        message.append("\nSnippet compilation note:");
        if (compilerDiagnostics != null && !compilerDiagnostics.isEmpty()) {
            message.append("\n- javac diagnostics:\n")
                    .append(compilerDiagnostics);
        } else {
            message.append("\n- No javac diagnostics were captured; only the snippet compilation wrapper message was available.");
        }
        message.append("\n- This came from EvoSuite-rendered synthesized code after parsing, not directly from raw LLM text.");
    }

    private boolean isSnippetCompilationThrowable(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        String className = throwable.getClass().getName();
        if (className.endsWith("ExecutableSnippetEngine$SnippetCompilationException")
                || className.contains("ExecutableSnippetEngine$SnippetCompilationException")) {
            return true;
        }
        String message = throwable.getMessage();
        return message != null
                && (message.contains("SnippetCompilationException")
                || message.contains("Snippet compilation failed")
                || message.contains("Snippet compilation interrupted"));
    }

    private String extractSnippetCompilerDiagnostics(String wrapperMessage, String causeMessage) {
        String[] candidates = new String[]{wrapperMessage, causeMessage};
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            int idx = candidate.indexOf("Snippet compilation failed for ");
            if (idx >= 0) {
                int colon = candidate.indexOf(':', idx);
                if (colon >= 0 && colon + 1 < candidate.length()) {
                    return candidate.substring(colon + 1).trim();
                }
            }
            if (candidate.contains("Could not compile snippet") || candidate.contains("Snippet compilation interrupted")) {
                return null;
            }
            if (candidate.contains("cannot find symbol")
                    || candidate.contains("illegal start of type")
                    || candidate.contains("error:")
                    || candidate.contains("cannot be applied to given types")
                    || candidate.contains("has protected access")
                    || candidate.contains("not public in")
                    || candidate.contains("incompatible types")) {
                return candidate.trim();
            }
        }
        return null;
    }

    private Throwable unwrapDiagnosticCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        int depth = 0;
        while (current.getCause() != null && depth < 12 && isDiagnosticWrapper(current)) {
            current = current.getCause();
            depth++;
        }
        return current;
    }

    private boolean isDiagnosticWrapper(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof java.lang.reflect.InvocationTargetException
                || throwable instanceof java.util.concurrent.ExecutionException) {
            return true;
        }
        String className = throwable.getClass().getName();
        return className.endsWith(".CodeUnderTestException")
                || className.contains(".CodeUnderTestException")
                || className.endsWith("UndeclaredThrowableException");
    }

    private String buildExecutionContext(ParseResult parseResult,
                                         Integer failingPosition,
                                         Throwable thrown) {
        if (parseResult == null || parseResult.getTestCase() == null) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        String statementCode = resolveDiagnosticStatementCode(parseResult, failingPosition, thrown);
        if (statementCode != null && !statementCode.isEmpty()) {
            context.append("\n")
                    .append(thrown instanceof TestCaseExecutor.TimeoutExceeded
                            ? "Timed out statement"
                            : "Failing statement");
            if (failingPosition != null) {
                context.append(" (index ").append(failingPosition).append(", zero-based)");
            }
            context.append(":\n").append(statementCode);
            String receiverNote = buildFailingStatementReceiverNote(parseResult, failingPosition, statementCode);
            if (receiverNote != null && !receiverNote.isEmpty()) {
                context.append(receiverNote);
            }
        }
        Integer laterAssertThrowsPosition = findLaterAssertThrowsStatementPosition(parseResult, failingPosition);
        if (laterAssertThrowsPosition != null) {
            context.append("\nAssertion reachability note: a later assertThrows(...) statement exists at index ")
                    .append(laterAssertThrowsPosition)
                    .append(" (zero-based), but execution failed earlier at index ")
                    .append(failingPosition)
                    .append(" (zero-based), so that assertion was never reached.");
        }
        String fallbackResolutionNote = buildFallbackResolutionNote(parseResult, failingPosition);
        if (fallbackResolutionNote != null && !fallbackResolutionNote.isEmpty()) {
            context.append(fallbackResolutionNote);
        }

        try {
            String testCode = renderIndexedTestCaseExcerpt(parseResult);
            if (testCode != null && !testCode.trim().isEmpty()) {
                context.append("\nParsed test code excerpt:\n```java\n")
                        .append(truncate(testCode.trim(), MAX_TEST_CODE_EXCERPT_CHARS))
                        .append("\n```");
            }
        } catch (Throwable ignored) {
            // best-effort diagnostic enrichment only
        }
        return context.toString();
    }

    private String buildFailingStatementReceiverNote(ParseResult parseResult,
                                                     Integer failingPosition,
                                                     String statementCode) {
        if (!isValidStatementPosition(parseResult, failingPosition)
                || statementCode == null
                || statementCode.trim().isEmpty()) {
            return "";
        }

        String receiverName = extractTopLevelReceiverVariableName(statementCode);
        if (receiverName == null || receiverName.isEmpty()) {
            return "";
        }

        String priorStatements = renderIndexedStatementRange(parseResult, 0, failingPosition);
        if (priorStatements.isEmpty()) {
            return "";
        }

        if (Pattern.compile("\\b" + Pattern.quote(receiverName) + "\\b").matcher(priorStatements).find()) {
            return "";
        }

        StringBuilder note = new StringBuilder();
        note.append("\nReceiver setup note: the receiver variable '")
                .append(receiverName)
                .append("' used by the failing statement has no visible earlier initialization in the parsed test excerpt.");

        String fallbackType = extractTypedFallbackDeclaredType(priorStatements);
        if (fallbackType != null && !fallbackType.isEmpty()) {
            note.append("\n- Earlier parsed statements contain parser-generated placeholder '")
                    .append(fallbackType)
                    .append(" __llm_fallback... = null;'. The missing receiver setup likely came from aliasing that null fallback.");
            note.append("\n- Replace the fallback-based collaborator chain with a real ")
                    .append(simpleName(fallbackType))
                    .append(" value before invoking or mutating it.");
        }
        return note.toString();
    }

    private String buildFallbackResolutionNote(ParseResult parseResult, Integer failingPosition) {
        if (parseResult == null || parseResult.getDiagnostics().isEmpty()) {
            return "";
        }
        int endExclusive = failingPosition == null
                ? parseResult.getTestCase().size()
                : Math.max(0, Math.min(parseResult.getTestCase().size(), failingPosition + 1));
        String relevantStatements = renderIndexedStatementRange(parseResult, 0, endExclusive);
        if (relevantStatements.isEmpty()) {
            return "";
        }

        boolean explicitFallbackMarker = relevantStatements.contains("__llm_fallback");
        String fallbackType = resolveFallbackTypeForRepairNote(parseResult, relevantStatements);
        List<ParseDiagnostic> relevantDiagnostics = findRelevantFallbackResolutionDiagnostics(parseResult, fallbackType);
        if (relevantDiagnostics.isEmpty()) {
            return "";
        }

        boolean constructorDiagnostic = false;
        boolean methodDiagnostic = false;
        String missingMethodName = null;
        InstantiationGuidance fallbackInstantiationGuidance = resolveInstantiationGuidance(fallbackType);
        LinkedHashSet<String> explicitRepairActions = new LinkedHashSet<>();
        LinkedHashSet<String> inventedHelperTypes = new LinkedHashSet<>();
        StringBuilder note = new StringBuilder();
        note.append("\nFallback origin note:");
        if (fallbackType != null && !fallbackType.isEmpty()) {
            if (explicitFallbackMarker) {
                note.append("\n- The earlier '")
                        .append(simpleName(fallbackType))
                        .append(" __llm_fallback... = null;' placeholder came from an unresolved constructor/method expression, not intended test logic.");
            } else {
                note.append("\n- The earlier typed null declaration for '")
                        .append(simpleName(fallbackType))
                        .append("' is parser-generated fallback state from an unresolved constructor/method expression, not intended test logic.");
            }
        }
        for (ParseDiagnostic diagnostic : relevantDiagnostics) {
            if (diagnostic == null || diagnostic.getMessage() == null || diagnostic.getMessage().trim().isEmpty()) {
                continue;
            }
            String message = diagnostic.getMessage().trim();
            String lower = message.toLowerCase();
            if (lower.contains("constructor")) {
                constructorDiagnostic = true;
            }
            if (lower.contains("method")) {
                methodDiagnostic = true;
            }
            String explicitRepairAction = extractExplicitRepairAction(message);
            if (explicitRepairAction != null && !explicitRepairAction.isEmpty()) {
                explicitRepairActions.add(explicitRepairAction);
            }
            if (missingMethodName == null) {
                missingMethodName = extractMissingMethodName(message);
            }
            note.append("\n- Parser detail: ")
                    .append(truncate(message, MAX_FALLBACK_DIAGNOSTIC_CHARS).replace('\n', ' '));
            String sourceSnippet = diagnostic.getSourceSnippet();
            if (sourceSnippet != null && !sourceSnippet.trim().isEmpty()) {
                note.append("\n- Source expression: ")
                        .append(truncate(sourceSnippet.trim(), MAX_FALLBACK_DIAGNOSTIC_CHARS).replace('\n', ' '));
                String inventedHelperType = extractInventedHelperTypeForFallback(diagnostic, fallbackType);
                if (inventedHelperType != null && !inventedHelperType.isEmpty()) {
                    inventedHelperTypes.add(inventedHelperType);
                }
            }
        }
        for (String explicitRepairAction : explicitRepairActions) {
            note.append("\n- Repair action: ").append(explicitRepairAction);
        }
        for (String inventedHelperType : inventedHelperTypes) {
            note.append("\n- The unresolved helper/local type inside that source expression is '")
                    .append(simpleName(inventedHelperType))
                    .append("'. Replace that invented helper with a real SUT/JDK/dependency value or rewrite the test to avoid it; do not only rename or re-alias the __llm_fallback placeholder.");
        }
        if (constructorDiagnostic) {
            if (fallbackInstantiationGuidance != null && fallbackInstantiationGuidance.isNonInstantiable()) {
                note.append("\n- ")
                        .append(buildNonInstantiableConstructionHint(fallbackType, fallbackInstantiationGuidance));
            } else {
                note.append("\n- Use one of the listed existing constructors exactly; match owner/package types exactly and include required trailing parameters.");
            }
        } else if (methodDiagnostic) {
            note.append("\n- Use one of the listed existing methods/overloads exactly; keep receiver and argument types aligned with the resolved signature.");
            String closest = findClosestAccessibleApiSuggestion(missingMethodName);
            if (closest != null && !closest.isEmpty()) {
                note.append("\n- Closest available API: ").append(closest);
            }
        }
        return note.toString();
    }

    private String extractMissingMethodName(String diagnosticMessage) {
        if (diagnosticMessage == null || diagnosticMessage.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = MISSING_METHOD_DIAGNOSTIC_PATTERN.matcher(diagnosticMessage);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private String findClosestAccessibleApiSuggestion(String missingMethodName) {
        if (missingMethodName == null || missingMethodName.isEmpty()
                || sutContextSummary == null || sutContextSummary.isEmpty()) {
            return null;
        }

        java.util.regex.Matcher matcher = METHOD_SIGNATURE_PATTERN.matcher(sutContextSummary);
        String bestSignature = null;
        String bestMethodName = null;
        int bestScore = 0;
        while (matcher.find()) {
            String candidateMethodName = matcher.group(1);
            if (candidateMethodName == null || candidateMethodName.isEmpty()
                    || candidateMethodName.equals(missingMethodName)) {
                continue;
            }
            int score = scoreMethodSimilarity(missingMethodName, candidateMethodName);
            if (score > bestScore) {
                bestScore = score;
                bestMethodName = candidateMethodName;
                bestSignature = candidateMethodName + "(" + matcher.group(2).trim() + ")";
            }
        }

        if (bestSignature == null || bestScore < 2) {
            return null;
        }
        if (bestMethodName == null) {
            return null;
        }
        return bestSignature;
    }

    private int scoreMethodSimilarity(String missingMethodName, String candidateMethodName) {
        if (missingMethodName == null || candidateMethodName == null
                || missingMethodName.isEmpty() || candidateMethodName.isEmpty()) {
            return 0;
        }
        String missingVerb = methodVerbPrefix(missingMethodName);
        String candidateVerb = methodVerbPrefix(candidateMethodName);
        String missingStem = stripMethodVerbPrefix(missingMethodName);
        String candidateStem = stripMethodVerbPrefix(candidateMethodName);
        Set<String> missingTokens = splitCamelCaseTokens(missingStem);
        Set<String> candidateTokens = splitCamelCaseTokens(candidateStem);
        missingTokens.retainAll(candidateTokens);
        int score = missingTokens.size();
        if (score == 0) {
            return 0;
        }
        if (!missingVerb.isEmpty()) {
            if (missingVerb.equals(candidateVerb)) {
                score += 5;
            } else {
                score -= 2;
            }
        }
        if (candidateTokens.containsAll(missingTokens)) {
            score += 1;
        }
        return score;
    }

    private String methodVerbPrefix(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return "";
        }
        if (methodName.startsWith("set")) {
            return "set";
        }
        if (methodName.startsWith("get")) {
            return "get";
        }
        if (methodName.startsWith("is")) {
            return "is";
        }
        return "";
    }

    private String stripMethodVerbPrefix(String methodName) {
        if (methodName == null || methodName.length() <= 3) {
            return methodName == null ? "" : methodName;
        }
        if (methodName.startsWith("set") || methodName.startsWith("get")) {
            return methodName.substring(3);
        }
        if (methodName.startsWith("is")) {
            return methodName.substring(2);
        }
        return methodName;
    }

    private Set<String> splitCamelCaseTokens(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (i > 0 && Character.isUpperCase(ch) && current.length() > 0) {
                tokens.add(current.toString().toLowerCase());
                current.setLength(0);
            }
            if (Character.isLetterOrDigit(ch)) {
                current.append(ch);
            } else if (current.length() > 0) {
                tokens.add(current.toString().toLowerCase());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString().toLowerCase());
        }
        return tokens;
    }

    private String extractExplicitRepairAction(String diagnosticMessage) {
        if (diagnosticMessage == null || diagnosticMessage.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = EXPLICIT_REPAIR_ACTION_PATTERN.matcher(diagnosticMessage);
        if (!matcher.find()) {
            return null;
        }
        String action = matcher.group(1);
        if (action == null) {
            return null;
        }
        return action.trim();
    }

    private String extractInventedHelperTypeForFallback(ParseDiagnostic diagnostic, String fallbackType) {
        if (diagnostic == null) {
            return null;
        }
        String sourceSnippet = diagnostic.getSourceSnippet();
        if (sourceSnippet == null || sourceSnippet.isEmpty()) {
            return null;
        }
        String diagnosticMessage = diagnostic.getMessage();
        String diagnosticLower = diagnosticMessage == null ? "" : diagnosticMessage.toLowerCase();
        if (!diagnosticLower.contains("invented/unknown type")
                && !diagnosticLower.contains("do not invent local/helper types")
                && !isUnresolvedTypeDiagnostic(diagnostic)) {
            return null;
        }

        if (diagnosticMessage != null) {
            java.util.regex.Matcher explicitTypeMatcher = INVENTED_UNKNOWN_TYPE_PATTERN.matcher(diagnosticMessage);
            if (explicitTypeMatcher.find()) {
                return stripTypeDecorators(explicitTypeMatcher.group(1));
            }
        }

        String fallbackSimple = simpleName(fallbackType);
        java.util.regex.Matcher matcher = NEW_CALL_PATTERN.matcher(sourceSnippet);
        String candidate = null;
        while (matcher.find()) {
            String constructedType = stripTypeDecorators(matcher.group(1));
            if (constructedType == null || constructedType.isEmpty()) {
                continue;
            }
            if (!fallbackSimple.isEmpty() && fallbackSimple.equals(simpleName(constructedType))) {
                continue;
            }
            candidate = constructedType;
        }
        return candidate;
    }

    private String resolveFallbackTypeForRepairNote(ParseResult parseResult, String relevantStatements) {
        String explicitFallbackType = extractTypedFallbackDeclaredType(relevantStatements);
        if (explicitFallbackType != null && !explicitFallbackType.isEmpty()) {
            return explicitFallbackType;
        }

        List<ParseDiagnostic> resolutionDiagnostics = findRelevantFallbackResolutionDiagnostics(parseResult, null);
        if (resolutionDiagnostics.size() == 1) {
            String diagnosticType = extractFallbackTypeFromDiagnostic(resolutionDiagnostics.get(0));
            if (diagnosticType != null && !diagnosticType.isEmpty()) {
                return diagnosticType;
            }
        }

        for (String candidateType : extractTypedNullDeclarationTypes(relevantStatements)) {
            if (!findRelevantFallbackResolutionDiagnostics(parseResult, candidateType).isEmpty()) {
                return candidateType;
            }
        }
        return null;
    }

    private String buildNonInstantiableConstructionHint(String fallbackType,
                                                        InstantiationGuidance guidance) {
        String simple = simpleName(fallbackType);
        String expression = "new " + simple + "()";
        if ("interface".equals(guidance.kind)) {
            if (!guidance.concreteSubtypes.isEmpty()) {
                return "The type '" + simple + "' is an interface, so `" + expression
                        + "` is invalid. Prefer a concrete implementation such as "
                        + joinTop(guidance.concreteSubtypes, 3)
                        + ", or use Mockito.mock(" + simple + ".class).";
            }
            return "The type '" + simple + "' is an interface, so `" + expression
                    + "` is invalid. Replace it with Mockito.mock(" + simple
                    + ".class) or a known concrete implementation.";
        }
        if (!guidance.concreteSubtypes.isEmpty()) {
            return "The type '" + simple + "' is abstract, so `" + expression
                    + "` is invalid. Prefer a concrete subtype such as "
                    + joinTop(guidance.concreteSubtypes, 3)
                    + ", or use Mockito.mock(" + simple + ".class).";
        }
        return "The type '" + simple + "' is abstract, so `" + expression
                + "` is invalid. Replace it with a concrete subtype or Mockito.mock("
                + simple + ".class).";
    }

    private InstantiationGuidance resolveInstantiationGuidance(String fallbackType) {
        String simple = simpleName(fallbackType);
        if (simple.isEmpty()) {
            return null;
        }

        ContextTypeIndex context = ContextTypeIndex.fromSummary(sutContextSummary);
        if (!context.isEmpty() && context.isNonInstantiable(simple)) {
            return new InstantiationGuidance(context.getKind(simple), context.getConcreteSubtypes(simple));
        }

        Class<?> resolved = tryResolveRepairType(fallbackType);
        if (resolved == null) {
            return null;
        }
        if (resolved.isInterface()) {
            return new InstantiationGuidance("interface", Collections.<String>emptyList());
        }
        if (Modifier.isAbstract(resolved.getModifiers())) {
            return new InstantiationGuidance("abstract", Collections.<String>emptyList());
        }
        return null;
    }

    private Class<?> tryResolveRepairType(String typeName) {
        String clean = stripTypeDecorators(typeName);
        if (clean.isEmpty()) {
            return null;
        }
        LinkedHashSet<ClassLoader> loaders = new LinkedHashSet<>();
        if (testParser != null && testParser.getClassLoader() != null) {
            loaders.add(testParser.getClassLoader());
        }
        ClassLoader threadLoader = Thread.currentThread().getContextClassLoader();
        if (threadLoader != null) {
            loaders.add(threadLoader);
        }
        ClassLoader localLoader = TestRepairLoop.class.getClassLoader();
        if (localLoader != null) {
            loaders.add(localLoader);
        }
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(clean, false, loader);
            } catch (ClassNotFoundException ignored) {
                // Try next loader.
            } catch (LinkageError ignored) {
                // Best-effort only.
            }
        }
        return null;
    }

    private List<ParseDiagnostic> findRelevantFallbackResolutionDiagnostics(ParseResult parseResult, String fallbackType) {
        if (parseResult == null || parseResult.getDiagnostics().isEmpty()) {
            return Collections.emptyList();
        }

        List<ParseDiagnostic> matchingType = new ArrayList<>();
        List<ParseDiagnostic> resolutionDiagnostics = new ArrayList<>();
        for (ParseDiagnostic diagnostic : parseResult.getDiagnostics()) {
            if (diagnostic == null || diagnostic.getMessage() == null) {
                continue;
            }
            if (!isFallbackResolutionDiagnostic(diagnostic)) {
                continue;
            }
            resolutionDiagnostics.add(diagnostic);
            if (matchesFallbackDiagnosticType(diagnostic, fallbackType)) {
                matchingType.add(diagnostic);
            }
        }

        List<ParseDiagnostic> selected = !matchingType.isEmpty()
                ? matchingType
                : (resolutionDiagnostics.size() == 1 ? resolutionDiagnostics : Collections.emptyList());
        if (selected.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(selected.subList(0, Math.min(MAX_FALLBACK_DIAGNOSTICS, selected.size())));
    }

    private static final class InstantiationGuidance {
        private final String kind;
        private final List<String> concreteSubtypes;

        private InstantiationGuidance(String kind, List<String> concreteSubtypes) {
            this.kind = kind;
            this.concreteSubtypes = concreteSubtypes == null
                    ? Collections.<String>emptyList()
                    : concreteSubtypes;
        }

        private boolean isNonInstantiable() {
            return "interface".equals(kind) || "abstract".equals(kind);
        }
    }

    private boolean isFallbackResolutionDiagnostic(ParseDiagnostic diagnostic) {
        if (diagnostic == null || diagnostic.getMessage() == null || diagnostic.getMessage().isEmpty()) {
            return false;
        }
        String lower = diagnostic.getMessage().toLowerCase();
        return lower.startsWith("no matching constructor:")
                || lower.startsWith("no matching method:")
                || lower.startsWith("no method named ")
                || lower.startsWith("no static method named ")
                || lower.startsWith("method argument mismatch:")
                || (lower.contains("llm_repair_action_required:") && isUnresolvedTypeDiagnostic(diagnostic));
    }

    private boolean isUnresolvedTypeDiagnostic(ParseDiagnostic diagnostic) {
        if (diagnostic == null) {
            return false;
        }
        if ("UNRESOLVED_TYPE".equals(diagnostic.getKindName())) {
            return true;
        }
        String message = diagnostic.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("cannot resolve class") || lower.contains("cannot resolve type");
    }

    private boolean matchesFallbackDiagnosticType(ParseDiagnostic diagnostic, String fallbackType) {
        if (diagnostic == null || fallbackType == null || fallbackType.isEmpty()) {
            return false;
        }
        String fullType = fallbackType.toLowerCase();
        String simpleType = simpleName(fallbackType).toLowerCase();
        String message = diagnostic.getMessage() == null ? "" : diagnostic.getMessage().toLowerCase();
        String sourceSnippet = diagnostic.getSourceSnippet() == null ? "" : diagnostic.getSourceSnippet().toLowerCase();
        return message.contains(fullType)
                || sourceSnippet.contains(fullType)
                || (!simpleType.isEmpty() && (message.contains(simpleType) || sourceSnippet.contains(simpleType)));
    }

    private String renderIndexedTestCaseExcerpt(ParseResult parseResult) {
        if (parseResult == null || parseResult.getTestCase() == null) {
            return "";
        }
        return renderIndexedStatementRange(parseResult, 0, parseResult.getTestCase().size());
    }

    private String renderIndexedStatementRange(ParseResult parseResult, int startInclusive, int endExclusive) {
        if (parseResult == null || parseResult.getTestCase() == null) {
            return "";
        }
        Map<Integer, String> renderedBlocks = renderIndexedStatementBlocks(parseResult);
        StringBuilder excerpt = new StringBuilder();
        int from = Math.max(0, startInclusive);
        int to = Math.min(parseResult.getTestCase().size(), Math.max(from, endExclusive));
        for (int i = from; i < to; i++) {
            String statementCode = renderedBlocks.get(i);
            if ((statementCode == null || statementCode.trim().isEmpty())) {
                statementCode = renderStatementRangeFallback(parseResult, i);
            }
            if (statementCode == null || statementCode.trim().isEmpty()) {
                continue;
            }
            if (excerpt.length() > 0) {
                excerpt.append("\n");
            }
            excerpt.append("// [").append(i).append("]\n")
                    .append(statementCode.trim());
        }
        return excerpt.toString();
    }

    private Map<Integer, String> renderIndexedStatementBlocks(ParseResult parseResult) {
        Map<Integer, String> blocks = new LinkedHashMap<>();
        String rendered = renderAnnotatedIndexedTestCase(parseResult);
        if (rendered == null || rendered.trim().isEmpty()) {
            return blocks;
        }

        List<String> pendingLines = new ArrayList<>();
        Integer currentIndex = null;
        List<String> currentLines = new ArrayList<>();
        for (String line : rendered.split("\\R", -1)) {
            java.util.regex.Matcher markerMatcher = EXCERPT_INDEX_MARKER_PATTERN.matcher(line);
            if (markerMatcher.matches()) {
                if (currentIndex != null) {
                    blocks.put(currentIndex, trimBoundaryBlankLines(currentLines));
                }
                currentIndex = Integer.parseInt(markerMatcher.group(1));
                currentLines = new ArrayList<>(pendingLines);
                pendingLines.clear();
                continue;
            }
            if (currentIndex == null) {
                pendingLines.add(line);
            } else {
                currentLines.add(line);
            }
        }
        if (currentIndex != null) {
            blocks.put(currentIndex, trimBoundaryBlankLines(currentLines));
        }
        return blocks;
    }

    private String renderAnnotatedIndexedTestCase(ParseResult parseResult) {
        if (parseResult == null || parseResult.getTestCase() == null) {
            return "";
        }
        try {
            org.evosuite.testcase.TestCase cloned = parseResult.getTestCase().clone();
            for (int i = 0; i < cloned.size(); i++) {
                org.evosuite.testcase.statements.Statement statement = cloned.getStatement(i);
                String markerComment = statement.getComment().isEmpty()
                        ? EXCERPT_INDEX_MARKER_PREFIX + i
                        : "\n" + EXCERPT_INDEX_MARKER_PREFIX + i;
                statement.addComment(markerComment);
            }
            org.evosuite.testcase.TestCodeVisitor visitor = new org.evosuite.testcase.TestCodeVisitor();
            cloned.accept(visitor);
            return visitor.getCode();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String renderStatementRangeFallback(ParseResult parseResult, int index) {
        try {
            return parseResult.getTestCase().getStatement(index).getCode();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String checkRenderedCompilation(ParseResult parseResult) {
        if (parseResult == null || parseResult.getTestCase() == null) {
            return "Compilation error in parsed test '<unnamed>': missing parsed test case";
        }
        ensureParseCompileThreadPrivileged();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return "Compilation error in parsed test '" + safeMethodName(parseResult)
                    + "': No Java compiler available in current runtime";
        }

        org.evosuite.testcase.TestCodeVisitor visitor = new org.evosuite.testcase.TestCodeVisitor();
        try {
            parseResult.getTestCase().accept(visitor);
        } catch (Throwable t) {
            return "Compilation error in parsed test '" + safeMethodName(parseResult)
                    + "': failed to render parsed test code: " + formatThrowable(t);
        }
        String methodBody = visitor.getCode() == null ? "" : visitor.getCode();
        String source = buildCompilationProbeSource(parseResult, methodBody, visitor.getImports());

        Path tmpDir = null;
        try {
            final String method = safeMethodName(parseResult);
            final String finalSource = source;
            Future<String> future = PARSE_COMPILE_EXECUTOR.submit(() -> runRenderedCompilationCheck(method, finalSource));
            return future.get();
        } catch (SecurityException securityException) {
            return "Compilation error in parsed test '" + safeMethodName(parseResult)
                    + "': parse-phase compile check failed due to sandbox/security restrictions: "
                    + securityException.getMessage()
                    + "\nNote: parse-phase compile checks are required; this test cannot be accepted when the check is unavailable.";
        } catch (Throwable t) {
            if (isSecurityInfrastructureError(t)) {
                return "Compilation error in parsed test '" + safeMethodName(parseResult)
                        + "': parse-phase compile check failed due to security infrastructure error: "
                        + t.toString()
                        + "\nNote: parse-phase compile checks are required; this test cannot be accepted when the check is unavailable.";
            }
            return "Compilation error in parsed test '" + safeMethodName(parseResult)
                    + "': " + formatThrowable(t)
                    + "\nNote: this error comes from EvoSuite-rendered synthesized test source (after parsing), not directly from raw LLM text."
                    + "\nRendered compile-check class excerpt:\n```java\n"
                    + truncate(source, MAX_TEST_CODE_EXCERPT_CHARS)
                    + "\n```"
                    + "\nParsed test code excerpt:\n```java\n"
                    + truncate(methodBody, MAX_TEST_CODE_EXCERPT_CHARS)
                    + "\n```";
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
        try {
            Future<Thread> future = PARSE_COMPILE_EXECUTOR.submit(Thread::currentThread);
            Thread worker = future.get();
            Sandbox.addPrivilegedThread(worker);
        } catch (SecurityException se) {
            // Caller is not privileged right now; another privileged caller may register later.
            logger.debug("Could not register parse-compile worker as privileged from thread '{}': {}",
                    Thread.currentThread().getName(), se.getMessage());
        } catch (Throwable t) {
            logger.debug("Could not bootstrap parse-compile worker privilege registration: {}", t.toString());
        }
    }

    private String runRenderedCompilationCheck(String methodName, String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return "Compilation error in parsed test '" + methodName
                    + "': No Java compiler available in current runtime";
        }
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("evosuite-llm-parse-compile-");
            String packageName = getSutPackage();
            String className = "__ParseCompileProbe_" + Math.abs(methodName.hashCode());
            Path pkgDir = tmpDir;
            if (packageName != null && !packageName.trim().isEmpty()) {
                pkgDir = tmpDir.resolve(packageName.replace('.', File.separatorChar));
                Files.createDirectories(pkgDir);
            }
            Path sourceFile = pkgDir.resolve(className + ".java");
            Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

            String classPath = System.getProperty("java.class.path", "");
            int maxPasses = 3;
            for (int pass = 0; pass <= maxPasses; pass++) {
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
                String repairedClasspath = org.evosuite.testcase.execution.ExecutableSnippetEngine.INSTANCE.sanitizeClasspathForKnownCompileIssues(classPath, diagnostics);
                if (repairedClasspath != null && !repairedClasspath.equals(classPath)) {
                    classPath = repairedClasspath;
                    repaired = true;
                }
                
                if (!repaired || pass == maxPasses) {
                    return "Compilation error in parsed test '" + methodName
                            + "': " + diagnostics
                            + "\nNote: this error comes from EvoSuite-rendered synthesized test source (after parsing), not directly from raw LLM text."
                            + "\nRendered compile-check class excerpt:\n```java\n"
                            + truncate(source, MAX_TEST_CODE_EXCERPT_CHARS)
                            + "\n```"
                            + "\n```";
                }
            }
            return "Compilation check failed"; // Fallback, shouldn't be reached
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

    private String buildCompilationProbeSource(ParseResult parseResult,
                                               String methodBody,
                                               Set<Class<?>> imports) {
        String packageName = getSutPackage();
        String className = "__ParseCompileProbe_" + Math.abs(safeMethodName(parseResult).hashCode());
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

    private String trimBoundaryBlankLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        int start = 0;
        while (start < lines.size() && lines.get(start).trim().isEmpty()) {
            start++;
        }
        int end = lines.size() - 1;
        while (end >= start && lines.get(end).trim().isEmpty()) {
            end--;
        }
        if (start > end) {
            return "";
        }
        return String.join("\n", lines.subList(start, end + 1));
    }

    private String extractTopLevelReceiverVariableName(String statementCode) {
        if (statementCode == null || statementCode.trim().isEmpty()) {
            return null;
        }
        try {
            String normalized = statementCode.trim();
            Statement parsed = StaticJavaParser.parseStatement(
                    normalized.endsWith(";") ? normalized : normalized + ";");
            if (!(parsed instanceof ExpressionStmt)) {
                return null;
            }
            Expression expression = ((ExpressionStmt) parsed).getExpression();
            if (!(expression instanceof MethodCallExpr)) {
                return null;
            }
            MethodCallExpr methodCallExpr = (MethodCallExpr) expression;
            if (!methodCallExpr.getScope().isPresent() || !(methodCallExpr.getScope().get() instanceof NameExpr)) {
                return null;
            }
            String receiverName = ((NameExpr) methodCallExpr.getScope().get()).getNameAsString();
            return isLikelyTypeReferenceName(receiverName) ? null : receiverName;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isLikelyTypeReferenceName(String name) {
        return name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private Integer resolveDiagnosticStatementPosition(ParseResult parseResult,
                                                       Integer reportedPosition,
                                                       Throwable thrown) {
        if (isValidStatementPosition(parseResult, reportedPosition)) {
            return reportedPosition;
        }
        if (thrown instanceof TestCaseExecutor.TimeoutExceeded) {
            Integer timeoutPosition = ((TestCaseExecutor.TimeoutExceeded) thrown).getStatementPosition();
            if (isValidStatementPosition(parseResult, timeoutPosition)) {
                return timeoutPosition;
            }
        }
        return null;
    }

    private String resolveDiagnosticStatementCode(ParseResult parseResult,
                                                  Integer statementPosition,
                                                  Throwable thrown) {
        if (thrown instanceof TestCaseExecutor.TimeoutExceeded) {
            String timeoutStatementCode = ((TestCaseExecutor.TimeoutExceeded) thrown).getStatementCode();
            if (timeoutStatementCode != null && !timeoutStatementCode.trim().isEmpty()) {
                return timeoutStatementCode.trim();
            }
        }
        if (isValidStatementPosition(parseResult, statementPosition)) {
            try {
                String statementCode = parseResult.getTestCase().getStatement(statementPosition).getCode();
                if (statementCode != null && !statementCode.trim().isEmpty()) {
                    return statementCode.trim();
                }
            } catch (Throwable ignored) {
                // best-effort diagnostic enrichment only
            }
        }
        return null;
    }

    private Integer findLaterAssertThrowsStatementPosition(ParseResult parseResult,
                                                           Integer failingPosition) {
        if (!isValidStatementPosition(parseResult, failingPosition)) {
            return null;
        }
        for (int i = failingPosition + 1; i < parseResult.getTestCase().size(); i++) {
            try {
                String code = parseResult.getTestCase().getStatement(i).getCode();
                if (code != null && code.contains("assertThrows(")) {
                    return i;
                }
            } catch (Throwable ignored) {
                // best-effort diagnostic enrichment only
            }
        }
        return null;
    }

    private boolean isValidStatementPosition(ParseResult parseResult, Integer position) {
        return parseResult != null
                && parseResult.getTestCase() != null
                && position != null
                && position >= 0
                && position < parseResult.getTestCase().size();
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars))
                + "\n// ... truncated (" + (text.length() - maxChars) + " chars omitted)";
    }

    private String buildFailureStackExcerpt(Throwable throwable,
                                           ParseResult parseResult,
                                           Integer failingPosition) {
        if (throwable == null) {
            return "";
        }
        StackTraceElement[] trace = throwable.getStackTrace();

        String targetClass = Properties.TARGET_CLASS;
        String targetPackage = getSutPackage();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        StackTraceElement targetFrame = null;
        boolean nonFrameworkFrameSelected = false;

        if (trace != null && trace.length > 0) {
            for (StackTraceElement frame : trace) {
                if (frame == null) {
                    continue;
                }
                String className = frame.getClassName();
                if (isTargetFrame(className, targetClass, targetPackage)) {
                    targetFrame = frame;
                    break;
                }
            }

            if (targetFrame != null) {
                selected.add(targetFrame.toString());
                nonFrameworkFrameSelected = true;
            }

            for (StackTraceElement frame : trace) {
                if (selected.size() >= MAX_STACK_EXCERPT_FRAMES) {
                    break;
                }
                if (frame == null) {
                    continue;
                }
                String className = frame.getClassName();
                if (isEvoSuiteStackFrame(className)) {
                    continue;
                }
                if (isJdkOrFrameworkFrame(className)) {
                    selected.add("[Dependency Stack] " + frame.toString());
                    nonFrameworkFrameSelected = true;
                    // Stop after capturing the first dependency cause
                    break;
                }
                selected.add(frame.toString());
                nonFrameworkFrameSelected = true;
            }

            if (selected.isEmpty()) {
                for (StackTraceElement frame : trace) {
                    if (frame == null) {
                        continue;
                    }
                    selected.add(frame.toString());
                    if (selected.size() >= 2) {
                        break;
                    }
                }
            }
        }

        String syntheticInvocationFrame = buildSyntheticInvocationFrame(parseResult, failingPosition);
        if (targetFrame == null && syntheticInvocationFrame != null && !syntheticInvocationFrame.isEmpty()) {
            LinkedHashSet<String> withSynthetic = new LinkedHashSet<>();
            withSynthetic.add(syntheticInvocationFrame);
            withSynthetic.addAll(selected);
            selected = withSynthetic;
        }

        if (selected.isEmpty()) {
            return "";
        }

        StringBuilder excerpt = new StringBuilder("\nFailure stack excerpt:");
        int count = 0;
        for (String frame : selected) {
            if (count >= MAX_STACK_EXCERPT_FRAMES) {
                break;
            }
            excerpt.append("\n- at ").append(frame);
            count++;
        }
        return excerpt.toString();
    }

    private String buildSyntheticInvocationFrame(ParseResult parseResult, Integer failingPosition) {
        if (!isValidStatementPosition(parseResult, failingPosition)) {
            return null;
        }
        org.evosuite.testcase.statements.Statement statement =
                parseResult.getTestCase().getStatement(failingPosition);
        if (statement == null) {
            return null;
        }

        org.evosuite.utils.generic.GenericAccessibleObject<?> accessibleObject = statement.getAccessibleObject();
        if (accessibleObject instanceof org.evosuite.utils.generic.GenericMethod) {
            org.evosuite.utils.generic.GenericMethod method =
                    (org.evosuite.utils.generic.GenericMethod) accessibleObject;
            String declaringClass = method.getDeclaringClass().getCanonicalName();
            if (declaringClass == null || declaringClass.isEmpty()) {
                declaringClass = method.getDeclaringClass().getName();
            }
            return declaringClass + "." + method.getName() + "(failing statement)";
        }

        String statementCode = resolveDiagnosticStatementCode(parseResult, failingPosition, null);
        String invocationFromCode = extractInvocationFromStatementCode(statementCode);
        if (invocationFromCode == null || invocationFromCode.isEmpty()) {
            return null;
        }
        return invocationFromCode + "(failing statement)";
    }

    private String extractInvocationFromStatementCode(String statementCode) {
        if (statementCode == null || statementCode.trim().isEmpty()) {
            return null;
        }
        try {
            String normalized = statementCode.trim();
            Statement parsed = StaticJavaParser.parseStatement(
                    normalized.endsWith(";") ? normalized : normalized + ";");
            if (!(parsed instanceof ExpressionStmt)) {
                return null;
            }
            Expression expression = ((ExpressionStmt) parsed).getExpression();
            if (!(expression instanceof MethodCallExpr)) {
                return null;
            }
            MethodCallExpr methodCallExpr = (MethodCallExpr) expression;
            if (!methodCallExpr.getScope().isPresent()) {
                return methodCallExpr.getNameAsString();
            }
            return methodCallExpr.getScope().get().toString() + "." + methodCallExpr.getNameAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isTargetFrame(String className, String targetClass, String targetPackage) {
        if (className == null) {
            return false;
        }
        if (targetClass != null && !targetClass.isEmpty()) {
            if (className.equals(targetClass) || className.startsWith(targetClass + "$")) {
                return true;
            }
        }
        return targetPackage != null
                && !targetPackage.isEmpty()
                && className.startsWith(targetPackage + ".");
    }

    private boolean isEvoSuiteStackFrame(String className) {
        return className.startsWith("org.evosuite.") || className.startsWith("shaded.org.evosuite.");
    }

    private boolean isJdkOrFrameworkFrame(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("org.junit.")
                || className.startsWith("org.mockito.");
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
