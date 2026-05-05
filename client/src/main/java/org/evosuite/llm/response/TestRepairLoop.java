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
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.evosuite.runtime.sandbox.Sandbox;

/**
 * Applies parse -> validate -> execute and iterative LLM repair.
 */
public class TestRepairLoop {

    private static final Logger logger = LoggerFactory.getLogger(TestRepairLoop.class);
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
            "(?m)^\\s*import\\s+(?:static\\s+)?[^;]*[/\\\\][^;]*;\\s*$");
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
    private boolean expansionAttempted;

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
        List<String> diagnostics = new ArrayList<>();
        List<String> expandedClasses = new ArrayList<>();
        Map<String, ParseResult> salvagedExecutableTests = new LinkedHashMap<>();
        String currentResponse = llmResponse;
        int attemptsUsed = 0;
        String previousError = null;

        // Accumulate the full conversation so repair requests include prior turns
        List<LlmMessage> conversation = new ArrayList<>();
        if (conversationHistory != null) {
            conversation.addAll(conversationHistory);
        }

        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            attemptsUsed = attempt + 1;
            List<ParseResult> parseResults;
            String extractedClass;
            try {
                String sutPackage = getSutPackage();
                LlmResponseParser.ExtractionResult extraction = responseParser.extractTestClassWithMetadata(
                        currentResponse, "GeneratedLlmTest", sutPackage);
                extractedClass = extraction.getSource();
                if (extraction.isRecoveryApplied()) {
                    diagnostics.add("Applied truncation recovery: " + extraction.getRecoveryReason());
                    validateRecoveredSource(extractedClass);
                }
                ReflectiveAssertThrowsRewriteResult rewriteResult =
                        rewriteReflectiveAssertThrowsAssertions(extractedClass);
                extractedClass = rewriteResult.source;
                if (rewriteResult.rewrites > 0) {
                    diagnostics.add("Normalized " + rewriteResult.rewrites
                            + " reflective assertThrows invocation(s) to unwrap InvocationTargetException");
                }
                parseResults = testParser.parseTestClass(extractedClass);
            } catch (Throwable parserFailure) {
                String parserFailureText = "Parser failure: " + formatThrowable(parserFailure);
                diagnostics.add(parserFailureText);
                String next = tryRepair(parserFailureText, attempt, previousError, diagnostics,
                        conversation, currentResponse, feature, expandedClasses);
                if (next == null) {
                    break;
                }
                previousError = parserFailureText;
                currentResponse = next;
                continue;
            }

            if (parseResults == null || parseResults.isEmpty()) {
                String parseErrorText = buildNoTestMethodsParseError(extractedClass);
                diagnostics.add(parseErrorText);
                String next = tryRepair(parseErrorText, attempt, previousError, diagnostics,
                        conversation, currentResponse, feature, expandedClasses);
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
            Map<ParseResult, String> executionErrorByTest = new LinkedHashMap<>();
            for (ParseResult pr : validTests) {
                String executionError = checkExecution(pr, repairOptions);
                if (executionError == null) {
                    finalTests.add(pr);
                } else {
                    droppedAtExecution.add(pr);
                    executionErrorByTest.put(pr, executionError);
                    diagnostics.add(executionError);
                }
            }

            // Record any executable tests we have so far so they survive later repair turns.
            mergeSalvagedTests(salvagedExecutableTests, finalTests);

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

            String next = tryRepair(combinedError, attempt, previousError, diagnostics,
                    conversation, currentResponse, feature, expandedClasses);
            if (next == null) {
                // Final-resort: perform brute-force salvage on failed tests
                List<ParseResult> allFailedTests = new ArrayList<>(droppedAtParse);
                allFailedTests.addAll(droppedAtExecution);
                for (ParseResult pr : allFailedTests) {
                    Optional<TestCase> salvaged = performBruteForceSalvage(pr.getTestCase());
                    if (salvaged.isPresent()) {
                        ParseResult salvagedResult = new ParseResult(salvaged.get(), 
                                                                     pr.getOriginalMethodName(), 
                                                                     pr.getDiagnostics());
                        mergeSalvagedTests(salvagedExecutableTests, Collections.singletonList(salvagedResult));
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
        return RepairResult.failure(diagnostics, attemptsUsed, expandedClasses);
    }

    private void mergeSalvagedTests(Map<String, ParseResult> salvaged, List<ParseResult> newResults) {
        if (newResults == null || newResults.isEmpty()) {
            return;
        }
        for (ParseResult parseResult : newResults) {
            if (parseResult == null) {
                continue;
            }
            String method = parseResult.getOriginalMethodName();
            String key = (method == null || method.trim().isEmpty())
                    ? "unknown_" + System.identityHashCode(parseResult)
                    : method.trim();
            if (!salvaged.containsKey(key)) {
                salvaged.put(key, parseResult);
            }
        }
    }

    private Optional<TestCase> performBruteForceSalvage(TestCase failedTestCase) {
        logger.warn("Attempting brute-force salvage for test case: {}", failedTestCase.getID());
        TestCase salvaged = failedTestCase.clone();

        // 1. Initial attempt: remove assertions
        salvaged.removeAssertions();
        ExecutionResult result = TestCaseExecutor.runTest(salvaged);

        if (result.noThrownExceptions()) {
            if (isMeaningful(salvaged)) {
                logger.warn("Successfully salvaged test {} by removing assertions.", failedTestCase.getID());
                return Optional.of(salvaged);
            }
            logger.warn("Discarded salvaged test {} as it is not meaningful.", failedTestCase.getID());
            return Optional.empty();
            }

            // 2. Iterative truncation at failure site
            while (!result.noThrownExceptions()) {
            Map<Integer, Throwable> exceptionMapping = result.getCopyOfExceptionMapping();
            Integer failIndex = exceptionMapping.keySet().stream().min(Integer::compareTo).orElse(-1);
            if (failIndex == null || failIndex <= 0) break;

            salvaged.chop(failIndex);
            result = TestCaseExecutor.runTest(salvaged);

            if (result.noThrownExceptions()) {
                if (isMeaningful(salvaged)) {
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
    private boolean isMeaningful(TestCase testCase) {
        for (org.evosuite.testcase.statements.Statement s : testCase) {
            if (s instanceof org.evosuite.testcase.statements.MethodStatement) {
                org.evosuite.testcase.statements.MethodStatement ms = 
                    (org.evosuite.testcase.statements.MethodStatement) s;
                if (ms.getMethod().getDeclaringClass().getName().equals(Properties.TARGET_CLASS)) {
                    return true;
                }
            }
        }
        return false;
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
                                              Map<ParseResult, String> executionErrorByTest) {
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
                String error = executionErrorByTest == null ? null : executionErrorByTest.get(pr);
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
                                        Map<ParseResult, String> executionErrorByTest) {
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

    private String buildSharedExecutionFailureHint(Map<ParseResult, String> executionErrorByTest) {
        if (executionErrorByTest == null || executionErrorByTest.size() < 2) {
            return null;
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> hints = new LinkedHashMap<>();
        for (String executionError : executionErrorByTest.values()) {
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

    private String checkExecution(ParseResult parseResult, RepairOptions options) {
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
                    return "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': " + formatThrowableWithStackExcerpt(thrown, parseResult, diagnosticPosition)
                            + buildExecutionContext(parseResult, diagnosticPosition, thrown);
                }
            }
        } catch (Throwable executionFailure) {
            return "Execution failure in test '" + parseResult.getOriginalMethodName()
                    + "': " + formatThrowableWithStackExcerpt(executionFailure, parseResult, null)
                    + buildExecutionContext(parseResult, null, executionFailure);
        }
        if (options.isRepairOnAssertionFailures()) {
            String assertionError = checkAssertions(parseResult, executionResult);
            if (assertionError != null) {
                return assertionError;
            }
        }
        return null;
    }

    private String checkAssertions(ParseResult parseResult, ExecutionResult executionResult) {
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
                    return "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': Assertion evaluation error at statement " + i + " - "
                            + formatThrowableWithStackExcerpt(assertionFailure, parseResult, i)
                            + buildExecutionContext(parseResult, i, assertionFailure);
                }
                if (!holds) {
                    return "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': Assertion failed at statement " + i + " - "
                            + assertion.getCode()
                            + buildExecutionContext(parseResult, i, null);
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
        if (position < 0 || position >= parseResult.getTestCase().size()) {
            // If execution reports an exception at an invalid position, treat it as undeclared.
            // Silently ignoring it can incorrectly mark a failing candidate as successful.
            return true;
        }
        return !parseResult.getTestCase().getStatement(position)
                .getDeclaredExceptions().contains(thrown.getClass());
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
                                 int repairAttempt) {
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
        appendInstantiationFailureRepairInstructions(error, repairMessage);
        appendNpePreconditionRepairInstructions(error, repairMessage);
        appendMockingRepairInstructions(error, repairMessage);
        appendStreamPreconditionRepairInstructions(error, repairMessage);
        appendArgumentPreconditionRepairInstructions(error, repairMessage);
        appendIndexedFixtureShapeRepairInstructions(error, repairMessage);
        appendReflectiveInvocationRepairInstructions(error, repairMessage);
        appendReflectiveAssertThrowsFallbackRepairInstructions(error, repairMessage);
        appendMissingMethodOnVariableRepairInstructions(error, repairMessage);
        appendMemberAccessRepairInstructions(error, repairMessage);
        appendNoTestMethodsRepairInstructions(error, previousResponse, repairMessage);
        appendCollaboratorFallbackRepairInstructions(error, repairMessage);
        appendSyntheticFallbackUsageRepairInstructions(error, repairMessage);
        appendNotAMockRepairInstructions(error, repairMessage);
        appendAnonymousImplementationRepairInstructions(error, previousResponse, repairMessage);
        appendContextSpecificRepairFacts(error, previousResponse, repairMessage);
        if (sutContextSummary != null && !sutContextSummary.isEmpty()) {
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
        return llmService.query(conversation, feature, repairAttempt,
                expanded, expandedClasses);
    }

    private void appendNpePreconditionRepairInstructions(String error, StringBuilder repairMessage) {
        if (error == null || error.isEmpty()) {
            return;
        }
        String lower = error.toLowerCase();
        if (!lower.contains("nullpointerexception") && !lower.contains("is null")) {
            return;
        }

        String nullVariable = extractNullVariableName(error);
        NpeDereferenceInfo dereferenceInfo = extractNpeDereferenceInfo(error);
        boolean syntheticLocal = isSyntheticLocalVariable(nullVariable);
        boolean directTestVariable = appearsInParsedTestCodeExcerpt(error, nullVariable);
        repairMessage.append("\n\nNPE precondition hint:");
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
        repairMessage.append("\n- Complete each stubbing expression fully (avoid unfinished stubbing chains).");
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
        repairMessage.append("\n\nArgument precondition hint:");
        repairMessage.append("\n- The failing test violates method preconditions for input arguments.");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
        repairMessage.append("\n- For failing tests only, replace invalid empty/default inputs with minimally valid non-empty values before invoking the SUT.");
        repairMessage.append("\n- Typical fixes: provide at least one required line/entry/token/element instead of empty structures or empty strings.");
        repairMessage.append("\n- If testing validation behavior, use assertThrows(...) for the invalid-input case and add a separate valid-input smoke test.");
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
        repairMessage.append("\n\nMember-access repair hint:");
        repairMessage.append("\n- The failing test directly accesses private or protected members (which are not allowed). Note: Package-private members ARE allowed as tests are in the same package.");
        repairMessage.append("\n- Keep all already executable tests unchanged.");
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
            repairMessage.append("\n- The previous response used invalid Java import syntax with file-path separators instead of package dots.");
            for (String importLine : malformedImports.subList(0, Math.min(2, malformedImports.size()))) {
                repairMessage.append("\n- Invalid import from previous response: ").append(importLine);
            }
            repairMessage.append("\n- Java imports must use dotted package names, for example `import br.com.jnfe.base.service.SimpleSecurityHandlerBean;`.");
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
            error.append("\nRepair action: Java import declarations must use package dots, not file-path slashes. ");
            error.append("Example: import br.com.jnfe.base.service.SimpleSecurityHandlerBean;");
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
                + "LLM output contained unresolved or unsupported code that had to be replaced with a compilable fallback.");
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
            logger.info("Aborting repair: equivalent error on consecutive attempts: {}", error);
            diagnostics.add("Skipped repair: identical error repeated");
            return true;
        }
        return false;
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
                             List<String> expandedClasses) {
        if (attempt == maxAttempts || shouldSkipRepair(errorText, previousError, diagnostics)) {
            return null;
        }
        return requestRepairSafely(conversation, currentResponse, errorText,
                feature, expandedClasses, diagnostics, attempt + 2);
    }

    private String requestRepairSafely(List<LlmMessage> conversation,
                                       String previousResponse,
                                       String error,
                                       LlmFeature feature,
                                       List<String> expandedClasses,
                                       List<String> diagnostics,
                                       int repairAttempt) {
        try {
            return requestRepair(conversation, previousResponse, error, feature,
                    expandedClasses, repairAttempt);
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
        }
        return note.toString();
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
        appendUniqueImportLines(sb, adapter.getImports());
        
        String mockitoCanonical = org.mockito.Mockito.class.getCanonicalName();
        if (mockitoCanonical != null) {
            sb.append("import ").append(mockitoCanonical).append(";\n");
            sb.append("import static ").append(mockitoCanonical).append(".*;\n");
        } else {
            sb.append("import org.mockito.Mockito;\n");
            sb.append("import static org.mockito.Mockito.*;\n");
        }
        
        String matchersCanonical = org.mockito.ArgumentMatchers.class.getCanonicalName();
        if (matchersCanonical != null) {
            sb.append("import ").append(matchersCanonical).append(";\n");
            sb.append("import static ").append(matchersCanonical).append(".*;\n");
        } else {
            sb.append("import org.mockito.ArgumentMatchers;\n");
            sb.append("import static org.mockito.ArgumentMatchers.*;\n");
        }
        
        String evoAssertionsCanonical = org.evosuite.runtime.EvoAssertions.class.getCanonicalName();
        if (evoAssertionsCanonical != null && evoAssertionsCanonical.startsWith("shaded.")) {
            sb.append("import static ").append(evoAssertionsCanonical).append(".*;\n");
        } else {
            sb.append("import static org.evosuite.runtime.EvoAssertions.*;\n");
        }
        
        String vaaCanonical = org.evosuite.runtime.ViolatedAssumptionAnswer.class.getCanonicalName();
        if (vaaCanonical != null && vaaCanonical.startsWith("shaded.")) {
            sb.append("import ").append(vaaCanonical).append(";\n");
        } else {
            sb.append("import org.evosuite.runtime.ViolatedAssumptionAnswer;\n");
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
                sb.append("import ").append(canonical.replace('$', '.')).append(";\n");
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

    private void appendUniqueImportLines(StringBuilder sb, String rawImports) {
        if (rawImports == null || rawImports.trim().isEmpty()) {
            return;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String line : rawImports.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.startsWith("import ")) {
                continue;
            }
            if (!trimmed.endsWith(";")) {
                trimmed = trimmed + ";";
            }
            if (seen.add(trimmed)) {
                sb.append(trimmed).append('\n');
            }
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
                if (className == null || isFrameworkStackFrame(className)) {
                    continue;
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

    private boolean isFrameworkStackFrame(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("org.junit.")
                || className.startsWith("org.mockito.")
                || className.startsWith("org.evosuite.")
                || className.startsWith("shaded.org.evosuite.");
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
