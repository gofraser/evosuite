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
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testcase;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.testcase.variable.VariableReference;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Source-level support for rendering uninterpreted (LLM-parsed) snippets: local-name
 * normalisation, identifier substitution outside literals and comments, and import
 * discovery for simple type names used in the snippet text.
 *
 * <p>The only visitor state it touches is reached through {@link Host}.</p>
 */
class SnippetSourceSupport {

    /**
     * The visitor state the snippet support reads and updates.
     */
    interface Host {
        /**
         * The classes already named by the visitor, mapped to their rendered names.
         */
        Map<Class<?>, String> classNames();

        /**
         * Registers {@code clazz} with the visitor and returns its rendered name.
         */
        String getClassName(Class<?> clazz);

        boolean isLocalNameReserved(String candidate);

        String ensureUniqueLocalVariableName(String baseName);
    }

    private final Host host;

    SnippetSourceSupport(Host host) {
        this.host = host;
    }

    SnippetLocalNameNormalization normalizeLlmSnippetLocalNames(String code,
                                                                        String returnExpression) {
        if (code == null || code.trim().isEmpty()) {
            return new SnippetLocalNameNormalization(code, returnExpression, Collections.<String>emptySet());
        }
        try {
            BlockStmt block = StaticJavaParser.parseBlock("{\n" + code + "\n}");
            Map<String, String> renames = new LinkedHashMap<>();
            Set<String> declaredNames = new LinkedHashSet<>();

            for (VariableDeclarator declarator : block.findAll(VariableDeclarator.class)) {
                String original = declarator.getNameAsString();
                String candidate = original;
                if (host.isLocalNameReserved(candidate) || declaredNames.contains(candidate)) {
                    candidate = host.ensureUniqueLocalVariableName(candidate);
                    renames.put(original, candidate);
                }
                declarator.setName(candidate);
                declaredNames.add(candidate);
            }

            for (Parameter parameter : block.findAll(Parameter.class)) {
                String original = parameter.getNameAsString();
                String candidate = original;
                if (host.isLocalNameReserved(candidate) || declaredNames.contains(candidate)) {
                    candidate = host.ensureUniqueLocalVariableName(candidate);
                    renames.put(original, candidate);
                }
                parameter.setName(candidate);
                declaredNames.add(candidate);
            }

            if (!renames.isEmpty()) {
                for (NameExpr nameExpr : block.findAll(NameExpr.class)) {
                    String replacement = renames.get(nameExpr.getNameAsString());
                    if (replacement != null && !replacement.equals(nameExpr.getNameAsString())) {
                        nameExpr.setName(replacement);
                    }
                }
                if (returnExpression != null && !returnExpression.isEmpty()) {
                    for (Map.Entry<String, String> entry : renames.entrySet()) {
                        returnExpression = replaceIdentifierOutsideLiteralsAndComments(
                                returnExpression, entry.getKey(), entry.getValue());
                    }
                }
            }

            return new SnippetLocalNameNormalization(
                    stripBlockBraces(block.toString()),
                    returnExpression,
                    declaredNames);
        } catch (ParseProblemException ignored) {
            return new SnippetLocalNameNormalization(code, returnExpression, Collections.<String>emptySet());
        }
    }

    private static String stripBlockBraces(String blockSource) {
        if (blockSource == null) {
            return null;
        }
        String trimmed = blockSource.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return blockSource;
        }
        String withoutLeading = trimmed.substring(1);
        String withoutBraces = withoutLeading.substring(0, withoutLeading.length() - 1);
        return withoutBraces.trim();
    }

    static final class SnippetLocalNameNormalization {
        final String code;
        final String returnExpression;
        final Set<String> declaredNames;

        private SnippetLocalNameNormalization(String code,
                                              String returnExpression,
                                              Set<String> declaredNames) {
            this.code = code;
            this.returnExpression = returnExpression;
            this.declaredNames = declaredNames == null
                    ? Collections.<String>emptySet()
                    : declaredNames;
        }
    }

    static String replaceIdentifierOutsideLiteralsAndComments(String source,
                                                               String identifier,
                                                               String replacement) {
        if (source == null || source.isEmpty()
                || identifier == null || identifier.isEmpty()
                || replacement == null) {
            return source;
        }

        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);

            if (c == '"' || c == '\'') {
                i = copyQuotedLiteral(source, i, out, c);
                continue;
            }
            if (c == '/' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    i = copyLineComment(source, i, out);
                    continue;
                }
                if (next == '*') {
                    i = copyBlockComment(source, i, out);
                    continue;
                }
            }

            if (startsWithIdentifier(source, i, identifier)
                    && isIdentifierBoundary(source, i - 1)
                    && isIdentifierBoundary(source, i + identifier.length())) {
                out.append(replacement);
                i += identifier.length();
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int copyQuotedLiteral(String source, int start, StringBuilder out, char quote) {
        int i = start;
        out.append(source.charAt(i++));
        while (i < source.length()) {
            char current = source.charAt(i);
            out.append(current);
            i++;
            if (current == '\\' && i < source.length()) {
                out.append(source.charAt(i));
                i++;
                continue;
            }
            if (current == quote) {
                break;
            }
        }
        return i;
    }

    private static int copyLineComment(String source, int start, StringBuilder out) {
        int i = start;
        while (i < source.length()) {
            char c = source.charAt(i);
            out.append(c);
            i++;
            if (c == '\n') {
                break;
            }
        }
        return i;
    }

    private static int copyBlockComment(String source, int start, StringBuilder out) {
        int i = start;
        out.append(source.charAt(i++)); // '/'
        if (i < source.length()) {
            out.append(source.charAt(i++)); // '*'
        }
        while (i < source.length()) {
            char c = source.charAt(i);
            out.append(c);
            i++;
            if (c == '*' && i < source.length() && source.charAt(i) == '/') {
                out.append(source.charAt(i));
                i++;
                break;
            }
        }
        return i;
    }

    static boolean startsWithIdentifier(String source, int offset, String identifier) {
        int remaining = source.length() - offset;
        return remaining >= identifier.length()
                && source.regionMatches(offset, identifier, 0, identifier.length());
    }

    static boolean isIdentifierBoundary(String source, int index) {
        if (index < 0 || index >= source.length()) {
            return true;
        }
        return !isJavaIdentifierChar(source.charAt(index));
    }

    private static boolean isJavaIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * Converts class literals written with JVM/binary inner-class separators ('$')
     * into source-level separators ('.').
     * Example:
     * shaded.org.evosuite.runtime.System$SystemExitException.class
     * ->
     * shaded.org.evosuite.runtime.System.SystemExitException.class
     */
    static String normalizeBinaryInnerClassLiterals(String source) {
        if (source == null || source.indexOf('$') < 0 || source.indexOf(".class") < 0) {
            return source;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)+"
                        + "\\$[A-Za-z_][A-Za-z0-9_$]*(?:\\$[A-Za-z_][A-Za-z0-9_$]*)*)\\.class\\b");
        java.util.regex.Matcher matcher = pattern.matcher(source);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String binaryTypeName = matcher.group(1);
            String sourceTypeName = binaryTypeName.replace('$', '.');
            matcher.appendReplacement(out,
                    java.util.regex.Matcher.quoteReplacement(sourceTypeName + ".class"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * LLM responses sometimes contain assignments inside preserved blocks without a prior
     * declaration (eg "s = new Scanner(...)"). When we keep those blocks as uninterpreted
     * code, generated tests do not compile. For first-time local assignments to constructor
     * expressions, rewrite to a declaration ("Scanner s = new Scanner(...)") unless the
     * variable is already known from parser bindings or earlier declarations in the same
     * snippet.
     */
    static String promoteUndeclaredNewAssignmentsToDeclarations(String code,
                                                                  Map<String, VariableReference> bindings) {
        if (code == null || code.isEmpty()) {
            return code;
        }

        Set<String> declaredNames = new HashSet<>();
        if (bindings != null) {
            declaredNames.addAll(bindings.keySet());
        }

        String[] lines = code.split("\\R", -1);
        java.util.regex.Pattern declarationPattern = java.util.regex.Pattern.compile(
                "^\\s*(?:final\\s+)?(?:[A-Za-z_$][A-Za-z0-9_$.]*(?:\\s*<[^>]*>)?(?:\\s*\\[\\s*\\])*)\\s+"
                        + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:[=;,)].*)?$");
        java.util.regex.Pattern newAssignmentPattern = java.util.regex.Pattern.compile(
                "^(\\s*)([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*new\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*\\(.*$");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            java.util.regex.Matcher declMatcher = declarationPattern.matcher(line);
            if (declMatcher.matches()) {
                declaredNames.add(declMatcher.group(1));
                continue;
            }

            java.util.regex.Matcher assignMatcher = newAssignmentPattern.matcher(line);
            if (!assignMatcher.matches()) {
                continue;
            }
            String indent = assignMatcher.group(1);
            String variable = assignMatcher.group(2);
            String typeName = assignMatcher.group(3);
            if (declaredNames.contains(variable)) {
                continue;
            }
            if (isJavaKeyword(variable)) {
                continue;
            }
            lines[i] = indent + typeName + " " + variable + line.substring(indent.length() + variable.length());
            declaredNames.add(variable);
        }
        return String.join(TestCodeVisitor.NEWLINE, lines);
    }

    private static boolean isJavaKeyword(String token) {
        return "abstract".equals(token) || "assert".equals(token) || "boolean".equals(token)
                || "break".equals(token) || "byte".equals(token) || "case".equals(token)
                || "catch".equals(token) || "char".equals(token) || "class".equals(token)
                || "const".equals(token) || "continue".equals(token) || "default".equals(token)
                || "do".equals(token) || "double".equals(token) || "else".equals(token)
                || "enum".equals(token) || "extends".equals(token) || "final".equals(token)
                || "finally".equals(token) || "float".equals(token) || "for".equals(token)
                || "goto".equals(token) || "if".equals(token) || "implements".equals(token)
                || "import".equals(token) || "instanceof".equals(token) || "int".equals(token)
                || "interface".equals(token) || "long".equals(token) || "native".equals(token)
                || "new".equals(token) || "package".equals(token) || "private".equals(token)
                || "protected".equals(token) || "public".equals(token) || "return".equals(token)
                || "short".equals(token) || "static".equals(token) || "strictfp".equals(token)
                || "super".equals(token) || "switch".equals(token) || "synchronized".equals(token)
                || "this".equals(token) || "throw".equals(token) || "throws".equals(token)
                || "transient".equals(token) || "try".equals(token) || "void".equals(token)
                || "volatile".equals(token) || "while".equals(token);
    }

    void ensureSnippetImports(String code,
                                      String returnExpression,
                                      Map<String, VariableReference> bindings) {
        String combined = ((code == null) ? "" : code) + "\n"
                + ((returnExpression == null) ? "" : returnExpression);
        if (combined.trim().isEmpty()) {
            return;
        }

        Set<String> boundNames = new HashSet<>();
        if (bindings != null) {
            boundNames.addAll(bindings.keySet());
        }

        Set<String> seen = new HashSet<>();
        registerSnippetImports(combined, "\\b([A-Z][A-Za-z0-9_]*)\\s*\\.", boundNames, seen);
        registerSnippetImports(combined, "\\b([A-Z][A-Za-z0-9_]*)\\s*\\.class\\b", boundNames, seen);
        registerSnippetImports(combined, "\\(\\s*([A-Z][A-Za-z0-9_]*)\\s*\\)", boundNames, seen);
        registerSnippetImports(combined, "\\binstanceof\\s+([A-Z][A-Za-z0-9_]*)\\b", boundNames, seen);
        registerSnippetImports(combined,
                "\\bnew\\s+([A-Z][A-Za-z0-9_]*)\\s*(?:<[^>]*>)?\\s*\\(",
                boundNames,
                seen);
        // Array creation: "new Foo[0]" / "new Foo[N][]". Lambdas preserved as
        // UninterpretedStatements often contain inline array creations whose
        // simple type names would otherwise miss the constructor-call regex.
        registerSnippetImports(combined,
                "\\bnew\\s+([A-Z][A-Za-z0-9_]*)\\s*\\[",
                boundNames,
                seen);
        // Method return types inside preserved anonymous classes / local classes,
        // e.g. "public Vector<Group> getGroups() { ... }".
        registerSnippetImports(combined,
                "\\b(?:public|protected|private)\\s+"
                        + "(?:static\\s+|final\\s+|abstract\\s+|synchronized\\s+)*"
                        + "([A-Z][A-Za-z0-9_]*)\\s*(?:<[^\\n\\r{};=]*>)?\\s+"
                        + "[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(",
                boundNames,
                seen);
        // Plain declarations inside preserved snippets, e.g. "List results = ...;"
        // or "Map<String, Integer> m;" do not contain '.', '.class', casts, or 'new'.
        registerSnippetImports(combined,
                "\\b([A-Z][A-Za-z0-9_]*)\\s*(?:<[^;{}()=]*>)?\\s*(?:\\[\\s*\\])*\\s+"
                        + "[A-Za-z_$][A-Za-z0-9_$]*\\s*(?==|;|,|\\))",
                boundNames,
                seen);
        registerGenericTypeArgumentImports(combined, boundNames, seen);
        registerThrownTypeImports(combined, boundNames, seen);
    }

    private void registerSnippetImports(String text,
                                        String regex,
                                        Set<String> boundNames,
                                        Set<String> seen) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            String simpleName = matcher.group(1);
            if (!seen.add(simpleName)) {
                continue;
            }
            if (boundNames.contains(simpleName)) {
                continue;
            }
            Class<?> resolved = resolveSimpleClassName(simpleName);
            if (resolved != null) {
                host.getClassName(resolved);
            }
        }
    }

    private void registerThrownTypeImports(String text,
                                           Set<String> boundNames,
                                           Set<String> seen) {
        java.util.regex.Matcher throwsMatcher =
                java.util.regex.Pattern.compile("\\bthrows\\s+([^\\{;]+)").matcher(text);
        while (throwsMatcher.find()) {
            String clause = throwsMatcher.group(1);
            java.util.regex.Matcher typeMatcher =
                    java.util.regex.Pattern.compile("(?<![A-Za-z0-9_$.])([A-Z][A-Za-z0-9_]*)\\b")
                            .matcher(clause);
            while (typeMatcher.find()) {
                String simpleName = typeMatcher.group(1);
                if (!seen.add(simpleName) || boundNames.contains(simpleName)) {
                    continue;
                }
                Class<?> resolved = resolveSimpleClassName(simpleName);
                if (resolved != null) {
                    host.getClassName(resolved);
                }
            }
        }
    }

    private void registerGenericTypeArgumentImports(String text,
                                                    Set<String> boundNames,
                                                    Set<String> seen) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("<([^<>]+)>").matcher(text);
        while (matcher.find()) {
            String genericClause = matcher.group(1);
            java.util.regex.Matcher typeMatcher =
                    java.util.regex.Pattern.compile("(?<![A-Za-z0-9_$.])([A-Z][A-Za-z0-9_]*)\\b")
                            .matcher(genericClause);
            while (typeMatcher.find()) {
                String simpleName = typeMatcher.group(1);
                if (!seen.add(simpleName) || boundNames.contains(simpleName)) {
                    continue;
                }
                Class<?> resolved = resolveSimpleClassName(simpleName);
                if (resolved != null) {
                    host.getClassName(resolved);
                }
            }
        }
    }

    Class<?> resolveSimpleClassName(String simpleName) {
        for (Map.Entry<Class<?>, String> entry : host.classNames().entrySet()) {
            if (simpleName.equals(entry.getValue())) {
                return entry.getKey();
            }
        }

        // If the snippet refers to a nested type by simple name (e.g. ChangeEventType.USER),
        // try declared member classes of already-known classes first.
        for (Class<?> knownClass : host.classNames().keySet()) {
            Class<?> nested = resolveNestedSimpleName(knownClass, simpleName);
            if (nested != null) {
                return nested;
            }
        }

        ClassLoader sut = null;
        try {
            sut = TestGenerationContext.getInstance().getClassLoaderForSUT();
        } catch (Throwable ignored) {
            // Best effort only.
        }

        String[] commonPackages = new String[]{
                "java.lang",
                "java.util",
                "java.util.concurrent",
                "java.util.regex",
                "java.util.stream",
                "java.nio",
                "java.nio.charset",
                "java.nio.file",
                "java.io",
                "java.net",
                "java.time",
                "java.math",
                "java.lang.reflect",
                "java.awt",
                "java.awt.event",
                "javax.naming",
                "javax.naming.directory",
                "javax.naming.ldap",
                "javax.swing",
                "org.junit.jupiter.api"
        };

        for (String pkg : commonPackages) {
            Class<?> resolved = tryLoadClass(pkg + "." + simpleName, sut);
            if (resolved != null) {
                return resolved;
            }
        }

        // Try same-package siblings of already-known classes. This helps preserved
        // snippets that use simple names from the CUT API package, e.g.
        // ChartDataModelEvent when ChartDataModelListener is already known.
        for (Class<?> knownClass : host.classNames().keySet()) {
            Package knownPackage = knownClass.getPackage();
            if (knownPackage == null) {
                continue;
            }
            String pkg = knownPackage.getName();
            if (pkg == null || pkg.isEmpty()) {
                continue;
            }
            Class<?> sibling = tryLoadClass(pkg + "." + simpleName, sut);
            if (sibling != null) {
                return sibling;
            }
        }

        // Try parent-package siblings of already-known classes. This handles
        // snippets that refer to project-level utility types (e.g. Constants)
        // while the known CUT/collaborators are in deeper subpackages.
        for (Class<?> knownClass : host.classNames().keySet()) {
            Package knownPackage = knownClass.getPackage();
            if (knownPackage == null) {
                continue;
            }
            String pkg = knownPackage.getName();
            if (pkg == null || pkg.isEmpty()) {
                continue;
            }
            int lastDot = pkg.lastIndexOf('.');
            while (lastDot > 0) {
                pkg = pkg.substring(0, lastDot);
                Class<?> parentSibling = tryLoadClass(pkg + "." + simpleName, sut);
                if (parentSibling != null) {
                    return parentSibling;
                }
                lastDot = pkg.lastIndexOf('.');
            }
        }

        // Resolve snippet-only simple names via known classes' related API types.
        // This covers preserved declarations like "Instances x = ...;" where the
        // type is not in common packages but is reachable from the CUT signature.
        for (Class<?> knownClass : host.classNames().keySet()) {
            Class<?> related = resolveRelatedSimpleName(knownClass, simpleName, 2, new HashSet<Class<?>>());
            if (related != null) {
                return related;
            }
        }

        // Common third-party utility classes that frequently appear in
        // preserved LLM snippets as qualified calls (e.g. Mockito.when(...)).
        if ("Mockito".equals(simpleName)) {
            Class<?> shadedMockito = tryLoadClass("shaded.org.evosuite.shaded.org.mockito.Mockito", sut);
            if (shadedMockito != null) {
                return shadedMockito;
            }
            Class<?> mockito = tryLoadClass("org.mockito.Mockito", sut);
            if (mockito != null) {
                return mockito;
            }
        }

        if (Properties.CLASS_PREFIX != null && !Properties.CLASS_PREFIX.trim().isEmpty()) {
            return tryLoadClass(Properties.CLASS_PREFIX + "." + simpleName, sut);
        }
        return null;
    }

    private static Class<?> resolveRelatedSimpleName(Class<?> owner,
                                              String simpleName,
                                              int depth,
                                              Set<Class<?>> visited) {
        if (owner == null || simpleName == null || simpleName.isEmpty() || depth < 0) {
            return null;
        }
        if (!visited.add(owner)) {
            return null;
        }

        if (simpleName.equals(owner.getSimpleName())) {
            return owner;
        }

        for (Class<?> nested : safeDeclaredClasses(owner)) {
            if (simpleName.equals(nested.getSimpleName())) {
                return nested;
            }
        }

        if (depth == 0) {
            return null;
        }

        Class<?> superClass = owner.getSuperclass();
        Class<?> fromSuper = resolveRelatedSimpleName(superClass, simpleName, depth - 1, visited);
        if (fromSuper != null) {
            return fromSuper;
        }

        for (Class<?> iface : safeInterfaces(owner)) {
            Class<?> fromIface = resolveRelatedSimpleName(iface, simpleName, depth - 1, visited);
            if (fromIface != null) {
                return fromIface;
            }
        }

        for (java.lang.reflect.Field field : safeDeclaredFields(owner)) {
            Class<?> match = resolveRelatedType(field.getGenericType(), simpleName, depth - 1, visited);
            if (match != null) {
                return match;
            }
        }

        for (java.lang.reflect.Constructor<?> ctor : safeDeclaredConstructors(owner)) {
            for (Type parameter : ctor.getGenericParameterTypes()) {
                Class<?> match = resolveRelatedType(parameter, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
            for (Type exception : ctor.getGenericExceptionTypes()) {
                Class<?> match = resolveRelatedType(exception, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
        }

        for (java.lang.reflect.Method method : safeDeclaredMethods(owner)) {
            Class<?> returnMatch = resolveRelatedType(method.getGenericReturnType(), simpleName, depth - 1, visited);
            if (returnMatch != null) {
                return returnMatch;
            }
            for (Type parameter : method.getGenericParameterTypes()) {
                Class<?> match = resolveRelatedType(parameter, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
            for (Type exception : method.getGenericExceptionTypes()) {
                Class<?> match = resolveRelatedType(exception, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
        }

        return null;
    }

    private static Class<?> resolveRelatedType(Type type,
                                        String simpleName,
                                        int depth,
                                        Set<Class<?>> visited) {
        if (type == null || simpleName == null || simpleName.isEmpty() || depth < 0) {
            return null;
        }
        if (type instanceof Class<?>) {
            Class<?> raw = (Class<?>) type;
            if (raw.isArray()) {
                return resolveRelatedType(raw.getComponentType(), simpleName, depth - 1, visited);
            }
            return resolveRelatedSimpleName(raw, simpleName, depth, visited);
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Class<?> raw = CastPolicy.safeErasure(parameterizedType);
            if (raw != null) {
                Class<?> match = resolveRelatedSimpleName(raw, simpleName, depth, visited);
                if (match != null) {
                    return match;
                }
            }
            Type ownerType = parameterizedType.getOwnerType();
            if (ownerType != null) {
                Class<?> ownerMatch = resolveRelatedType(ownerType, simpleName, depth - 1, visited);
                if (ownerMatch != null) {
                    return ownerMatch;
                }
            }
            for (Type argumentType : parameterizedType.getActualTypeArguments()) {
                Class<?> match = resolveRelatedType(argumentType, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
            return null;
        }
        if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            for (Type bound : wildcardType.getLowerBounds()) {
                Class<?> match = resolveRelatedType(bound, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
            for (Type bound : wildcardType.getUpperBounds()) {
                Class<?> match = resolveRelatedType(bound, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
            return null;
        }
        if (type instanceof TypeVariable) {
            for (Type bound : ((TypeVariable<?>) type).getBounds()) {
                Class<?> match = resolveRelatedType(bound, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
            return null;
        }
        if (type instanceof GenericArrayType) {
            return resolveRelatedType(((GenericArrayType) type).getGenericComponentType(),
                    simpleName, depth - 1, visited);
        }
        Class<?> raw = CastPolicy.safeErasure(type);
        if (raw == null) {
            return null;
        }
        if (raw.isArray()) {
            return resolveRelatedType(raw.getComponentType(), simpleName, depth - 1, visited);
        }
        return resolveRelatedSimpleName(raw, simpleName, depth, visited);
    }

    private static java.lang.reflect.Field[] safeDeclaredFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (Throwable ignored) {
            return new java.lang.reflect.Field[0];
        }
    }

    private static java.lang.reflect.Constructor<?>[] safeDeclaredConstructors(Class<?> type) {
        try {
            return type.getDeclaredConstructors();
        } catch (Throwable ignored) {
            return new java.lang.reflect.Constructor<?>[0];
        }
    }

    private static java.lang.reflect.Method[] safeDeclaredMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable ignored) {
            return new java.lang.reflect.Method[0];
        }
    }

    private static Class<?>[] safeInterfaces(Class<?> type) {
        try {
            return type.getInterfaces();
        } catch (Throwable ignored) {
            return new Class<?>[0];
        }
    }

    private static Class<?>[] safeDeclaredClasses(Class<?> type) {
        try {
            return type.getDeclaredClasses();
        } catch (Throwable ignored) {
            return new Class<?>[0];
        }
    }

    private static Class<?> resolveNestedSimpleName(Class<?> owner, String simpleName) {
        if (owner == null || simpleName == null || simpleName.isEmpty()) {
            return null;
        }
        try {
            for (Class<?> nested : owner.getDeclaredClasses()) {
                if (simpleName.equals(nested.getSimpleName())) {
                    return nested;
                }
            }
        } catch (Throwable ignored) {
            // Best effort only.
        }
        return null;
    }

    private static Class<?> tryLoadClass(String fqcn, ClassLoader preferred) {
        try {
            if (preferred != null) {
                return Class.forName(fqcn, false, preferred);
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Try fallback loader below.
        }
        try {
            // initialize=false: we only need the Class object for name resolution
            // during code generation. Running <clinit> outside the sandboxed
            // executor thread has caused non-determinism in the past.
            return Class.forName(fqcn, false, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }
}
