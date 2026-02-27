package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.*;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces randomly generated string/numeric literals with readable LLM-suggested
 * alternatives. Replacements are applied directly without coverage re-verification.
 *
 * <p><b>Limitation:</b> This implementation does not verify that test suite coverage
 * is preserved after replacement. Replacements may alter branch coverage if the
 * literal values were coverage-sensitive. For safety, this feature should only be
 * used on finalized test suites where coverage has already been measured.
 *
 * <p>This is an alternative to the existing {@code org.evosuite.lm} n-gram language
 * model ({@code Properties.LM_STRINGS}). Both should not be used simultaneously.
 */
public class LlmLiteralNiceifier {

    private static final Logger logger = LoggerFactory.getLogger(LlmLiteralNiceifier.class);

    /** Pattern for parsing replacement suggestions: "original" -> "replacement". */
    private static final Pattern REPLACEMENT_PATTERN =
            Pattern.compile("^\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*(?:->|→|=>)\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$");

    /** Pattern for numeric replacements: 42 -> 100. */
    private static final Pattern NUMERIC_REPLACEMENT_PATTERN =
            Pattern.compile("^\\s*(-?[\\d.]+[LlFfDd]?)\\s*(?:->|→|=>)\\s*(-?[\\d.]+[LlFfDd]?)\\s*$");

    private final AtomicInteger literalsNiceified = new AtomicInteger();

    /**
     * Apply literal niceification to a test suite. Collects all string and numeric
     * literals, queries LLM for readable replacements, and applies them.
     *
     * <p>Note: coverage preservation is not verified; replacements are best-effort.
     *
     * @param suite the test suite to niceify (modified in place)
     */
    public void niceify(TestSuiteChromosome suite) {
        if (suite == null || suite.getTests().isEmpty()) {
            return;
        }

        LlmService llmService = LlmService.getInstance();
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            logger.debug("LLM not available for literal niceification");
            return;
        }

        try {
            doNiceify(suite, llmService);
        } catch (Exception e) {
            logger.warn("LLM literal niceification failed", e);
        }
    }

    private void doNiceify(TestSuiteChromosome suite, LlmService llmService) {
        // Collect literals from all tests
        List<LiteralInfo> literals = collectLiterals(suite);
        if (literals.isEmpty()) {
            return;
        }

        // Query LLM for replacements
        Map<String, String> replacements = queryLlmForReplacements(literals, llmService);
        if (replacements.isEmpty()) {
            return;
        }

        // Apply replacements
        for (LiteralInfo info : literals) {
            String replacement = replacements.get(info.originalValue);
            if (replacement != null && !replacement.equals(info.originalValue)) {
                if (applyReplacement(info, replacement)) {
                    literalsNiceified.incrementAndGet();
                }
            }
        }
    }

    private List<LiteralInfo> collectLiterals(TestSuiteChromosome suite) {
        List<LiteralInfo> result = new ArrayList<>();
        for (TestCase test : suite.getTests()) {
            for (int i = 0; i < test.size(); i++) {
                Statement stmt = test.getStatement(i);
                if (stmt instanceof StringPrimitiveStatement) {
                    String val = ((StringPrimitiveStatement) stmt).getValue();
                    if (val != null && !val.isEmpty()) {
                        result.add(new LiteralInfo(test, i, val, LiteralType.STRING));
                    }
                } else if (stmt instanceof IntPrimitiveStatement) {
                    int val = ((IntPrimitiveStatement) stmt).getValue();
                    result.add(new LiteralInfo(test, i, String.valueOf(val), LiteralType.INT));
                } else if (stmt instanceof DoublePrimitiveStatement) {
                    double val = ((DoublePrimitiveStatement) stmt).getValue();
                    if (!Double.isNaN(val) && !Double.isInfinite(val)) {
                        result.add(new LiteralInfo(test, i, String.valueOf(val), LiteralType.DOUBLE));
                    }
                } else if (stmt instanceof LongPrimitiveStatement) {
                    long val = ((LongPrimitiveStatement) stmt).getValue();
                    result.add(new LiteralInfo(test, i, String.valueOf(val), LiteralType.LONG));
                } else if (stmt instanceof FloatPrimitiveStatement) {
                    float val = ((FloatPrimitiveStatement) stmt).getValue();
                    if (!Float.isNaN(val) && !Float.isInfinite(val)) {
                        result.add(new LiteralInfo(test, i, String.valueOf(val), LiteralType.FLOAT));
                    }
                }
            }
        }
        return result;
    }

    private Map<String, String> queryLlmForReplacements(List<LiteralInfo> literals, LlmService llmService) {
        // Deduplicate by value
        Set<String> uniqueValues = new LinkedHashSet<>();
        for (LiteralInfo info : literals) {
            uniqueValues.add(info.originalValue);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Replace these test literals with readable alternatives that serve the same purpose ")
                .append("(e.g., replace random strings like 'xK9#q' with 'hello', replace 42 with contextually ")
                .append("meaningful numbers). For class: ").append(Properties.TARGET_CLASS).append("\n\n");
        prompt.append("For each value, respond with: \"original\" -> \"replacement\"\n");
        prompt.append("If a value is already readable, keep it the same.\n\n");

        for (String val : uniqueValues) {
            prompt.append("\"").append(val).append("\"\n");
        }

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(
                "You are a test readability assistant. Return only replacement mappings, one per line."));
        messages.add(LlmMessage.user(prompt.toString()));

        String response = llmService.query(messages, LlmFeature.LITERAL_NICEIFY);
        return parseReplacements(response);
    }

    /**
     * Parse replacement mappings from LLM response.
     * Package-private for testing.
     */
    static Map<String, String> parseReplacements(String response) {
        Map<String, String> result = new LinkedHashMap<>();
        if (response == null || response.trim().isEmpty()) {
            return result;
        }

        for (String line : response.split("\\r?\\n")) {
            // Try string replacement pattern
            Matcher m = REPLACEMENT_PATTERN.matcher(line);
            if (m.matches()) {
                String original = unescapeJava(m.group(1));
                String replacement = unescapeJava(m.group(2));
                if (replacement.length() <= 200) { // Sanity limit
                    result.put(original, replacement);
                }
                continue;
            }
            // Try numeric replacement pattern
            Matcher nm = NUMERIC_REPLACEMENT_PATTERN.matcher(line);
            if (nm.matches()) {
                result.put(nm.group(1), nm.group(2));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean applyReplacement(LiteralInfo info, String replacement) {
        try {
            Statement stmt = info.testCase.getStatement(info.statementIndex);
            if (!(stmt instanceof PrimitiveStatement)) {
                return false;
            }

            PrimitiveStatement<?> primStmt = (PrimitiveStatement<?>) stmt;
            switch (info.type) {
                case STRING:
                    ((PrimitiveStatement<String>) primStmt).setValue(replacement);
                    return true;
                case INT:
                    ((PrimitiveStatement<Integer>) primStmt).setValue(Integer.parseInt(replacement));
                    return true;
                case LONG:
                    String longVal = replacement.replaceAll("[Ll]$", "");
                    ((PrimitiveStatement<Long>) primStmt).setValue(Long.parseLong(longVal));
                    return true;
                case DOUBLE:
                    ((PrimitiveStatement<Double>) primStmt).setValue(Double.parseDouble(replacement));
                    return true;
                case FLOAT:
                    String floatVal = replacement.replaceAll("[Ff]$", "");
                    ((PrimitiveStatement<Float>) primStmt).setValue(Float.parseFloat(floatVal));
                    return true;
                default:
                    return false;
            }
        } catch (NumberFormatException e) {
            logger.debug("Cannot parse replacement value '{}' for type {}", replacement, info.type);
            return false;
        } catch (Exception e) {
            logger.debug("Failed to apply literal replacement", e);
            return false;
        }
    }

    static String unescapeJava(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t");
    }

    /** Number of literals successfully replaced by LLM suggestions. */
    public int getLiteralsNiceified() {
        return literalsNiceified.get();
    }

    enum LiteralType {
        STRING, INT, LONG, DOUBLE, FLOAT
    }

    static class LiteralInfo {
        final TestCase testCase;
        final int statementIndex;
        final String originalValue;
        final LiteralType type;

        LiteralInfo(TestCase testCase, int statementIndex, String originalValue, LiteralType type) {
            this.testCase = testCase;
            this.statementIndex = statementIndex;
            this.originalValue = originalValue;
            this.type = type;
        }
    }
}
