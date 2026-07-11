/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License along with
 * EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small shared helpers for the canonical expression dialect used by unified
 * LLM post-processing.
 */
public final class LlmPostProcessingExpressionUtils {

    private static final Pattern SYMBOLIC_VARIABLE = Pattern.compile("\\bv\\d+\\b");

    private LlmPostProcessingExpressionUtils() {
        // Utility class.
    }

    public static Expression parseExpressionOrNull(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        try {
            return StaticJavaParser.parseExpression(expression);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static Set<String> extractSymbolicVariables(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Expression parsed = parseExpressionOrNull(expression);
        if (parsed != null) {
            Set<String> names = new LinkedHashSet<>();
            for (NameExpr nameExpr : parsed.findAll(NameExpr.class)) {
                String name = nameExpr.getNameAsString();
                if (looksLikeVariableId(name)) {
                    names.add(name);
                }
            }
            return names;
        }

        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = SYMBOLIC_VARIABLE.matcher(expression);
        while (matcher.find()) {
            names.add(matcher.group());
        }
        return names;
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

    public static boolean containsUnsupportedExpressionConstruct(Expression expression) {
        if (expression == null) {
            return false;
        }
        if (!expression.findAll(AssignExpr.class).isEmpty()
                || !expression.findAll(LambdaExpr.class).isEmpty()
                || !expression.findAll(MethodReferenceExpr.class).isEmpty()
                || !expression.findAll(VariableDeclarationExpr.class).isEmpty()) {
            return true;
        }
        for (UnaryExpr unaryExpr : expression.findAll(UnaryExpr.class)) {
            if (unaryExpr.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT
                    || unaryExpr.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
                    || unaryExpr.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT
                    || unaryExpr.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsRawAssertionCall(Expression expression) {
        if (expression == null) {
            return false;
        }
        for (MethodCallExpr methodCall : expression.findAll(MethodCallExpr.class)) {
            if (isAssertionMethodName(methodCall.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAssertionMethodName(String methodName) {
        return methodName != null && (methodName.startsWith("assert") || "fail".equals(methodName));
    }

    public static boolean containsArbitraryToStringCall(Expression expression) {
        if (expression == null) {
            return false;
        }
        for (MethodCallExpr methodCall : expression.findAll(MethodCallExpr.class)) {
            if ("toString".equals(methodCall.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    public static boolean exceedsMemberChainDepth(Expression expression, int limit) {
        if (expression == null || limit <= 0) {
            return false;
        }
        int maxDepth = 0;
        for (MethodCallExpr methodCall : expression.findAll(MethodCallExpr.class)) {
            maxDepth = Math.max(maxDepth, memberChainDepth(methodCall));
        }
        for (FieldAccessExpr fieldAccess : expression.findAll(FieldAccessExpr.class)) {
            maxDepth = Math.max(maxDepth, memberChainDepth(fieldAccess));
        }
        return maxDepth > limit;
    }

    private static int memberChainDepth(Expression expression) {
        if (expression instanceof MethodCallExpr) {
            MethodCallExpr methodCall = (MethodCallExpr) expression;
            if (methodCall.getScope().isPresent()) {
                return 1 + memberChainDepth(methodCall.getScope().get());
            }
            return 1;
        }
        if (expression instanceof FieldAccessExpr) {
            return 1 + memberChainDepth(((FieldAccessExpr) expression).getScope());
        }
        return 0;
    }
}
