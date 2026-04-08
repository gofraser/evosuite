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
import java.util.HashSet;
import java.util.List;
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

    /** Error patterns that the LLM cannot fix (missing native libs, sandbox, etc.). */
    private static final Set<String> UNFIXABLE_ERROR_PATTERNS = new HashSet<>(Arrays.asList(
            "NoClassDefFoundError",
            "ExceptionInInitializerError",
            "UnsatisfiedLinkError",
            "AccessControlException",
            "java.security.AccessControlException"
    ));

    private final LlmService llmService;
    private final TestParser testParser;
    private final LlmResponseParser responseParser;
    private final ClusterExpansionManager clusterExpansionManager;
    private final TestExecutor testExecutor;
    private final int maxAttempts;
    private final String systemPrompt;
    private final String sutContextSummary;
    private boolean expansionAttempted;

    /**
     * Creates a standard repair loop wired to the given LLM service, using
     * the SUT-aware parser, default response parser, and cluster expansion manager.
     */
    public static TestRepairLoop createDefault(LlmService llmService) {
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
                sutContext);
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
                null);
    }

    /** Creates a repair loop with explicit executor and attempt count (no system prompt/SUT context). */
    public TestRepairLoop(LlmService llmService,
                          TestParser testParser,
                          LlmResponseParser responseParser,
                          ClusterExpansionManager clusterExpansionManager,
                          TestExecutor testExecutor,
                          int maxAttempts) {
        this(llmService, testParser, responseParser, clusterExpansionManager,
                testExecutor, maxAttempts, null, null);
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
        this.llmService = llmService;
        this.testParser = testParser;
        this.responseParser = responseParser;
        this.clusterExpansionManager = clusterExpansionManager;
        this.testExecutor = testExecutor;
        this.maxAttempts = Math.max(0, maxAttempts);
        this.systemPrompt = systemPrompt;
        this.sutContextSummary = sutContextSummary;
    }

    /** Attempts to parse the LLM response and repair it iteratively if parsing fails. */
    public RepairResult attemptParse(String llmResponse,
                                     List<LlmMessage> conversationHistory,
                                     LlmFeature feature) {
        List<String> diagnostics = new ArrayList<>();
        List<String> expandedClasses = new ArrayList<>();
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
                if (attempt == maxAttempts || shouldSkipRepair(parserFailureText, previousError, diagnostics)) {
                    break;
                }
                previousError = parserFailureText;
                currentResponse = requestRepairSafely(conversation, currentResponse, parserFailureText,
                        feature, expandedClasses, diagnostics, attempt + 2);
                if (currentResponse == null) {
                    break;
                }
                continue;
            }

            if (parseResults == null || parseResults.isEmpty()) {
                String parseErrorText = "Parser produced no test methods.";
                diagnostics.add(parseErrorText);
                if (attempt == maxAttempts || shouldSkipRepair(parseErrorText, previousError, diagnostics)) {
                    break;
                }
                previousError = parseErrorText;
                currentResponse = requestRepairSafely(conversation, currentResponse, parseErrorText,
                        feature, expandedClasses, diagnostics, attempt + 2);
                if (currentResponse == null) {
                    break;
                }
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
                String parseErrorText = errorReport.toString().trim();
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

                if (attempt == maxAttempts || shouldSkipRepair(parseErrorText, previousError, diagnostics)) {
                    break;
                }
                previousError = parseErrorText;
                currentResponse = requestRepairSafely(conversation, currentResponse, parseErrorText,
                        feature, expandedClasses, diagnostics, attempt + 2);
                if (currentResponse == null) {
                    break;
                }
                continue;
            }

            // Execute valid tests and further filter those that throw undeclared exceptions
            List<ParseResult> finalTests = new ArrayList<>();
            String lastExecutionError = null;
            for (ParseResult pr : validTests) {
                String executionError = checkExecution(pr);
                if (executionError == null) {
                    finalTests.add(pr);
                } else {
                    lastExecutionError = executionError;
                    diagnostics.add(executionError);
                }
            }

            if (finalTests.isEmpty()) {
                if (attempt == maxAttempts || shouldSkipRepair(lastExecutionError, previousError, diagnostics)) {
                    break;
                }
                previousError = lastExecutionError;
                currentResponse = requestRepairSafely(conversation, currentResponse, lastExecutionError,
                        feature, expandedClasses, diagnostics, attempt + 2);
                if (currentResponse == null) {
                    break;
                }
                continue;
            }

            return RepairResult.success(finalTests, diagnostics, attemptsUsed, expandedClasses);
        }

        return RepairResult.failure(diagnostics, attemptsUsed, expandedClasses);
    }

    private String checkExecution(ParseResult parseResult) {
        try {
            ExecutionResult executionResult = testExecutor.execute(parseResult.getTestCase());
            if (executionResult != null && executionResult.hasUndeclaredException()) {
                Integer first = executionResult.getFirstPositionOfThrownException();
                Throwable thrown = first == null ? null : executionResult.getExceptionThrownAtPosition(first);
                String type = thrown == null ? "UnknownException" : thrown.getClass().getName();
                String message = thrown == null ? "" : thrown.getMessage();
                return "Execution error in test '" + parseResult.getOriginalMethodName()
                        + "': " + type + " - " + message;
            }
        } catch (Throwable executionFailure) {
            return "Execution failure in test '" + parseResult.getOriginalMethodName()
                    + "': " + formatThrowable(executionFailure);
        }
        return null;
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
        if (sutContextSummary != null && !sutContextSummary.isEmpty()) {
            repairMessage.append("\n\nFor reference, here are the available constructors and methods:\n")
                         .append(sutContextSummary);
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

    private String requestRepairSafely(List<LlmMessage> conversation,
                                       String previousResponse,
                                       String error,
                                       LlmFeature feature,
                                       List<String> expandedClasses,
                                       List<String> diagnostics,
                                       int repairAttempt) {
        try {
            return requestRepair(conversation, previousResponse, error, feature, expandedClasses, repairAttempt);
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
        for (String pattern : UNFIXABLE_ERROR_PATTERNS) {
            if (error.contains(pattern)) {
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
            return TestCaseExecutor.runTest(testCase);
        }
    }
}
