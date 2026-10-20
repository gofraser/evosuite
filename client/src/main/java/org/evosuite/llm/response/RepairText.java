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
import org.evosuite.testparser.ParseDiagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Text helpers shared by the repair messages: type names, the typed null
 * placeholders the parser leaves for unresolved expressions, and parser diagnostics.
 */
final class RepairText {

    static final Pattern NULL_DECLARATION_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_$.<>]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*null\\s*;");
    private static final Pattern FALLBACK_NULL_DECLARATION_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_$.<>]*)\\s+(__llm_fallback\\d+)\\s*=\\s*null\\s*;");
    static final Pattern NEW_CALL_PATTERN = Pattern.compile(
            "\\bnew\\s+([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\(");
    private static final Pattern CONSTRUCTOR_DIAGNOSTIC_TYPE_PATTERN = Pattern.compile(
            "No matching constructor found for\\s+([A-Za-z_][A-Za-z0-9_$.]+)");
    static final Pattern MISSING_METHOD_DIAGNOSTIC_PATTERN = Pattern.compile(
            "No method named\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s+in\\s+([A-Za-z_][A-Za-z0-9_$.]+)",
            Pattern.CASE_INSENSITIVE);

    private RepairText() {
    }

    /**
     * Derives the SUT's package name from {@link Properties#TARGET_CLASS}
     * so that the generated test class resides in the same package, enabling
     * access to package-private members.
     */
    static String getSutPackage() {
        String target = Properties.TARGET_CLASS;
        if (target == null || target.isEmpty()) {
            return null;
        }
        int lastDot = target.lastIndexOf('.');
        return lastDot > 0 ? target.substring(0, lastDot) : null;
    }

    static String stripTypeDecorators(String typeName) {
        if (typeName == null) {
            return "";
        }
        String base = typeName.trim();
        int generic = base.indexOf('<');
        if (generic >= 0) {
            base = base.substring(0, generic);
        }
        return base;
    }

    static String simpleName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "";
        }
        String clean = stripTypeDecorators(typeName);
        int lastDot = clean.lastIndexOf('.');
        return lastDot >= 0 ? clean.substring(lastDot + 1) : clean;
    }

    static String joinTop(List<String> values, int maxItems) {
        if (values == null || values.isEmpty() || maxItems <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(values.size(), maxItems);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        if (values.size() > limit) {
            sb.append(", ...");
        }
        return sb.toString();
    }

    static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars))
                + "\n// ... truncated (" + (text.length() - maxChars) + " chars omitted)";
    }

    static String extractTypedFallbackDeclaredType(String error) {
        if (error == null || error.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = FALLBACK_NULL_DECLARATION_PATTERN.matcher(error);
        while (matcher.find()) {
            String declaredType = stripTypeDecorators(matcher.group(1));
            String simpleType = simpleName(declaredType);
            if (declaredType.isEmpty()
                    || "Object".equals(simpleType)
                    || "InvocationTargetException".equals(simpleType)) {
                continue;
            }
            return declaredType;
        }
        return null;
    }

    static List<String> extractTypedNullDeclarationTypes(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> declaredTypes = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = NULL_DECLARATION_PATTERN.matcher(text);
        while (matcher.find()) {
            String declaredType = stripTypeDecorators(matcher.group(1));
            String simpleType = simpleName(declaredType);
            if (declaredType.isEmpty()
                    || "Object".equals(simpleType)
                    || "InvocationTargetException".equals(simpleType)) {
                continue;
            }
            declaredTypes.add(declaredType);
        }
        return new ArrayList<>(declaredTypes);
    }

    static String extractFallbackTypeFromDiagnostic(ParseDiagnostic diagnostic) {
        if (diagnostic == null) {
            return null;
        }
        String message = diagnostic.getMessage() == null ? "" : diagnostic.getMessage();
        java.util.regex.Matcher constructorMatcher = CONSTRUCTOR_DIAGNOSTIC_TYPE_PATTERN.matcher(message);
        if (constructorMatcher.find()) {
            return stripTypeDecorators(constructorMatcher.group(1));
        }

        String sourceSnippet = diagnostic.getSourceSnippet();
        if (sourceSnippet == null || sourceSnippet.trim().isEmpty()) {
            return null;
        }
        java.util.regex.Matcher constructorSourceMatcher = NEW_CALL_PATTERN.matcher(sourceSnippet);
        if (constructorSourceMatcher.find()) {
            return stripTypeDecorators(constructorSourceMatcher.group(1));
        }
        return null;
    }

    static String extractMissingMethodName(String diagnosticMessage) {
        if (diagnosticMessage == null || diagnosticMessage.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = MISSING_METHOD_DIAGNOSTIC_PATTERN.matcher(diagnosticMessage);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    static boolean isUnresolvedTypeDiagnostic(ParseDiagnostic diagnostic) {
        if (diagnostic == null) {
            return false;
        }
        if ("UNRESOLVED_TYPE".equals(diagnostic.getKindName())) {
            return true;
        }
        String message = diagnostic.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("cannot resolve class") || lower.contains("cannot resolve type");
    }
}
