package org.evosuite.llm.response;

import org.evosuite.testcase.TestCase;
import org.evosuite.testparser.ParseResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of parse/repair/execute pipeline.
 */
public class RepairResult {

    private final boolean success;
    private final List<ParseResult> parseResults;
    private final List<String> diagnostics;
    private final int attemptsUsed;
    private final List<String> expandedClasses;

    private RepairResult(boolean success,
                         List<ParseResult> parseResults,
                         List<String> diagnostics,
                         int attemptsUsed,
                         List<String> expandedClasses) {
        this.success = success;
        this.parseResults = parseResults;
        this.diagnostics = diagnostics;
        this.attemptsUsed = attemptsUsed;
        this.expandedClasses = expandedClasses;
    }

    /** Creates a successful repair result with the given parse results, diagnostics, and expanded classes. */
    public static RepairResult success(List<ParseResult> parseResults,
                                       List<String> diagnostics,
                                       int attemptsUsed,
                                       List<String> expandedClasses) {
        return new RepairResult(true,
                Collections.unmodifiableList(new ArrayList<>(parseResults)),
                Collections.unmodifiableList(new ArrayList<>(diagnostics)),
                attemptsUsed,
                Collections.unmodifiableList(new ArrayList<>(expandedClasses)));
    }

    /** Creates a failure result with diagnostics, attempt count, and any expanded classes. */
    public static RepairResult failure(List<String> diagnostics,
                                       int attemptsUsed,
                                       List<String> expandedClasses) {
        return new RepairResult(false,
                Collections.<ParseResult>emptyList(),
                Collections.unmodifiableList(new ArrayList<>(diagnostics)),
                attemptsUsed,
                Collections.unmodifiableList(new ArrayList<>(expandedClasses)));
    }

    public boolean isSuccess() {
        return success;
    }

    public List<ParseResult> getParseResults() {
        return parseResults;
    }

    public List<String> getDiagnostics() {
        return diagnostics;
    }

    public int getAttemptsUsed() {
        return attemptsUsed;
    }

    public List<String> getExpandedClasses() {
        return expandedClasses;
    }

    /** Returns all successfully parsed test cases contained in this result. */
    public List<TestCase> getTestCases() {
        List<TestCase> tests = new ArrayList<>();
        for (ParseResult parseResult : parseResults) {
            tests.add(parseResult.getTestCase());
        }
        return tests;
    }
}
