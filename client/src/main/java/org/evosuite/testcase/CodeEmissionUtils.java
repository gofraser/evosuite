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
package org.evosuite.testcase;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;

public final class CodeEmissionUtils {

    private CodeEmissionUtils() {
    }

    public static String renderCompilableSource(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        try {
            ParserConfiguration config = new ParserConfiguration().setAttributeComments(false);
            com.github.javaparser.ParseResult<com.github.javaparser.ast.stmt.Statement> parsed =
                    new JavaParser(config).parseStatement(code);
            com.github.javaparser.ast.stmt.Statement statement = parsed.getResult()
                    .orElseThrow(() -> new ParseProblemException(parsed.getProblems()));
            if (!(statement instanceof ExpressionStmt)) {
                return code;
            }
            Expression expression = ((ExpressionStmt) statement).getExpression();
            if (!(expression instanceof MethodCallExpr)) {
                return code;
            }
            MethodCallExpr call = (MethodCallExpr) expression;
            if (!"assertDoesNotThrow".equals(call.getNameAsString()) || call.getArguments().isEmpty()) {
                return code;
            }
            Expression firstArg = call.getArgument(0);
            if (!(firstArg instanceof MethodReferenceExpr)) {
                return code;
            }

            Expression invocation = asZeroArgInvocation((MethodReferenceExpr) firstArg);
            if (invocation == null) {
                return code;
            }

            BlockStmt body = new BlockStmt(new NodeList<>(new ExpressionStmt(invocation)));
            LambdaExpr lambda = new LambdaExpr(new NodeList<>(), body);
            lambda.setEnclosingParameters(true);
            call.setArgument(0, lambda);
            return statement.toString();
        } catch (Exception ignored) {
            return code;
        }
    }

    private static Expression asZeroArgInvocation(MethodReferenceExpr methodReference) {
        Expression scope = methodReference.getScope();
        String identifier = methodReference.getIdentifier();
        if ("new".equals(identifier)) {
            if (!(scope instanceof TypeExpr)) {
                return null;
            }
            if (!((TypeExpr) scope).getType().isClassOrInterfaceType()) {
                return null;
            }
            return new ObjectCreationExpr(null,
                    ((TypeExpr) scope).getType().asClassOrInterfaceType().clone(),
                    new NodeList<>());
        }

        return new MethodCallExpr(scope.clone(), identifier);
    }
}
