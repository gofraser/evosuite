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
package org.evosuite.testparser;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.*;
import org.evosuite.testcase.variable.VariableReference;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides whether a statement that EvoSuite cannot represent should be dropped instead of being
 * preserved as raw source: a statement that calls a method its receiver type does not have would
 * not compile in the generated test. Used by {@link StatementParser} in best-effort mode.
 */
final class UnsupportedStatementScrubber {

    private final StatementParser parser;
    private final TypeResolver typeResolver;
    private final VariableScope scope;
    private final OverloadResolver overloadResolver;

    UnsupportedStatementScrubber(StatementParser parser,
                                 TypeResolver typeResolver,
                                 VariableScope scope,
                                 OverloadResolver overloadResolver) {
        this.parser = parser;
        this.typeResolver = typeResolver;
        this.scope = scope;
        this.overloadResolver = overloadResolver;
    }

    boolean shouldDropUnsupportedStatement(com.github.javaparser.ast.stmt.Statement stmt) {
        UnsupportedMethodTarget invalid = findInvalidUnsupportedStatementMethodCall(stmt);
        if (invalid == null) {
            return false;
        }
        parser.addWarning(invalid.call,
                "Dropped unsupported statement in LLM best-effort mode because it contains "
                        + "an invalid method call that would not compile");
        return true;
    }

    private UnsupportedMethodTarget findInvalidUnsupportedStatementMethodCall(
            com.github.javaparser.ast.stmt.Statement stmt) {
        Map<String, Type> localTypes = collectUnsupportedStatementLocalTypes(stmt);
        for (MethodCallExpr call : stmt.findAll(MethodCallExpr.class)) {
            UnsupportedMethodTarget target = resolveUnsupportedMethodTarget(call, localTypes);
            if (target == null || target.rawClass == null) {
                continue;
            }
            if (!overloadResolver.hasMethodNamed(target.rawClass, call.getNameAsString(), target.staticCall)) {
                return target;
            }
        }
        return null;
    }

    private Map<String, Type> collectUnsupportedStatementLocalTypes(com.github.javaparser.ast.stmt.Statement stmt) {
        Map<String, Type> localTypes = new LinkedHashMap<>();
        if (stmt == null) {
            return localTypes;
        }
        stmt.walk(com.github.javaparser.ast.Node.TreeTraversal.PREORDER, node -> {
            if (node instanceof VariableDeclarator) {
                VariableDeclarator declarator = (VariableDeclarator) node;
                localTypes.put(declarator.getNameAsString(),
                        resolveUnsupportedAstType(declarator.getType()));
            } else if (node instanceof com.github.javaparser.ast.body.Parameter) {
                com.github.javaparser.ast.body.Parameter parameter =
                        (com.github.javaparser.ast.body.Parameter) node;
                localTypes.put(parameter.getNameAsString(),
                        resolveUnsupportedAstType(parameter.getType()));
            }
        });
        return localTypes;
    }

    private Type resolveUnsupportedAstType(com.github.javaparser.ast.type.Type astType) {
        if (astType == null) {
            return Object.class;
        }
        try {
            return typeResolver.resolveType(astType);
        } catch (ClassNotFoundException e) {
            Type democked = parser.getMockitoPatternParser().tryResolveDemockedDeclaredType(astType);
            return democked != null ? democked : Object.class;
        }
    }

    private UnsupportedMethodTarget resolveUnsupportedMethodTarget(MethodCallExpr call,
                                                                   Map<String, Type> localTypes) {
        if (call == null) {
            return null;
        }

        if (!call.getScope().isPresent()) {
            String staticClass = typeResolver.resolveStaticImportClass(call.getNameAsString());
            if (staticClass == null) {
                return null;
            }
            try {
                return new UnsupportedMethodTarget(
                        call,
                        typeResolver.resolveClass(staticClass),
                        true);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }

        Expression scopeExpr = call.getScope().get();
        Class<?> staticClass = parser.resolveClassFromExpression(scopeExpr);
        if (staticClass != null) {
            return new UnsupportedMethodTarget(call, staticClass, true);
        }

        Type scopeType = resolveUnsupportedExpressionType(scopeExpr, localTypes);
        if (scopeType == null) {
            return new UnsupportedMethodTarget(call, null, false);
        }
        return new UnsupportedMethodTarget(call, StatementParser.getRawClass(scopeType), false);
    }

    private Type resolveUnsupportedExpressionType(Expression expr, Map<String, Type> localTypes) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof EnclosedExpr) {
            return resolveUnsupportedExpressionType(((EnclosedExpr) expr).getInner(), localTypes);
        }
        if (expr instanceof NameExpr) {
            String name = ((NameExpr) expr).getNameAsString();
            if (localTypes.containsKey(name)) {
                return localTypes.get(name);
            }
            VariableReference ref = scope.resolve(name);
            if (ref != null) {
                return ref.getType();
            }
            Class<?> clazz = parser.resolveClassFromExpression(expr);
            return clazz != null ? clazz : null;
        }
        if (expr instanceof CastExpr) {
            return resolveUnsupportedAstType(((CastExpr) expr).getType());
        }
        if (expr instanceof ObjectCreationExpr) {
            return resolveUnsupportedAstType(((ObjectCreationExpr) expr).getType());
        }
        if (expr instanceof MethodCallExpr) {
            UnsupportedMethodTarget target = resolveUnsupportedMethodTarget((MethodCallExpr) expr, localTypes);
            if (target == null || target.rawClass == null) {
                return null;
            }
            if (!overloadResolver.hasMethodNamed(target.rawClass,
                    ((MethodCallExpr) expr).getNameAsString(), target.staticCall)) {
                return null;
            }
            try {
                Class<?>[] argTypes = new Class<?>[((MethodCallExpr) expr).getArguments().size()];
                Arrays.fill(argTypes, Object.class);
                Method method = overloadResolver.resolveMethod(
                        target.rawClass,
                        ((MethodCallExpr) expr).getNameAsString(),
                        argTypes);
                return method.getGenericReturnType();
            } catch (NoSuchMethodException e) {
                return Object.class;
            }
        }
        if (expr instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccessExpr = (FieldAccessExpr) expr;
            Type scopeType = resolveUnsupportedExpressionType(fieldAccessExpr.getScope(), localTypes);
            Class<?> ownerClass = StatementParser.getRawClass(scopeType);
            if (ownerClass == null || ownerClass == Object.class) {
                ownerClass = parser.resolveClassFromExpression(fieldAccessExpr.getScope());
            }
            if (ownerClass == null) {
                return null;
            }
            if (ownerClass.isEnum()) {
                return ownerClass;
            }
            try {
                return ownerClass.getField(fieldAccessExpr.getNameAsString()).getGenericType();
            } catch (NoSuchFieldException e) {
                try {
                    return ownerClass.getDeclaredField(fieldAccessExpr.getNameAsString()).getGenericType();
                } catch (NoSuchFieldException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static final class UnsupportedMethodTarget {
        private final MethodCallExpr call;
        private final Class<?> rawClass;
        private final boolean staticCall;

        private UnsupportedMethodTarget(MethodCallExpr call, Class<?> rawClass, boolean staticCall) {
            this.call = call;
            this.rawClass = rawClass;
            this.staticCall = staticCall;
        }
    }
}
