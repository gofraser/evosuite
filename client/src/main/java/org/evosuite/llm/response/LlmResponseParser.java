package org.evosuite.llm.response;

import org.evosuite.Properties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Java source snippets from free-form LLM responses.
 */
public class LlmResponseParser {

    private static final Pattern JAVA_FENCE_PATTERN = Pattern.compile("```java\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern GENERIC_FENCE_PATTERN = Pattern.compile("```\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern CLASS_DECLARATION_PATTERN = Pattern.compile("\\bclass\\s+\\w+");
    private static final Pattern IMPORT_STATEMENT_PATTERN =
            Pattern.compile("^import\\s+[\\w.]+;\\s*$", Pattern.MULTILINE);
    private static final Pattern ASSERT_CALL_PATTERN =
            Pattern.compile("\\bassert(Equals|True|False|NotNull|Null|That|Throws)\\s*\\(");

    /**
     * Extracts Java source code blocks from a free-form LLM response string.
     */
    public List<String> extractCodeBlocks(String response) {
        if (response == null || response.trim().isEmpty()) {
            return new ArrayList<>();
        }

        Set<String> blocks = new LinkedHashSet<>();
        Matcher javaMatcher = JAVA_FENCE_PATTERN.matcher(response);
        while (javaMatcher.find()) {
            blocks.add(javaMatcher.group(1).trim());
        }

        if (blocks.isEmpty()) {
            Matcher genericMatcher = GENERIC_FENCE_PATTERN.matcher(response);
            while (genericMatcher.find()) {
                String candidate = genericMatcher.group(1).trim();
                if (looksLikeJava(candidate)) {
                    blocks.add(candidate);
                }
            }
        }

        if (blocks.isEmpty() && looksLikeJava(response)) {
            blocks.add(response.trim());
        }

        return new ArrayList<>(blocks);
    }

    /**
     * Extracts or synthesises a complete Java class from the LLM response,
     * using {@code className} as the class name.
     */
    public String extractTestClass(String response, String className) {
        List<String> blocks = extractCodeBlocks(response);
        String code = blocks.isEmpty() ? "" : blocks.get(0);
        if (code.isEmpty()) {
            return "public class " + className + " {\n"
                    + "    " + getTestAnnotation() + "\n"
                    + "    public void generatedTest() {\n"
                    + "    }\n"
                    + "}";
        }

        if (code.contains("class ")) {
            return code;
        }

        StringBuilder imports = new StringBuilder();
        StringBuilder body = new StringBuilder();

        for (String line : code.split("\\R")) {
            if (line.trim().startsWith("import ")) {
                imports.append(line).append(System.lineSeparator());
            } else {
                body.append(line).append(System.lineSeparator());
            }
        }

        String bodyCode = body.toString().trim();
        if (!containsAnyTestAnnotation(bodyCode)) {
            bodyCode = getTestAnnotation() + "\npublic void generatedTest() {\n" + bodyCode + "\n}";
        }

        return imports.toString()
                + "public class " + className + " {\n"
                + indent(bodyCode)
                + "\n}";
    }

    private String getTestAnnotation() {
        if (Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5) {
            return "@org.junit.jupiter.api.Test";
        }
        return "@org.junit.Test";
    }

    private boolean containsAnyTestAnnotation(String bodyCode) {
        return bodyCode.contains("@Test")
                || bodyCode.contains("@org.junit.Test")
                || bodyCode.contains("@org.junit.jupiter.api.Test");
    }

    private boolean looksLikeJava(String snippet) {
        String normalized = snippet == null ? "" : snippet;
        int signals = 0;

        if (normalized.contains("@Test")) {
            signals++;
        }
        if (CLASS_DECLARATION_PATTERN.matcher(normalized).find()) {
            signals++;
        }
        if (normalized.contains("public void")) {
            signals++;
        }
        if (IMPORT_STATEMENT_PATTERN.matcher(normalized).find()) {
            signals++;
        }
        if (ASSERT_CALL_PATTERN.matcher(normalized).find()) {
            signals++;
        }
        return signals >= 2;
    }

    private String indent(String input) {
        StringBuilder builder = new StringBuilder();
        for (String line : input.split("\\R")) {
            builder.append("    ").append(line).append(System.lineSeparator());
        }
        return builder.toString();
    }
}
