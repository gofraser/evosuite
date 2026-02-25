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

            if (hasErrors(parseResults)) {
                String parseErrorText = formatParseErrors(parseResults);
                diagnostics.add(parseErrorText);

                if (hasResolutionErrors(parseResults) && Properties.LLM_EXPAND_CLUSTER_ON_DEMAND) {
                    boolean expanded = false;
                    try {
                        expanded = clusterExpansionManager.tryExpandFrom(parseResults);
                    } catch (Throwable expansionFailure) {
                        diagnostics.add("Cluster expansion failure: " + formatThrowable(expansionFailure));
                    }
                    if (expanded) {
                        expandedClasses.clear();
                        expandedClasses.addAll(clusterExpansionManager.getLastExpandedClasses());
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

            String executionError = findExecutionError(parseResults);
            if (executionError != null) {
                diagnostics.add(executionError);
                if (attempt == maxAttempts) {
                    break;
                }
                currentResponse = requestRepairSafely(conversationHistory, executionError, feature,
                        expandedClasses, diagnostics);
                if (currentResponse == null) {
                    break;
                }
                continue;
            }

            return RepairResult.success(parseResults, diagnostics, attemptsUsed, expandedClasses);
        }

        return RepairResult.failure(diagnostics, attemptsUsed, expandedClasses);
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

    private boolean hasErrors(List<ParseResult> parseResults) {
        for (ParseResult parseResult : parseResults) {
            if (parseResult.hasErrors()) {
                return true;
            }
        }
        return false;
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

    private String formatParseErrors(List<ParseResult> parseResults) {
        StringBuilder builder = new StringBuilder();
        for (ParseResult parseResult : parseResults) {
            for (ParseDiagnostic diagnostic : parseResult.getDiagnostics()) {
                if (diagnostic.getSeverity() != ParseDiagnostic.Severity.ERROR) {
                    continue;
                }
                builder.append(diagnostic.toString()).append(System.lineSeparator());
            }
        }
        return builder.toString().trim();
    }

    private String findExecutionError(List<ParseResult> parseResults) {
        for (ParseResult parseResult : parseResults) {
            try {
                ExecutionResult executionResult = testExecutor.execute(parseResult.getTestCase());
                if (executionResult != null && executionResult.hasUndeclaredException()) {
                    Integer first = executionResult.getFirstPositionOfThrownException();
                    Throwable thrown = first == null ? null : executionResult.getExceptionThrownAtPosition(first);
                    String type = thrown == null ? "UnknownException" : thrown.getClass().getName();
                    String message = thrown == null ? "" : thrown.getMessage();
                    return "Execution error: " + type + " - " + message;
                }
            } catch (Throwable executionFailure) {
                return "Execution failure: " + formatThrowable(executionFailure);
            }
        }
        return null;
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
