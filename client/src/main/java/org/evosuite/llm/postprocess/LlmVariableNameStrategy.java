package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.name.AbstractVariableNameStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.variable.name.TypeBasedVariableNameStrategy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based variable naming strategy. On first variable name request for a test,
 * batch-queries the LLM for all variables in that test and caches results.
 * Falls back to type-based naming on LLM failure or for variables not in the response.
 *
 * <p>Activated with {@code -Dvariable_naming_strategy=LLM} or
 * {@code Properties.LLM_RENAME_VARIABLES = true}.
 *
 * <p>Uses a ThreadLocal re-entrancy guard to prevent stack overflow: rendering
 * the test code for the LLM prompt uses TYPE_BASED naming to avoid recursion
 * through {@code TestCodeVisitor → LlmVariableNameStrategy → toCode()}.
 */
public class LlmVariableNameStrategy extends AbstractVariableNameStrategy {

    private static final Logger logger = LoggerFactory.getLogger(LlmVariableNameStrategy.class);

    /** Pattern: "varN -> suggestedName" or "N. suggestedName" or "N: suggestedName" */
    private static final Pattern VAR_LINE_PATTERN =
            Pattern.compile("^\\s*(?:var)?(\\d+)\\s*[.:)\\->]+\\s*`?([a-zA-Z_][a-zA-Z0-9_]*)`?\\s*$");

    private static final Pattern VALID_VAR_NAME = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,63}$");

    /**
     * Re-entrancy guard: prevents infinite recursion when rendering test code
     * for the LLM prompt. When true on the current thread, queryLlmForTest is
     * already in progress and we must not recurse.
     */
    private static final ThreadLocal<Boolean> RENDERING_FOR_LLM = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Tracks which tests have already been batch-queried. */
    private final Set<Integer> queriedTests = ConcurrentHashMap.newKeySet();

    /** Per-variable LLM-suggested name cache. */
    private final Map<VariableReference, String> llmSuggestions = new ConcurrentHashMap<>();

    private final AtomicInteger variablesRenamed = new AtomicInteger();
    private final AtomicInteger fallbackCount = new AtomicInteger();

    private int typeCounter = 0;

    /** Returns true if the current thread is inside an LLM rendering call. */
    static boolean isRenderingForLlm() {
        return RENDERING_FOR_LLM.get();
    }

    @Override
    public String createNameForVariable(VariableReference variable) {
        // If we're re-entering from an LLM prompt render, use type-based fallback immediately
        if (RENDERING_FOR_LLM.get()) {
            return createTypeBasedName(variable);
        }

        // Try to batch-query LLM for this test's variables if not done yet
        TestCase test = variable.getTestCase();
        if (test != null) {
            int testId = System.identityHashCode(test);
            if (queriedTests.add(testId)) {
                queryLlmForTest(test);
            }
        }

        String llmName = llmSuggestions.get(variable);
        if (llmName != null) {
            variablesRenamed.incrementAndGet();
            return llmName;
        }

        // Fallback: type-based name
        fallbackCount.incrementAndGet();
        return createTypeBasedName(variable);
    }

    private void queryLlmForTest(TestCase test) {
        LlmService llmService = LlmService.getInstance();
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return;
        }

        try {
            // Render test code with TYPE_BASED naming to avoid recursion.
            // Set the thread-local guard so any nested LlmVariableNameStrategy
            // instances created during rendering fall back to type-based naming.
            String testCode;
            RENDERING_FOR_LLM.set(Boolean.TRUE);
            try {
                TestCodeVisitor visitor = new TestCodeVisitor();
                visitor.setVariableNameStrategy(new TypeBasedVariableNameStrategy());
                test.accept(visitor);
                testCode = visitor.getCode();
            } finally {
                RENDERING_FOR_LLM.set(Boolean.FALSE);
            }

            List<LlmMessage> messages = new ArrayList<>();
            messages.add(LlmMessage.system(
                    "You are a variable naming assistant. Return only a numbered list of variable names."));

            StringBuilder prompt = new StringBuilder();
            prompt.append("Given this Java test for class ").append(Properties.TARGET_CLASS)
                    .append(", suggest meaningful camelCase variable names for each numbered variable.\n\n")
                    .append("```java\n").append(testCode).append("\n```\n\n")
                    .append("Return as: 0: suggestedName, 1: anotherName, etc.");
            messages.add(LlmMessage.user(prompt.toString()));

            String response = llmService.query(messages, LlmFeature.VARIABLE_NAMING);
            Map<Integer, String> names = parseVariableResponse(response);

            // Map indices to actual variable references
            List<VariableReference> allVars = new ArrayList<>();
            for (int i = 0; i < test.size(); i++) {
                VariableReference retVal = test.getStatement(i).getReturnValue();
                if (retVal != null) {
                    allVars.add(retVal);
                }
            }

            Set<String> usedNames = new HashSet<>();
            for (Map.Entry<Integer, String> entry : names.entrySet()) {
                int idx = entry.getKey();
                String name = entry.getValue();
                if (idx >= 0 && idx < allVars.size() && !usedNames.contains(name)) {
                    llmSuggestions.put(allVars.get(idx), name);
                    usedNames.add(name);
                }
            }
        } catch (Exception e) {
            logger.warn("LLM variable naming failed for test; using type-based fallback", e);
        }
    }

    /**
     * Parses numbered variable name mappings from LLM response.
     * Package-private for testing.
     */
    static Map<Integer, String> parseVariableResponse(String response) {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (response == null || response.trim().isEmpty()) {
            return result;
        }
        for (String line : response.split("\\r?\\n")) {
            Matcher m = VAR_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                int index = Integer.parseInt(m.group(1));
                String name = m.group(2).trim();
                if (isValidVariableName(name)) {
                    result.put(index, name);
                }
            }
        }
        return result;
    }

    static boolean isValidVariableName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!VALID_VAR_NAME.matcher(name).matches()) {
            return false;
        }
        Set<String> reserved = Set.of("class", "return", "void", "public", "private",
                "static", "final", "abstract", "new", "this", "super", "null", "true", "false",
                "int", "long", "double", "float", "boolean", "char", "byte", "short");
        return !reserved.contains(name);
    }

    private String createTypeBasedName(VariableReference variable) {
        String typeName = variable.getSimpleClassName();
        if (typeName == null || typeName.isEmpty()) {
            typeName = "var";
        }
        // Lowercase first char
        typeName = Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
        return typeName + (typeCounter++);
    }

    @Override
    public void addVariableInformation(Map<String, Map<VariableReference, String>> information) {
        // Accept method/argument info like HeuristicsVariableNameStrategy
        // No-op for LLM strategy; LLM gets context from test code directly
    }

    /** Number of variables successfully renamed by LLM. */
    public int getVariablesRenamed() {
        return variablesRenamed.get();
    }

    /** Number of variables that fell back to type-based naming. */
    public int getFallbackCount() {
        return fallbackCount.get();
    }
}
