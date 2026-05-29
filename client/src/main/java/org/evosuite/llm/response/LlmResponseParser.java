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

import org.evosuite.Properties;

import java.util.ArrayList;
import java.util.Collections;
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

    // Java has no `import ... as ...` syntax (Kotlin/Scala/Python carry-over). We rewrite
    // the alias to its fully qualified name everywhere it appears as a standalone identifier,
    // then drop the alias import line; FQN substitution makes a replacement plain import
    // unnecessary (and avoids duplicate-import errors when both the aliased and plain forms
    // were emitted, as we have observed for collisions like javax.persistence.Query vs
    // net.sourceforge.beanbin.query.Query).
    private static final Pattern ALIASED_IMPORT_LINE_PATTERN = Pattern.compile(
            "(?m)^[ \\t]*import\\s+(?:static\\s+)?([\\w.$]+)\\s+as\\s+([A-Za-z_][\\w$]*)\\s*;[ \\t]*\\r?\\n?");

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
    private final TruncatedJavaRecovery truncatedJavaRecovery = new TruncatedJavaRecovery();

    /**
     * Extraction result carrying synthesized source and recovery metadata.
     */
    public static final class ExtractionResult {
        private final String source;
        private final boolean recoveryApplied;
        private final String recoveryReason;

        ExtractionResult(String source, boolean recoveryApplied, String recoveryReason) {
            this.source = source;
            this.recoveryApplied = recoveryApplied;
            this.recoveryReason = recoveryReason;
        }

        public String getSource() {
            return source;
        }

        public boolean isRecoveryApplied() {
            return recoveryApplied;
        }

        public String getRecoveryReason() {
            return recoveryReason;
        }
    }

    /**
     * Extracts or synthesises a complete Java class from the LLM response,
     * using {@code className} as the class name.
     */
    public String extractTestClass(String response, String className) {
        return extractTestClassWithMetadata(response, className, null).getSource();
    }

    /**
     * Extracts or synthesises a complete Java class from the LLM response,
     * using {@code className} as the class name. If the extracted code does
     * not contain a package declaration and {@code packageName} is non-null,
     * the package declaration is prepended so that the test resides in the
     * same package as the SUT (enabling access to package-private members).
     */
    public String extractTestClass(String response, String className, String packageName) {
        return extractTestClassWithMetadata(response, className, packageName).getSource();
    }

    /**
     * Extracts or synthesises a complete Java class and returns recovery metadata.
     */
    public ExtractionResult extractTestClassWithMetadata(String response, String className, String packageName) {
        List<ExtractionResult> extractions =
                extractAllTestClassesWithMetadata(response, className, packageName);
        if (extractions.isEmpty()) {
            return maybeRecover(emptyFallbackClass(className, packageName));
        }
        return extractions.get(0);
    }

    /**
     * Extracts or synthesises complete Java classes from all detected code blocks
     * and returns recovery metadata for each block.
     */
    public List<ExtractionResult> extractAllTestClassesWithMetadata(String response,
                                                                    String className,
                                                                    String packageName) {
        List<String> blocks = extractCodeBlocks(response);
        if (blocks.isEmpty()) {
            return Collections.singletonList(
                    maybeRecover(emptyFallbackClass(className, packageName)));
        }
        List<ExtractionResult> results = new ArrayList<>();
        for (String block : blocks) {
            results.add(extractFromCodeBlockWithMetadata(block, className, packageName));
        }
        return results;
    }

    private ExtractionResult extractFromCodeBlockWithMetadata(String code,
                                                              String className,
                                                              String packageName) {
        code = sanitizeAliasImports(code);
        if (code.isEmpty()) {
            return maybeRecover(emptyFallbackClass(className, packageName));
        }

        if (code.contains("class ")) {
            return maybeRecover(ensurePackageDeclaration(code, packageName));
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

        String extractedSource = packageDeclaration(packageName)
                + imports.toString()
                + "public class " + className + " {\n"
                + indent(bodyCode)
                + "\n}";
        return maybeRecover(extractedSource);
    }

    private String emptyFallbackClass(String className, String packageName) {
        return packageDeclaration(packageName)
                + "public class " + className + " {\n"
                + "    " + getTestAnnotation() + "\n"
                + "    public void generatedTest() {\n"
                + "    }\n"
                + "}";
    }

    /**
     * Rewrites invalid Kotlin/Scala-style alias imports (e.g.
     * {@code import a.b.C as D;}) into valid Java by deleting the import line
     * and substituting every standalone occurrence of the alias identifier with
     * the original fully qualified name. This is invoked before the source is
     * handed to JavaParser, since JavaParser cannot recover from this syntax
     * and downstream extraction would silently produce no test methods.
     */
    static String sanitizeAliasImports(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        Matcher matcher = ALIASED_IMPORT_LINE_PATTERN.matcher(code);
        if (!matcher.find()) {
            return code;
        }
        // Collect alias -> fqn mappings in the order they appear, then perform
        // substitutions on the whole source. We deliberately do NOT add a
        // replacement plain import: a colliding simple name was the reason the
        // alias was introduced, and the FQN substitution removes the need.
        java.util.LinkedHashMap<String, String> aliasToFqn = new java.util.LinkedHashMap<>();
        do {
            String fqn = matcher.group(1);
            String alias = matcher.group(2);
            if (alias != null && !alias.isEmpty() && fqn != null && !fqn.isEmpty()) {
                aliasToFqn.putIfAbsent(alias, fqn);
            }
        } while (matcher.find());
        String rewritten = ALIASED_IMPORT_LINE_PATTERN.matcher(code).replaceAll("");
        for (java.util.Map.Entry<String, String> entry : aliasToFqn.entrySet()) {
            String alias = entry.getKey();
            String fqn = entry.getValue();
            // Skip the no-op case where the alias matches the FQN's simple name.
            int lastDot = fqn.lastIndexOf('.');
            String simple = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
            if (alias.equals(simple)) {
                continue;
            }
            rewritten = rewritten.replaceAll(
                    "\\b" + Pattern.quote(alias) + "\\b",
                    Matcher.quoteReplacement(fqn));
        }
        return rewritten;
    }

    private ExtractionResult maybeRecover(String source) {
        if (!Properties.LLM_ENABLE_TRUNCATION_RECOVERY) {
            return new ExtractionResult(source, false, "disabled");
        }
        TruncatedJavaRecovery.RecoveryResult recovery = truncatedJavaRecovery.recover(source);
        return new ExtractionResult(recovery.getSource(), recovery.isRecovered(), recovery.getReason());
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
