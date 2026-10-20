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

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
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
        private final boolean emptyFallback;

        ExtractionResult(String source, boolean recoveryApplied, String recoveryReason) {
            this(source, recoveryApplied, recoveryReason, false);
        }

        private ExtractionResult(String source, boolean recoveryApplied, String recoveryReason,
                                 boolean emptyFallback) {
            this.source = source;
            this.recoveryApplied = recoveryApplied;
            this.recoveryReason = recoveryReason;
            this.emptyFallback = emptyFallback;
        }

        /**
         * True when the response held no code and {@link #getSource()} is a placeholder class
         * with an empty test method, which callers must not treat as a generated test.
         */
        public boolean isEmptyFallback() {
            return emptyFallback;
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
     * Extracts or synthesises complete Java classes from all detected code blocks
     * and returns recovery metadata for each block.
     */
    public List<ExtractionResult> extractAllTestClassesWithMetadata(String response,
                                                                    String className,
                                                                    String packageName) {
        List<String> blocks = extractCodeBlocks(response);
        if (blocks.isEmpty()) {
            return Collections.singletonList(
                    emptyFallbackResult(className, packageName));
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
            return emptyFallbackResult(className, packageName);
        }

        ExtractionResult normalizedWrapper = normalizeSingleWrapperClass(code, className, packageName);
        if (normalizedWrapper != null) {
            return normalizedWrapper;
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

    /**
     * Safely normalizes a single wrapper class into the generated-class shape.
     *
     * <p>Only applies when the code contains exactly one top-level concrete class
     * whose members are exclusively {@code @Test} methods (no fields, nested
     * types, constructors, or helper methods). This keeps the transformation
     * deterministic and avoids unsafe rewrites.
     */
    private ExtractionResult normalizeSingleWrapperClass(String code,
                                                         String className,
                                                         String packageName) {
        if (code == null || !code.contains("class ")) {
            return null;
        }
        final CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(code);
        } catch (ParseProblemException parserFailure) {
            return null;
        } catch (RuntimeException parserFailure) {
            return null;
        }
        if (cu.getTypes().size() != 1 || !(cu.getType(0) instanceof ClassOrInterfaceDeclaration)) {
            return null;
        }
        ClassOrInterfaceDeclaration wrapper = (ClassOrInterfaceDeclaration) cu.getType(0);
        if (wrapper.isInterface() || wrapper.isAbstract()) {
            return null;
        }
        if (wrapper.getMembers().isEmpty() || !wrapper.getConstructors().isEmpty()) {
            return null;
        }
        List<MethodDeclaration> testMethods = new ArrayList<>();
        for (BodyDeclaration<?> member : wrapper.getMembers()) {
            if (!(member instanceof MethodDeclaration)) {
                return null;
            }
            MethodDeclaration method = (MethodDeclaration) member;
            if (!method.getBody().isPresent() || !isTestAnnotated(method)) {
                return null;
            }
            testMethods.add(method);
        }
        if (testMethods.isEmpty()) {
            return null;
        }
        String resolvedPackage = cu.getPackageDeclaration().isPresent()
                ? cu.getPackageDeclaration().get().getNameAsString()
                : packageName;
        StringBuilder source = new StringBuilder();
        source.append(packageDeclaration(resolvedPackage));
        cu.getImports().forEach(importDecl -> source.append(importDecl.toString()));
        if (!cu.getImports().isEmpty()) {
            source.append(System.lineSeparator());
        }
        source.append("public class ").append(className).append(" {").append(System.lineSeparator());
        for (MethodDeclaration method : testMethods) {
            source.append(indent(method.toString())).append(System.lineSeparator());
        }
        source.append("}");
        return maybeRecover(source.toString(), true, "wrapper-normalized");
    }

    private ExtractionResult emptyFallbackResult(String className, String packageName) {
        return new ExtractionResult(emptyFallbackClass(className, packageName), false, "disabled", true);
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

    private ExtractionResult maybeRecover(String source, boolean normalizationApplied, String normalizationReason) {
        ExtractionResult base = maybeRecover(source);
        if (!normalizationApplied) {
            return base;
        }
        StringBuilder reason = new StringBuilder();
        if (normalizationReason != null && !normalizationReason.isEmpty()) {
            reason.append(normalizationReason);
        }
        if (base.getRecoveryReason() != null && !base.getRecoveryReason().isEmpty()
                && !"disabled".equals(base.getRecoveryReason())) {
            if (reason.length() > 0) {
                reason.append(";");
            }
            reason.append(base.getRecoveryReason());
        }
        return new ExtractionResult(base.getSource(), true, reason.toString());
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

    private boolean isTestAnnotated(MethodDeclaration declaration) {
        if (declaration == null || declaration.getAnnotations() == null) {
            return false;
        }
        return declaration.getAnnotations().stream().anyMatch(annotation -> {
            String name = annotation == null ? "" : annotation.getNameAsString();
            return "Test".equals(name)
                    || "org.junit.Test".equals(name)
                    || "org.junit.jupiter.api.Test".equals(name);
        });
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

    /**
     * Rejects a block whose truncation or wrapper recovery still left invalid Java.
     *
     * @throws IllegalArgumentException when the recovered source does not parse
     */
    static void validateRecoveredSource(String source) {
        try {
            StaticJavaParser.parse(source);
        } catch (ParseProblemException parseProblemException) {
            throw new IllegalArgumentException("Recovered source is still not valid Java: "
                    + parseProblemException.getMessage(), parseProblemException);
        }
    }

    static final class ReflectiveAssertThrowsRewriteResult {
        final String source;
        final int rewrites;

        private ReflectiveAssertThrowsRewriteResult(String source, int rewrites) {
            this.source = source;
            this.rewrites = rewrites;
        }
    }

    /**
     * Rewrites {@code assertThrows(X.class, () -> method.invoke(..))} on a reflective
     * {@code Method} so that it expects the {@code InvocationTargetException} and casts its
     * cause to {@code X}. Best effort: the source is returned unchanged when it does not parse.
     */
    static ReflectiveAssertThrowsRewriteResult rewriteReflectiveAssertThrowsAssertions(String source) {
        if (source == null || source.isEmpty()) {
            return new ReflectiveAssertThrowsRewriteResult(source, 0);
        }
        final CompilationUnit compilationUnit;
        try {
            compilationUnit = StaticJavaParser.parse(source);
        } catch (Throwable ignored) {
            // Best-effort normalization only; keep original source if parsing fails.
            return new ReflectiveAssertThrowsRewriteResult(source, 0);
        }

        int rewrites = 0;
        List<MethodCallExpr> assertThrowsCalls = new ArrayList<>(compilationUnit.findAll(MethodCallExpr.class));
        for (MethodCallExpr call : assertThrowsCalls) {
            if (!"assertThrows".equals(call.getNameAsString()) || call.getArguments().size() != 2) {
                continue;
            }

            Expression expectedArg = call.getArgument(0);
            if (!(expectedArg instanceof ClassExpr)) {
                continue;
            }
            String expectedType = ((ClassExpr) expectedArg).getType().toString();
            if (expectedType.endsWith("InvocationTargetException")
                    || "java.lang.reflect.InvocationTargetException".equals(expectedType)) {
                continue;
            }

            Expression executableArg = call.getArgument(1);
            if (!(executableArg instanceof LambdaExpr)) {
                continue;
            }
            LambdaExpr lambdaExpr = (LambdaExpr) executableArg;

            MethodCallExpr invokeCall = extractSingleReflectiveInvokeCall(lambdaExpr);
            if (invokeCall == null) {
                continue;
            }

            Node parent = call.getParentNode().orElse(null);
            if (!(parent instanceof ExpressionStmt)) {
                continue;
            }
            ExpressionStmt expressionStmt = (ExpressionStmt) parent;
            BlockStmt blockStmt = expressionStmt.findAncestor(BlockStmt.class).orElse(null);
            if (blockStmt == null) {
                continue;
            }

            int statementIndex = blockStmt.getStatements().indexOf(expressionStmt);
            if (statementIndex < 0) {
                continue;
            }
            if (!isReflectiveMethodInvokeReceiver(invokeCall, blockStmt, statementIndex)) {
                continue;
            }

            String variableName = "invocationTargetException" + rewrites;
            String causeVariableName = "expectedCause" + rewrites;
            String rewrittenThrow = "java.lang.reflect.InvocationTargetException " + variableName
                    + " = org.junit.jupiter.api.Assertions.assertThrows("
                    + "java.lang.reflect.InvocationTargetException.class, "
                    + lambdaExpr.toString() + ");";
            String rewrittenCauseAssert = expectedType + " " + causeVariableName
                    + " = (" + expectedType + ") " + variableName + ".getCause();";

            Statement rewrittenThrowStmt;
            Statement rewrittenCauseAssertStmt;
            try {
                rewrittenThrowStmt = StaticJavaParser.parseStatement(rewrittenThrow);
                rewrittenCauseAssertStmt = StaticJavaParser.parseStatement(rewrittenCauseAssert);
            } catch (Throwable ignored) {
                continue;
            }

            blockStmt.getStatements().set(statementIndex, rewrittenThrowStmt);
            blockStmt.addStatement(statementIndex + 1, rewrittenCauseAssertStmt);
            rewrites++;
        }

        if (rewrites == 0) {
            return new ReflectiveAssertThrowsRewriteResult(source, 0);
        }
        return new ReflectiveAssertThrowsRewriteResult(compilationUnit.toString(), rewrites);
    }

    private static MethodCallExpr extractSingleReflectiveInvokeCall(LambdaExpr lambdaExpr) {
        if (lambdaExpr == null) {
            return null;
        }
        if (lambdaExpr.getBody() instanceof ExpressionStmt) {
            Expression expression = ((ExpressionStmt) lambdaExpr.getBody()).getExpression();
            if (expression instanceof MethodCallExpr) {
                MethodCallExpr callExpr = (MethodCallExpr) expression;
                return "invoke".equals(callExpr.getNameAsString()) ? callExpr : null;
            }
            return null;
        }
        if (lambdaExpr.getBody() instanceof BlockStmt) {
            BlockStmt blockStmt = (BlockStmt) lambdaExpr.getBody();
            if (blockStmt.getStatements().size() != 1) {
                return null;
            }
            Statement single = blockStmt.getStatement(0);
            if (!(single instanceof ExpressionStmt)) {
                return null;
            }
            Expression expression = ((ExpressionStmt) single).getExpression();
            if (expression instanceof MethodCallExpr) {
                MethodCallExpr callExpr = (MethodCallExpr) expression;
                return "invoke".equals(callExpr.getNameAsString()) ? callExpr : null;
            }
        }
        return null;
    }

    private static boolean isReflectiveMethodInvokeReceiver(MethodCallExpr invokeCall,
                                                     BlockStmt blockStmt,
                                                     int statementIndex) {
        if (invokeCall == null || blockStmt == null || statementIndex < 0) {
            return false;
        }
        if (!invokeCall.getScope().isPresent()) {
            return false;
        }
        Expression scope = invokeCall.getScope().get();
        if (!(scope instanceof NameExpr)) {
            return false;
        }
        String receiverName = ((NameExpr) scope).getNameAsString();
        if (receiverName == null || receiverName.isEmpty()) {
            return false;
        }

        // Only treat invoke(...) as reflective when we can find a prior local declaration
        // of that receiver as java.lang.reflect.Method (or Method via import).
        for (int i = statementIndex - 1; i >= 0; i--) {
            Statement statement = blockStmt.getStatement(i);
            if (!(statement instanceof ExpressionStmt)) {
                continue;
            }
            Expression expression = ((ExpressionStmt) statement).getExpression();
            if (!(expression instanceof VariableDeclarationExpr)) {
                continue;
            }
            VariableDeclarationExpr declarationExpr = (VariableDeclarationExpr) expression;
            for (com.github.javaparser.ast.body.VariableDeclarator variable : declarationExpr.getVariables()) {
                if (!receiverName.equals(variable.getNameAsString())) {
                    continue;
                }
                String typeName = variable.getType().asString();
                return "Method".equals(typeName)
                        || "java.lang.reflect.Method".equals(typeName)
                        || typeName.endsWith(".Method");
            }
        }
        return false;
    }
}
