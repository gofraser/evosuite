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

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.*;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.*;
import org.evosuite.testcase.statements.reflection.PrivateFieldStatement;
import org.evosuite.testcase.statements.reflection.PrivateMethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Rewrites the legacy reflection helper calls that LLM-written tests use ({@code setField},
 * {@code setStaticField}, {@code invokeFoo(target, ...)}) into private-field and private-method
 * statements. EvoSuite parses test bodies only, so the helper declarations themselves are dropped
 * and the calls would not compile otherwise. Used by {@link StatementParser} in best-effort mode.
 */
final class LegacyHelperRewriter {

    private final StatementParser parser;
    private final DefaultTestCase testCase;
    private final TypeResolver typeResolver;
    private final VariableScope scope;
    private final OverloadResolver overloadResolver;

    LegacyHelperRewriter(StatementParser parser, DefaultTestCase testCase, TypeResolver typeResolver,
                         VariableScope scope, OverloadResolver overloadResolver) {
        this.parser = parser;
        this.testCase = testCase;
        this.typeResolver = typeResolver;
        this.scope = scope;
        this.overloadResolver = overloadResolver;
    }

    /**
     * LLMs sometimes emit helper-wrapper objects that are not present in the final
     * parsed class body, e.g. {@code new MethodAccess(target).invokeGetBuilder(arg)}.
     * Treat these like legacy invoke helpers by rewriting to reflective private-method
     * invocation on the wrapped target object.
     */
    boolean tryHandleLegacyScopedInvokeHelperCall(MethodCallExpr expr, ObjectCreationExpr scopeCtor) {
        String helperName = expr.getNameAsString();
        if (!helperName.startsWith("invoke") || helperName.length() <= "invoke".length()) {
            return false;
        }
        if (scopeCtor.getArguments().isEmpty()) {
            return false;
        }

        // Only rewrite when the helper type cannot be resolved; if it is a real SUT/JDK type,
        // let normal method-call handling proceed.
        try {
            typeResolver.resolveType(scopeCtor.getType());
            return false;
        } catch (Exception ignored) {
            // Unresolved helper type: proceed with legacy rewrite path.
        }

        if (!canRewriteLegacyInvokeHelperCall(
                helperName, scopeCtor.getArgument(0), expr.getArguments(), false)) {
            return false;
        }

        NodeList<Expression> syntheticArgs = new NodeList<>();
        syntheticArgs.add(scopeCtor.getArgument(0));
        syntheticArgs.addAll(expr.getArguments());
        MethodCallExpr syntheticInvokeHelperCall =
                StatementParser.copySyntheticRange(new MethodCallExpr(null, helperName, syntheticArgs), expr);
        return tryHandleLegacyInvokeHelperCall(syntheticInvokeHelperCall);
    }

    /**
     * LLMs sometimes emit local helper methods like setField()/setStaticField() and call them
     * from tests. EvoSuite parses test bodies only, so helper method declarations are dropped.
     * Convert these helper calls directly into PrivateFieldStatement so generated tests compile.
     */
    boolean tryHandleLegacyFieldHelperCall(MethodCallExpr expr) {
        String name = expr.getNameAsString();
        if (!"setField".equals(name) && !"setStaticField".equals(name)) {
            return false;
        }
        if (expr.getArguments().size() != 3) {
            return false;
        }

        String fieldName = resolveFieldNameLiteral(expr.getArguments().get(1));
        if (fieldName == null || fieldName.isEmpty()) {
            parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                    "Could not resolve legacy helper field name literal for call: " + expr);
            return false;
        }

        try {
            if ("setField".equals(name)) {
                VariableReference owner = parser.handleExpression(
                        parser.nextSyntheticName("__legacy_owner"), expr.getArguments().get(0), Object.class);
                if (owner == null) {
                    parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                            "Could not resolve receiver for legacy setField call: " + expr);
                    return false;
                }
                Class<?> ownerClass = owner.getVariableClass();
                Type fieldType = resolveFieldType(ownerClass, fieldName);
                VariableReference value = parser.handleExpression(
                        parser.nextSyntheticName("__legacy_value"), expr.getArguments().get(2),
                        fieldType != null ? fieldType : Object.class);
                if (value == null) {
                    parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                            "Could not resolve value for legacy setField call: " + expr);
                    return false;
                }
                parser.addStatement(new PrivateFieldStatement(testCase, ownerClass, fieldName, owner, value));
                parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                        "Rewrote legacy helper call setField(...) to reflective field write");
                return true;
            }

            Class<?> ownerClass = resolveOwnerClassForLegacyStaticField(expr.getArguments().get(0));
            if (ownerClass == null) {
                parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                        "Could not resolve class for legacy setStaticField call: " + expr);
                return false;
            }
            Type fieldType = resolveFieldType(ownerClass, fieldName);
            VariableReference value = parser.handleExpression(
                    parser.nextSyntheticName("__legacy_value"), expr.getArguments().get(2),
                    fieldType != null ? fieldType : Object.class);
            if (value == null) {
                parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                        "Could not resolve value for legacy setStaticField call: " + expr);
                return false;
            }
            VariableReference nullOwner = parser.addStatement(new NullStatement(testCase, ownerClass));
            parser.addStatement(new PrivateFieldStatement(testCase, ownerClass, fieldName, nullOwner, value));
            parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                    "Rewrote legacy helper call setStaticField(...) to reflective field write");
            return true;
        } catch (Exception e) {
            parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                    "Failed to rewrite legacy helper call '" + name + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * LLMs sometimes emit helper wrappers such as invokeGetBuilder(target, arg),
     * where the helper itself is dropped because only method bodies are parsed.
     * Rewrite the call directly to a reflective private-method invocation.
     */
    boolean tryHandleLegacyInvokeHelperCall(MethodCallExpr expr) {
        String helperName = expr.getNameAsString();
        if (!helperName.startsWith("invoke") || helperName.length() <= "invoke".length()) {
            return false;
        }
        if (expr.getArguments().isEmpty()) {
            return false;
        }

        String reflectedMethodName = toLegacyInvokeReflectedMethodName(helperName);
        if (reflectedMethodName == null) {
            return false;
        }
        if (!canRewriteLegacyInvokeHelperCall(
                helperName,
                expr.getArguments().get(0),
                expr.getArguments().subList(1, expr.getArguments().size()),
                true)) {
            return false;
        }

        try {
            VariableReference callee = null;
            Class<?> ownerClass = null;
            Expression firstArg = expr.getArguments().get(0);

            // Support invokeX(Target.class, ...) static wrappers too.
            if (firstArg instanceof ClassExpr) {
                Type ownerType = typeResolver.resolveType(((ClassExpr) firstArg).getType());
                ownerClass = StatementParser.getRawClass(ownerType);
            } else {
                callee = parser.handleExpression(parser.nextSyntheticName("__legacy_invoke_owner"), firstArg, Object.class);
                if (callee == null) {
                    parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                            "Could not resolve receiver for legacy helper call: " + expr);
                    return false;
                }
                ownerClass = callee.getVariableClass();
            }

            if (ownerClass == null) {
                parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                        "Could not resolve owner class for legacy helper call: " + expr);
                return false;
            }

            List<Expression> helperArgs = expr.getArguments().subList(1, expr.getArguments().size());
            List<VariableReference> argRefs = parser.resolveArguments(helperArgs, null, null);
            Class<?>[] argTypes = parser.getArgTypes(argRefs);

            Method reflectedMethod = overloadResolver.resolveMethod(ownerClass, reflectedMethodName, argTypes);
            parser.retypeNullArguments(argRefs, reflectedMethod.getParameterTypes(), reflectedMethod.isVarArgs());
            StatementParser.DiagnosticMessage mismatch = parser.validateArgumentTypes(argRefs, reflectedMethod.getParameterTypes(),
                    reflectedMethod.getGenericParameterTypes(), expr, reflectedMethod.isVarArgs());
            if (mismatch != null) {
                parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                        "Could not rewrite legacy helper call " + helperName
                                + " due to argument mismatch: " + mismatch.render());
                return false;
            }
            argRefs = parser.normalizeVarArgsArguments(
                    argRefs, reflectedMethod.getParameterTypes(), reflectedMethod.isVarArgs());

            Class<?> reflectedOwner = reflectedMethod.getDeclaringClass();
            boolean isStatic = Modifier.isStatic(reflectedMethod.getModifiers());
            if (isStatic) {
                callee = null;
            }
            parser.addStatement(new PrivateMethodStatement(
                    testCase,
                    reflectedOwner,
                    reflectedMethod,
                    callee,
                    argRefs,
                    isStatic));
            parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                    "Rewrote legacy helper call " + helperName + "(...) to reflective method call "
                            + reflectedMethodName + "(...)");
            return true;
        } catch (Exception e) {
            parser.addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                    "Failed to rewrite legacy helper call '" + helperName + "': " + e.getMessage());
            return false;
        }
    }

    private boolean canRewriteLegacyInvokeHelperCall(String helperName,
                                                     Expression receiverExpr,
                                                     List<Expression> helperArgs,
                                                     boolean checkStaticImport) {
        String reflectedMethodName = toLegacyInvokeReflectedMethodName(helperName);
        if (reflectedMethodName == null) {
            return false;
        }

        Class<?> ownerClass = inferLegacyInvokeOwnerClass(receiverExpr);
        if (ownerClass == null) {
            return false;
        }

        Class<?>[] helperArgTypes = inferArgumentTypes(helperArgs);
        Method reflectedMethod = findLegacyInvokeRewriteTarget(ownerClass, reflectedMethodName);
        if (!isLegacyInvokeRewriteTarget(reflectedMethod)) {
            return false;
        }

        boolean requireStaticOriginal = receiverExpr instanceof ClassExpr;
        if (resolvesAccessibleReceiverMethod(ownerClass, helperName, helperArgTypes, requireStaticOriginal)) {
            return false;
        }

        if (checkStaticImport) {
            Class<?>[] fullArgTypes = inferArgumentTypesWithReceiver(receiverExpr, helperArgs);
            if (resolvesAccessibleStaticImportMethod(helperName, fullArgTypes)) {
                return false;
            }
        }

        return true;
    }

    private String toLegacyInvokeReflectedMethodName(String helperName) {
        if (helperName == null || !helperName.startsWith("invoke") || helperName.length() <= "invoke".length()) {
            return null;
        }
        String reflectedMethodName = helperName.substring("invoke".length());
        if (reflectedMethodName.isEmpty() || !Character.isUpperCase(reflectedMethodName.charAt(0))) {
            return null;
        }
        return Character.toLowerCase(reflectedMethodName.charAt(0)) + reflectedMethodName.substring(1);
    }

    private Class<?> inferLegacyInvokeOwnerClass(Expression receiverExpr) {
        try {
            if (receiverExpr instanceof ClassExpr) {
                Type ownerType = typeResolver.resolveType(((ClassExpr) receiverExpr).getType());
                return StatementParser.getRawClass(ownerType);
            }
            return StatementParser.getRawClass(parser.inferTypeForUntypedArgument(receiverExpr));
        } catch (Exception e) {
            return null;
        }
    }

    private Class<?>[] inferArgumentTypes(List<Expression> expressions) {
        Class<?>[] argTypes = new Class<?>[expressions.size()];
        for (int i = 0; i < expressions.size(); i++) {
            argTypes[i] = StatementParser.getRawClass(parser.inferTypeForUntypedArgument(expressions.get(i)));
        }
        return argTypes;
    }

    private Class<?>[] inferArgumentTypesWithReceiver(Expression receiverExpr, List<Expression> helperArgs) {
        Class<?>[] argTypes = new Class<?>[helperArgs.size() + 1];
        argTypes[0] = StatementParser.getRawClass(parser.inferTypeForUntypedArgument(receiverExpr));
        for (int i = 0; i < helperArgs.size(); i++) {
            argTypes[i + 1] = StatementParser.getRawClass(parser.inferTypeForUntypedArgument(helperArgs.get(i)));
        }
        return argTypes;
    }

    private Method tryResolveMethod(Class<?> clazz, String name, Class<?>[] argTypes) {
        if (clazz == null || name == null) {
            return null;
        }
        try {
            return overloadResolver.resolveMethod(clazz, name, argTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private Method findLegacyInvokeRewriteTarget(Class<?> ownerClass, String reflectedMethodName) {
        Method best = OverloadResolver.findByNameLoose(ownerClass, reflectedMethodName);
        if (best == null) {
            return null;
        }
        for (Method candidate : ownerClass.getDeclaredMethods()) {
            if (isPreferredLegacyInvokeRewriteTarget(candidate, reflectedMethodName, best)) {
                best = candidate;
            }
        }
        for (Method candidate : ownerClass.getMethods()) {
            if (isPreferredLegacyInvokeRewriteTarget(candidate, reflectedMethodName, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isLegacyInvokeRewriteTarget(Method method) {
        if (method == null) {
            return false;
        }
        int modifiers = method.getModifiers();
        return Modifier.isPrivate(modifiers) || Modifier.isProtected(modifiers);
    }

    private boolean isPreferredLegacyInvokeRewriteTarget(Method candidate,
                                                         String reflectedMethodName,
                                                         Method currentBest) {
        if (candidate == null || !candidate.getName().equals(reflectedMethodName)) {
            return false;
        }
        return legacyInvokeVisibilityRank(candidate) < legacyInvokeVisibilityRank(currentBest);
    }

    private int legacyInvokeVisibilityRank(Method method) {
        if (method == null) {
            return Integer.MAX_VALUE;
        }
        int modifiers = method.getModifiers();
        if (Modifier.isPrivate(modifiers)) {
            return 0;
        }
        if (Modifier.isProtected(modifiers)) {
            return 1;
        }
        if (!Modifier.isPublic(modifiers)) {
            return 2;
        }
        return 3;
    }

    private boolean resolvesAccessibleReceiverMethod(Class<?> ownerClass,
                                                     String methodName,
                                                     Class<?>[] argTypes,
                                                     boolean requireStatic) {
        Method method = tryResolveMethod(ownerClass, methodName, argTypes);
        if (method == null || !parser.isAccessibleMember(method)) {
            return false;
        }
        return requireStatic == Modifier.isStatic(method.getModifiers());
    }

    private boolean resolvesAccessibleStaticImportMethod(String methodName, Class<?>[] argTypes) {
        try {
            String staticClass = typeResolver.resolveStaticImportClass(methodName);
            if (staticClass == null) {
                return false;
            }
            Class<?> ownerClass = typeResolver.resolveClass(staticClass);
            Method method = tryResolveMethod(ownerClass, methodName, argTypes);
            return method != null && parser.isAccessibleMember(method) && Modifier.isStatic(method.getModifiers());
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveFieldNameLiteral(Expression expr) {
        if (expr instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) expr).getValue();
        }
        if (expr instanceof NameExpr) {
            VariableReference ref = scope.resolve(((NameExpr) expr).getNameAsString());
            Statement st = parser.getStatementForReference(ref);
            if (st instanceof StringPrimitiveStatement) {
                return ((StringPrimitiveStatement) st).getValue();
            }
        }
        return null;
    }

    private Class<?> resolveOwnerClassForLegacyStaticField(Expression classExpr) {
        try {
            if (classExpr instanceof ClassExpr) {
                Type t = typeResolver.resolveType(((ClassExpr) classExpr).getType());
                return StatementParser.getRawClass(t);
            }
            if (classExpr instanceof NameExpr) {
                VariableReference ref = scope.resolve(((NameExpr) classExpr).getNameAsString());
                Statement st = parser.getStatementForReference(ref);
                if (st instanceof ClassPrimitiveStatement) {
                    return ((ClassPrimitiveStatement) st).getValue();
                }
            }
        } catch (Exception ignored) {
            // best-effort only
        }
        return null;
    }

    private Type resolveFieldType(Class<?> ownerClass, String fieldName) {
        if (ownerClass == null || fieldName == null) {
            return null;
        }
        try {
            try {
                return ownerClass.getField(fieldName).getGenericType();
            } catch (NoSuchFieldException e) {
                return ownerClass.getDeclaredField(fieldName).getGenericType();
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
