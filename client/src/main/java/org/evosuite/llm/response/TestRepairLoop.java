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
import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.SystemPromptProvider;
import org.evosuite.llm.prompt.TestClusterSummarizer;
import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.CodeAssertion;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies parse -> validate -> execute and iterative LLM repair.
 */
public class TestRepairLoop {

    private static final Logger logger = LoggerFactory.getLogger(TestRepairLoop.class);

    /** Pattern to normalize line numbers for fuzzy error comparison. */
    private static final Pattern LINE_NUMBER_PATTERN = Pattern.compile(
            "(?<=\\(line |line |Line )\\d+|(?<=position )\\d+");

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
            try {
                String sutPackage = getSutPackage();
                LlmResponseParser.ExtractionResult extraction = responseParser.extractTestClassWithMetadata(
                        currentResponse, "GeneratedLlmTest", sutPackage);
                String extractedClass = extraction.getSource();
                if (extraction.isRecoveryApplied()) {
                    diagnostics.add("Applied truncation recovery: " + extraction.getRecoveryReason());
                    validateRecoveredSource(extractedClass);
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
                String parseErrorText = "Parser produced no test methods.";
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

            // Filter out tests with errors
            List<ParseResult> validTests = new ArrayList<>();
            StringBuilder errorReport = new StringBuilder();
            for (ParseResult pr : parseResults) {
                if (pr.hasErrors()) {
                    for (ParseDiagnostic d : pr.getDiagnostics()) {
                        if (d.getSeverity() == ParseDiagnostic.Severity.ERROR) {
                            errorReport.append(d.toString()).append(System.lineSeparator());
                        }
                    }
                } else {
                    validTests.add(pr);
                }
            }

            if (validTests.isEmpty()) {
                String parseErrorText = enrichParseErrorWithRepairHints(parseResults, errorReport.toString().trim());
                diagnostics.add(parseErrorText);

                if (hasResolutionErrors(parseResults) && Properties.LLM_EXPAND_CLUSTER_ON_DEMAND) {
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

                String next = tryRepair(parseErrorText, attempt, previousError, diagnostics,
                        conversation, currentResponse, feature, expandedClasses);
                if (next == null) {
                    break;
                }
                previousError = parseErrorText;
                currentResponse = next;
                continue;
            }

            if (!repairOptions.isKeepAssertionsInParsedTests()) {
                int removed = 0;
                for (ParseResult pr : validTests) {
                    removed += LlmAssertionSanitizer.sanitize(pr.getTestCase());
                }
                if (removed > 0) {
                    diagnostics.add("Removed " + removed + " LLM assertion artifact(s) by policy");
                }
            }

            // Execute valid tests and further filter those that throw undeclared exceptions
            List<ParseResult> finalTests = new ArrayList<>();
            List<String> executionErrors = new ArrayList<>();
            String lastExecutionError = null;
            for (ParseResult pr : validTests) {
                String executionError = checkExecution(pr, repairOptions);
                if (executionError == null) {
                    finalTests.add(pr);
                } else {
                    lastExecutionError = executionError;
                    executionErrors.add(executionError);
                    diagnostics.add(executionError);
                }
            }

            if (!finalTests.isEmpty()) {
                mergeSalvagedTests(salvagedExecutableTests, finalTests);
                if (executionErrors.isEmpty()) {
                    return RepairResult.success(new ArrayList<>(salvagedExecutableTests.values()),
                            diagnostics, attemptsUsed, expandedClasses);
                }
                diagnostics.add("Partial success: kept " + finalTests.size()
                        + " executable test(s), dropped " + executionErrors.size()
                        + " failing test(s), and attempting repair for additional salvage.");
                String repairError = summarizeExecutionErrors(executionErrors);
                String next = tryRepair(repairError, attempt, previousError, diagnostics,
                        conversation, currentResponse, feature, expandedClasses);
                if (next == null) {
                    return RepairResult.success(new ArrayList<>(salvagedExecutableTests.values()),
                            diagnostics, attemptsUsed, expandedClasses);
                }
                previousError = repairError;
                currentResponse = next;
                continue;
            }

            if (finalTests.isEmpty()) {
                String next = tryRepair(lastExecutionError, attempt, previousError, diagnostics,
                        conversation, currentResponse, feature, expandedClasses);
                if (next == null) {
                    if (!salvagedExecutableTests.isEmpty()) {
                        return RepairResult.success(new ArrayList<>(salvagedExecutableTests.values()),
                                diagnostics, attemptsUsed, expandedClasses);
                    }
                    break;
                }
                previousError = lastExecutionError;
                currentResponse = next;
                continue;
            }
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

    private String summarizeExecutionErrors(List<String> executionErrors) {
        if (executionErrors == null || executionErrors.isEmpty()) {
            return "Execution error in generated tests";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(executionErrors);
        StringBuilder summary = new StringBuilder("Execution errors in some generated tests:");
        for (String error : unique) {
            summary.append(System.lineSeparator()).append("- ").append(error);
        }
        summary.append(System.lineSeparator())
                .append("Keep already executable tests unchanged and repair only failing tests.");
        return summary.toString();
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
                    String type = thrown == null ? "UnknownException" : thrown.getClass().getName();
                    String message = thrown == null ? "" : thrown.getMessage();
                    return "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': " + type + " - " + message;
                }
            }
        } catch (Throwable executionFailure) {
            return "Execution failure in test '" + parseResult.getOriginalMethodName()
                    + "': " + formatThrowable(executionFailure);
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
                if (assertion == null || assertion instanceof CodeAssertion) {
                    continue;
                }
                final boolean holds;
                try {
                    holds = assertion.evaluate(executionResult.getFinalScope());
                } catch (Throwable assertionFailure) {
                    return "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': Assertion evaluation error at statement " + i + " - "
                            + formatThrowable(assertionFailure);
                }
                if (!holds) {
                    return "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': Assertion failed at statement " + i + " - "
                            + assertion.getCode();
                }
            }
        }
        return null;
    }

    private boolean isUndeclaredException(ParseResult parseResult, Integer position, Throwable thrown) {
        if (parseResult == null || parseResult.getTestCase() == null || position == null || thrown == null) {
            return false;
        }
        if (position < 0 || position >= parseResult.getTestCase().size()) {
            return false;
        }
        return !parseResult.getTestCase().getStatement(position)
                .getDeclaredExceptions().contains(thrown.getClass());
    }

    private void validateRecoveredSource(String source) {
        try {
            StaticJavaParser.parse(source);
        } catch (ParseProblemException parseProblemException) {
            throw new IllegalArgumentException("Recovered source is still not valid Java: "
                    + parseProblemException.getMessage(), parseProblemException);
        }
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
        if (sutContextSummary != null && !sutContextSummary.isEmpty()) {
            repairMessage.append("\n\nFor reference, here are the available constructors and methods:\n")
                         .append(sutContextSummary);
        }
        if (repairOptions.isInstructRepairToAvoidAssertions()) {
            repairMessage.append("\n\nIMPORTANT: Do NOT include assertions "
                    + "(no assert* calls or Java assert statements).");
        }
        repairMessage.append("\n\nPlease provide the corrected complete test class with all test methods.");

        conversation.add(LlmMessage.user(repairMessage.toString()));

        boolean expanded = this.expansionAttempted;
        this.expansionAttempted = false;
        return llmService.query(conversation, feature, repairAttempt,
                expanded, expandedClasses);
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
                } else if (lower.contains("cannot resolve type")) {
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
        for (String pattern : UNFIXABLE_ERROR_PATTERNS) {
            if (error.contains(pattern)) {
                return true;
            }
        }
        return false;
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

    public interface TestExecutor {
        ExecutionResult execute(org.evosuite.testcase.TestCase testCase);
    }

    private static class DefaultExecutor implements TestExecutor {
        @Override
        public ExecutionResult execute(org.evosuite.testcase.TestCase testCase) {
            if (!TestCaseExecutor.isAvailable()) {
                logger.debug("TestCaseExecutor has been shut down; skipping execution check");
                return null;
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
