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

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

/**
 * Expression limits and identifier-safety checks for assertion validation.
 */
final class PostProcessingExpressionSafetyPolicy {

    private final LlmPostProcessingResponseParser.ParseContext context;
    private final PostProcessingOptions options;

    PostProcessingExpressionSafetyPolicy(LlmPostProcessingResponseParser.ParseContext context,
                                         PostProcessingOptions options) {
        this.context = context;
        this.options = options;
    }

    boolean exceedsNodeLimit(Expression expression) {
        int limit = options.assertionPolicy().maxExpressionNodes();
        return limit > 0 && expression.findAll(Node.class).size() > limit;
    }

    boolean exceedsChainDepthLimit(Expression expression) {
        return LlmPostProcessingExpressionUtils.exceedsMemberChainDepth(
                expression, options.assertionPolicy().maxChainDepth());
    }

    boolean referencesUnknownVariableId(Expression expression) {
        if (!context.knowsVariableIds()) {
            return false;
        }
        for (NameExpr name : expression.findAll(NameExpr.class)) {
            String identifier = name.getNameAsString();
            if (LlmPostProcessingExpressionUtils.looksLikeVariableId(identifier)
                    && !context.hasVariableId(identifier)) {
                return true;
            }
        }
        return false;
    }

    boolean exceedsLiteralCharLimit(Expression expression) {
        int limit = options.assertionPolicy().maxLiteralChars();
        if (limit <= 0) {
            return false;
        }
        int chars = 0;
        for (StringLiteralExpr literal : expression.findAll(StringLiteralExpr.class)) {
            chars += literal.getValue().length();
            if (chars > limit) {
                return true;
            }
        }
        for (CharLiteralExpr literal : expression.findAll(CharLiteralExpr.class)) {
            chars += literal.getValue().length();
            if (chars > limit) {
                return true;
            }
        }
        return false;
    }

    boolean exceedsConstructedArrayElementLimit(Expression expression) {
        int limit = options.assertionPolicy().maxConstructedArrayElements();
        if (limit <= 0) {
            return false;
        }
        for (ArrayInitializerExpr initializer : expression.findAll(ArrayInitializerExpr.class)) {
            if (initializer.getValues().size() > limit) {
                return true;
            }
        }
        return false;
    }

    boolean containsDisallowedArrayConstruction(Expression expression) {
        for (ArrayCreationExpr arrayCreation : expression.findAll(ArrayCreationExpr.class)) {
            if (arrayCreation.getLevels().size() != 1 || !arrayCreation.getInitializer().isPresent()) {
                return true;
            }
            ArrayInitializerExpr initializer = arrayCreation.getInitializer().get();
            for (Expression value : initializer.getValues()) {
                if (value instanceof ArrayInitializerExpr || !isLiteralOrNull(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isLiteralOrNull(Expression expression) {
        return expression.isLiteralExpr() || expression.isNullLiteralExpr();
    }
}
