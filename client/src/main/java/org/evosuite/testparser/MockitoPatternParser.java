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
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.StaticJavaParser;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.mock.OverrideMock;
import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.FunctionalMockForAbstractClassStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class MockitoPatternParser {

    private static final Logger logger = LoggerFactory.getLogger(MockitoPatternParser.class);
    private static final Pattern MOCK_TYPE_TOKEN = Pattern.compile("\\bMock([A-Z][A-Za-z0-9_$]*)\\b");

    private final DefaultTestCase testCase;
    private final TypeResolver typeResolver;
    private final VariableScope scope;
    private final ParseResult result;
    private final StatementParser parser;
    private final Map<String, CapturedWhenStubbingContext> capturedWhenStubbings = new LinkedHashMap<>();

    MockitoPatternParser(DefaultTestCase tc,
                         TypeResolver tr,
                         VariableScope vs,
                         ParseResult pr,
                         StatementParser parser) {
        this.testCase = tc;
        this.typeResolver = tr;
        this.scope = vs;
        this.result = pr;
        this.parser = parser;
    }

    int handleVariableDeclarationWithLookahead(
            VariableDeclarationExpr varDeclExpr,
            List<com.github.javaparser.ast.stmt.Statement> allStatements,
            int currentIndex) {
        if (allStatements == null) {
            return 0;
        }

        if (varDeclExpr.getVariables().size() != 1) {
            return 0;
        }
        VariableDeclarator declarator = varDeclExpr.getVariables().get(0);
        if (!declarator.getInitializer().isPresent()) {
            return 0;
        }

        Expression initializer = declarator.getInitializer().get();
        int capturedWhenStubbing = handleCapturedWhenStubbingDeclaration(
                declarator, initializer, allStatements, currentIndex);
        if (capturedWhenStubbing > 0) {
            return capturedWhenStubbing;
        }
        if (!isMockCreation(initializer)) {
            return 0;
        }

        MethodCallExpr mockCall = (MethodCallExpr) initializer;
        String varName = declarator.getNameAsString();

        Class<?> mockTargetClass = extractMockTargetClass(mockCall);
        if (mockTargetClass == null) {
            return 0;
        }

        MockVariant variant = detectMockVariant(mockCall);
        GenericClass<?> targetGenericClass = GenericClassFactory.get(mockTargetClass);
        try {
            if (variant == MockVariant.CALLS_REAL_METHODS) {
                if (!FunctionalMockStatement.canBeFunctionalMockedIncludingSUT(mockTargetClass)) {
                    return 0;
                }
            } else if (!FunctionalMockStatement.canBeFunctionalMocked(mockTargetClass)) {
                return 0;
            }
        } catch (Exception e) {
            return 0;
        }

        FunctionalMockStatement mockStmt;
        try {
            if (variant == MockVariant.CALLS_REAL_METHODS) {
                mockStmt = new FunctionalMockForAbstractClassStatement(
                        testCase, mockTargetClass, targetGenericClass);
            } else {
                mockStmt = new FunctionalMockStatement(
                        testCase, mockTargetClass, targetGenericClass);
            }
        } catch (IllegalArgumentException e) {
            logger.debug("Cannot create FunctionalMockStatement for {}: {}",
                    mockTargetClass.getName(), e.getMessage());
            return 0;
        }

        int stubbingsConsumed = collectAndApplyStubbings(
                mockStmt, varName, mockTargetClass, targetGenericClass,
                allStatements, currentIndex + 1);

        VariableReference varRef = parser.addStatement(mockStmt);
        scope.register(varName, varRef, targetGenericClass);

        return 1 + stubbingsConsumed;
    }

    VariableReference tryHandleLlmMockitoMockCall(MethodCallExpr expr,
                                                  String methodName,
                                                  Class<?> targetClass,
                                                  boolean staticCall,
                                                  List<VariableReference> argRefs) {
        if (!parser.isMarkParsedFromLlm()
                || !staticCall
                || !"mock".equals(methodName)
                || !isMockitoClass(targetClass)) {
            return null;
        }
        if (expr.getArguments().isEmpty()) {
            return null;
        }

        Class<?> mockTargetClass = resolveMockitoMockTargetClass(expr.getArgument(0), argRefs);
        if (mockTargetClass == null) {
            return null;
        }

        MockVariant variant = detectMockVariant(expr);
        GenericClass<?> targetGenericClass = GenericClassFactory.get(mockTargetClass);
        try {
            if (variant == MockVariant.CALLS_REAL_METHODS) {
                if (!FunctionalMockStatement.canBeFunctionalMockedIncludingSUT(mockTargetClass)) {
                    return null;
                }
                return parser.addStatement(new FunctionalMockForAbstractClassStatement(
                        testCase, mockTargetClass, targetGenericClass));
            }

            if (!FunctionalMockStatement.canBeFunctionalMocked(mockTargetClass)) {
                return null;
            }
            return parser.addStatement(new FunctionalMockStatement(
                    testCase, mockTargetClass, targetGenericClass));
        } catch (IllegalArgumentException e) {
            logger.debug("Cannot normalize Mockito.mock({}) into FunctionalMockStatement: {}",
                    mockTargetClass.getName(), e.getMessage());
            return null;
        }
    }

    VariableReference tryHandleLlmMockitoMockCallBeforeMethodResolution(MethodCallExpr expr,
                                                                         String methodName,
                                                                         Class<?> targetClass,
                                                                         boolean staticCall) {
        if (!parser.isMarkParsedFromLlm()
                || !"mock".equals(methodName)
                || expr.getArguments().isEmpty()) {
            return null;
        }

        boolean looksLikeMockitoStaticMock =
                (staticCall && isMockitoClass(targetClass))
                        || isLikelyMockitoMockScope(expr.getScope().orElse(null))
                        || (parser.isMarkParsedFromLlm() && !expr.getScope().isPresent()); // LLM often omits 'Mockito.'
        if (!looksLikeMockitoStaticMock) {
            return null;
        }

        Class<?> mockTargetClass = extractMockTargetClass(expr.getArgument(0));
        if (mockTargetClass == null) {
            return null;
        }

        MockVariant variant = detectMockVariant(expr);
        GenericClass<?> targetGenericClass = GenericClassFactory.get(mockTargetClass);
        try {
            if (variant == MockVariant.CALLS_REAL_METHODS) {
                if (!FunctionalMockStatement.canBeFunctionalMockedIncludingSUT(mockTargetClass)) {
                    return null;
                }
                return parser.addStatement(new FunctionalMockForAbstractClassStatement(
                        testCase, mockTargetClass, targetGenericClass));
            }

            if (!FunctionalMockStatement.canBeFunctionalMocked(mockTargetClass)) {
                return null;
            }
            return parser.addStatement(new FunctionalMockStatement(
                    testCase, mockTargetClass, targetGenericClass));
        } catch (IllegalArgumentException e) {
            logger.debug("Cannot normalize pre-resolution Mockito.mock({}) into FunctionalMockStatement: {}",
                    mockTargetClass.getName(), e.getMessage());
            return null;
        }
    }

    VariableReference tryNormalizeAnonymousInterfaceCreationToMock(ObjectCreationExpr expr, Type declaredType) {
        Type targetType = declaredType;
        if (targetType == null || targetType == Object.class) {
            try {
                targetType = typeResolver.resolveType(expr.getType());
            } catch (ClassNotFoundException e) {
                Type democked = tryResolveDemockedDeclaredType(expr.getType());
                targetType = democked != null ? democked : Object.class;
            }
        }

        Class<?> rawTargetClass = parser.getRawClass(targetType);
        if (rawTargetClass == null || !rawTargetClass.isInterface()) {
            return null;
        }
        if (!FunctionalMockStatement.canBeFunctionalMocked(rawTargetClass)) {
            return null;
        }

        GenericClass<?> targetGenericClass = targetType instanceof java.lang.reflect.ParameterizedType
                ? GenericClassFactory.get(targetType)
                : GenericClassFactory.get(rawTargetClass);
        parser.addWarning(expr,
                "Normalized anonymous interface implementation to FunctionalMockStatement; discarded anonymous body");
        return parser.addStatement(new FunctionalMockStatement(
                testCase, rawTargetClass, targetGenericClass));
    }

    Class<?> chooseOverrideMockConstructorTarget(Class<?> rawClass) {
        Class<?> mockClass = getCompatibleOverrideMockClass(rawClass);
        return mockClass != null ? mockClass : rawClass;
    }

    Class<?> chooseOverrideMockStaticMethodTarget(Class<?> rawClass,
                                                  String methodName,
                                                  Class<?>[] argTypes) {
        if (!parser.isMarkParsedFromLlm() || rawClass == null) {
            return rawClass;
        }
        Class<?> mockClass = getCompatibleStaticMethodMockClass(rawClass);
        if (mockClass == null) {
            return rawClass;
        }
        try {
            Method method = parser.getOverloadResolver().resolveMethod(mockClass, methodName, argTypes);
            if (!Modifier.isStatic(method.getModifiers())) {
                return rawClass;
            }
            return mockClass;
        } catch (NoSuchMethodException ignored) {
            return rawClass;
        }
    }

    Class<?> resolveClassWithLlmMockFallback(String typeName) throws ClassNotFoundException {
        try {
            return typeResolver.resolveClass(typeName);
        } catch (ClassNotFoundException e) {
            if (!parser.isMarkParsedFromLlm()) {
                throw e;
            }
            String democked = demockTypeTokens(typeName);
            if (!democked.equals(typeName)) {
                return typeResolver.resolveClass(democked);
            }
            throw e;
        }
    }

    Type tryResolveDemockedDeclaredType(com.github.javaparser.ast.type.Type originalType) {
        try {
            String typeText = originalType.toString();
            String democked = demockTypeTokens(typeText);
            if (democked.equals(typeText)) {
                return null;
            }
            com.github.javaparser.ast.type.Type demockedType = parser.parseType(democked, originalType);
            if (demockedType == null) {
                return null;
            }
            return typeResolver.resolveType(demockedType);
        } catch (Exception ignored) {
            return null;
        }
    }

    boolean tryHandleCapturedWhenStubbingTerminalCall(MethodCallExpr methodCall) {
        if (!methodCall.getScope().isPresent() || !(methodCall.getScope().get() instanceof NameExpr)) {
            return false;
        }

        String aliasName = ((NameExpr) methodCall.getScope().get()).getNameAsString();
        CapturedWhenStubbingContext context = capturedWhenStubbings.get(aliasName);
        if (context == null) {
            return false;
        }

        String methodName = methodCall.getNameAsString();
        if ("thenReturn".equals(methodName)) {
            MethodCallExpr reconstructed = new MethodCallExpr(
                    StatementParser.copySyntheticRange(context.whenCall.clone(), context.whenCall),
                    "thenReturn",
                    cloneArguments(methodCall));
            StatementParser.copySyntheticRange(reconstructed, methodCall);
            StubbingInfo info = parseWhenThenReturnPattern(
                    reconstructed,
                    context.mockVarName,
                    context.targetClass,
                    context.targetGenericClass);
            if (info == null) {
                return false;
            }
            // If the terminal call was syntactically matched but semantically invalid
            // (eg unresolved method, void return, etc.), keep the captured alias pending
            // so flushCapturedWhenStubbingDiagnostics() can report it as stranded.
            if (!info.applyToMockStatement) {
                return false;
            }
            relocateMockAfterStubbingValuesIfNeeded(context.mockRef, info.returnValues);
            List<VariableReference> orderedReturnValues =
                    ensureStubbingValuesAvailableBeforeMock(context.mockRef, info.returnValues, methodCall);
            context.mockStatement.addMethodStubbing(info.descriptor, orderedReturnValues);
            capturedWhenStubbings.remove(aliasName);
            return true;
        }

        if (parser.isMarkParsedFromLlm() && "thenThrow".equals(methodName)) {
            MethodCallExpr reconstructed = new MethodCallExpr(
                    StatementParser.copySyntheticRange(context.whenCall.clone(), context.whenCall),
                    "thenThrow",
                    cloneArguments(methodCall));
            StatementParser.copySyntheticRange(reconstructed, methodCall);
            parser.addStatement(parser.createUninterpretedStatement(
                    reconstructed, reconstructed.toString() + ";"));
            capturedWhenStubbings.remove(aliasName);
            return true;
        }

        return false;
    }

    boolean tryHandleStandaloneStubbingCall(MethodCallExpr methodCall) {
        String mockVarName = extractMockVarFromStubbingPattern(methodCall);
        if (mockVarName == null) {
            return false;
        }

        VariableReference mockRef = scope.resolve(mockVarName);
        if (mockRef == null) {
            if (parser.isMarkParsedFromLlm()) {
                parser.addWarning(methodCall, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                        "Dropped Mockito stubbing with unresolved receiver '" + mockVarName
                                + "'. Do not use spy/fallback receivers; stub only Mockito.mock(...) values.");
                return true;
            }
            return false;
        }

        Statement stmt = testCase.getStatement(mockRef.getStPosition());
        if (!(stmt instanceof FunctionalMockStatement)) {
            if (parser.isMarkParsedFromLlm()) {
                parser.addWarning(methodCall, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                        "Dropped Mockito stubbing on non-functional-mock receiver '" + mockVarName
                                + "'. EvoSuite LLM parsing does not support spy-style stubbing "
                                + "(e.g., doReturn(...).when(spy)...).");
                return true;
            }
            return false;
        }
        FunctionalMockStatement mockStmt = (FunctionalMockStatement) stmt;

        Class<?> targetClass = mockStmt.getTargetClass();
        GenericClass<?> targetGenericClass = scope.resolveGenericType(mockVarName);
        if (targetGenericClass == null) {
            targetGenericClass = GenericClassFactory.get(targetClass);
        }

        StubbingInfo info = parseStubbingChain(methodCall, mockVarName, targetClass, targetGenericClass);
        if (info == null) {
            return false;
        }

        if (info.applyToMockStatement) {
            relocateMockAfterStubbingValuesIfNeeded(mockRef, info.returnValues);
            List<VariableReference> orderedReturnValues =
                    ensureStubbingValuesAvailableBeforeMock(mockRef, info.returnValues, methodCall);
            mockStmt.addMethodStubbing(info.descriptor, orderedReturnValues);
        }
        return true;
    }

    /**
     * If any stubbing return value is defined later in the test case than the mock itself,
     * try to move the mock statement to just after the latest such value. This handles the
     * common LLM pattern of declaring all mocks first and writing the stubbings afterwards,
     * which would otherwise cause {@link #ensureStubbingValuesAvailableBeforeMock} to fall
     * back to a typed null when the value's defining statement transitively depends on
     * variables that are also after the mock.
     *
     * <p>The relocation is skipped when any statement between the mock's current position
     * and the target position already references the mock — moving would invalidate that
     * downstream use, and the existing hoist path is left to handle (or report) it.
     */
    private void relocateMockAfterStubbingValuesIfNeeded(VariableReference mockRef,
                                                         List<VariableReference> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        int currentMockPos = mockRef.getStPosition();
        int latestValuePos = currentMockPos;
        for (VariableReference value : values) {
            if (value == null) {
                continue;
            }
            int p = value.getStPosition();
            if (p > latestValuePos) {
                latestValuePos = p;
            }
        }
        if (latestValuePos <= currentMockPos) {
            return;
        }

        // Refuse the move if anything in the spanned range already uses the mock —
        // moving would leave that earlier statement with an unresolved reference.
        for (int i = currentMockPos + 1; i <= latestValuePos; i++) {
            Statement spanned = testCase.getStatement(i);
            if (spanned != null && spanned.references(mockRef)) {
                return;
            }
        }

        Statement mockStmt = testCase.getStatement(currentMockPos);
        testCase.remove(currentMockPos);
        // After remove, every statement after currentMockPos shifts down by one;
        // inserting at latestValuePos puts the mock immediately after the (shifted) value.
        parser.addStatement(mockStmt, latestValuePos);
    }

    boolean tryPreserveStandaloneThrowStubbingCall(MethodCallExpr methodCall) {
        if (!parser.isMarkParsedFromLlm() || methodCall == null
                || !"thenThrow".equals(methodCall.getNameAsString())) {
            return false;
        }
        String rewrittenVoidThrow = rewriteVoidThenThrowStubbing(methodCall);
        if (rewrittenVoidThrow != null) {
            parser.addWarning(methodCall, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                    "Rewrote Mockito thenThrow on void method to doThrow(...).when(...) form");
            parser.addStatement(parser.createUninterpretedStatement(methodCall, rewrittenVoidThrow));
            return true;
        }
        parser.addWarning(methodCall, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                "Preserved Mockito throw-stubbing as UninterpretedStatement");
        parser.addStatement(parser.createUninterpretedStatement(methodCall, methodCall.toString() + ";"));
        return true;
    }

    boolean tryPreserveStandaloneThrowStubbingSource(String source) {
        if (!parser.isMarkParsedFromLlm() || source == null || !source.contains("thenThrow(")) {
            return false;
        }

        String trimmed = source.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }

        Expression parsedExpression;
        try {
            parsedExpression = StaticJavaParser.parseExpression(trimmed);
        } catch (Throwable ignored) {
            return false;
        }

        String rewritten = rewriteThenThrowSource(trimmed);
        if (rewritten != null) {
            parser.addWarning(parsedExpression, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                    "Rewrote Mockito thenThrow chain to doThrow(...).when(...) form");
            parser.addStatement(parser.createUninterpretedStatement(parsedExpression, rewritten));
            return true;
        }
        return false;
    }

    String rewriteThenThrowSource(String source) {
        if (source == null) {
            return null;
        }
        source = source.trim();
        int thenThrowIndex = source.indexOf(".thenThrow(");
        if (thenThrowIndex < 0 || !source.startsWith("when(")) {
            return null;
        }

        String whenExprText = source.substring("when(".length(), thenThrowIndex);
        if (whenExprText.endsWith(")")) {
            whenExprText = whenExprText.substring(0, whenExprText.length() - 1);
        }

        int thenThrowArgsStart = thenThrowIndex + ".thenThrow(".length();
        int thenThrowArgsEnd = source.lastIndexOf(')');
        if (thenThrowArgsEnd < thenThrowArgsStart) {
            return null;
        }

        String thenThrowArgs = source.substring(thenThrowArgsStart, thenThrowArgsEnd);

        Expression innerExpression;
        try {
            innerExpression = StaticJavaParser.parseExpression(whenExprText);
        } catch (Throwable ignored) {
            return null;
        }
        if (!(innerExpression instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr innerMethodCall = (MethodCallExpr) innerExpression;
        if (!innerMethodCall.getScope().isPresent()) {
            return null;
        }

        StringBuilder rewritten = new StringBuilder();
        rewritten.append("doThrow(").append(thenThrowArgs).append(").when(")
                .append(innerMethodCall.getScope().get().toString())
                .append(").").append(innerMethodCall.getNameAsString()).append("(");
        for (int i = 0; i < innerMethodCall.getArguments().size(); i++) {
            if (i > 0) {
                rewritten.append(", ");
            }
            rewritten.append(innerMethodCall.getArgument(i).toString());
        }
        rewritten.append(");");
        return rewritten.toString();
    }

    private String rewriteVoidThenThrowStubbing(MethodCallExpr methodCall) {
        if (methodCall == null || !"thenThrow".equals(methodCall.getNameAsString())) {
            return null;
        }
        if (!methodCall.getScope().isPresent() || !(methodCall.getScope().get() instanceof MethodCallExpr)) {
            return null;
        }

        MethodCallExpr whenCall = (MethodCallExpr) methodCall.getScope().get();
        if (!"when".equals(whenCall.getNameAsString()) || whenCall.getArguments().size() != 1) {
            return null;
        }
        Expression innerExpr = whenCall.getArgument(0);
        if (!(innerExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr innerMethodCall = (MethodCallExpr) innerExpr;
        if (!innerMethodCall.getScope().isPresent()) {
            return null;
        }

        Expression receiverExpr = unwrapCastsAndParentheses(innerMethodCall.getScope().get());
        Class<?> targetClass = null;
        if (receiverExpr instanceof NameExpr) {
            VariableReference receiverRef = scope.resolve(((NameExpr) receiverExpr).getNameAsString());
            if (receiverRef != null) {
                targetClass = parser.getRawClass(receiverRef.getType());
            }
        }
        if (targetClass == null) {
            targetClass = parser.resolveClassFromExpression(receiverExpr);
        }
        if (targetClass == null) {
            return null;
        }

        List<VariableReference> argRefs = new ArrayList<>();
        for (Expression arg : innerMethodCall.getArguments()) {
            VariableReference ref = parser.resolveArgument(arg, Object.class);
            if (ref == null) {
                return null;
            }
            argRefs.add(ref);
        }
        Class<?>[] argTypes = new Class<?>[argRefs.size()];
        for (int i = 0; i < argRefs.size(); i++) {
            argTypes[i] = parser.getRawClass(argRefs.get(i).getType());
        }

        Method method;
        try {
            method = parser.getOverloadResolver().resolveMethod(targetClass, innerMethodCall.getNameAsString(), argTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
        if (method == null || !void.class.equals(method.getReturnType())) {
            return null;
        }

        StringBuilder rewritten = new StringBuilder();
        rewritten.append("doThrow(");
        for (int i = 0; i < methodCall.getArguments().size(); i++) {
            if (i > 0) {
                rewritten.append(", ");
            }
            rewritten.append(methodCall.getArgument(i).toString());
        }
        rewritten.append(").when(")
                .append(innerMethodCall.getScope().get().toString())
                .append(").")
                .append(innerMethodCall.getNameAsString())
                .append("(");
        for (int i = 0; i < innerMethodCall.getArguments().size(); i++) {
            if (i > 0) {
                rewritten.append(", ");
            }
            rewritten.append(innerMethodCall.getArgument(i).toString());
        }
        rewritten.append(");");
        return rewritten.toString();
    }

    void flushCapturedWhenStubbingDiagnostics() {
        for (Map.Entry<String, CapturedWhenStubbingContext> entry : capturedWhenStubbings.entrySet()) {
            CapturedWhenStubbingContext context = entry.getValue();
            String aliasName = context.aliasName != null ? context.aliasName : entry.getKey();
            String details = "captured `when(...)` alias `" + aliasName
                    + "` had no matching terminal `thenReturn`/`thenThrow`; complete the stubbing or remove the call.";
            if (parser.isMarkParsedFromLlm()) {
                parser.addWarning(context.whenCall, DiagnosticKind.STRANDED_WHEN_ALIAS, details);
            } else {
                parser.addError(context.whenCall, DiagnosticKind.STRANDED_WHEN_ALIAS, details);
            }
        }
        capturedWhenStubbings.clear();
    }

    private int handleCapturedWhenStubbingDeclaration(
            VariableDeclarator declarator,
            Expression initializer,
            List<com.github.javaparser.ast.stmt.Statement> allStatements,
            int currentIndex) {
        if (!(initializer instanceof MethodCallExpr)) {
            return 0;
        }

        MethodCallExpr whenCall = (MethodCallExpr) initializer;
        CapturedWhenStubbingContext context = resolveCapturedWhenStubbingContext(whenCall);
        if (context == null) {
            return 0;
        }

        String aliasName = declarator.getNameAsString();
        if (!hasCapturedWhenTerminalCallAhead(aliasName, allStatements, currentIndex + 1)) {
            return 0;
        }

        capturedWhenStubbings.put(aliasName, context.withAlias(aliasName));
        return 1;
    }

    private CapturedWhenStubbingContext resolveCapturedWhenStubbingContext(MethodCallExpr whenCall) {
        if (!isMockitoWhenCall(whenCall) || whenCall.getArguments().size() != 1) {
            return null;
        }

        Expression whenArgument = whenCall.getArgument(0);
        if (!(whenArgument instanceof MethodCallExpr)) {
            return null;
        }

        MethodCallExpr innerMethodCall = (MethodCallExpr) whenArgument;
        if (!innerMethodCall.getScope().isPresent()) {
            return null;
        }
        Expression innerScope = unwrapCastsAndParentheses(innerMethodCall.getScope().get());
        if (!(innerScope instanceof NameExpr)) {
            return null;
        }

        String mockVarName = ((NameExpr) innerScope).getNameAsString();
        VariableReference mockRef = scope.resolve(mockVarName);
        if (mockRef == null || mockRef.getStPosition() < 0 || mockRef.getStPosition() >= testCase.size()) {
            return null;
        }

        Statement mockSource = testCase.getStatement(mockRef.getStPosition());
        if (!(mockSource instanceof FunctionalMockStatement)) {
            return null;
        }

        FunctionalMockStatement mockStatement = (FunctionalMockStatement) mockSource;
        Class<?> targetClass = mockStatement.getTargetClass();
        GenericClass<?> targetGenericClass = scope.resolveGenericType(mockVarName);
        if (targetGenericClass == null) {
            targetGenericClass = GenericClassFactory.get(targetClass);
        }
        return new CapturedWhenStubbingContext(
                mockVarName,
                null,
                mockRef,
                mockStatement,
                targetClass,
                targetGenericClass,
                StatementParser.copySyntheticRange(whenCall.clone(), whenCall));
    }

    private boolean isMockitoWhenCall(MethodCallExpr whenCall) {
        if (whenCall == null || !"when".equals(whenCall.getNameAsString())) {
            return false;
        }
        if (!whenCall.getScope().isPresent()) {
            return true;
        }
        Class<?> scopeClass = parser.resolveClassFromExpression(whenCall.getScope().get());
        return isMockitoClass(scopeClass);
    }

    private boolean hasCapturedWhenTerminalCallAhead(String aliasName,
                                                     List<com.github.javaparser.ast.stmt.Statement> allStatements,
                                                     int startIndex) {
        if (aliasName == null || allStatements == null) {
            return false;
        }
        for (int i = startIndex; i < allStatements.size(); i++) {
            com.github.javaparser.ast.stmt.Statement stmt = allStatements.get(i);
            if (!(stmt instanceof ExpressionStmt)) {
                continue;
            }
            Expression expression = ((ExpressionStmt) stmt).getExpression();
            if (!(expression instanceof MethodCallExpr)) {
                continue;
            }
            MethodCallExpr methodCall = (MethodCallExpr) expression;
            if (!methodCall.getScope().isPresent() || !(methodCall.getScope().get() instanceof NameExpr)) {
                continue;
            }
            if (!aliasName.equals(((NameExpr) methodCall.getScope().get()).getNameAsString())) {
                continue;
            }
            String methodName = methodCall.getNameAsString();
            if ("thenReturn".equals(methodName)) {
                return true;
            }
            return parser.isMarkParsedFromLlm() && "thenThrow".equals(methodName);
        }
        return false;
    }

    private boolean isMockCreation(Expression expr) {
        if (!(expr instanceof MethodCallExpr)) {
            return false;
        }
        MethodCallExpr call = (MethodCallExpr) expr;
        if (!"mock".equals(call.getNameAsString()) || call.getArguments().isEmpty()) {
            return false;
        }
        return call.getArgument(0) instanceof ClassExpr;
    }

    private Class<?> extractMockTargetClass(MethodCallExpr mockCall) {
        return extractMockTargetClass(mockCall.getArgument(0));
    }

    private MockVariant detectMockVariant(MethodCallExpr mockCall) {
        if (mockCall.getArguments().size() < 2) {
            return MockVariant.PLAIN;
        }

        String secondArgText = mockCall.getArgument(1).toString();
        if (secondArgText.contains("ViolatedAssumptionAnswer")) {
            return MockVariant.VIOLATED_ASSUMPTION_ANSWER;
        }
        if (secondArgText.contains("CALLS_REAL_METHODS")) {
            return MockVariant.CALLS_REAL_METHODS;
        }
        return MockVariant.PLAIN;
    }

    private boolean isMockitoClass(Class<?> targetClass) {
        if (targetClass == null) {
            return false;
        }
        String name = targetClass.getName();
        return "org.mockito.Mockito".equals(name)
                || "shaded.org.evosuite.shaded.org.mockito.Mockito".equals(name);
    }

    private boolean isLikelyMockitoMockScope(Expression scopeExpression) {
        if (scopeExpression == null) {
            return false;
        }
        if (scopeExpression instanceof NameExpr) {
            return "Mockito".equals(((NameExpr) scopeExpression).getNameAsString());
        }
        if (scopeExpression instanceof FieldAccessExpr) {
            String scopeText = scopeExpression.toString();
            return "org.mockito.Mockito".equals(scopeText)
                    || "org.evosuite.shaded.org.mockito.Mockito".equals(scopeText)
                    || "shaded.org.evosuite.shaded.org.mockito.Mockito".equals(scopeText)
                    || scopeText.endsWith(".Mockito");
        }
        return false;
    }

    private Class<?> resolveMockitoMockTargetClass(Expression firstArgument, List<VariableReference> argRefs) {
        Class<?> classLiteralTarget = extractMockTargetClass(firstArgument);
        if (classLiteralTarget != null) {
            return classLiteralTarget;
        }
        if (argRefs == null || argRefs.isEmpty()) {
            return null;
        }
        return resolveClassLiteralValue(argRefs.get(0));
    }

    private Class<?> extractMockTargetClass(Expression firstArgument) {
        if (firstArgument == null) {
            return null;
        }

        Expression unwrapped = unwrapCastsAndParentheses(firstArgument);
        if (!(unwrapped instanceof ClassExpr)) {
            return null;
        }
        try {
            return typeResolver.resolveClass(((ClassExpr) unwrapped).getTypeAsString());
        } catch (ClassNotFoundException e) {
            logger.debug("Cannot resolve mock target class: {}", firstArgument);
            return null;
        }
    }

    private Expression unwrapCastsAndParentheses(Expression expression) {
        Expression current = expression;
        while (current instanceof CastExpr || current instanceof EnclosedExpr) {
            if (current instanceof CastExpr) {
                current = ((CastExpr) current).getExpression();
            } else {
                current = ((EnclosedExpr) current).getInner();
            }
        }
        return current;
    }

    private Class<?> resolveClassLiteralValue(VariableReference reference) {
        if (reference == null || reference.getStPosition() < 0 || reference.getStPosition() >= testCase.size()) {
            return null;
        }

        Statement sourceStatement = testCase.getStatement(reference.getStPosition());
        if (!(sourceStatement instanceof PrimitiveStatement<?>)) {
            return null;
        }

        Object value = ((PrimitiveStatement<?>) sourceStatement).getValue();
        return value instanceof Class<?> ? (Class<?>) value : null;
    }

    private int collectAndApplyStubbings(FunctionalMockStatement mockStmt,
                                         String mockVarName,
                                         Class<?> targetClass,
                                         GenericClass<?> targetGenericClass,
                                         List<com.github.javaparser.ast.stmt.Statement> allStatements,
                                         int startIndex) {
        int consumed = 0;
        for (int i = startIndex; i < allStatements.size(); i++) {
            com.github.javaparser.ast.stmt.Statement astStmt = allStatements.get(i);
            if (!(astStmt instanceof ExpressionStmt)) {
                break;
            }

            Expression expr = ((ExpressionStmt) astStmt).getExpression();
            StubbingInfo stubbing = parseStubbingChain(expr, mockVarName, targetClass, targetGenericClass);
            if (stubbing == null) {
                break;
            }

            if (stubbing.applyToMockStatement) {
                mockStmt.addMethodStubbing(stubbing.descriptor, stubbing.returnValues);
            }
            consumed++;
        }
        return consumed;
    }

    private StubbingInfo parseStubbingChain(Expression expr,
                                            String mockVarName,
                                            Class<?> targetClass,
                                            GenericClass<?> targetGenericClass) {
        if (!(expr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr outerCall = (MethodCallExpr) expr;

        StubbingInfo info = parseDoReturnWhenPattern(outerCall, mockVarName, targetClass, targetGenericClass);
        if (info != null) {
            return info;
        }

        return parseWhenThenReturnPattern(outerCall, mockVarName, targetClass, targetGenericClass);
    }

    private StubbingInfo parseDoReturnWhenPattern(MethodCallExpr outerCall,
                                                  String mockVarName,
                                                  Class<?> targetClass,
                                                  GenericClass<?> targetGenericClass) {
        String stubbedMethodName = outerCall.getNameAsString();

        if (!outerCall.getScope().isPresent()) {
            return null;
        }
        Expression whenCallExpr = outerCall.getScope().get();
        if (!(whenCallExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr whenCall = (MethodCallExpr) whenCallExpr;

        if (!"when".equals(whenCall.getNameAsString()) || whenCall.getArguments().size() != 1) {
            return null;
        }
        Expression whenArg = whenCall.getArgument(0);
        if (!(whenArg instanceof NameExpr)) {
            return null;
        }
        if (!mockVarName.equals(((NameExpr) whenArg).getNameAsString())) {
            return null;
        }

        if (!whenCall.getScope().isPresent()) {
            return null;
        }
        Expression doReturnExpr = whenCall.getScope().get();
        if (!(doReturnExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr doReturnCall = (MethodCallExpr) doReturnExpr;

        if (!"doReturn".equals(doReturnCall.getNameAsString())) {
            return null;
        }

        Method method = OverloadResolver.findByNameLoose(targetClass, stubbedMethodName);
        if (method == null) {
            if (parser.isMarkParsedFromLlm()) {
                parser.addWarning(outerCall, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                        "Ignored invalid Mockito doReturn(...).when(...)." + stubbedMethodName
                                + "(...) stubbing: no matching method on mock target type");
                return StubbingInfo.consumeOnly();
            }
            return null;
        }
        if (isVoidReturn(method) && parser.isMarkParsedFromLlm()) {
            parser.addWarning(outerCall, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                    "Ignored invalid Mockito doReturn(...).when(...)." + stubbedMethodName
                            + "(...) stubbing on void method");
            return StubbingInfo.consumeOnly();
        }

        List<VariableReference> returnValues = resolveReturnValueArguments(
                doReturnCall.getArguments(), method.getGenericReturnType());

        MethodDescriptor descriptor = new MethodDescriptor(method, targetGenericClass);
        for (int i = 0; i < returnValues.size(); i++) {
            descriptor.increaseCounter();
        }

        return new StubbingInfo(descriptor, returnValues);
    }

    private StubbingInfo parseWhenThenReturnPattern(MethodCallExpr outerCall,
                                                    String mockVarName,
                                                    Class<?> targetClass,
                                                    GenericClass<?> targetGenericClass) {
        if (!"thenReturn".equals(outerCall.getNameAsString())) {
            return null;
        }

        if (!outerCall.getScope().isPresent()) {
            return null;
        }
        Expression whenExpr = outerCall.getScope().get();
        if (!(whenExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr whenCall = (MethodCallExpr) whenExpr;

        if (!"when".equals(whenCall.getNameAsString()) || whenCall.getArguments().size() != 1) {
            return null;
        }

        Expression whenArg = whenCall.getArgument(0);
        if (!(whenArg instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr innerMethodCall = (MethodCallExpr) whenArg;

        if (!innerMethodCall.getScope().isPresent()) {
            return null;
        }
        Expression innerScope = unwrapCastsAndParentheses(innerMethodCall.getScope().get());
        if (!(innerScope instanceof NameExpr)) {
            return null;
        }
        if (!mockVarName.equals(((NameExpr) innerScope).getNameAsString())) {
            return null;
        }

        String stubbedMethodName = innerMethodCall.getNameAsString();

        Method method = OverloadResolver.findByNameLoose(targetClass, stubbedMethodName);
        if (method == null) {
            if (parser.isMarkParsedFromLlm()) {
                parser.addWarning(outerCall, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                        "Ignored invalid Mockito when(...).thenReturn(...) stubbing: no method named "
                                + stubbedMethodName + " on mock target type");
                return StubbingInfo.consumeOnly();
            }
            return null;
        }
        if (isVoidReturn(method) && parser.isMarkParsedFromLlm()) {
            parser.addWarning(outerCall, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                    "Ignored invalid Mockito when(...).thenReturn(...) stubbing on void method");
            return StubbingInfo.consumeOnly();
        }

        List<VariableReference> returnValues = resolveReturnValueArguments(
                outerCall.getArguments(), method.getGenericReturnType());

        MethodDescriptor descriptor = new MethodDescriptor(method, targetGenericClass);
        for (int i = 0; i < returnValues.size(); i++) {
            descriptor.increaseCounter();
        }

        return new StubbingInfo(descriptor, returnValues);
    }

    private List<VariableReference> resolveReturnValueArguments(List<Expression> args, Type returnType) {
        List<VariableReference> refs = new ArrayList<>();
        for (Expression arg : args) {
            VariableReference ref = parser.resolveArgument(arg, returnType != null ? returnType : Object.class);
            if (ref == null && parser.isMarkParsedFromLlm()) {
                ref = parser.fallbackForInaccessibleMember(
                        arg,
                        "Unresolved stubbing return value: " + arg,
                        returnType != null ? returnType : Object.class);
            }
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private static boolean isVoidReturn(Method method) {
        if (method == null) {
            return false;
        }
        Class<?> returnType = method.getReturnType();
        return returnType == void.class || returnType == Void.class || returnType == Void.TYPE;
    }

    private boolean isUnsupportedMockitoThrowStubbing(MethodCallExpr methodCall) {
        if ("thenThrow".equals(methodCall.getNameAsString())) {
            if (!methodCall.getScope().isPresent()
                    || !(methodCall.getScope().get() instanceof MethodCallExpr)) {
                return false;
            }
            MethodCallExpr whenCall = (MethodCallExpr) methodCall.getScope().get();
            if (!"when".equals(whenCall.getNameAsString()) || whenCall.getArguments().size() != 1) {
                return false;
            }
            return whenCall.getArgument(0) instanceof MethodCallExpr;
        }

        if (!methodCall.getScope().isPresent()
                || !(methodCall.getScope().get() instanceof MethodCallExpr)) {
            return false;
        }
        MethodCallExpr whenCall = (MethodCallExpr) methodCall.getScope().get();
        if (!"when".equals(whenCall.getNameAsString())
                || whenCall.getArguments().size() != 1
                || !(whenCall.getArgument(0) instanceof NameExpr)
                || !whenCall.getScope().isPresent()
                || !(whenCall.getScope().get() instanceof MethodCallExpr)) {
            return false;
        }
        MethodCallExpr doThrowCall = (MethodCallExpr) whenCall.getScope().get();
        return "doThrow".equals(doThrowCall.getNameAsString());
    }

    private List<VariableReference> ensureStubbingValuesAvailableBeforeMock(VariableReference mockRef,
                                                                            List<VariableReference> values,
                                                                            com.github.javaparser.ast.Node diagnosticNode) {
        int mockPos = mockRef.getStPosition();
        List<VariableReference> adjusted = new ArrayList<>(values.size());
        Map<VariableReference, VariableReference> hoisted = new IdentityHashMap<>();

        for (VariableReference valueRef : values) {
            if (valueRef == null || valueRef.getStPosition() < 0 || valueRef.getStPosition() < mockPos) {
                adjusted.add(valueRef);
                continue;
            }
            VariableReference alreadyHoisted = hoisted.get(valueRef);
            if (alreadyHoisted != null) {
                adjusted.add(alreadyHoisted);
                continue;
            }

            Statement definingStmt = testCase.getStatement(valueRef.getStPosition());
            if (canSafelyHoist(definingStmt, valueRef, mockPos)) {
                Statement cloned = definingStmt.clone(testCase);
                VariableReference hoistedRef = parser.addStatement(cloned, mockPos);
                hoisted.put(valueRef, hoistedRef);
                mockPos++;
                adjusted.add(hoistedRef);
                continue;
            }

            if (parser.isMarkParsedFromLlm()) {
                logger.debug("Could not hoist stubbing value '{}' before mock; using typed fallback value",
                        valueRef.getName());
                if (diagnosticNode != null) {
                    parser.addWarning(diagnosticNode, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                            "Stubbing return value '" + valueRef.getName()
                                    + "' is defined later in the test than the mock receiver and could not be"
                                    + " reordered; substituted a typed default value. Declare the value variable"
                                    + " before the mock it is returned from.");
                }
                adjusted.add(parser.createTypedFallbackValue(valueRef.getType()));
            } else {
                adjusted.add(valueRef);
            }
        }
        return adjusted;
    }

    private boolean canSafelyHoist(Statement stmt, VariableReference valueRef, int mockPos) {
        if (stmt == null) {
            return false;
        }
        if (!(stmt instanceof PrimitiveStatement
                || stmt instanceof org.evosuite.testcase.statements.NullStatement
                || stmt instanceof org.evosuite.testcase.statements.ConstructorStatement
                || stmt instanceof org.evosuite.testcase.statements.FunctionalMockStatement
                || stmt instanceof org.evosuite.testcase.statements.EnumPrimitiveStatement
                || stmt instanceof org.evosuite.testcase.statements.ArrayStatement)) {
            return false;
        }
        Set<VariableReference> deps = testCase.getDependencies(valueRef);
        for (VariableReference dep : deps) {
            int depPos = dep.getStPosition();
            if (depPos >= mockPos && depPos != valueRef.getStPosition()) {
                return false;
            }
        }
        return true;
    }

    private String extractMockVarFromStubbingPattern(MethodCallExpr expr) {
        if ("thenReturn".equals(expr.getNameAsString())) {
            if (!expr.getScope().isPresent() || !(expr.getScope().get() instanceof MethodCallExpr)) {
                return null;
            }
            MethodCallExpr whenCall = (MethodCallExpr) expr.getScope().get();
            if (!"when".equals(whenCall.getNameAsString()) || whenCall.getArguments().size() != 1) {
                return null;
            }
            Expression whenArg = whenCall.getArgument(0);
            if (!(whenArg instanceof MethodCallExpr)) {
                return null;
            }
            MethodCallExpr innerCall = (MethodCallExpr) whenArg;
            if (!innerCall.getScope().isPresent()) {
                return null;
            }
            Expression receiver = unwrapCastsAndParentheses(innerCall.getScope().get());
            if (!(receiver instanceof NameExpr)) {
                return null;
            }
            return ((NameExpr) receiver).getNameAsString();
        }

        if (expr.getScope().isPresent() && expr.getScope().get() instanceof MethodCallExpr) {
            MethodCallExpr whenCall = (MethodCallExpr) expr.getScope().get();
            if ("when".equals(whenCall.getNameAsString()) && whenCall.getArguments().size() == 1) {
                Expression whenArg = whenCall.getArgument(0);
                if (whenArg instanceof NameExpr
                        && whenCall.getScope().isPresent()
                        && whenCall.getScope().get() instanceof MethodCallExpr
                        && "doReturn".equals(((MethodCallExpr) whenCall.getScope().get()).getNameAsString())) {
                    return ((NameExpr) whenArg).getNameAsString();
                }
            }
        }

        return null;
    }

    private Class<?> getCompatibleOverrideMockClass(Class<?> rawClass) {
        if (!parser.isMarkParsedFromLlm() || rawClass == null) {
            return null;
        }
        String canonicalName = rawClass.getCanonicalName();
        if (canonicalName == null || canonicalName.isEmpty()) {
            return null;
        }
        Class<?> mockClass;
        try {
            mockClass = MockList.getMockClass(canonicalName);
        } catch (Throwable ignored) {
            return null;
        }
        if (mockClass == null) {
            return null;
        }
        if (!OverrideMock.class.isAssignableFrom(mockClass)) {
            return null;
        }
        if (!rawClass.isAssignableFrom(mockClass)) {
            return null;
        }
        return mockClass;
    }

    private Class<?> getCompatibleStaticMethodMockClass(Class<?> rawClass) {
        if (!parser.isMarkParsedFromLlm() || rawClass == null) {
            return null;
        }
        String canonicalName = rawClass.getCanonicalName();
        if (canonicalName == null || canonicalName.isEmpty()) {
            return null;
        }

        Class<?> mockClass;
        try {
            mockClass = MockList.getMockClass(canonicalName);
        } catch (Throwable ignored) {
            return null;
        }
        if (mockClass == null) {
            return null;
        }

        if (OverrideMock.class.isAssignableFrom(mockClass)) {
            return rawClass.isAssignableFrom(mockClass) ? mockClass : null;
        }
        if (StaticReplacementMock.class.isAssignableFrom(mockClass)) {
            return mockClass;
        }
        return null;
    }

    private static String demockTypeTokens(String typeText) {
        Matcher matcher = MOCK_TYPE_TOKEN.matcher(typeText);
        if (!matcher.find()) {
            return typeText;
        }
        StringBuffer out = new StringBuffer(typeText.length());
        do {
            matcher.appendReplacement(out, matcher.group(1));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }

    private NodeList<Expression> cloneArguments(MethodCallExpr methodCallExpr) {
        NodeList<Expression> clonedArguments = new NodeList<>();
        for (Expression argument : methodCallExpr.getArguments()) {
            clonedArguments.add(StatementParser.copySyntheticRange(argument.clone(), argument));
        }
        return clonedArguments;
    }

    private static final class CapturedWhenStubbingContext {
        private final String mockVarName;
        private final String aliasName;
        private final VariableReference mockRef;
        private final FunctionalMockStatement mockStatement;
        private final Class<?> targetClass;
        private final GenericClass<?> targetGenericClass;
        private final MethodCallExpr whenCall;

        private CapturedWhenStubbingContext(String mockVarName,
                                            String aliasName,
                                            VariableReference mockRef,
                                            FunctionalMockStatement mockStatement,
                                            Class<?> targetClass,
                                            GenericClass<?> targetGenericClass,
                                            MethodCallExpr whenCall) {
            this.mockVarName = mockVarName;
            this.aliasName = aliasName;
            this.mockRef = mockRef;
            this.mockStatement = mockStatement;
            this.targetClass = targetClass;
            this.targetGenericClass = targetGenericClass;
            this.whenCall = whenCall;
        }

        private CapturedWhenStubbingContext withAlias(String aliasName) {
            return new CapturedWhenStubbingContext(
                    mockVarName,
                    aliasName,
                    mockRef,
                    mockStatement,
                    targetClass,
                    targetGenericClass,
                    StatementParser.copySyntheticRange(whenCall.clone(), whenCall));
        }
    }

    private enum MockVariant {
        VIOLATED_ASSUMPTION_ANSWER,
        CALLS_REAL_METHODS,
        PLAIN
    }

    private static class StubbingInfo {
        final MethodDescriptor descriptor;
        final List<VariableReference> returnValues;
        final boolean applyToMockStatement;

        StubbingInfo(MethodDescriptor descriptor, List<VariableReference> returnValues) {
            this(descriptor, returnValues, true);
        }

        private StubbingInfo(MethodDescriptor descriptor,
                             List<VariableReference> returnValues,
                             boolean applyToMockStatement) {
            this.descriptor = descriptor;
            this.returnValues = returnValues;
            this.applyToMockStatement = applyToMockStatement;
        }

        static StubbingInfo consumeOnly() {
            return new StubbingInfo(null, java.util.Collections.emptyList(), false);
        }
    }
}
