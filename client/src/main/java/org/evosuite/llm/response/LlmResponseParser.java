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

    private static final Pattern LEADING_FENCE_PATTERN =
            Pattern.compile("^\\s*```(?:java)?\\s*\\n?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_FENCE_PATTERN =
            Pattern.compile("\\n?\\s*```\\s*$");

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

        // Fallback for truncated responses: opening fence present but no closing fence
        if (blocks.isEmpty()) {
            String stripped = stripLeadingAndTrailingFences(response);
            if (!stripped.equals(response.trim()) && looksLikeJava(stripped)) {
                blocks.add(stripped);
            }
        }

        if (blocks.isEmpty() && looksLikeJava(response)) {
            blocks.add(response.trim());
        }

        return new ArrayList<>(blocks);
    }

    /**
     * Strips leading/trailing markdown code fences from a response, handling
     * truncated responses where the closing fence may be missing.
     */
    private String stripLeadingAndTrailingFences(String response) {
        String result = LEADING_FENCE_PATTERN.matcher(response).replaceFirst("");
        result = TRAILING_FENCE_PATTERN.matcher(result).replaceFirst("");
        return result.trim();
    }

    private static final Pattern PACKAGE_DECLARATION_PATTERN =
            Pattern.compile("^\\s*package\\s+[\\w.]+\\s*;", Pattern.MULTILINE);

    /**
     * Extracts or synthesises a complete Java class from the LLM response,
     * using {@code className} as the class name.
     */
    public String extractTestClass(String response, String className) {
        return extractTestClass(response, className, null);
    }

    /**
     * Extracts or synthesises a complete Java class from the LLM response,
     * using {@code className} as the class name. If the extracted code does
     * not contain a package declaration and {@code packageName} is non-null,
     * the package declaration is prepended so that the test resides in the
     * same package as the SUT (enabling access to package-private members).
     */
    public String extractTestClass(String response, String className, String packageName) {
        List<String> blocks = extractCodeBlocks(response);
        String code = blocks.isEmpty() ? "" : blocks.get(0);
        if (code.isEmpty()) {
            return packageDeclaration(packageName)
                    + "public class " + className + " {\n"
                    + "    " + getTestAnnotation() + "\n"
                    + "    public void generatedTest() {\n"
                    + "    }\n"
                    + "}";
        }

        if (code.contains("class ")) {
            return ensurePackageDeclaration(code, packageName);
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

        return packageDeclaration(packageName)
                + imports.toString()
                + "public class " + className + " {\n"
                + indent(bodyCode)
                + "\n}";
    }

    /**
     * Returns a package declaration string, or empty string if packageName is null/empty.
     */
    private String packageDeclaration(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "";
        }
        return "package " + packageName + ";" + System.lineSeparator() + System.lineSeparator();
    }

    /**
     * If the code does not already contain a package declaration and packageName
     * is non-null, prepend one.
     */
    private String ensurePackageDeclaration(String code, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return code;
        }
        if (PACKAGE_DECLARATION_PATTERN.matcher(code).find()) {
            return code;
        }
        return "package " + packageName + ";" + System.lineSeparator() + System.lineSeparator() + code;
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
