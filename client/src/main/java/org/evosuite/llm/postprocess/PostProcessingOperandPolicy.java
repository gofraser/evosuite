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
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;

/**
 * Pure operand-category and conversion rules used by assertion validation.
 */
final class PostProcessingOperandPolicy {

    enum ArrayCompatibility {
        VALID,
        NOT_EQUALS,
        UNKNOWN_OPERAND,
        NOT_ARRAY,
        MULTI_DIMENSIONAL,
        COMPONENT_MISMATCH
    }

    private final PostProcessingExpressionTypeResolver expressionTypes;

    PostProcessingOperandPolicy(PostProcessingExpressionTypeResolver expressionTypes) {
        this.expressionTypes = expressionTypes;
    }

    boolean areEqualsCompatible(ExprType expectedType, ExprType actualType) {
        if (expectedType.isNull() || actualType.isNull()) {
            return expectedType.isReferenceLike() || actualType.isReferenceLike();
        }
        if (expectedType.isNumericLike() && actualType.isNumericLike()) {
            return true;
        }
        if (expectedType.isBooleanLike() && actualType.isBooleanLike()) {
            return true;
        }
        if (expectedType.isCharLike() && actualType.isCharLike()) {
            return true;
        }
        if (expectedType.isReferenceLike() && actualType.isReferenceLike()) {
            return expressionTypes.sameType(expectedType.typeName, actualType.typeName)
                    || "java.lang.Object".equals(expressionTypes.canonicalType(expectedType.typeName))
                    || "java.lang.Object".equals(expressionTypes.canonicalType(actualType.typeName))
                    || areReferenceTypesAssignmentCompatible(expectedType.typeName, actualType.typeName);
        }
        return false;
    }

    ArrayCompatibility arrayCompatibility(LlmPostProcessingResponse.AssertionKind kind,
                                            ExprType expectedType, ExprType actualType) {
        if (kind == LlmPostProcessingResponse.AssertionKind.NOT_EQUALS) {
            return ArrayCompatibility.NOT_EQUALS;
        }
        if ((expectedType.isArray() && !actualType.isKnown())
                || (actualType.isArray() && !expectedType.isKnown())) {
            return ArrayCompatibility.UNKNOWN_OPERAND;
        }
        if (!expectedType.isArray() || !actualType.isArray()) {
            return ArrayCompatibility.NOT_ARRAY;
        }
        if (expectedType.arrayDepth != 1 || actualType.arrayDepth != 1) {
            return ArrayCompatibility.MULTI_DIMENSIONAL;
        }
        if (expectedType.componentType != null && actualType.componentType != null
                && !expressionTypes.sameType(expectedType.componentType, actualType.componentType)) {
            return ArrayCompatibility.COMPONENT_MISMATCH;
        }
        return ArrayCompatibility.VALID;
    }

    boolean isNegativeNumericLiteral(String expression) {
        try {
            Expression parsed = StaticJavaParser.parseExpression(expression);
            if (parsed instanceof UnaryExpr) {
                UnaryExpr unaryExpr = (UnaryExpr) parsed;
                return unaryExpr.getOperator() == UnaryExpr.Operator.MINUS
                        && expressionTypes.resolve(unaryExpr.getExpression()).isNumeric();
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean areReferenceTypesAssignmentCompatible(String first, String second) {
        try {
            ClassLoader loader = org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT();
            Class<?> firstClass = Class.forName(expressionTypes.canonicalType(first), false, loader);
            Class<?> secondClass = Class.forName(expressionTypes.canonicalType(second), false, loader);
            return firstClass.isAssignableFrom(secondClass) || secondClass.isAssignableFrom(firstClass)
                    || hasCommonComparableSupertype(firstClass, secondClass);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasCommonComparableSupertype(Class<?> first, Class<?> second) {
        for (Class<?> type : first.getInterfaces()) {
            if (type.equals(java.io.Serializable.class) || type.equals(Cloneable.class)) {
                continue;
            }
            if (type.isAssignableFrom(second)) {
                return true;
            }
        }
        Class<?> superclass = first.getSuperclass();
        return superclass != null && !Object.class.equals(superclass) && superclass.isAssignableFrom(second);
    }
}
