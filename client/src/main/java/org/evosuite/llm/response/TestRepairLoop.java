/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.response;

import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies parse -> validate -> execute and iterative LLM repair.
 */
public class TestRepairLoop {

    private final LlmService llmService;
    private final TestParser testParser;
    private final LlmResponseParser responseParser;
    private final ClusterExpansionManager clusterExpansionManager;
    private final TestExecutor testExecutor;
    private final int maxAttempts;

    /**
     * Creates a standard repair loop wired to the given LLM service, using
     * the SUT-aware parser, default response parser, and cluster expansion manager.
     */
    public static TestRepairLoop createDefault(LlmService llmService) {
        return new TestRepairLoop(
                llmService,
                TestParser.forSUTWithLlmProvenance(),
                new LlmResponseParser(),
                new ClusterExpansionManager());
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
                Properties.LLM_REPAIR_ATTEMPTS);
    }

    /** Creates a repair loop with explicit executor and attempt count. */
    public TestRepairLoop(LlmService llmService,
                          TestParser testParser,
                          LlmResponseParser responseParser,
                          ClusterExpansionManager clusterExpansionManager,
                          TestExecutor testExecutor,
                          int maxAttempts) {
        this.llmService = llmService;
        this.testParser = testParser;
        this.responseParser = responseParser;
        this.clusterExpansionManager = clusterExpansionManager;
        this.testExecutor = testExecutor;
        this.maxAttempts = Math.max(0, maxAttempts);
    }

    /** Attempts to parse the LLM response and repair it iteratively if parsing fails. */
    public RepairResult attemptParse(String llmResponse,
                                     List<LlmMessage> conversationHistory,
                                     LlmFeature feature) {
        List<String> diagnostics = new ArrayList<>();
        List<String> expandedClasses = new ArrayList<>();
        String currentResponse = llmResponse;
        int attemptsUsed = 0;

        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            attemptsUsed = attempt + 1;
            List<ParseResult> parseResults;
            try {
                String extractedClass = responseParser.extractTestClass(currentResponse, "GeneratedLlmTest");
                parseResults = testParser.parseTestClass(extractedClass);
            } catch (Throwable parserFailure) {
                String parserFailureText = "Parser failure: " + formatThrowable(parserFailure);
                diagnostics.add(parserFailureText);
                if (attempt == maxAttempts) {
                    break;
                }
                currentResponse = requestRepairSafely(conversationHistory, parserFailureText, feature,
                        expandedClasses, diagnostics);
                if (currentResponse == null) {
                    break;
                }
                continue;
            }

            if (parseResults == null || parseResults.isEmpty()) {
                String parseErrorText = "Parser produced no test methods.";
                diagnostics.add(parseErrorText);
                if (attempt == maxAttempts) {
                    break;
                }
                currentResponse = requestRepairSafely(conversationHistory, parseErrorText, feature,
                        expandedClasses, diagnostics);
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
                    try {
                        expanded = clusterExpansionManager.tryExpandFrom(parseResults);
                    } catch (Throwable expansionFailure) {
                        diagnostics.add("Cluster expansion failure: " + formatThrowable(expansionFailure));
                    }
                    if (expanded) {
                        for (String cls : clusterExpansionManager.getLastExpandedClasses()) {
                            if (!expandedClasses.contains(cls)) {
                                expandedClasses.add(cls);
                            }
                        }
                        diagnostics.add("Expanded cluster with: " + expandedClasses);
                        continue;
                    }
                }

                if (attempt == maxAttempts) {
                    break;
                }
                currentResponse = requestRepairSafely(conversationHistory, parseErrorText, feature,
                        expandedClasses, diagnostics);
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
                if (attempt == maxAttempts) {
                    break;
                }
                currentResponse = requestRepairSafely(conversationHistory, lastExecutionError, feature,
                        expandedClasses, diagnostics);
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

    private String requestRepair(List<LlmMessage> conversationHistory,
                                 String error,
                                 LlmFeature feature,
                                 List<String> expandedClasses) {
        List<LlmMessage> request = new ArrayList<>();
        if (conversationHistory != null) {
            request.addAll(conversationHistory);
        }
        if (!expandedClasses.isEmpty()) {
            request.add(LlmMessage.user("Cluster expanded with newly resolved classes: " + expandedClasses));
        }
        request.add(LlmMessage.user("Repair this test based on the following issue:\n" + error));
        return llmService.query(request, feature);
    }

    private String requestRepairSafely(List<LlmMessage> conversationHistory,
                                       String error,
                                       LlmFeature feature,
                                       List<String> expandedClasses,
                                       List<String> diagnostics) {
        try {
            return requestRepair(conversationHistory, error, feature, expandedClasses);
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
            return TestCaseExecutor.runTest(testCase);
        }
    }
}
