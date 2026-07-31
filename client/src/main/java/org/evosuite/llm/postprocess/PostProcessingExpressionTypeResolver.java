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
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 */
package org.evosuite.llm.postprocess;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;

/**
 * Resolves the small expression type model used by assertion validation.
 */
final class PostProcessingExpressionTypeResolver {

    interface MethodCallResolver {
        ExprType resolve(MethodCallExpr methodCall);
    }

    private final LlmPostProcessingResponseParser.ParseContext context;
    private final MethodCallResolver methodCallResolver;

    PostProcessingExpressionTypeResolver(LlmPostProcessingResponseParser.ParseContext context,
                                         MethodCallResolver methodCallResolver) {
        this.context = context;
        this.methodCallResolver = methodCallResolver;
    }

    ExprType resolve(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return ExprType.unknown();
        }
        try {
            return resolve(StaticJavaParser.parseExpression(expression));
        } catch (RuntimeException e) {
            return ExprType.unknown();
        }
    }

    ExprType resolve(Expression expression) {
        if (expression == null) {
            return ExprType.unknown();
        }
        if (expression.isEnclosedExpr()) {
            return resolve(expression.asEnclosedExpr().getInner());
        }
        if (expression.isBooleanLiteralExpr()) {
            return ExprType.primitive("boolean");
        }
        if (expression.isNullLiteralExpr()) {
            return ExprType.nullType();
        }
        if (expression.isStringLiteralExpr()) {
            return ExprType.reference("java.lang.String");
        }
        if (expression.isCharLiteralExpr()) {
            return ExprType.primitive("char");
        }
        if (expression.isIntegerLiteralExpr()) {
            return ExprType.primitive("int");
        }
        if (expression.isLongLiteralExpr()) {
            return ExprType.primitive("long");
        }
        if (expression.isDoubleLiteralExpr()) {
            String value = expression.asDoubleLiteralExpr().getValue().toLowerCase();
            return ExprType.primitive(value.endsWith("f") ? "float" : "double");
        }
        if (expression instanceof NameExpr) {
            return ExprType.fromTypeName(context.variableType(((NameExpr) expression).getNameAsString()));
        }
        if (expression instanceof ArrayCreationExpr) {
            ArrayCreationExpr arrayCreation = (ArrayCreationExpr) expression;
            return ExprType.array(arrayCreation.getElementType().asString(), arrayCreation.getLevels().size());
        }
        if (expression instanceof ObjectCreationExpr) {
            return ExprType.reference(((ObjectCreationExpr) expression).getTypeAsString());
        }
        if (expression instanceof MethodCallExpr) {
            return methodCallResolver.resolve((MethodCallExpr) expression);
        }
        if (expression instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccess = (FieldAccessExpr) expression;
            if ("length".equals(fieldAccess.getNameAsString())
                    && resolve(fieldAccess.getScope()).isArray()) {
                return ExprType.primitive("int");
            }
            return ExprType.unknown();
        }
        if (expression instanceof UnaryExpr) {
            UnaryExpr unaryExpr = (UnaryExpr) expression;
            if (unaryExpr.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                return ExprType.primitive("boolean");
            }
            return resolve(unaryExpr.getExpression());
        }
        if (expression instanceof BinaryExpr) {
            return resolveBinaryType((BinaryExpr) expression);
        }
        return ExprType.unknown();
    }

    boolean sameType(String first, String second) {
        return first != null && second != null && canonicalType(first).equals(canonicalType(second));
    }

    String canonicalType(String typeName) {
        return ExprType.canonicalName(typeName);
    }

    private ExprType resolveBinaryType(BinaryExpr binaryExpr) {
        switch (binaryExpr.getOperator()) {
            case EQUALS:
            case NOT_EQUALS:
            case LESS:
            case LESS_EQUALS:
            case GREATER:
            case GREATER_EQUALS:
            case AND:
            case OR:
                return ExprType.primitive("boolean");
            case PLUS:
                ExprType left = resolve(binaryExpr.getLeft());
                ExprType right = resolve(binaryExpr.getRight());
                if ("java.lang.String".equals(canonicalType(left.typeName))
                        || "java.lang.String".equals(canonicalType(right.typeName))) {
                    return ExprType.reference("java.lang.String");
                }
                return promotedNumericType(left, right);
            case MINUS:
            case MULTIPLY:
            case DIVIDE:
            case REMAINDER:
                return promotedNumericType(resolve(binaryExpr.getLeft()), resolve(binaryExpr.getRight()));
            default:
                return ExprType.unknown();
        }
    }

    private ExprType promotedNumericType(ExprType left, ExprType right) {
        if (!left.isNumericLike() || !right.isNumericLike()) {
            return ExprType.unknown();
        }
        if (left.isDoubleLike() || right.isDoubleLike()) {
            return ExprType.primitive("double");
        }
        if (left.isFloatLike() || right.isFloatLike()) {
            return ExprType.primitive("float");
        }
        if (left.isLongLike() || right.isLongLike()) {
            return ExprType.primitive("long");
        }
        return ExprType.primitive("int");
    }
}
