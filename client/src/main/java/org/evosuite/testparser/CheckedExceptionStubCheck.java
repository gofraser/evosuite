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

import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.apache.commons.lang3.ClassUtils;
import org.evosuite.testcase.variable.VariableReference;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds Mockito throw-stubs that make a mock throw a checked exception its stubbed method does not
 * declare, which Mockito rejects at execution time ("Checked exception is invalid for this method").
 * Handles {@code when(mock.m(...)).thenThrow(...)} and {@code doThrow(...).when(mock).m(...)}. A stub
 * whose mock, method or exception cannot be resolved is not reported.
 */
final class CheckedExceptionStubCheck {

    private final TypeResolver typeResolver;
    private final VariableScope scope;

    CheckedExceptionStubCheck(TypeResolver typeResolver, VariableScope scope) {
        this.typeResolver = typeResolver;
        this.scope = scope;
    }

    /**
     * Returns a description of the invalid stub, or null if the call is not a throw-stub that
     * names an undeclared checked exception.
     */
    String describeViolation(MethodCallExpr rootCall) {
        StubThrowInfo info = extractStubThrowInfo(rootCall);
        if (info == null || info.thrownExceptionClass == null || info.mockVariableName == null) {
            return null;
        }
        if (!isCheckedException(info.thrownExceptionClass)) {
            return null;
        }
        VariableReference mockReference = scope.resolve(info.mockVariableName);
        if (mockReference == null || mockReference.getVariableClass() == null) {
            return null;
        }
        Class<?> mockClass = mockReference.getVariableClass();
        List<Method> compatible = findCompatibleMethods(
                mockClass,
                info.stubbedMethodCall.getNameAsString(),
                info.stubbedMethodCall.getArguments());
        if (compatible.isEmpty()) {
            return null;
        }
        for (Method method : compatible) {
            if (declaresCheckedException(method, info.thrownExceptionClass)) {
                return null;
            }
        }
        Method first = compatible.get(0);
        return first.getName() + "() of " + first.getDeclaringClass().getSimpleName()
                + " cannot throw " + info.thrownExceptionClass.getName()
                + ", which it does not declare (declared: " + declaredExceptions(compatible) + ")";
    }

    private static String declaredExceptions(List<Method> methods) {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : methods) {
            for (Class<?> declared : method.getExceptionTypes()) {
                names.add(declared.getName());
            }
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private Class<?> resolveClass(String typeName) {
        if (typeName == null || typeName.trim().isEmpty()) {
            return null;
        }
        try {
            return typeResolver.resolveClass(typeName.trim());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private StubThrowInfo extractStubThrowInfo(MethodCallExpr expression) {
        StubThrowInfo doThrowInfo = extractDoThrowInfo(expression);
        if (doThrowInfo != null) {
            return doThrowInfo;
        }
        return extractThenThrowInfo(expression);
    }

    private StubThrowInfo extractDoThrowInfo(MethodCallExpr stubbedMethodCall) {
        if (!stubbedMethodCall.getScope().isPresent()) {
            return null;
        }
        Expression whenExpr = ExpressionUnwrapper.unwrapCastsAndParentheses(stubbedMethodCall.getScope().get());
        if (!(whenExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr whenCall = (MethodCallExpr) whenExpr;
        if (!"when".equals(whenCall.getNameAsString()) || whenCall.getArguments().size() != 1) {
            return null;
        }
        String mockVarName = tryExtractName(whenCall.getArgument(0));
        if (mockVarName == null) {
            return null;
        }
        if (!whenCall.getScope().isPresent()) {
            return null;
        }
        Expression doThrowExpr = ExpressionUnwrapper.unwrapCastsAndParentheses(whenCall.getScope().get());
        if (!(doThrowExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr doThrowCall = (MethodCallExpr) doThrowExpr;
        if (!"doThrow".equals(doThrowCall.getNameAsString())
                || doThrowCall.getArguments().isEmpty()) {
            return null;
        }
        Class<?> thrownException = resolveThrowableType(doThrowCall.getArgument(0));
        if (thrownException == null) {
            return null;
        }
        return new StubThrowInfo(mockVarName, stubbedMethodCall, thrownException);
    }

    private StubThrowInfo extractThenThrowInfo(MethodCallExpr thenThrowCall) {
        if (!"thenThrow".equals(thenThrowCall.getNameAsString())
                || thenThrowCall.getArguments().isEmpty()
                || !thenThrowCall.getScope().isPresent()) {
            return null;
        }
        Expression whenExpr = ExpressionUnwrapper.unwrapCastsAndParentheses(thenThrowCall.getScope().get());
        if (!(whenExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr whenCall = (MethodCallExpr) whenExpr;
        if (!"when".equals(whenCall.getNameAsString()) || whenCall.getArguments().size() != 1) {
            return null;
        }
        Expression invoked = ExpressionUnwrapper.unwrapCastsAndParentheses(whenCall.getArgument(0));
        if (!(invoked instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr stubbedMethodCall = (MethodCallExpr) invoked;
        if (!stubbedMethodCall.getScope().isPresent()) {
            return null;
        }
        String mockVarName = tryExtractName(stubbedMethodCall.getScope().get());
        if (mockVarName == null) {
            return null;
        }
        Class<?> thrownException = resolveThrowableType(thenThrowCall.getArgument(0));
        if (thrownException == null) {
            return null;
        }
        return new StubThrowInfo(mockVarName, stubbedMethodCall, thrownException);
    }

    private List<Method> findCompatibleMethods(Class<?> mockClass,
                                                      String methodName,
                                                      List<Expression> arguments) {
        if (mockClass == null || methodName == null) {
            return Collections.emptyList();
        }
        List<Method> compatible = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Method method : getAllMethods(mockClass)) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            if (!isArityCompatible(method, arguments.size())) {
                continue;
            }
            if (!areArgumentsCompatible(method, arguments)) {
                continue;
            }
            String key = method.toGenericString();
            if (seen.add(key)) {
                compatible.add(method);
            }
        }
        return compatible;
    }

    private static List<Method> getAllMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        Collections.addAll(methods, type.getMethods());
        Collections.addAll(methods, type.getDeclaredMethods());
        return methods;
    }

    private static boolean isArityCompatible(Method method, int argCount) {
        int params = method.getParameterCount();
        if (!method.isVarArgs()) {
            return params == argCount;
        }
        return argCount >= params - 1;
    }

    private boolean areArgumentsCompatible(Method method,
                                                  List<Expression> arguments) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < arguments.size(); i++) {
            Class<?> formalType = getFormalType(method, parameterTypes, i);
            if (formalType == null) {
                return false;
            }
            InferredArgType inferred = inferArgumentType(arguments.get(i));
            if (inferred.kind == InferredArgKind.UNKNOWN) {
                continue;
            }
            if (inferred.kind == InferredArgKind.NULL_LITERAL) {
                if (formalType.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (inferred.type == null) {
                continue;
            }
            if (!isAssignable(formalType, inferred.type)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> getFormalType(Method method, Class<?>[] parameterTypes, int argumentIndex) {
        if (!method.isVarArgs()) {
            return argumentIndex < parameterTypes.length ? parameterTypes[argumentIndex] : null;
        }
        int fixedCount = parameterTypes.length - 1;
        if (argumentIndex < fixedCount) {
            return parameterTypes[argumentIndex];
        }
        Class<?> varArgArray = parameterTypes[fixedCount];
        return varArgArray == null ? null : varArgArray.getComponentType();
    }

    private InferredArgType inferArgumentType(Expression expression) {
        Expression unwrapped = ExpressionUnwrapper.unwrapCastsAndParentheses(expression);
        if (unwrapped instanceof NullLiteralExpr) {
            return InferredArgType.nullLiteral();
        }
        if (unwrapped instanceof NameExpr) {
            String name = ((NameExpr) unwrapped).getNameAsString();
            VariableReference binding = scope.resolve(name);
            if (binding != null && binding.getVariableClass() != null) {
                return InferredArgType.known(binding.getVariableClass());
            }
            return InferredArgType.unknown();
        }
        if (unwrapped.isStringLiteralExpr()) {
            return InferredArgType.known(String.class);
        }
        if (unwrapped.isIntegerLiteralExpr()) {
            return InferredArgType.known(int.class);
        }
        if (unwrapped.isLongLiteralExpr()) {
            return InferredArgType.known(long.class);
        }
        if (unwrapped.isDoubleLiteralExpr()) {
            return InferredArgType.known(double.class);
        }
        if (unwrapped.isBooleanLiteralExpr()) {
            return InferredArgType.known(boolean.class);
        }
        if (unwrapped.isCharLiteralExpr()) {
            return InferredArgType.known(char.class);
        }
        if (unwrapped instanceof ObjectCreationExpr) {
            Class<?> resolved = resolveClass(((ObjectCreationExpr) unwrapped).getType().asString());
            return resolved == null ? InferredArgType.unknown() : InferredArgType.known(resolved);
        }
        if (unwrapped instanceof CastExpr) {
            Class<?> castType = resolveClass(((CastExpr) unwrapped).getType().asString());
            return castType == null ? InferredArgType.unknown() : InferredArgType.known(castType);
        }
        if (unwrapped instanceof MethodCallExpr) {
            InferredArgType matcherType = inferMatcherType((MethodCallExpr) unwrapped);
            if (matcherType != null) {
                return matcherType;
            }
        }
        return InferredArgType.unknown();
    }

    private InferredArgType inferMatcherType(MethodCallExpr matcherCall) {
        String name = matcherCall.getNameAsString();
        if ("any".equals(name) || "argThat".equals(name)) {
            return InferredArgType.unknown();
        }
        if ("anyString".equals(name)) {
            return InferredArgType.known(String.class);
        }
        if ("anyInt".equals(name)) {
            return InferredArgType.known(int.class);
        }
        if ("anyLong".equals(name)) {
            return InferredArgType.known(long.class);
        }
        if ("anyBoolean".equals(name)) {
            return InferredArgType.known(boolean.class);
        }
        if ("anyDouble".equals(name)) {
            return InferredArgType.known(double.class);
        }
        if ("anyFloat".equals(name)) {
            return InferredArgType.known(float.class);
        }
        if ("anyByte".equals(name)) {
            return InferredArgType.known(byte.class);
        }
        if ("anyShort".equals(name)) {
            return InferredArgType.known(short.class);
        }
        if ("anyChar".equals(name)) {
            return InferredArgType.known(char.class);
        }
        if ("anyList".equals(name)) {
            return InferredArgType.known(List.class);
        }
        if ("eq".equals(name) || "same".equals(name)) {
            if (matcherCall.getArguments().isEmpty()) {
                return InferredArgType.unknown();
            }
            return inferArgumentType(matcherCall.getArgument(0));
        }
        if ("isNull".equals(name)) {
            return InferredArgType.nullLiteral();
        }
        if ("nullable".equals(name)
                && matcherCall.getArguments().size() == 1
                && matcherCall.getArgument(0) instanceof ClassExpr) {
            ClassExpr classExpr = (ClassExpr) matcherCall.getArgument(0);
            Class<?> nullableType = resolveClass(classExpr.getType().asString());
            return nullableType == null ? InferredArgType.unknown() : InferredArgType.known(nullableType);
        }
        return InferredArgType.unknown();
    }

    private Class<?> resolveThrowableType(Expression expression) {
        Expression unwrapped = ExpressionUnwrapper.unwrapCastsAndParentheses(expression);
        Class<?> candidate = null;
        if (unwrapped instanceof ObjectCreationExpr) {
            candidate = resolveClass(((ObjectCreationExpr) unwrapped).getType().asString());
        } else if (unwrapped instanceof ClassExpr) {
            candidate = resolveClass(((ClassExpr) unwrapped).getType().asString());
        } else if (unwrapped instanceof NameExpr) {
            VariableReference binding =
                    scope.resolve(((NameExpr) unwrapped).getNameAsString());
            candidate = binding == null ? null : binding.getVariableClass();
        }
        if (candidate == null || !Throwable.class.isAssignableFrom(candidate)) {
            return null;
        }
        return candidate;
    }

    private static boolean isCheckedException(Class<?> throwableType) {
        return Throwable.class.isAssignableFrom(throwableType)
                && !RuntimeException.class.isAssignableFrom(throwableType)
                && !Error.class.isAssignableFrom(throwableType);
    }

    private static boolean declaresCheckedException(Method method, Class<?> thrownType) {
        if (method == null || thrownType == null) {
            return false;
        }
        for (Class<?> declared : method.getExceptionTypes()) {
            if (declared.isAssignableFrom(thrownType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAssignable(Class<?> formal, Class<?> actual) {
        return formal.isAssignableFrom(actual)
                || ClassUtils.primitiveToWrapper(formal).isAssignableFrom(ClassUtils.primitiveToWrapper(actual));
    }

    private static String tryExtractName(Expression expression) {
        Expression unwrapped = ExpressionUnwrapper.unwrapCastsAndParentheses(expression);
        if (unwrapped instanceof NameExpr) {
            return ((NameExpr) unwrapped).getNameAsString();
        }
        return null;
    }

    private static final class StubThrowInfo {
        private final String mockVariableName;
        private final MethodCallExpr stubbedMethodCall;
        private final Class<?> thrownExceptionClass;

        private StubThrowInfo(String mockVariableName,
                              MethodCallExpr stubbedMethodCall,
                              Class<?> thrownExceptionClass) {
            this.mockVariableName = mockVariableName;
            this.stubbedMethodCall = stubbedMethodCall;
            this.thrownExceptionClass = thrownExceptionClass;
        }
    }

    private enum InferredArgKind {
        KNOWN,
        NULL_LITERAL,
        UNKNOWN
    }

    private static final class InferredArgType {
        private final InferredArgKind kind;
        private final Class<?> type;

        private InferredArgType(InferredArgKind kind, Class<?> type) {
            this.kind = kind;
            this.type = type;
        }

        private static InferredArgType known(Class<?> type) {
            return new InferredArgType(InferredArgKind.KNOWN, type);
        }

        private static InferredArgType nullLiteral() {
            return new InferredArgType(InferredArgKind.NULL_LITERAL, null);
        }

        private static InferredArgType unknown() {
            return new InferredArgType(InferredArgKind.UNKNOWN, null);
        }
    }
}
