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

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.variable.VariableReference;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes parser-retained assertion artifacts from LLM-generated test cases.
 */
public final class LlmAssertionSanitizer {

    private static final Pattern BARE_ASSERTION_CALL_PATTERN = Pattern.compile(
            "^\\s*(?:assertAll|assertArrayEquals|assertDoesNotThrow|assertEquals|assertFalse|assertInstanceOf|"
                    + "assertIterableEquals|assertLinesMatch|assertNotEquals|assertNotNull|assertNotSame|"
                    + "assertNull|assertSame|assertThat|assertThrows|assertTimeout|"
                    + "assertTimeoutPreemptively|assertTrue|fail)\\s*\\(.*",
            Pattern.DOTALL);

    private static final Pattern QUALIFIED_ASSERTION_CALL_PATTERN = Pattern.compile(
            "^\\s*(?:[A-Za-z_$][\\w$]*\\.)*(?:Assert|Assertions|MatcherAssert)\\."
                    + "(?:assert[A-Za-z0-9_]*|fail)\\s*\\(.*",
            Pattern.DOTALL);

    private static final Pattern BARE_MOCKITO_VERIFY_CALL_PATTERN = Pattern.compile(
            "^\\s*verify(?:NoInteractions|NoMoreInteractions)?\\s*\\(.*",
            Pattern.DOTALL);

    private static final Pattern QUALIFIED_MOCKITO_VERIFY_CALL_PATTERN = Pattern.compile(
            "^\\s*(?:[A-Za-z_$][\\w$]*\\.)*(?:Mockito|BDDMockito)\\."
                    + "(?:verify(?:NoInteractions|NoMoreInteractions)?|then)\\s*\\(.*",
            Pattern.DOTALL);

    private LlmAssertionSanitizer() {
        // utility class
    }

    /**
     * Strips assertions from a test case in-place.
     *
     * @param testCase the test case to sanitize
     * @return number of removed assertion artifacts
     */
    public static int sanitize(TestCase testCase) {
        if (testCase == null) {
            return 0;
        }
        int removed = 0;

        // Remove attached EvoSuite assertion objects.
        int before = testCase.getAssertions().size();
        testCase.removeAssertions();
        removed += Math.max(0, before);

        // Remove raw assertion-only uninterpreted statements.
        for (int i = testCase.size() - 1; i >= 0; i--) {
            Statement statement = testCase.getStatement(i);
            if (!(statement instanceof UninterpretedStatement)) {
                continue;
            }
            UninterpretedStatement uninterpreted = (UninterpretedStatement) statement;
            String code = uninterpreted.getSourceCode();
            if (isAssertionOnlySnippet(code) || hasInvalidCheckedExceptionStub(uninterpreted)) {
                testCase.remove(i);
                removed++;
            }
        }
        return removed;
    }

    static boolean isAssertionOnlySnippet(String code) {
        if (code == null) {
            return false;
        }
        String trimmed = code.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.startsWith("assert ")) {
            return true;
        }
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return BARE_ASSERTION_CALL_PATTERN.matcher(trimmed).matches()
                || QUALIFIED_ASSERTION_CALL_PATTERN.matcher(trimmed).matches()
                || BARE_MOCKITO_VERIFY_CALL_PATTERN.matcher(trimmed).matches()
                || QUALIFIED_MOCKITO_VERIFY_CALL_PATTERN.matcher(trimmed).matches();
    }

    static boolean hasInvalidCheckedExceptionStub(UninterpretedStatement statement) {
        if (statement == null) {
            return false;
        }
        String source = statement.getSourceCode();
        if (source == null || (!source.contains("doThrow(") && !source.contains("thenThrow("))) {
            return false;
        }

        MethodCallExpr rootCall;
        try {
            String trimmed = source.trim();
            if (trimmed.endsWith(";")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
            Expression parsed = StaticJavaParser.parseExpression(trimmed);
            if (!(parsed instanceof MethodCallExpr)) {
                return false;
            }
            rootCall = (MethodCallExpr) parsed;
        } catch (Throwable parseFailure) {
            return false;
        }

        StubThrowInfo info = extractStubThrowInfo(rootCall, statement.getBindings());
        if (info == null || info.thrownExceptionClass == null || info.mockVariableName == null) {
            return false;
        }
        if (!isCheckedException(info.thrownExceptionClass)) {
            return false;
        }

        VariableReference mockReference = statement.getBindings().get(info.mockVariableName);
        if (mockReference == null || mockReference.getVariableClass() == null) {
            return false;
        }
        Class<?> mockClass = mockReference.getVariableClass();
        List<Method> compatible = findCompatibleMethods(
                mockClass,
                info.stubbedMethodCall.getNameAsString(),
                info.stubbedMethodCall.getArguments(),
                statement.getBindings());
        if (compatible.isEmpty()) {
            return false;
        }
        for (Method method : compatible) {
            if (declaresCheckedException(method, info.thrownExceptionClass)) {
                return false;
            }
        }
        return true;
    }

    private static StubThrowInfo extractStubThrowInfo(MethodCallExpr expression,
                                                      Map<String, VariableReference> bindings) {
        StubThrowInfo doThrowInfo = extractDoThrowInfo(expression, bindings);
        if (doThrowInfo != null) {
            return doThrowInfo;
        }
        return extractThenThrowInfo(expression, bindings);
    }

    private static StubThrowInfo extractDoThrowInfo(MethodCallExpr stubbedMethodCall,
                                                    Map<String, VariableReference> bindings) {
        if (!stubbedMethodCall.getScope().isPresent()) {
            return null;
        }
        Expression whenExpr = unwrap(stubbedMethodCall.getScope().get());
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
        Expression doThrowExpr = unwrap(whenCall.getScope().get());
        if (!(doThrowExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr doThrowCall = (MethodCallExpr) doThrowExpr;
        if (!"doThrow".equals(doThrowCall.getNameAsString())
                || doThrowCall.getArguments().isEmpty()) {
            return null;
        }
        Class<?> thrownException = resolveThrowableType(doThrowCall.getArgument(0), bindings);
        if (thrownException == null) {
            return null;
        }
        return new StubThrowInfo(mockVarName, stubbedMethodCall, thrownException);
    }

    private static StubThrowInfo extractThenThrowInfo(MethodCallExpr thenThrowCall,
                                                      Map<String, VariableReference> bindings) {
        if (!"thenThrow".equals(thenThrowCall.getNameAsString())
                || thenThrowCall.getArguments().isEmpty()
                || !thenThrowCall.getScope().isPresent()) {
            return null;
        }
        Expression whenExpr = unwrap(thenThrowCall.getScope().get());
        if (!(whenExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr whenCall = (MethodCallExpr) whenExpr;
        if (!"when".equals(whenCall.getNameAsString()) || whenCall.getArguments().size() != 1) {
            return null;
        }
        Expression invoked = unwrap(whenCall.getArgument(0));
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
        Class<?> thrownException = resolveThrowableType(thenThrowCall.getArgument(0), bindings);
        if (thrownException == null) {
            return null;
        }
        return new StubThrowInfo(mockVarName, stubbedMethodCall, thrownException);
    }

    private static List<Method> findCompatibleMethods(Class<?> mockClass,
                                                      String methodName,
                                                      List<Expression> arguments,
                                                      Map<String, VariableReference> bindings) {
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
            if (!areArgumentsCompatible(method, arguments, bindings)) {
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

    private static boolean areArgumentsCompatible(Method method,
                                                  List<Expression> arguments,
                                                  Map<String, VariableReference> bindings) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < arguments.size(); i++) {
            Class<?> formalType = getFormalType(method, parameterTypes, i);
            if (formalType == null) {
                return false;
            }
            InferredArgType inferred = inferArgumentType(arguments.get(i), bindings);
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

    private static InferredArgType inferArgumentType(Expression expression,
                                                     Map<String, VariableReference> bindings) {
        Expression unwrapped = unwrap(expression);
        if (unwrapped instanceof NullLiteralExpr) {
            return InferredArgType.nullLiteral();
        }
        if (unwrapped instanceof NameExpr) {
            String name = ((NameExpr) unwrapped).getNameAsString();
            VariableReference binding = bindings == null ? null : bindings.get(name);
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
            Class<?> resolved = resolveClassByName(((ObjectCreationExpr) unwrapped).getType().asString());
            return resolved == null ? InferredArgType.unknown() : InferredArgType.known(resolved);
        }
        if (unwrapped instanceof CastExpr) {
            Class<?> castType = resolveClassByName(((CastExpr) unwrapped).getType().asString());
            return castType == null ? InferredArgType.unknown() : InferredArgType.known(castType);
        }
        if (unwrapped instanceof MethodCallExpr) {
            InferredArgType matcherType = inferMatcherType((MethodCallExpr) unwrapped, bindings);
            if (matcherType != null) {
                return matcherType;
            }
        }
        return InferredArgType.unknown();
    }

    private static InferredArgType inferMatcherType(MethodCallExpr matcherCall,
                                                    Map<String, VariableReference> bindings) {
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
            return inferArgumentType(matcherCall.getArgument(0), bindings);
        }
        if ("isNull".equals(name)) {
            return InferredArgType.nullLiteral();
        }
        if ("nullable".equals(name)
                && matcherCall.getArguments().size() == 1
                && matcherCall.getArgument(0) instanceof ClassExpr) {
            ClassExpr classExpr = (ClassExpr) matcherCall.getArgument(0);
            Class<?> nullableType = resolveClassByName(classExpr.getType().asString());
            return nullableType == null ? InferredArgType.unknown() : InferredArgType.known(nullableType);
        }
        return InferredArgType.unknown();
    }

    private static Class<?> resolveThrowableType(Expression expression, Map<String, VariableReference> bindings) {
        Expression unwrapped = unwrap(expression);
        Class<?> candidate = null;
        if (unwrapped instanceof ObjectCreationExpr) {
            candidate = resolveClassByName(((ObjectCreationExpr) unwrapped).getType().asString());
        } else if (unwrapped instanceof ClassExpr) {
            candidate = resolveClassByName(((ClassExpr) unwrapped).getType().asString());
        } else if (unwrapped instanceof NameExpr) {
            VariableReference binding = bindings == null ? null : bindings.get(((NameExpr) unwrapped).getNameAsString());
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
        if (formal.isAssignableFrom(actual)) {
            return true;
        }
        Class<?> boxedFormal = formal.isPrimitive() ? box(formal) : formal;
        Class<?> boxedActual = actual.isPrimitive() ? box(actual) : actual;
        return boxedFormal != null && boxedActual != null && boxedFormal.isAssignableFrom(boxedActual);
    }

    private static Class<?> box(Class<?> primitive) {
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        if (primitive == char.class) {
            return Character.class;
        }
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == void.class) {
            return Void.class;
        }
        return primitive;
    }

    private static String tryExtractName(Expression expression) {
        Expression unwrapped = unwrap(expression);
        if (unwrapped instanceof NameExpr) {
            return ((NameExpr) unwrapped).getNameAsString();
        }
        return null;
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current instanceof EnclosedExpr || current instanceof CastExpr) {
            if (current instanceof EnclosedExpr) {
                current = ((EnclosedExpr) current).getInner();
            } else {
                current = ((CastExpr) current).getExpression();
            }
        }
        return current;
    }

    private static Class<?> resolveClassByName(String typeName) {
        if (typeName == null || typeName.trim().isEmpty()) {
            return null;
        }
        String normalized = typeName.trim();
        Class<?> primitive = resolvePrimitiveClass(normalized);
        if (primitive != null) {
            return primitive;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            return Class.forName(normalized, false, cl);
        } catch (ClassNotFoundException ignored) {
        }
        if (!normalized.contains(".")) {
            try {
                return Class.forName("java.lang." + normalized, false, cl);
            } catch (ClassNotFoundException ignored) {
            }
        }
        try {
            return Class.forName(normalized, false, LlmAssertionSanitizer.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static Class<?> resolvePrimitiveClass(String typeName) {
        if ("boolean".equals(typeName)) {
            return boolean.class;
        }
        if ("byte".equals(typeName)) {
            return byte.class;
        }
        if ("short".equals(typeName)) {
            return short.class;
        }
        if ("char".equals(typeName)) {
            return char.class;
        }
        if ("int".equals(typeName)) {
            return int.class;
        }
        if ("long".equals(typeName)) {
            return long.class;
        }
        if ("float".equals(typeName)) {
            return float.class;
        }
        if ("double".equals(typeName)) {
            return double.class;
        }
        if ("void".equals(typeName)) {
            return void.class;
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
