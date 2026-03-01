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
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based assertion generator. Given a test and its execution result,
 * queries the LLM for meaningful assertions and returns them as strings.
 *
 * <p>Operates in two modes:
 * <ul>
 *   <li><b>REGRESSION</b>: Assert observed behavior (for catching future regressions)</li>
 *   <li><b>SPECIFICATION</b>: Assert expected behavior (LLM infers intent from source)</li>
 * </ul>
 *
 * <p>The generated assertion strings are intended to be appended as comments or
 * documentation hints in the output; actual assertion statement objects are managed
 * by EvoSuite's existing assertion pipeline. This class provides supplementary
 * assertion suggestions that can be reviewed or used for enrichment.
 */
public class LlmAssertionGenerator {

    private static final Logger logger = LoggerFactory.getLogger(LlmAssertionGenerator.class);

    /** Matches assertion statement lines: assertEquals(...), assertTrue(...), etc. */
    private static final Pattern ASSERTION_PATTERN =
            Pattern.compile("^\\s*(assert\\w+\\s*\\(.*\\)\\s*;)\\s*$");

    /** Matches valid Java assertion calls to avoid accepting arbitrary code. */
    private static final Pattern SAFE_ASSERTION_PATTERN =
            Pattern.compile("^assert(Equals|NotEquals|True|False|Null|NotNull|Same|NotSame|"
                    + "ArrayEquals|Throws|DoesNotThrow|That|Timeout)\\s*\\(.*\\)\\s*;$");

    /** Tokens that should never appear in assertion arguments (injection indicators). */
    private static final Set<String> SUSPICIOUS_TOKENS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "Runtime", "exec(", "System.exit", "ProcessBuilder",
            "import ", "class ", "void ", "new File(", "delete(", "forName(")));

    public enum Mode {
        /** Assert observed behavior from execution results. */
        REGRESSION,
        /** Assert expected behavior inferred from source code. */
        SPECIFICATION
    }

    private final Mode mode;
    private final AtomicInteger assertionsGenerated = new AtomicInteger();

    public LlmAssertionGenerator() {
        this(Mode.REGRESSION);
    }

    public LlmAssertionGenerator(Mode mode) {
        this.mode = mode;
    }

    /**
     * Generate assertion suggestions for a test based on its execution result.
     *
     * @param test   the test case
     * @param result the execution result (may be null for SPECIFICATION mode)
     * @return list of assertion statement strings, empty on failure
     */
    public List<String> generateAssertions(TestCase test, ExecutionResult result) {
        if (test == null) {
            return Collections.emptyList();
        }

        LlmService llmService = LlmService.getInstance();
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            logger.debug("LLM not available for assertion generation");
            return Collections.emptyList();
        }

        try {
            return queryLlmForAssertions(test, result, llmService);
        } catch (Exception e) {
            logger.warn("LLM assertion generation failed", e);
            return Collections.emptyList();
        }
    }

    private List<String> queryLlmForAssertions(TestCase test, ExecutionResult result, LlmService llmService) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(
                "You are a test assertion assistant. Return only assertion statements, one per line. "
                + "Use standard JUnit assertions (assertEquals, assertTrue, assertNotNull, etc)."));

        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate meaningful assertions for this test of class ")
                .append(Properties.TARGET_CLASS).append(".\n\n");

        prompt.append("Test code:\n```java\n").append(test.toCode()).append("\n```\n\n");

        if (mode == Mode.REGRESSION && result != null) {
            prompt.append("Mode: REGRESSION - Assert the observed behavior to catch future regressions.\n");
            if (!result.getAllThrownExceptions().isEmpty()) {
                Throwable firstException = result.getAllThrownExceptions().iterator().next();
                prompt.append("The test threw: ").append(firstException.getClass().getName()).append("\n");
            }
        } else if (mode == Mode.SPECIFICATION) {
            prompt.append("Mode: SPECIFICATION - Assert the expected behavior based on the method's purpose.\n");
        }

        prompt.append("\nReturn only assertion statements, one per line.");
        messages.add(LlmMessage.user(prompt.toString()));

        String response = llmService.query(messages, LlmFeature.ASSERTION_GENERATION);
        List<String> assertions = parseAssertions(response);
        assertionsGenerated.addAndGet(assertions.size());
        return assertions;
    }

    /**
     * Parse assertion statements from LLM response.
     * Only accepts lines that look like valid JUnit assertion calls.
     * Public for reuse by {@link org.evosuite.assertion.LlmAssertionGeneratorStrategy}.
     */
    public static List<String> parseAssertions(String response) {
        List<String> result = new ArrayList<>();
        if (response == null || response.trim().isEmpty()) {
            return result;
        }

        for (String line : response.split("\\r?\\n")) {
            Matcher m = ASSERTION_PATTERN.matcher(line);
            if (m.matches()) {
                String assertion = m.group(1).trim();
                if (isSafeAssertion(assertion)) {
                    result.add(assertion);
                }
            }
        }
        return result;
    }

    /**
     * Validates that an assertion is a safe JUnit assertion call.
     * Rejects payloads with multiple statements (extra semicolons),
     * suspicious injection tokens, or non-assertion patterns.
     * Public for reuse by {@link org.evosuite.assertion.LlmAssertionGeneratorStrategy}.
     */
    public static boolean isSafeAssertion(String assertion) {
        if (assertion == null || assertion.isEmpty()) {
            return false;
        }
        // Reject multiple statements: only one trailing semicolon allowed
        long semicolonCount = assertion.chars().filter(c -> c == ';').count();
        if (semicolonCount != 1) {
            return false;
        }
        // Reject suspicious tokens (injection indicators)
        for (String token : SUSPICIOUS_TOKENS) {
            if (assertion.contains(token)) {
                return false;
            }
        }
        return SAFE_ASSERTION_PATTERN.matcher(assertion).matches();
    }

    /** Number of assertions generated by LLM. */
    public int getAssertionsGenerated() {
        return assertionsGenerated.get();
    }

    public Mode getMode() {
        return mode;
    }
}
