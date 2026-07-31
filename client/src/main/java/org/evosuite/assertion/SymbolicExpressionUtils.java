/* Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors. */
package org.evosuite.assertion;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable expression helpers needed by assertion rendering. */
public final class SymbolicExpressionUtils {
    private static final Pattern SYMBOLIC_VARIABLE = Pattern.compile("\\bv\\d+\\b");
    private SymbolicExpressionUtils() { }

    public static Set<String> extractSymbolicVariables(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return Collections.emptySet();
        }
        try {
            Expression parsed = StaticJavaParser.parseExpression(expression);
            Set<String> result = new LinkedHashSet<>();
            for (NameExpr name : parsed.findAll(NameExpr.class)) {
                if (looksLikeVariableId(name.getNameAsString())) {
                    result.add(name.getNameAsString());
                }
            }
            return result;
        } catch (RuntimeException ignored) {
            Set<String> result = new LinkedHashSet<>();
            Matcher matcher = SYMBOLIC_VARIABLE.matcher(expression);
            while (matcher.find()) {
                result.add(matcher.group());
            }
            return result;
        }
    }

    public static boolean looksLikeVariableId(String identifier) {
        if (identifier == null || identifier.length() < 2 || identifier.charAt(0) != 'v') {
            return false;
        }
        for (int i = 1; i < identifier.length(); i++) {
            if (!Character.isDigit(identifier.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
