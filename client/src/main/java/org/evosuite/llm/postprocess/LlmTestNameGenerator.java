package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.junit.naming.methods.CoverageGoalTestNameGenerationStrategy;
import org.evosuite.junit.naming.methods.TestNameGenerationStrategy;
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
 * LLM-based test name generator. Batch-names all tests in a single LLM call
 * for efficiency, falling back to {@link CoverageGoalTestNameGenerationStrategy}
 * for any tests that do not receive a valid LLM-generated name.
 *
 * <p>Activated with {@code -Dtest_naming_strategy=LLM} or
 * {@code Properties.LLM_RENAME_TESTS = true} (which overrides the naming strategy).
 */
public class LlmTestNameGenerator implements TestNameGenerationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(LlmTestNameGenerator.class);

    /** Pattern: numbered list item like "1. testMethodName" or "1: testMethodName" or "1) testMethodName" */
    private static final Pattern NAME_LINE_PATTERN =
            Pattern.compile("^\\s*(\\d+)\\s*[.:)\\-]\\s*`?([a-zA-Z_][a-zA-Z0-9_]*)`?\\s*$");

    /** Valid Java method name: starts with letter/underscore, camelCase, reasonable length. */
    private static final Pattern VALID_METHOD_NAME = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,127}$");

    private final Map<TestCase, String> testToName = new IdentityHashMap<>();
    private final AtomicInteger testsRenamed = new AtomicInteger();
    private final AtomicInteger fallbacks = new AtomicInteger();

    public LlmTestNameGenerator(List<TestCase> testCases, List<ExecutionResult> results) {
        generateNames(testCases, results);
    }

    private void generateNames(List<TestCase> testCases, List<ExecutionResult> results) {
        // Always prepare a fallback generator
        CoverageGoalTestNameGenerationStrategy fallbackStrategy =
                new CoverageGoalTestNameGenerationStrategy(testCases, results);

        if (testCases.isEmpty()) {
            return;
        }

        LlmService llmService = LlmService.getInstance();
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            logger.info("LLM not available for test naming; using coverage-goal fallback");
            for (TestCase tc : testCases) {
                testToName.put(tc, fallbackStrategy.getName(tc));
                fallbacks.incrementAndGet();
            }
            return;
        }

        Map<TestCase, String> llmNames = Collections.emptyMap();
        try {
            llmNames = queryLlmForNames(testCases, llmService);
        } catch (Exception e) {
            logger.warn("LLM test naming failed; falling back to coverage-goal naming", e);
        }

        // Assign LLM names where available, fallback otherwise
        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < testCases.size(); i++) {
            TestCase tc = testCases.get(i);
            String llmName = llmNames.get(tc);
            if (llmName != null && !usedNames.contains(llmName)) {
                testToName.put(tc, llmName);
                usedNames.add(llmName);
                testsRenamed.incrementAndGet();
            } else {
                String fallbackName = fallbackStrategy.getName(tc);
                testToName.put(tc, fallbackName);
                fallbacks.incrementAndGet();
            }
        }
    }

    private Map<TestCase, String> queryLlmForNames(List<TestCase> testCases, LlmService llmService) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Given these JUnit tests for class ").append(Properties.TARGET_CLASS)
                .append(", suggest a descriptive camelCase method name for each test. ")
                .append("The name should describe what the test verifies. ")
                .append("Return names as a numbered list (1. nameForFirstTest, 2. nameForSecondTest, etc).\n\n");

        for (int i = 0; i < testCases.size(); i++) {
            prompt.append("Test ").append(i + 1).append(":\n```java\n")
                    .append(testCases.get(i).toCode())
                    .append("\n```\n\n");
        }

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system("You are a test naming assistant. Return only a numbered list of method names."));
        messages.add(LlmMessage.user(prompt.toString()));

        String response = llmService.query(messages, LlmFeature.TEST_NAMING);
        return parseNameResponse(response, testCases);
    }

    /**
     * Parses numbered list response from LLM into test-name mappings.
     * Package-private for testing.
     */
    static Map<TestCase, String> parseNameResponse(String response, List<TestCase> testCases) {
        Map<TestCase, String> result = new IdentityHashMap<>();
        if (response == null || response.trim().isEmpty()) {
            return result;
        }

        String[] lines = response.split("\\r?\\n");
        for (String line : lines) {
            Matcher m = NAME_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                int index = Integer.parseInt(m.group(1)) - 1; // 1-based → 0-based
                String name = m.group(2).trim();
                if (index >= 0 && index < testCases.size() && isValidMethodName(name)) {
                    result.put(testCases.get(index), name);
                }
            }
        }
        return result;
    }

    /**
     * Validates that a name is a valid Java method name suitable for tests.
     * Must start with lowercase, be camelCase, and have reasonable length.
     */
    static boolean isValidMethodName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        // Must match pattern and not be a Java keyword
        if (!VALID_METHOD_NAME.matcher(name).matches()) {
            return false;
        }
        // Reject common Java keywords that could appear
        Set<String> reserved = Set.of("class", "return", "void", "public", "private",
                "static", "final", "abstract", "new", "this", "super", "null", "true", "false");
        return !reserved.contains(name);
    }

    @Override
    public String getName(TestCase test) {
        return testToName.getOrDefault(test, "test" + System.identityHashCode(test));
    }

    /** Number of tests successfully renamed by LLM. */
    public int getTestsRenamed() {
        return testsRenamed.get();
    }

    /** Number of tests that fell back to baseline naming. */
    public int getFallbacks() {
        return fallbacks.get();
    }
}
