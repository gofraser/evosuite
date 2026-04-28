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

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import org.evosuite.assertion.EqualsAssertion;
import org.evosuite.assertion.NullAssertion;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.assertion.SameAssertion;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.mock.OverrideMock;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.FunctionalMockForAbstractClassStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.numeric.*;
import org.evosuite.testcase.statements.reflection.PrivateFieldStatement;
import org.evosuite.testcase.statements.reflection.PrivateMethodStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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

/**
 * Converts JavaParser AST statement/expression nodes into EvoSuite Statement objects
 * and adds them to a TestCase.
 *
 * <p>This is the core conversion logic of the test parser. Each handler method maps
 * a specific expression type to the corresponding EvoSuite statement type.
 */
public class StatementParser {

    private static final Logger logger = LoggerFactory.getLogger(StatementParser.class);
    private static final Pattern MOCK_TYPE_TOKEN = Pattern.compile("\\bMock([A-Z][A-Za-z0-9_$]*)\\b");

    private final DefaultTestCase testCase;
    private final TypeResolver typeResolver;
    private final VariableScope scope;
    private final ParseResult result;
    private final Map<String, MethodDeclaration> inlineHelperMethods = new LinkedHashMap<>();
    /** LLM-mode declarations without initializer; consumed by subsequent NameExpr assignments. */
    private final Map<String, Type> pendingLlmDeclarations = new LinkedHashMap<>();
    /** Captured Mockito when(...) aliases waiting for a later thenReturn/thenThrow terminal call. */
    private final Map<String, CapturedWhenStubbingContext> capturedWhenStubbings = new LinkedHashMap<>();

    /** Counter for generating unique names for synthetic variables (inline literals, etc.) */
    private int syntheticVarCounter = 0;

    /** When true, all statements created by this parser are marked as LLM-parsed. */
    private boolean markParsedFromLlm = false;

    /**
     * Create a new StatementParser.
     *
     * @param testCase the test case.
     * @param typeResolver the type resolver.
     * @param scope the variable scope.
     * @param result the parse result.
     */
    public StatementParser(DefaultTestCase testCase, TypeResolver typeResolver,
                           VariableScope scope, ParseResult result) {
        this.testCase = testCase;
        this.typeResolver = typeResolver;
        this.scope = scope;
        this.result = result;
    }

    /**
     * Sets whether statements created by this parser should be marked
     * as originating from LLM-generated code.
     */
    public void setMarkParsedFromLlm(boolean mark) {
        this.markParsedFromLlm = mark;
    }

    public boolean isMarkParsedFromLlm() {
        return markParsedFromLlm;
    }

    /**
     * Registers helper methods (arity 0/1) that can be inlined as a plain
     * return expression.
     */
    public void setInlineHelperMethods(Map<String, MethodDeclaration> helpers) {
        inlineHelperMethods.clear();
        if (helpers != null) {
            inlineHelperMethods.putAll(helpers);
        }
    }

    // ========================================================================
    // Top-level dispatch
    // ========================================================================

    /**
     * Parse a single JavaParser statement and add corresponding EvoSuite statement(s)
     * to the TestCase.
     */
    public void parseStatement(com.github.javaparser.ast.stmt.Statement astStmt) {
        parseStatement(astStmt, null, 0);
    }

    /**
     * Parse a JavaParser statement with look-ahead access to subsequent statements.
     * Used for multi-statement patterns like mock creation + stubbing.
     *
     * @param astStmt       the current statement
     * @param allStatements the full list of statements (for look-ahead), or null
     * @param currentIndex  the index of the current statement in allStatements
     * @return the number of statements consumed (always >= 1)
     */
    public int parseStatement(com.github.javaparser.ast.stmt.Statement astStmt,
                              List<com.github.javaparser.ast.stmt.Statement> allStatements,
                              int currentIndex) {
        if (astStmt instanceof ExpressionStmt) {
            ExpressionStmt exprStmt = (ExpressionStmt) astStmt;
            return handleExpressionStatement(exprStmt.getExpression(), allStatements, currentIndex);
        } else if (astStmt instanceof AssertStmt) {
            handleAssertStatement((AssertStmt) astStmt);
            return 1;
        } else if (markParsedFromLlm && astStmt instanceof TryStmt) {
            // Best-effort: flatten try-body statements instead of preserving raw source,
            // which often leaks undeclared local assignments from LLM snippets.
            handleTryStatement((TryStmt) astStmt);
            return 1;
        } else {
            // Fallback: preserve as UninterpretedStatement
            int line = astStmt.getBegin().map(p -> p.line).orElse(0);
            com.github.javaparser.ast.stmt.Statement preserved = astStmt;
            if (markParsedFromLlm) {
                preserved = inlineHelperCallsInUnsupportedStatement(astStmt);
            }
            result.addDiagnostic(new ParseDiagnostic(
                    ParseDiagnostic.Severity.WARNING,
                    "Unsupported statement type, preserved as UninterpretedStatement: "
                            + preserved.getClass().getSimpleName(),
                    line,
                    preserved.toString()));
            testCase.addStatement(createUninterpretedStatement(preserved, preserved.toString()));
            return 1;
        }
    }

    private void handleExpressionStatement(Expression expr) {
        handleExpressionStatement(expr, null, 0);
    }

    private int handleExpressionStatement(Expression expr,
                                          List<com.github.javaparser.ast.stmt.Statement> allStatements,
                                          int currentIndex) {
        if (expr instanceof VariableDeclarationExpr) {
            int consumed = handleVariableDeclarationWithLookahead(
                    (VariableDeclarationExpr) expr, allStatements, currentIndex);
            if (consumed > 0) {
                return consumed;
            }
            handleVariableDeclaration((VariableDeclarationExpr) expr);
            return 1;
        } else if (expr instanceof MethodCallExpr) {
            handleTopLevelMethodCall((MethodCallExpr) expr);
            return 1;
        } else if (expr instanceof ObjectCreationExpr) {
            // Standalone constructor call used as statement (e.g. "new Foo(...);").
            // Parse it through the regular constructor path instead of preserving raw
            // source as uninterpreted code, which can leak unresolved symbols.
            handleObjectCreation((ObjectCreationExpr) expr, Object.class, null);
            return 1;
        } else if (expr instanceof AssignExpr) {
            handleAssignment((AssignExpr) expr);
            return 1;
        } else {
            // Fallback: preserve as UninterpretedStatement in strict mode.
            // In LLM best-effort mode, avoid emitting raw unsupported expression
            // statements (e.g., ternary expressions) because they can be
            // non-compilable as standalone statements.
            if (markParsedFromLlm) {
                fallbackForUnresolvedExpression(
                        expr,
                        Object.class,
                        "Unsupported expression statement in LLM mode");
                return 1;
            }
            // Strict mode fallback: preserve raw source.
            int line = expr.getBegin().map(p -> p.line).orElse(0);
            result.addDiagnostic(new ParseDiagnostic(
                    ParseDiagnostic.Severity.WARNING,
                    "Unsupported expression type, preserved as UninterpretedStatement: "
                            + expr.getClass().getSimpleName(),
                    line,
                    expr.toString()));
            testCase.addStatement(createUninterpretedStatement(expr, expr.toString() + ";"));
            return 1;
        }
    }

    // ========================================================================
    // Variable declarations: Type var = initializer;
    // ========================================================================

    private void handleVariableDeclaration(VariableDeclarationExpr varDeclExpr) {
        for (VariableDeclarator declarator : varDeclExpr.getVariables()) {
            String varName = declarator.getNameAsString();
            String emittedVarName = chooseDeclarationName(varName, declarator);

            if (!declarator.getInitializer().isPresent()) {
                Type declaredType;
                try {
                    declaredType = typeResolver.resolveType(declarator.getType());
                } catch (ClassNotFoundException e) {
                    if (markParsedFromLlm) {
                        declaredType = tryResolveDemockedDeclaredType(declarator.getType());
                        if (declaredType == null) {
                            declaredType = Object.class;
                            int line = declarator.getBegin().map(p -> p.line).orElse(0);
                            result.addDiagnostic(new ParseDiagnostic(
                                    ParseDiagnostic.Severity.WARNING,
                                    "Cannot resolve type for declaration without initializer: "
                                            + declarator.getType() + " — " + e.getMessage()
                                            + " — downgraded to Object for LLM best-effort parsing. "
                                            + "LLM_REPAIR_ACTION_REQUIRED: replace invented/unknown type '"
                                            + declarator.getType() + "' with an existing SUT or JDK type.",
                                    line,
                                    declarator.toString()));
                        }
                    } else {
                        int line = declarator.getBegin().map(p -> p.line).orElse(0);
                        result.addDiagnostic(new ParseDiagnostic(
                                ParseDiagnostic.Severity.WARNING,
                                "Variable declaration without initializer: " + varName,
                                line,
                                declarator.toString()));
                        continue;
                    }
                }

                if (markParsedFromLlm) {
                    // Defer emission until first assignment to avoid uncompilable declarations
                    // with inaccessible type arguments (e.g., Constructor<Outer.Inner>).
                    pendingLlmDeclarations.put(varName, declaredType);
                    continue;
                }

                int line = declarator.getBegin().map(p -> p.line).orElse(0);
                result.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.WARNING,
                        "Variable declaration without initializer: " + varName,
                        line,
                        declarator.toString()));
                continue;
            }

            Expression initializer = declarator.getInitializer().get();
            Type declaredType;
            try {
                declaredType = typeResolver.resolveType(declarator.getType());
            } catch (ClassNotFoundException e) {
                if (markParsedFromLlm) {
                    declaredType = tryResolveDemockedDeclaredType(declarator.getType());
                    if (declaredType != null) {
                        addWarning(initializer, "Resolved inferred mock-prefixed type '"
                                + declarator.getType() + "' as '" + declaredType.getTypeName() + "'");
                    } else {
                        // LLM best-effort mode: keep parsing by downgrading unknown declared
                        // types to Object so later statements can still be repaired/mutated.
                        declaredType = Object.class;
                        addWarning(initializer,
                                "Cannot resolve type: " + declarator.getType() + " — " + e.getMessage()
                                        + " — downgraded to Object for LLM best-effort parsing. "
                                        + "LLM_REPAIR_ACTION_REQUIRED: replace invented/unknown type '"
                                        + declarator.getType()
                                        + "' with an existing SUT or JDK type.");
                    }
                } else {
                    int line = declarator.getBegin().map(p -> p.line).orElse(0);
                    result.addDiagnostic(new ParseDiagnostic(
                            ParseDiagnostic.Severity.ERROR,
                            "Cannot resolve type: " + declarator.getType() + " — " + e.getMessage(),
                            line,
                            declarator.toString()));
                    continue;
                }
            }

            if (markParsedFromLlm && shouldPreserveAssertionReturningDeclaration(initializer)) {
                Expression preservedInitializer = normalizeAssertionExpressionForPreservation(initializer);
                String code = getFallbackTypeName(declaredType) + " " + emittedVarName
                        + " = " + preservedInitializer + ";";
                VariableReference preserved = testCase.addStatement(
                        createUninterpretedStatement(declaredType, code, emittedVarName, declarator));
                GenericClass<?> genericType = null;
                if (declaredType instanceof java.lang.reflect.ParameterizedType) {
                    genericType = GenericClassFactory.get(declaredType);
                }
                scope.register(varName, preserved, genericType);
                continue;
            }

            // Preserve declared variable identity for "Type var = null;".
            // If we model this as a NullReference constant, later references/assignments
            // render as literal "null", producing invalid code such as "null = value;"
            // or "null.method()".
            if (initializer instanceof NullLiteralExpr) {
                if (!markParsedFromLlm) {
                    VariableReference strictNull = handleNullLiteral(declaredType);
                    if (strictNull != null) {
                        scope.register(varName, strictNull, null);
                    }
                    continue;
                }
                Class<?> rawDeclared = getRawClass(declaredType);
                if (rawDeclared != null && rawDeclared.isPrimitive()) {
                    if (markParsedFromLlm) {
                        addWarning(initializer,
                                "Primitive declaration initialized with null; using typed default value");
                        VariableReference primitiveFallback = fallbackForInaccessibleMember(
                                initializer,
                                "Primitive declaration cannot be initialized with null",
                                declaredType);
                        if (primitiveFallback != null) {
                            scope.register(varName, primitiveFallback, null);
                        }
                    } else {
                        addError(initializer, "Primitive declaration cannot be initialized with null: "
                                + declarator.getType() + " " + varName);
                    }
                    continue;
                }

                String code = getFallbackTypeName(declaredType) + " " + emittedVarName + " = null;";
                VariableReference declaredNullVar = testCase.addStatement(
                        createUninterpretedStatement(declaredType, code, emittedVarName, declarator));
                GenericClass<?> genericType = null;
                if (declaredType instanceof java.lang.reflect.ParameterizedType) {
                    genericType = GenericClassFactory.get(declaredType);
                }
                scope.register(varName, declaredNullVar, genericType);
                continue;
            }

            VariableReference varRef = handleExpression(emittedVarName, initializer, declaredType);
            if (varRef != null) {
                varRef = coerceIncompatibleAliasDeclaration(emittedVarName, initializer, declaredType, varRef);
            }
            if (varRef != null) {
                GenericClass<?> genericType = null;
                if (declaredType instanceof java.lang.reflect.ParameterizedType) {
                    genericType = GenericClassFactory.get(declaredType);
                }
                scope.register(varName, varRef, genericType);
            }
        }
    }

    private boolean shouldPreserveAssertionReturningDeclaration(Expression initializer) {
        if (!(initializer instanceof MethodCallExpr)) {
            return false;
        }
        String methodName = ((MethodCallExpr) initializer).getNameAsString();
        return "assertThrows".equals(methodName);
    }

    private String chooseDeclarationName(String requestedName, com.github.javaparser.ast.Node declarationNode) {
        if (!markParsedFromLlm || requestedName == null || requestedName.isEmpty() || !scope.isDefined(requestedName)) {
            return requestedName;
        }
        String candidate;
        do {
            candidate = requestedName + "_" + syntheticVarCounter++;
        } while (scope.isDefined(candidate));
        int line = declarationNode.getBegin().map(p -> p.line).orElse(0);
        result.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.WARNING,
                "Duplicate local variable name '" + requestedName
                        + "' renamed to '" + candidate
                        + "' to keep generated test compilable.",
                line,
                declarationNode.toString()));
        return candidate;
    }

    /**
     * When an LLM declaration aliases an already-defined variable with an incompatible declared type
     * (eg, {@code Integer x = __arg;} where {@code __arg} was parsed as {@code Object}),
     * synthesize a typed cast assignment so generated code remains compilable.
     */
    private VariableReference coerceIncompatibleAliasDeclaration(String varName,
                                                                 Expression initializer,
                                                                 Type declaredType,
                                                                 VariableReference resolvedRef) {
        if (!(initializer instanceof NameExpr) || declaredType == null || resolvedRef == null) {
            return resolvedRef;
        }
        Class<?> formal = getRawClass(declaredType);
        Class<?> actual = resolvedRef.getVariableClass();
        if (formal == null || actual == null || isAssignableFrom(formal, actual)) {
            return resolvedRef;
        }

        String rhsName = ((NameExpr) initializer).getNameAsString();
        String lhsType = getFallbackTypeName(declaredType);
        String castType = formal.isPrimitive()
                ? getFallbackTypeName(box(formal) != null ? box(formal) : formal)
                : lhsType;
        String castExpr = "(" + castType + ") " + rhsName;
        String code = lhsType + " " + varName + " = " + castExpr + ";";

        if (!markParsedFromLlm) {
            addError(initializer, "Incompatible declaration alias: cannot assign " + actual.getTypeName()
                    + " to " + formal.getTypeName());
            return null;
        }

        addWarning(initializer, "Inserted typed cast to preserve compilability for incompatible alias declaration: "
                + code);
        return testCase.addStatement(createUninterpretedStatement(
                declaredType,
                code,
                varName,
                initializer));
    }

    // ========================================================================
    // Mock pattern recognition (Phase 2: EvoSuite's doReturn().when() pattern)
    // ========================================================================

    /**
     * Try to handle a variable declaration as a mock creation with look-ahead
     * for subsequent stubbing calls. Returns 0 if this is not a mock pattern,
     * or the total number of AST statements consumed if it is.
     */
    private int handleVariableDeclarationWithLookahead(
            VariableDeclarationExpr varDeclExpr,
            List<com.github.javaparser.ast.stmt.Statement> allStatements,
            int currentIndex) {
        if (allStatements == null) {
            return 0;
        }

        // Only handle single-variable declarations
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

        // Extract the target class from the first argument (Foo.class)
        Class<?> mockTargetClass = extractMockTargetClass(mockCall);
        if (mockTargetClass == null) {
            return 0;
        }

        // Determine variant: ViolatedAssumptionAnswer vs CALLS_REAL_METHODS vs plain
        MockVariant variant = detectMockVariant(mockCall);

        // Check if we can create a FunctionalMockStatement for this class
        GenericClass<?> targetGenericClass = GenericClassFactory.get(mockTargetClass);
        try {
            if (variant == MockVariant.CALLS_REAL_METHODS) {
                // Verify it's mockable including SUT
                if (!FunctionalMockStatement.canBeFunctionalMockedIncludingSUT(mockTargetClass)) {
                    return 0;
                }
            } else {
                // For regular mocks, try but fall back if not mockable
                if (!FunctionalMockStatement.canBeFunctionalMocked(mockTargetClass)) {
                    return 0;
                }
            }
        } catch (Exception e) {
            return 0;
        }

        // Create the FunctionalMockStatement
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
            // Class cannot be mocked — fall back to regular parsing
            logger.debug("Cannot create FunctionalMockStatement for {}: {}",
                    mockTargetClass.getName(), e.getMessage());
            return 0;
        }

        // Collect stubbing calls from subsequent statements
        int stubbingsConsumed = collectAndApplyStubbings(
                mockStmt, varName, mockTargetClass, targetGenericClass,
                allStatements, currentIndex + 1);

        // Add the fully populated statement to the test case
        VariableReference varRef = testCase.addStatement(mockStmt);
        scope.register(varName, varRef, targetGenericClass);

        return 1 + stubbingsConsumed;
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
        if (!innerMethodCall.getScope().isPresent() || !(innerMethodCall.getScope().get() instanceof NameExpr)) {
            return null;
        }

        String mockVarName = ((NameExpr) innerMethodCall.getScope().get()).getNameAsString();
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
                whenCall.clone());
    }

    private boolean isMockitoWhenCall(MethodCallExpr whenCall) {
        if (whenCall == null || !"when".equals(whenCall.getNameAsString())) {
            return false;
        }
        if (!whenCall.getScope().isPresent()) {
            return true;
        }
        Class<?> scopeClass = resolveClassFromExpression(whenCall.getScope().get());
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
            return markParsedFromLlm && "thenThrow".equals(methodName);
        }
        return false;
    }

    private NodeList<Expression> cloneArguments(MethodCallExpr methodCallExpr) {
        NodeList<Expression> clonedArguments = new NodeList<>();
        for (Expression argument : methodCallExpr.getArguments()) {
            clonedArguments.add(argument.clone());
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
                    whenCall.clone());
        }
    }

    private enum MockVariant {
        VIOLATED_ASSUMPTION_ANSWER,
        CALLS_REAL_METHODS,
        PLAIN
    }

    /**
     * Check if a method call expression is a Mockito mock() creation.
     */
    private boolean isMockCreation(Expression expr) {
        if (!(expr instanceof MethodCallExpr)) {
            return false;
        }
        MethodCallExpr call = (MethodCallExpr) expr;
        String name = call.getNameAsString();
        if (!"mock".equals(name)) {
            return false;
        }
        if (call.getArguments().isEmpty()) {
            return false;
        }

        // First arg should be ClassName.class
        Expression firstArg = call.getArgument(0);
        return firstArg instanceof ClassExpr;
    }

    /**
     * Extract the target class from a mock(Foo.class, ...) call.
     */
    private Class<?> extractMockTargetClass(MethodCallExpr mockCall) {
        return extractMockTargetClass(mockCall.getArgument(0));
    }

    /**
     * Detect the mock variant from the arguments.
     */
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

    private VariableReference tryHandleLlmMockitoMockCall(MethodCallExpr expr,
                                                          String methodName,
                                                          Class<?> targetClass,
                                                          boolean staticCall,
                                                          List<VariableReference> argRefs) {
        if (!markParsedFromLlm || !staticCall || !"mock".equals(methodName) || !isMockitoClass(targetClass)) {
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
                return testCase.addStatement(new FunctionalMockForAbstractClassStatement(
                        testCase, mockTargetClass, targetGenericClass));
            }

            if (!FunctionalMockStatement.canBeFunctionalMocked(mockTargetClass)) {
                return null;
            }
            return testCase.addStatement(new FunctionalMockStatement(
                    testCase, mockTargetClass, targetGenericClass));
        } catch (IllegalArgumentException e) {
            logger.debug("Cannot normalize Mockito.mock({}) into FunctionalMockStatement: {}",
                    mockTargetClass.getName(), e.getMessage());
            return null;
        }
    }

    private boolean isMockitoClass(Class<?> targetClass) {
        if (targetClass == null) {
            return false;
        }
        String name = targetClass.getName();
        return "org.mockito.Mockito".equals(name)
                || "shaded.org.evosuite.shaded.org.mockito.Mockito".equals(name);
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

        Expression unwrapped = unwrapMockClassArgument(firstArgument);
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

    private Expression unwrapMockClassArgument(Expression expression) {
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

    /**
     * Scan subsequent AST statements for doReturn().when(mockVar).method() patterns,
     * parse them, and add stubbings to the mock statement.
     *
     * @return the number of stubbing statements consumed
     */
    private int collectAndApplyStubbings(
            FunctionalMockStatement mockStmt,
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

            mockStmt.addMethodStubbing(stubbing.descriptor, stubbing.returnValues);
            consumed++;
        }
        return consumed;
    }

    /**
     * Info holder for a single parsed stubbing.
     */
    private static class StubbingInfo {
        final MethodDescriptor descriptor;
        final List<VariableReference> returnValues;

        StubbingInfo(MethodDescriptor descriptor, List<VariableReference> returnValues) {
            this.descriptor = descriptor;
            this.returnValues = returnValues;
        }
    }

    /**
     * Try to parse an expression as a stubbing chain. Supports two patterns:
     * <ul>
     *   <li>doReturn(v0, v1).when(mockVar).method(matchers)</li>
     *   <li>when(mockVar.method(args)).thenReturn(v0, v1)</li>
     * </ul>
     *
     * @return StubbingInfo if parsed successfully, null otherwise
     */
    private StubbingInfo parseStubbingChain(Expression expr, String mockVarName,
                                            Class<?> targetClass,
                                            GenericClass<?> targetGenericClass) {
        if (!(expr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr outerCall = (MethodCallExpr) expr;

        // Try pattern 1: doReturn(...).when(mockVar).method(matchers)
        StubbingInfo info = parseDoReturnWhenPattern(outerCall, mockVarName, targetClass, targetGenericClass);
        if (info != null) {
            return info;
        }

        // Try pattern 2: when(mockVar.method(args)).thenReturn(v0, v1)
        info = parseWhenThenReturnPattern(outerCall, mockVarName, targetClass, targetGenericClass);
        return info;
    }

    /**
     * Parse doReturn(v0, v1).when(mockVar).method(matchers) pattern.
     * The structure is: MethodCallExpr[name=method, scope=MethodCallExpr[name=when,
     *   scope=MethodCallExpr[name=doReturn]]]
     */
    private StubbingInfo parseDoReturnWhenPattern(MethodCallExpr outerCall, String mockVarName,
                                                  Class<?> targetClass,
                                                  GenericClass<?> targetGenericClass) {
        // outerCall is .method(matchers)
        String stubbedMethodName = outerCall.getNameAsString();

        // scope should be doReturn(...).when(mockVar)
        if (!outerCall.getScope().isPresent()) {
            return null;
        }
        Expression whenCallExpr = outerCall.getScope().get();
        if (!(whenCallExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr whenCall = (MethodCallExpr) whenCallExpr;

        if (!"when".equals(whenCall.getNameAsString())) {
            return null;
        }

        // when() should have one argument: the mock variable
        if (whenCall.getArguments().size() != 1) {
            return null;
        }
        Expression whenArg = whenCall.getArgument(0);
        if (!(whenArg instanceof NameExpr)) {
            return null;
        }
        if (!mockVarName.equals(((NameExpr) whenArg).getNameAsString())) {
            return null;
        }

        // scope of when() should be doReturn(...)
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

        // Resolve the method on the target class
        Method method = resolveMethodByNameLoose(targetClass, stubbedMethodName);
        if (method == null) {
            return null;
        }
        // Extract the return values from doReturn() arguments
        List<VariableReference> returnValues = resolveReturnValueArguments(
                doReturnCall.getArguments(), method.getGenericReturnType());

        MethodDescriptor descriptor = new MethodDescriptor(method, targetGenericClass);
        // Set the counter to the number of return values
        for (int i = 0; i < returnValues.size(); i++) {
            descriptor.increaseCounter();
        }

        return new StubbingInfo(descriptor, returnValues);
    }

    /**
     * Parse when(mockVar.method(args)).thenReturn(v0, v1) pattern.
     * The structure is: MethodCallExpr[name=thenReturn, scope=MethodCallExpr[name=when]]
     */
    private StubbingInfo parseWhenThenReturnPattern(MethodCallExpr outerCall, String mockVarName,
                                                    Class<?> targetClass,
                                                    GenericClass<?> targetGenericClass) {
        // outerCall should be .thenReturn(v0, v1)
        if (!"thenReturn".equals(outerCall.getNameAsString())) {
            return null;
        }

        // scope should be when(mockVar.method(args))
        if (!outerCall.getScope().isPresent()) {
            return null;
        }
        Expression whenExpr = outerCall.getScope().get();
        if (!(whenExpr instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr whenCall = (MethodCallExpr) whenExpr;

        if (!"when".equals(whenCall.getNameAsString())) {
            return null;
        }
        if (whenCall.getArguments().size() != 1) {
            return null;
        }

        // The argument to when() should be mockVar.method(args)
        Expression whenArg = whenCall.getArgument(0);
        if (!(whenArg instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr innerMethodCall = (MethodCallExpr) whenArg;

        // Check that the scope of the inner call is our mock variable
        if (!innerMethodCall.getScope().isPresent()) {
            return null;
        }
        Expression innerScope = innerMethodCall.getScope().get();
        if (!(innerScope instanceof NameExpr)) {
            return null;
        }
        if (!mockVarName.equals(((NameExpr) innerScope).getNameAsString())) {
            return null;
        }

        String stubbedMethodName = innerMethodCall.getNameAsString();

        // Resolve the method on the target class
        Method method = resolveMethodByNameLoose(targetClass, stubbedMethodName);
        if (method == null) {
            return null;
        }
        // Extract return values from thenReturn arguments
        List<VariableReference> returnValues = resolveReturnValueArguments(
                outerCall.getArguments(), method.getGenericReturnType());

        MethodDescriptor descriptor = new MethodDescriptor(method, targetGenericClass);
        for (int i = 0; i < returnValues.size(); i++) {
            descriptor.increaseCounter();
        }

        return new StubbingInfo(descriptor, returnValues);
    }

    /**
     * Resolve return value arguments from a doReturn() or thenReturn() call.
     * Each argument is parsed as a regular expression to create the appropriate statement.
     */
    private List<VariableReference> resolveReturnValueArguments(List<Expression> args, Type returnType) {
        List<VariableReference> refs = new ArrayList<>();
        for (Expression arg : args) {
            VariableReference ref = resolveArgument(arg, returnType != null ? returnType : Object.class);
            if (ref == null && markParsedFromLlm) {
                ref = fallbackForInaccessibleMember(
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

    /**
     * Find a method on a class by name alone. When there are multiple overloads,
     * prefer the one with no parameters, then fall back to the first found.
     * Returns null if no method with that name exists.
     */
    private Method resolveMethodByNameLoose(Class<?> clazz, String name) {
        Method noArgs = null;
        Method first = null;
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) {
                if (first == null) {
                    first = m;
                }
                if (m.getParameterCount() == 0) {
                    noArgs = m;
                }
            }
        }
        // Also check declared methods
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                if (first == null) {
                    first = m;
                }
                if (m.getParameterCount() == 0 && noArgs == null) {
                    noArgs = m;
                }
            }
        }
        return noArgs != null ? noArgs : first;
    }

    // ========================================================================
    // Expression dispatch (returns VariableReference for the result)
    // ========================================================================

    /**
     * Handle an expression that produces a value. Creates the appropriate EvoSuite
     * statement and returns the VariableReference for the result.
     *
     * @param varName      the variable name to register (may be synthetic)
     * @param expr         the JavaParser expression
     * @param declaredType the declared type from the LHS (or inferred)
     * @return VariableReference for the result, or null on failure
     */
    VariableReference handleExpression(String varName, Expression expr, Type declaredType) {
        // Unwrap cast: (Type) expr → handle inner expression with cast type
        if (expr instanceof CastExpr) {
            CastExpr castExpr = (CastExpr) expr;
            if (markParsedFromLlm && castExpr.getExpression() instanceof NullLiteralExpr) {
                // LLM output frequently over-casts null literals (e.g. (Object) null),
                // which can force false argument mismatches for generic APIs and leak
                // uncompilable casts into emitted tests. Keep only the null literal and
                // let method/constructor resolution retype it to the formal parameter.
                addWarning(expr, "Ignoring cast on null literal in LLM mode: " + castExpr.getType());
                return handleExpression(varName, castExpr.getExpression(), declaredType);
            }
            try {
                Type castType = typeResolver.resolveType(castExpr.getType());
                return handleExpression(varName, castExpr.getExpression(), castType);
            } catch (ClassNotFoundException e) {
                if (markParsedFromLlm && castExpr.getExpression() instanceof NullLiteralExpr) {
                    // LLMs sometimes cast null to invented/unresolvable helper types
                    // (eg "(OutputDestination[]) null"). Keep the null literal and let
                    // method/constructor resolution retype it to the actual formal type.
                    addWarning(expr, "Cannot resolve cast type for null literal; ignoring cast in LLM mode: "
                            + e.getMessage());
                    return handleExpression(varName, castExpr.getExpression(), declaredType);
                }
                addError(expr, "Cannot resolve cast type: " + e.getMessage());
                return null;
            }
        }

        // Literals
        if (expr instanceof IntegerLiteralExpr) {
            return handleIntLiteral(varName, (IntegerLiteralExpr) expr, declaredType);
        }
        if (expr instanceof LongLiteralExpr) {
            return handleLongLiteral((LongLiteralExpr) expr);
        }
        if (expr instanceof DoubleLiteralExpr) {
            return handleDoubleLiteral((DoubleLiteralExpr) expr, declaredType);
        }
        if (expr instanceof BooleanLiteralExpr) {
            return handleBooleanLiteral((BooleanLiteralExpr) expr);
        }
        if (expr instanceof CharLiteralExpr) {
            return handleCharLiteral((CharLiteralExpr) expr);
        }
        if (expr instanceof StringLiteralExpr) {
            return handleStringLiteral((StringLiteralExpr) expr);
        }
        if (expr instanceof NullLiteralExpr) {
            return handleNullLiteral(declaredType);
        }
        if (expr instanceof TextBlockLiteralExpr) {
            return handleTextBlockLiteral((TextBlockLiteralExpr) expr);
        }

        // Constructor: new Type(args)
        if (expr instanceof ObjectCreationExpr) {
            return handleObjectCreation((ObjectCreationExpr) expr, declaredType, varName);
        }

        // Method call: obj.method(args) or Class.staticMethod(args)
        if (expr instanceof MethodCallExpr) {
            return handleMethodCall((MethodCallExpr) expr, declaredType, varName);
        }

        // Field access: obj.field or Class.FIELD
        if (expr instanceof FieldAccessExpr) {
            return handleFieldAccess((FieldAccessExpr) expr, declaredType, varName);
        }

        // Class literal: Foo.class
        if (expr instanceof ClassExpr) {
            return handleClassExpression((ClassExpr) expr);
        }

        // Array creation: new Type[n] or new Type[]{...}
        if (expr instanceof ArrayCreationExpr) {
            return handleArrayCreation((ArrayCreationExpr) expr);
        }

        // Array access read: arr[i]
        if (expr instanceof ArrayAccessExpr) {
            return handleArrayAccessRead((ArrayAccessExpr) expr);
        }

        // Array initializer: {1, 2, 3} (rarely standalone, usually in ArrayCreationExpr)
        if (expr instanceof ArrayInitializerExpr) {
            return handleArrayInitializer((ArrayInitializerExpr) expr, declaredType);
        }

        // Binary expression: a + b, x == y
        if (expr instanceof BinaryExpr) {
            return handleBinaryExpression(varName, (BinaryExpr) expr, declaredType);
        }

        // Name reference: existing variable
        if (expr instanceof NameExpr) {
            String name = ((NameExpr) expr).getNameAsString();
            VariableReference ref = scope.resolve(name);
            if (ref != null) {
                return ref;
            }
            // Not in scope. Emit a clear diagnostic whether or not the token happens
            // to also be a class name — a bare class reference is not a valid
            // value-producing expression and previously silently fell through to
            // the generic "unsupported expression" branch below.
            boolean isClassName;
            try {
                typeResolver.resolveClass(name);
                isClassName = true;
            } catch (ClassNotFoundException e) {
                isClassName = false;
            }
            String msg = isClassName
                    ? "Bare class name used as value expression: " + name
                            + " (did you mean " + name + ".class?)"
                    : "Unresolved variable: " + name;
            addError(expr, msg);
            return null;
        }

        // Unary expression: -5, +3, !flag
        if (expr instanceof UnaryExpr) {
            return handleUnaryExpression(varName, (UnaryExpr) expr, declaredType);
        }

        // Enclosed expression: (expr)
        if (expr instanceof EnclosedExpr) {
            return handleExpression(varName, ((EnclosedExpr) expr).getInner(), declaredType);
        }

        // Lambda expression: preserve as UninterpretedStatement.
        // If it appears in a declaration/assignment value position, keep the typed
        // declaration shape so later emitted JUnit remains compilable.
        if (expr instanceof LambdaExpr) {
            addWarning(expr, "Lambda expression preserved as UninterpretedStatement");
            Type effectiveType = declaredType == null ? Object.class : declaredType;
            if (!isFunctionalInterfaceType(effectiveType)) {
                String message = "Lambda expression requires a functional interface target type";
                if (markParsedFromLlm) {
                    return fallbackForUnresolvedExpression(expr, effectiveType, message);
                }
                addError(expr, message + ": " + effectiveType.getTypeName());
                return null;
            }
            if (varName != null && !varName.trim().isEmpty()) {
                String code = getFallbackTypeName(effectiveType) + " " + varName + " = " + expr + ";";
                UninterpretedStatement stmt = createUninterpretedStatement(effectiveType, code, varName, expr);
                return testCase.addStatement(stmt);
            }
            String message = "Standalone lambda expression has no declaration target type";
            if (markParsedFromLlm) {
                return fallbackForUnresolvedExpression(expr, effectiveType, message);
            }
            addError(expr, message);
            return null;
        }

        // Unsupported
        addWarning(expr, "Unsupported expression type: " + expr.getClass().getSimpleName());
        return null;
    }

    // ========================================================================
    // Primitive literal handlers
    // ========================================================================

    private VariableReference handleIntLiteral(String varName, IntegerLiteralExpr expr, Type declaredType) {
        // IntegerLiteralExpr can also be used for byte/short with a cast
        long value = expr.asNumber().longValue();
        seedConstantPool((int) value);

        if (declaredType == byte.class || declaredType == Byte.class) {
            BytePrimitiveStatement stmt = new BytePrimitiveStatement(testCase, (byte) value);
            return testCase.addStatement(stmt);
        }
        if (declaredType == short.class || declaredType == Short.class) {
            ShortPrimitiveStatement stmt = new ShortPrimitiveStatement(testCase, (short) value);
            return testCase.addStatement(stmt);
        }
        IntPrimitiveStatement stmt = new IntPrimitiveStatement(testCase, (int) value);
        return testCase.addStatement(stmt);
    }

    private VariableReference handleLongLiteral(LongLiteralExpr expr) {
        long value = expr.asNumber().longValue();
        seedConstantPool(value);
        LongPrimitiveStatement stmt = new LongPrimitiveStatement(testCase, value);
        return testCase.addStatement(stmt);
    }

    private VariableReference handleDoubleLiteral(DoubleLiteralExpr expr, Type declaredType) {
        double value = expr.asDouble();
        seedConstantPool(value);
        // JavaParser uses DoubleLiteralExpr for both double and float literals.
        // Keep explicit float suffixes (e.g. "1.0f") as float even when the
        // surrounding declared type is still unknown (Object.class).
        String token = expr.getValue();
        boolean hasFloatSuffix = token != null
                && !token.isEmpty()
                && (token.endsWith("f") || token.endsWith("F"));
        if (declaredType == float.class || declaredType == Float.class || hasFloatSuffix) {
            FloatPrimitiveStatement stmt = new FloatPrimitiveStatement(testCase, (float) value);
            return testCase.addStatement(stmt);
        }
        DoublePrimitiveStatement stmt = new DoublePrimitiveStatement(testCase, value);
        return testCase.addStatement(stmt);
    }

    private VariableReference handleBooleanLiteral(BooleanLiteralExpr expr) {
        // Don't seed booleans — only two possible values
        BooleanPrimitiveStatement stmt = new BooleanPrimitiveStatement(testCase, expr.getValue());
        return testCase.addStatement(stmt);
    }

    private VariableReference handleCharLiteral(CharLiteralExpr expr) {
        seedConstantPool((int) expr.asChar());
        CharPrimitiveStatement stmt = new CharPrimitiveStatement(testCase, expr.asChar());
        return testCase.addStatement(stmt);
    }

    private VariableReference handleStringLiteral(StringLiteralExpr expr) {
        seedConstantPool(expr.asString());
        StringPrimitiveStatement stmt = new StringPrimitiveStatement(testCase, expr.asString());
        return testCase.addStatement(stmt);
    }

    private VariableReference handleTextBlockLiteral(TextBlockLiteralExpr expr) {
        seedConstantPool(expr.asString());
        StringPrimitiveStatement stmt = new StringPrimitiveStatement(testCase, expr.asString());
        return testCase.addStatement(stmt);
    }

    private VariableReference handleUnaryExpression(String varName, UnaryExpr expr, Type declaredType) {
        // Handle unary minus on numeric literals: -5 → IntPrimitiveStatement(-5)
        if (expr.getOperator() == UnaryExpr.Operator.MINUS
                && expr.getExpression() instanceof IntegerLiteralExpr) {
            int value = -((IntegerLiteralExpr) expr.getExpression()).asNumber().intValue();
            seedConstantPool(value);
            if (declaredType == byte.class || declaredType == Byte.class) {
                return testCase.addStatement(new BytePrimitiveStatement(testCase, (byte) value));
            }
            if (declaredType == short.class || declaredType == Short.class) {
                return testCase.addStatement(new ShortPrimitiveStatement(testCase, (short) value));
            }
            return testCase.addStatement(new IntPrimitiveStatement(testCase, value));
        }
        if (expr.getOperator() == UnaryExpr.Operator.MINUS
                && expr.getExpression() instanceof LongLiteralExpr) {
            long value = -((LongLiteralExpr) expr.getExpression()).asNumber().longValue();
            seedConstantPool(value);
            return testCase.addStatement(new LongPrimitiveStatement(testCase, value));
        }
        if (expr.getOperator() == UnaryExpr.Operator.MINUS
                && expr.getExpression() instanceof DoubleLiteralExpr) {
            double value = -((DoubleLiteralExpr) expr.getExpression()).asDouble();
            seedConstantPool(value);
            if (declaredType == float.class || declaredType == Float.class) {
                return testCase.addStatement(new FloatPrimitiveStatement(testCase, (float) value));
            }
            return testCase.addStatement(new DoublePrimitiveStatement(testCase, value));
        }
        // Unary plus: +5 → just the inner expression
        if (expr.getOperator() == UnaryExpr.Operator.PLUS) {
            return handleExpression(varName, expr.getExpression(), declaredType);
        }
        // Other unary operators (!, ~, ++, --): preserve as uninterpreted, but
        // keep value-producing context compilable by materializing a typed assignment.
        addWarning(expr, "Unsupported unary operator preserved as UninterpretedStatement: " + expr.getOperator());
        if (varName != null && !varName.trim().isEmpty()) {
            Type effectiveType = declaredType == null ? Object.class : declaredType;
            String code = getFallbackTypeName(effectiveType) + " " + varName + " = " + expr.toString() + ";";
            UninterpretedStatement stmt = createUninterpretedStatement(effectiveType, code, varName, expr);
            return testCase.addStatement(stmt);
        }
        UninterpretedStatement stmt = createUninterpretedStatement(expr, expr.toString() + ";");
        return testCase.addStatement(stmt);
    }

    private VariableReference handleNullLiteral(Type declaredType) {
        Class<?> rawClass = getRawClass(declaredType);

        // String null → StringPrimitiveStatement(null), not NullStatement
        if (rawClass == String.class) {
            StringPrimitiveStatement stmt = new StringPrimitiveStatement(testCase, null);
            return testCase.addStatement(stmt);
        }

        // When the declared type is Object (i.e., unknown — no type hint from
        // a resolved parameter), use Void.class as the null sentinel so that
        // isAssignableFrom matches any reference type during constructor/method
        // resolution.  retypeNullArguments() fixes these up to the actual
        // parameter type once the target method/constructor is resolved.
        Type nullType = (rawClass == Object.class) ? Void.class : declaredType;
        NullStatement stmt = new NullStatement(testCase, nullType);
        return testCase.addStatement(stmt);
    }

    // ========================================================================
    // Constructor: new Type(args)
    // ========================================================================

    private VariableReference handleObjectCreation(ObjectCreationExpr expr, Type declaredType, String targetVarName) {
        Class<?> rawClass = null;
        try {
            if (markParsedFromLlm && expr.getAnonymousClassBody().isPresent()) {
                VariableReference normalizedAnonymousMock =
                        tryNormalizeAnonymousInterfaceCreationToMock(expr, declaredType);
                if (normalizedAnonymousMock != null) {
                    return normalizedAnonymousMock;
                }
                return preserveAnonymousObjectCreation(expr, declaredType, targetVarName);
            }
            // Resolve the class being constructed
            // Keep package qualifiers but strip generic arguments (e.g., new java.io.File(...),
            // new ArrayList<>() -> "ArrayList").
            String typeName = expr.getType().getNameWithScope();
            rawClass = resolveClassWithLlmMockFallback(typeName);

            // Pre-resolve arguments without type hints to find the constructor
            int argumentCheckpoint = testCase.size();
            List<VariableReference> argRefs = resolveArguments(expr.getArguments(), null, null);

            // Find matching constructor
            Class<?>[] argTypes = getArgTypes(argRefs);
            Constructor<?> constructor;
            Class<?> constructorTargetClass = chooseOverrideMockConstructorTarget(rawClass);
            try {
                constructor = resolveConstructor(constructorTargetClass, argTypes);
            } catch (NoSuchMethodException e) {
                if (constructorTargetClass != rawClass) {
                    try {
                        constructor = resolveConstructor(rawClass, argTypes);
                    } catch (NoSuchMethodException originalCtorError) {
                        return failOrFallbackWithRollback(expr, declaredType, rawClass,
                                argumentCheckpoint,
                                "No matching constructor: " + originalCtorError.getMessage());
                    }
                } else {
                    return failOrFallbackWithRollback(expr, declaredType, rawClass,
                            argumentCheckpoint,
                            "No matching constructor: " + e.getMessage());
                }
            }

            if (!isAccessibleMember(constructor)) {
                return failOrTypedFallbackWithRollback(expr, declaredType, rawClass, targetVarName,
                        argumentCheckpoint,
                        rawClass.getSimpleName() + " constructor has private access");
            }

            // Re-type Void-typed null arguments now that we know the parameter types
            retypeNullArguments(argRefs, constructor.getParameterTypes(), constructor.isVarArgs());

            // Validate argument types against constructor parameter types
            String mismatch = validateArgumentTypes(argRefs, constructor.getParameterTypes(),
                    constructor.getGenericParameterTypes(), expr, constructor.isVarArgs());
            if (mismatch != null) {
                return failOrFallbackWithRollback(expr, declaredType, rawClass,
                        argumentCheckpoint,
                        "Constructor argument mismatch: " + mismatch);
            }
            argRefs = normalizeVarArgsArguments(argRefs, constructor.getParameterTypes(), constructor.isVarArgs());

            // Handle diamond type inference
            Type constructedType;
            if (constructor.getDeclaringClass() != rawClass) {
                // Constructor was rewritten to a compatible OverrideMock subclass.
                // Keep the mock type in the model so generated code dispatches mocked behavior.
                constructedType = constructor.getDeclaringClass();
            } else {
                if (expr.getType().getTypeArguments().isPresent()
                        && expr.getType().getTypeArguments().get().isEmpty()) {
                    // Diamond: new HashMap<>() — infer from LHS
                    constructedType = typeResolver.inferDiamondType(rawClass, declaredType);
                } else {
                    constructedType = typeResolver.resolveType(expr.getType());
                }
            }

            GenericClass<?> ownerClass = GenericClassFactory.get(constructedType);
            TestClusterUtils.makeAccessible(constructor);
            GenericConstructor genericConstructor = new GenericConstructor(constructor, ownerClass);

            ConstructorStatement stmt = new ConstructorStatement(testCase, genericConstructor, argRefs);
            return testCase.addStatement(stmt);

        } catch (Exception e) {
            return failOrFallbackWithRollback(expr, declaredType, rawClass,
                    testCase.size(),
                    "Failed to parse constructor: " + e.getMessage());
        }
    }

    private VariableReference tryNormalizeAnonymousInterfaceCreationToMock(ObjectCreationExpr expr, Type declaredType) {
        Type targetType = declaredType;
        if (targetType == null || targetType == Object.class) {
            try {
                targetType = typeResolver.resolveType(expr.getType());
            } catch (ClassNotFoundException e) {
                Type democked = tryResolveDemockedDeclaredType(expr.getType());
                targetType = democked != null ? democked : Object.class;
            }
        }

        Class<?> rawTargetClass = getRawClass(targetType);
        if (rawTargetClass == null || !rawTargetClass.isInterface()) {
            return null;
        }
        if (!FunctionalMockStatement.canBeFunctionalMocked(rawTargetClass)) {
            return null;
        }

        GenericClass<?> targetGenericClass = targetType instanceof java.lang.reflect.ParameterizedType
                ? GenericClassFactory.get(targetType)
                : GenericClassFactory.get(rawTargetClass);
        addWarning(expr, "Normalized anonymous interface implementation to FunctionalMockStatement; discarded anonymous body");
        return testCase.addStatement(new FunctionalMockStatement(
                testCase, rawTargetClass, targetGenericClass));
    }

    private VariableReference preserveAnonymousObjectCreation(ObjectCreationExpr expr,
                                                              Type declaredType,
                                                              String targetVarName) {
        Type preservedType = declaredType;
        if (preservedType == null || preservedType == Object.class) {
            try {
                preservedType = typeResolver.resolveType(expr.getType());
            } catch (ClassNotFoundException e) {
                Type democked = tryResolveDemockedDeclaredType(expr.getType());
                preservedType = democked != null ? democked : Object.class;
            }
        }

        String preservedTypeName = getFallbackTypeName(preservedType);
        ObjectCreationExpr preservedExpr = expr.clone();
        preservedExpr.setType(StaticJavaParser.parseClassOrInterfaceType(preservedTypeName));

        addWarning(expr, "Preserved anonymous class implementation as raw Java source");
        if (targetVarName != null && !targetVarName.trim().isEmpty()) {
            String code = preservedTypeName + " " + targetVarName + " = " + preservedExpr + ";";
            return testCase.addStatement(
                    createUninterpretedStatement(preservedType, code, targetVarName, expr));
        }

        return testCase.addStatement(createUninterpretedStatement(expr, preservedExpr + ";"));
    }

    /**
     * In LLM best-effort parsing, prefer a drop-in OverrideMock constructor target
     * when available and assignable to the declared raw class.
     */
    private Class<?> chooseOverrideMockConstructorTarget(Class<?> rawClass) {
        Class<?> mockClass = getCompatibleOverrideMockClass(rawClass);
        return mockClass != null ? mockClass : rawClass;
    }

    /**
     * In LLM best-effort parsing, prefer a drop-in OverrideMock class for static
     * helper/factory methods (e.g., File.createTempFile -> MockFile.createTempFile)
     * when the mock provides a compatible overload.
     */
    private Class<?> chooseOverrideMockStaticMethodTarget(Class<?> rawClass,
                                                          String methodName,
                                                          Class<?>[] argTypes) {
        if (!markParsedFromLlm || rawClass == null) {
            return rawClass;
        }
        Class<?> mockClass = getCompatibleOverrideMockClass(rawClass);
        if (mockClass == null) {
            return rawClass;
        }
        try {
            Method method = resolveMethod(mockClass, methodName, argTypes);
            if (!Modifier.isStatic(method.getModifiers())) {
                return rawClass;
            }
            return mockClass;
        } catch (NoSuchMethodException ignored) {
            return rawClass;
        }
    }

    private Class<?> getCompatibleOverrideMockClass(Class<?> rawClass) {
        if (!markParsedFromLlm || rawClass == null) {
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

    private Class<?> resolveClassWithLlmMockFallback(String typeName) throws ClassNotFoundException {
        try {
            return typeResolver.resolveClass(typeName);
        } catch (ClassNotFoundException e) {
            if (!markParsedFromLlm) {
                throw e;
            }
            String democked = demockTypeTokens(typeName);
            if (!democked.equals(typeName)) {
                return typeResolver.resolveClass(democked);
            }
            throw e;
        }
    }

    private Type tryResolveDemockedDeclaredType(com.github.javaparser.ast.type.Type originalType) {
        try {
            String typeText = originalType.toString();
            String democked = demockTypeTokens(typeText);
            if (democked.equals(typeText)) {
                return null;
            }
            // Use a local JavaParser so we don't touch StaticJavaParser's shared config.
            com.github.javaparser.ParseResult<com.github.javaparser.ast.type.Type> parsed =
                    new com.github.javaparser.JavaParser().parseType(democked);
            com.github.javaparser.ast.type.Type demockedType = parsed.getResult().orElse(null);
            if (demockedType == null) {
                return null;
            }
            return typeResolver.resolveType(demockedType);
        } catch (Exception ignored) {
            return null;
        }
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

    // ========================================================================
    // Method call: obj.method(args) or Class.staticMethod(args)
    // ========================================================================

    private VariableReference handleMethodCall(MethodCallExpr expr, Type declaredType, String targetVarName) {
        try {
            String methodName = expr.getNameAsString();
            VariableReference callee = null;
            Class<?> targetClass;
            boolean staticCall = false;

            if (expr.getScope().isPresent()) {
                Expression scopeExpr = expr.getScope().get();
                if (markParsedFromLlm
                        && scopeExpr instanceof ObjectCreationExpr
                        && tryHandleLegacyScopedInvokeHelperCall(expr, (ObjectCreationExpr) scopeExpr)) {
                    return null; // helper wrapper call handled as reflective private method call
                }
                callee = resolveCalleeOrClass(scopeExpr);

                if (callee != null) {
                    // Instance method: callee.method(args)
                    targetClass = getRawClass(callee.getType());
                } else {
                    // Static method: Class.method(args) — scopeExpr is a class name
                    staticCall = true;
                    targetClass = resolveClassFromExpression(scopeExpr);
                    if (targetClass == null) {
                        return failOrFallback(expr, declaredType, null,
                                "Cannot resolve method scope: " + scopeExpr);
                    }
                }
            } else {
                if (markParsedFromLlm && tryHandleLegacyFieldHelperCall(expr)) {
                    return null; // void helper call handled as reflective statement
                }
                if (markParsedFromLlm && tryHandleLegacyInvokeHelperCall(expr)) {
                    return null; // helper wrapper call handled as reflective private method call
                }
                // Unscoped method call — could be static import
                String staticClass = typeResolver.resolveStaticImportClass(methodName);
                if (staticClass != null) {
                    staticCall = true;
                    targetClass = typeResolver.resolveClass(staticClass);
                } else {
                    if (markParsedFromLlm) {
                        MethodDeclaration inlineHelper = inlineHelperMethods.get(
                                inlineHelperKey(methodName, expr.getArguments().size()));
                        if (inlineHelper != null) {
                            Expression inlinedReturn = instantiateInlineHelperReturn(inlineHelper, expr.getArguments());
                            if (inlinedReturn != null) {
                                Type helperType = (declaredType == null) ? Object.class : declaredType;
                                return handleExpression("__inlinedHelper" + syntheticVarCounter++,
                                        inlinedReturn, helperType);
                            }
                            Expression inlinedSideEffect = instantiateInlineHelperExpression(inlineHelper,
                                    expr.getArguments());
                            if (inlinedSideEffect != null && isVoidContext(declaredType, targetVarName)) {
                                handleExpressionStatement(inlinedSideEffect);
                                return null;
                            }
                        }
                    }
                    return failOrFallback(expr, declaredType, null,
            "Cannot resolve unscoped method call: " + methodName);
                }
            }

            if (staticCall) {
                if (!hasMethodNamed(targetClass, methodName, true)) {
                    return failOrTypedFallback(expr, declaredType, null, targetVarName,
                            "No static method named " + methodName + " in " + targetClass.getSimpleName());
                }
            } else {
                if (!hasMethodNamed(targetClass, methodName, false)) {
                    return failOrTypedFallback(expr, declaredType, null, targetVarName,
                            "No method named " + methodName + " in " + targetClass.getSimpleName());
                }
            }

            // Resolve arguments
            int argumentCheckpoint = testCase.size();
            List<VariableReference> argRefs = resolveArguments(expr.getArguments(), null, null);

            VariableReference normalizedMockitoMock = tryHandleLlmMockitoMockCall(
                    expr, methodName, targetClass, staticCall, argRefs);
            if (normalizedMockitoMock != null) {
                return normalizedMockitoMock;
            }

            // Find matching method
            Class<?>[] argTypes = getArgTypes(argRefs);
            Class<?> methodTargetClass = staticCall
                    ? chooseOverrideMockStaticMethodTarget(targetClass, methodName, argTypes)
                    : targetClass;
            Method method;
            try {
                method = resolveMethod(methodTargetClass, methodName, argTypes);
            } catch (NoSuchMethodException e) {
                return failOrTypedFallbackWithRollback(expr, declaredType,
                        inferFallbackMethodReturnType(methodTargetClass, methodName, declaredType),
                        targetVarName,
                        argumentCheckpoint,
                        "No matching method: " + e.getMessage());
            }

            if (!isAccessibleMember(method)) {
                return failOrTypedFallbackWithRollback(expr, declaredType, method.getGenericReturnType(), targetVarName,
                        argumentCheckpoint,
                        method.getName() + "() has private access in " + methodTargetClass.getSimpleName());
            }

            // Re-type Void-typed null arguments now that we know the parameter types
            retypeNullArguments(argRefs, method.getParameterTypes(), method.isVarArgs());

            // Validate argument types against method parameter types (generics + casts)
            String mismatch = validateArgumentTypes(argRefs, method.getParameterTypes(),
                    method.getGenericParameterTypes(), expr, method.isVarArgs());
            if (mismatch != null) {
                return failOrTypedFallbackWithRollback(expr, declaredType, method.getGenericReturnType(), targetVarName,
                        argumentCheckpoint,
                        "Method argument mismatch: " + mismatch);
            }
            argRefs = normalizeVarArgsArguments(argRefs, method.getParameterTypes(), method.isVarArgs());

            GenericClass<?> ownerClass;
            if (!staticCall && callee != null) {
                GenericClass<?> calleeGeneric = findGenericTypeForRef(callee);
                ownerClass = calleeGeneric != null ? calleeGeneric : GenericClassFactory.get(methodTargetClass);
            } else {
                ownerClass = GenericClassFactory.get(methodTargetClass);
            }
            TestClusterUtils.makeAccessible(method);
            GenericMethod genericMethod = new GenericMethod(method, ownerClass);

            MethodStatement stmt = new MethodStatement(testCase, genericMethod, callee, argRefs);
            return testCase.addStatement(stmt);

        } catch (Exception e) {
            return failOrFallback(expr, declaredType, null,
                    "Failed to parse method call: " + e.getMessage());
        }
    }

    /**
     * LLMs sometimes emit helper-wrapper objects that are not present in the final
     * parsed class body, e.g. {@code new MethodAccess(target).invokeGetBuilder(arg)}.
     * Treat these like legacy invoke helpers by rewriting to reflective private-method
     * invocation on the wrapped target object.
     */
    private boolean tryHandleLegacyScopedInvokeHelperCall(MethodCallExpr expr, ObjectCreationExpr scopeCtor) {
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

        NodeList<Expression> syntheticArgs = new NodeList<>();
        syntheticArgs.add(scopeCtor.getArgument(0));
        syntheticArgs.addAll(expr.getArguments());
        MethodCallExpr syntheticInvokeHelperCall = new MethodCallExpr(null, helperName, syntheticArgs);
        return tryHandleLegacyInvokeHelperCall(syntheticInvokeHelperCall);
    }

    /**
     * LLMs sometimes emit local helper methods like setField()/setStaticField() and call them
     * from tests. EvoSuite parses test bodies only, so helper method declarations are dropped.
     * Convert these helper calls directly into PrivateFieldStatement so generated tests compile.
     */
    private boolean tryHandleLegacyFieldHelperCall(MethodCallExpr expr) {
        String name = expr.getNameAsString();
        if (!"setField".equals(name) && !"setStaticField".equals(name)) {
            return false;
        }
        if (expr.getArguments().size() != 3) {
            return false;
        }

        String fieldName = resolveFieldNameLiteral(expr.getArguments().get(1));
        if (fieldName == null || fieldName.isEmpty()) {
            addWarning(expr, "Could not resolve legacy helper field name literal for call: " + expr);
            return false;
        }

        try {
            if ("setField".equals(name)) {
                VariableReference owner = handleExpression(
                        "__legacy_owner" + syntheticVarCounter++, expr.getArguments().get(0), Object.class);
                if (owner == null) {
                    addWarning(expr, "Could not resolve receiver for legacy setField call: " + expr);
                    return false;
                }
                Class<?> ownerClass = owner.getVariableClass();
                Type fieldType = resolveFieldType(ownerClass, fieldName);
                VariableReference value = handleExpression(
                        "__legacy_value" + syntheticVarCounter++, expr.getArguments().get(2),
                        fieldType != null ? fieldType : Object.class);
                if (value == null) {
                    addWarning(expr, "Could not resolve value for legacy setField call: " + expr);
                    return false;
                }
                testCase.addStatement(new PrivateFieldStatement(testCase, ownerClass, fieldName, owner, value));
                addWarning(expr, "Rewrote legacy helper call setField(...) to reflective field write");
                return true;
            }

            Class<?> ownerClass = resolveOwnerClassForLegacyStaticField(expr.getArguments().get(0));
            if (ownerClass == null) {
                addWarning(expr, "Could not resolve class for legacy setStaticField call: " + expr);
                return false;
            }
            Type fieldType = resolveFieldType(ownerClass, fieldName);
            VariableReference value = handleExpression(
                    "__legacy_value" + syntheticVarCounter++, expr.getArguments().get(2),
                    fieldType != null ? fieldType : Object.class);
            if (value == null) {
                addWarning(expr, "Could not resolve value for legacy setStaticField call: " + expr);
                return false;
            }
            VariableReference nullOwner = testCase.addStatement(new NullStatement(testCase, ownerClass));
            testCase.addStatement(new PrivateFieldStatement(testCase, ownerClass, fieldName, nullOwner, value));
            addWarning(expr, "Rewrote legacy helper call setStaticField(...) to reflective field write");
            return true;
        } catch (Exception e) {
            addWarning(expr, "Failed to rewrite legacy helper call '" + name + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * LLMs sometimes emit helper wrappers such as invokeGetBuilder(target, arg),
     * where the helper itself is dropped because only method bodies are parsed.
     * Rewrite the call directly to a reflective private-method invocation.
     */
    private boolean tryHandleLegacyInvokeHelperCall(MethodCallExpr expr) {
        String helperName = expr.getNameAsString();
        if (!helperName.startsWith("invoke") || helperName.length() <= "invoke".length()) {
            return false;
        }
        if (expr.getArguments().isEmpty()) {
            return false;
        }

        String reflectedMethodName = helperName.substring("invoke".length());
        if (reflectedMethodName.isEmpty() || !Character.isUpperCase(reflectedMethodName.charAt(0))) {
            return false;
        }
        reflectedMethodName = Character.toLowerCase(reflectedMethodName.charAt(0))
                + reflectedMethodName.substring(1);

        try {
            VariableReference callee = null;
            Class<?> ownerClass = null;
            Expression firstArg = expr.getArguments().get(0);

            // Support invokeX(Target.class, ...) static wrappers too.
            if (firstArg instanceof ClassExpr) {
                Type ownerType = typeResolver.resolveType(((ClassExpr) firstArg).getType());
                ownerClass = getRawClass(ownerType);
            } else {
                callee = handleExpression("__legacy_invoke_owner" + syntheticVarCounter++, firstArg, Object.class);
                if (callee == null) {
                    addWarning(expr, "Could not resolve receiver for legacy helper call: " + expr);
                    return false;
                }
                ownerClass = callee.getVariableClass();
            }

            if (ownerClass == null) {
                addWarning(expr, "Could not resolve owner class for legacy helper call: " + expr);
                return false;
            }

            List<Expression> helperArgs = expr.getArguments().subList(1, expr.getArguments().size());
            List<VariableReference> argRefs = resolveArguments(helperArgs, null, null);
            Class<?>[] argTypes = getArgTypes(argRefs);

            Method reflectedMethod = resolveMethod(ownerClass, reflectedMethodName, argTypes);
            retypeNullArguments(argRefs, reflectedMethod.getParameterTypes(), reflectedMethod.isVarArgs());
            String mismatch = validateArgumentTypes(argRefs, reflectedMethod.getParameterTypes(),
                    reflectedMethod.getGenericParameterTypes(), expr, reflectedMethod.isVarArgs());
            if (mismatch != null) {
                addWarning(expr, "Could not rewrite legacy helper call " + helperName
                        + " due to argument mismatch: " + mismatch);
                return false;
            }
            argRefs = normalizeVarArgsArguments(argRefs, reflectedMethod.getParameterTypes(), reflectedMethod.isVarArgs());

            Class<?> reflectedOwner = reflectedMethod.getDeclaringClass();
            boolean isStatic = Modifier.isStatic(reflectedMethod.getModifiers());
            if (isStatic) {
                callee = null;
            }
            testCase.addStatement(new PrivateMethodStatement(
                    testCase,
                    reflectedOwner,
                    reflectedMethod,
                    callee,
                    argRefs,
                    isStatic));
            addWarning(expr, "Rewrote legacy helper call " + helperName + "(...) to reflective method call "
                    + reflectedMethodName + "(...)");
            return true;
        } catch (Exception e) {
            addWarning(expr, "Failed to rewrite legacy helper call '" + helperName + "': " + e.getMessage());
            return false;
        }
    }

    private String resolveFieldNameLiteral(Expression expr) {
        if (expr instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) expr).getValue();
        }
        if (expr instanceof NameExpr) {
            VariableReference ref = scope.resolve(((NameExpr) expr).getNameAsString());
            Statement st = getStatementForReference(ref);
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
                return getRawClass(t);
            }
            if (classExpr instanceof NameExpr) {
                VariableReference ref = scope.resolve(((NameExpr) classExpr).getNameAsString());
                Statement st = getStatementForReference(ref);
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

    private VariableReference failOrTypedFallbackWithRollback(Expression expr,
                                                              Type declaredType,
                                                              Type knownType,
                                                              String targetVarName,
                                                              int checkpointSize,
                                                              String errorMsg) {
        rollbackTemporaryStatements(checkpointSize);
        return failOrTypedFallback(expr, declaredType, knownType, targetVarName, errorMsg);
    }

    private VariableReference failOrFallbackWithRollback(Expression expr,
                                                         Type declaredType,
                                                         Type knownType,
                                                         int checkpointSize,
                                                         String errorMsg) {
        rollbackTemporaryStatements(checkpointSize);
        return failOrFallback(expr, declaredType, knownType, errorMsg);
    }

    private void rollbackTemporaryStatements(int checkpointSize) {
        if (checkpointSize < 0) {
            return;
        }
        if (testCase.size() > checkpointSize) {
            testCase.chop(checkpointSize);
        }
    }

    private static String inlineHelperKey(String methodName, int arity) {
        return methodName + "#" + arity;
    }

    private boolean isVoidContext(Type declaredType, String targetVarName) {
        if (targetVarName == null) {
            return true;
        }
        if (!(declaredType instanceof Class<?>)) {
            return false;
        }
        Class<?> raw = (Class<?>) declaredType;
        return raw == void.class || raw == Void.class || raw == Void.TYPE;
    }

    private Expression instantiateInlineHelperReturn(MethodDeclaration helper,
                                                     List<Expression> callArgs) {
        if (!helper.getBody().isPresent()) {
            return null;
        }
        List<com.github.javaparser.ast.stmt.Statement> statements = helper.getBody().get().getStatements();
        if (statements.size() != 1 || !(statements.get(0) instanceof ReturnStmt)) {
            return null;
        }
        ReturnStmt returnStmt = (ReturnStmt) statements.get(0);
        if (!returnStmt.getExpression().isPresent()) {
            return null;
        }
        Expression returnExpr = returnStmt.getExpression().get().clone();
        return substituteInlineHelperParams(helper, callArgs, returnExpr);
    }

    private Expression instantiateInlineHelperExpression(MethodDeclaration helper,
                                                         List<Expression> callArgs) {
        if (!helper.getBody().isPresent()) {
            return null;
        }
        List<com.github.javaparser.ast.stmt.Statement> statements = helper.getBody().get().getStatements();
        if (statements.size() != 1 || !(statements.get(0) instanceof ExpressionStmt)) {
            return null;
        }
        Expression expr = ((ExpressionStmt) statements.get(0)).getExpression().clone();
        return substituteInlineHelperParams(helper, callArgs, expr);
    }

    private Expression substituteInlineHelperParams(MethodDeclaration helper,
                                                    List<Expression> callArgs,
                                                    Expression template) {
        if (helper.getParameters().isEmpty()) {
            return template;
        }
        if (helper.getParameters().size() != 1 || callArgs.size() != 1) {
            return null;
        }
        Parameter param = helper.getParameter(0);
        final String paramName = param.getNameAsString();
        final Expression actualArg = callArgs.get(0);
        ModifierVisitor<Void> substituter = new ModifierVisitor<Void>() {
            @Override
            public Visitable visit(NameExpr n, Void arg) {
                if (n.getNameAsString().equals(paramName)) {
                    return actualArg.clone();
                }
                return super.visit(n, arg);
            }
        };
        return (Expression) template.accept(substituter, null);
    }

    private com.github.javaparser.ast.stmt.Statement inlineHelperCallsInUnsupportedStatement(
            com.github.javaparser.ast.stmt.Statement astStmt) {
        com.github.javaparser.ast.stmt.Statement cloned = astStmt.clone();
        ModifierVisitor<Void> visitor = new ModifierVisitor<Void>() {
            @Override
            public Visitable visit(ExpressionStmt n, Void arg) {
                Expression expr = n.getExpression();
                if (expr instanceof MethodCallExpr) {
                    MethodCallExpr call = (MethodCallExpr) expr;
                    if (!call.getScope().isPresent()) {
                        MethodDeclaration helper = inlineHelperMethods.get(
                                inlineHelperKey(call.getNameAsString(), call.getArguments().size()));
                        if (helper != null) {
                            Expression replacement = instantiateInlineHelperExpression(helper, call.getArguments());
                            if (replacement == null) {
                                replacement = instantiateInlineHelperReturn(helper, call.getArguments());
                            }
                            if (replacement != null) {
                                return new ExpressionStmt(replacement);
                            }
                        }
                    }
                }
                return (ExpressionStmt) super.visit(n, arg);
            }
        };
        Visitable rewritten = cloned.accept(visitor, null);
        if (rewritten instanceof com.github.javaparser.ast.stmt.Statement) {
            return (com.github.javaparser.ast.stmt.Statement) rewritten;
        }
        return cloned;
    }

    private void handleAssertStatement(AssertStmt assertStmt) {
        try {
            Expression condition = assertStmt.getCheck();
            VariableReference var = handleExpression("__assert_cond" + syntheticVarCounter++, condition, boolean.class);
            if (var != null) {
                PrimitiveAssertion assertion = new PrimitiveAssertion();
                assertion.setSource(var);
                assertion.setValue(true);
                attachAssertionToSource(var, assertion);
            } else {
                addError(assertStmt, "Cannot resolve assertion condition: " + condition);
            }
        } catch (Exception e) {
            addError(assertStmt, "Failed to parse assert statement: " + e.getMessage());
        }
    }

    /**
     * Handle a top-level method call (standalone expression statement, e.g. void call).
     * Assertion calls are intercepted and converted to EvoSuite Assertion objects.
     */
    private void handleTopLevelMethodCall(MethodCallExpr methodCall) {
        String name = methodCall.getNameAsString();
        if (isAssertionMethodName(name)) {
            handleAssertionCall(methodCall);
        } else if (tryHandleCapturedWhenStubbingTerminalCall(methodCall)) {
            // Handled as a delayed terminal call for a previously captured OngoingStubbing alias
        } else if (tryHandleStandaloneStubbingCall(methodCall)) {
            // Handled as standalone Mockito stubbing (when/thenReturn or doReturn/when)
        } else if (tryPreserveStandaloneThrowStubbingCall(methodCall)) {
            // Preserve Mockito throw-stubbing chains that cannot be represented as FunctionalMockStatement
        } else {
            handleMethodCall(methodCall, void.class, null);
        }
    }

    private boolean tryHandleCapturedWhenStubbingTerminalCall(MethodCallExpr methodCall) {
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
                    context.whenCall.clone(),
                    "thenReturn",
                    cloneArguments(methodCall));
            StubbingInfo info = parseWhenThenReturnPattern(
                    reconstructed,
                    context.mockVarName,
                    context.targetClass,
                    context.targetGenericClass);
            if (info == null) {
                return false;
            }
            List<VariableReference> orderedReturnValues =
                    ensureStubbingValuesAvailableBeforeMock(context.mockRef, info.returnValues);
            context.mockStatement.addMethodStubbing(info.descriptor, orderedReturnValues);
            capturedWhenStubbings.remove(aliasName);
            return true;
        }

        if (markParsedFromLlm && "thenThrow".equals(methodName)) {
            MethodCallExpr reconstructed = new MethodCallExpr(
                    context.whenCall.clone(),
                    "thenThrow",
                    cloneArguments(methodCall));
            testCase.addStatement(createUninterpretedStatement(reconstructed, reconstructed.toString() + ";"));
            capturedWhenStubbings.remove(aliasName);
            return true;
        }

        return false;
    }

    /**
     * Try to handle a standalone Mockito stubbing expression that is not adjacent
     * to its mock declaration. This handles patterns like:
     * <ul>
     *   <li>when(mockVar.method(args)).thenReturn(value)</li>
     *   <li>doReturn(value).when(mockVar).method(args)</li>
     * </ul>
     * The mock variable must already be registered in scope and its statement
     * must be a FunctionalMockStatement.
     *
     * @return true if the expression was handled as a stubbing call
     */
    private boolean tryHandleStandaloneStubbingCall(MethodCallExpr methodCall) {
        // Detect the mock variable name from the stubbing pattern
        String mockVarName = extractMockVarFromStubbingPattern(methodCall);
        if (mockVarName == null) {
            return false;
        }

        // Look up the mock variable in scope
        VariableReference mockRef = scope.resolve(mockVarName);
        if (mockRef == null) {
            return false;
        }

        // Retrieve the statement and verify it's a FunctionalMockStatement
        Statement stmt = testCase.getStatement(mockRef.getStPosition());
        if (!(stmt instanceof FunctionalMockStatement)) {
            return false;
        }
        FunctionalMockStatement mockStmt = (FunctionalMockStatement) stmt;

        // Get the target class info for method resolution
        Class<?> targetClass = mockStmt.getTargetClass();
        GenericClass<?> targetGenericClass = scope.resolveGenericType(mockVarName);
        if (targetGenericClass == null) {
            targetGenericClass = GenericClassFactory.get(targetClass);
        }

        // Try parsing with the existing stubbing chain parser
        StubbingInfo info = parseStubbingChain(methodCall, mockVarName, targetClass, targetGenericClass);
        if (info == null) {
            return false;
        }

        List<VariableReference> orderedReturnValues =
                ensureStubbingValuesAvailableBeforeMock(mockRef, info.returnValues);
        mockStmt.addMethodStubbing(info.descriptor, orderedReturnValues);
        return true;
    }

    private boolean tryPreserveStandaloneThrowStubbingCall(MethodCallExpr methodCall) {
        if (!markParsedFromLlm || !isUnsupportedMockitoThrowStubbing(methodCall)) {
            return false;
        }
        addWarning(methodCall,
                "Preserved Mockito throw-stubbing as UninterpretedStatement");
        testCase.addStatement(createUninterpretedStatement(methodCall, methodCall.toString() + ";"));
        return true;
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

    /**
     * Standalone stubbing calls are parsed at their lexical position, but stubbings are
     * emitted from the original FunctionalMockStatement position. If a stubbing return value
     * is declared later in the test, emitted code can reference it before declaration.
     *
     * <p>To preserve compilability, hoist safe value-producing statements before the mock
     * statement and rewrite stubbing values to the hoisted variables.
     */
    private List<VariableReference> ensureStubbingValuesAvailableBeforeMock(VariableReference mockRef,
                                                                            List<VariableReference> values) {
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
                VariableReference hoistedRef = testCase.addStatement(cloned, mockPos);
                hoisted.put(valueRef, hoistedRef);
                mockPos++;
                adjusted.add(hoistedRef);
                continue;
            }

            if (markParsedFromLlm) {
                logger.debug("Could not hoist stubbing value '{}' before mock; using typed fallback value",
                        valueRef.getName());
                adjusted.add(createTypedFallbackValue(valueRef.getType()));
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
                || stmt instanceof NullStatement
                || stmt instanceof ConstructorStatement
                || stmt instanceof EnumPrimitiveStatement
                || stmt instanceof ArrayStatement)) {
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

    private VariableReference createTypedFallbackValue(Type expectedType) {
        Class<?> raw = getRawClass(expectedType);
        if (raw == void.class || raw == Void.class) {
            return testCase.addStatement(createUninterpretedStatement(new NameExpr("fallback"), ";"));
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return testCase.addStatement(new BooleanPrimitiveStatement(testCase, false));
        }
        if (raw == byte.class || raw == Byte.class) {
            return testCase.addStatement(new BytePrimitiveStatement(testCase, (byte) 0));
        }
        if (raw == short.class || raw == Short.class) {
            return testCase.addStatement(new ShortPrimitiveStatement(testCase, (short) 0));
        }
        if (raw == int.class || raw == Integer.class) {
            return testCase.addStatement(new IntPrimitiveStatement(testCase, 0));
        }
        if (raw == long.class || raw == Long.class) {
            return testCase.addStatement(new LongPrimitiveStatement(testCase, 0L));
        }
        if (raw == float.class || raw == Float.class) {
            return testCase.addStatement(new FloatPrimitiveStatement(testCase, 0.0f));
        }
        if (raw == double.class || raw == Double.class) {
            return testCase.addStatement(new DoublePrimitiveStatement(testCase, 0.0d));
        }
        if (raw == char.class || raw == Character.class) {
            return testCase.addStatement(new CharPrimitiveStatement(testCase, '\0'));
        }
        if (raw == String.class) {
            return testCase.addStatement(new StringPrimitiveStatement(testCase, null));
        }
        return testCase.addStatement(new NullStatement(testCase, expectedType == null ? Object.class : expectedType));
    }

    /**
     * Extract the mock variable name from a stubbing expression, or null
     * if this is not a recognized stubbing pattern.
     */
    private String extractMockVarFromStubbingPattern(MethodCallExpr expr) {
        // Pattern: when(mockVar.method(args)).thenReturn(...)
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
            if (!innerCall.getScope().isPresent() || !(innerCall.getScope().get() instanceof NameExpr)) {
                return null;
            }
            return ((NameExpr) innerCall.getScope().get()).getNameAsString();
        }

        // Pattern: doReturn(...).when(mockVar).method(...)
        // outerCall is .method(...), scope is when(...), scope of when is doReturn(...)
        if (expr.getScope().isPresent() && expr.getScope().get() instanceof MethodCallExpr) {
            MethodCallExpr whenCall = (MethodCallExpr) expr.getScope().get();
            if ("when".equals(whenCall.getNameAsString()) && whenCall.getArguments().size() == 1) {
                Expression whenArg = whenCall.getArgument(0);
                if (whenArg instanceof NameExpr) {
                    // Check that when()'s scope is doReturn(...)
                    if (whenCall.getScope().isPresent()
                            && whenCall.getScope().get() instanceof MethodCallExpr
                            && "doReturn".equals(((MethodCallExpr) whenCall.getScope().get()).getNameAsString())) {
                        return ((NameExpr) whenArg).getNameAsString();
                    }
                }
            }
        }

        return null;
    }

    // ========================================================================
    // Assertion handling: assertEquals, assertTrue, assertNull, etc.
    // ========================================================================

    private static boolean isAssertionMethodName(String name) {
        switch (name) {
            case "assertEquals":
            case "assertNotEquals":
            case "assertTrue":
            case "assertFalse":
            case "assertNull":
            case "assertNotNull":
            case "assertSame":
            case "assertNotSame":
            case "assertArrayEquals":
            case "assertThrows":
            case "assertDoesNotThrow":
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse a JUnit assertion call and attach an EvoSuite Assertion to the
     * statement that produced the asserted variable.
     *
     * <p>Handles both JUnit 4 (message-first optional) and JUnit 5 (message-last optional).
     * Unrecognized assertion patterns are preserved as UninterpretedStatements.
     */
    private void handleAssertionCall(MethodCallExpr assertCall) {
        String name = assertCall.getNameAsString();
        List<Expression> args = assertCall.getArguments();

        try {
            switch (name) {
                case "assertTrue":
                    handleAssertBoolean(args, true);
                    return;
                case "assertFalse":
                    handleAssertBoolean(args, false);
                    return;
                case "assertNull":
                    handleAssertNull(args, true);
                    return;
                case "assertNotNull":
                    handleAssertNull(args, false);
                    return;
                case "assertEquals":
                    handleAssertEquals(args);
                    return;
                case "assertNotEquals":
                    handleAssertNotEquals(args);
                    return;
                case "assertSame":
                    handleAssertSame(args, true);
                    return;
                case "assertNotSame":
                    handleAssertSame(args, false);
                    return;
                case "assertArrayEquals":
                    handleAssertArrayEquals(assertCall, args);
                    return;
                case "assertThrows":
                    if (handleAssertThrows(args)) {
                        return;
                    }
                    break;
                case "assertDoesNotThrow":
                    if (handleAssertDoesNotThrow(args)) {
                        return;
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            logger.debug("Could not parse assertion {}: {}", assertCall, e.getMessage());
        }

        // Fallback: preserve as UninterpretedStatement
        Expression preservedAssertion = normalizeAssertionExpressionForPreservation(assertCall);
        testCase.addStatement(createUninterpretedStatement(assertCall, preservedAssertion.toString() + ";"));
    }

    /**
     * assertTrue(condition) / assertTrue(message, condition) [JUnit4].
     * assertTrue(condition) / assertTrue(condition, message) [JUnit5].
     */
    private void handleAssertBoolean(List<Expression> args, boolean expectedValue) {
        if (args.isEmpty()) {
            return;
        }
        // The condition is in the 1-arg form, or last arg (JUnit5) or second arg (JUnit4)
        // Heuristic: if 1 arg, it's the condition. If 2 args, try last arg first (it's a NameExpr variable)
        Expression conditionExpr = args.size() == 1 ? args.get(0) : pickVariableArg(args);
        if (conditionExpr == null) {
            conditionExpr = args.get(args.size() - 1);
        }

        VariableReference sourceRef = resolveAssertionVariable(conditionExpr);
        if (sourceRef == null) {
            return;
        }

        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(sourceRef);
        assertion.setValue(expectedValue);
        attachAssertionToSource(sourceRef, assertion);
    }

    /**
     * assertNull(object) / assertNull(message, object) [JUnit4].
     * assertNull(object) / assertNull(object, message) [JUnit5].
     */
    private void handleAssertNull(List<Expression> args, boolean isNull) {
        if (args.isEmpty()) {
            return;
        }
        Expression objExpr = args.size() == 1 ? args.get(0) : pickVariableArg(args);
        if (objExpr == null) {
            objExpr = args.get(args.size() - 1);
        }

        VariableReference sourceRef = resolveAssertionVariable(objExpr);
        if (sourceRef == null) {
            return;
        }

        NullAssertion assertion = new NullAssertion();
        assertion.setSource(sourceRef);
        assertion.setValue(isNull);
        attachAssertionToSource(sourceRef, assertion);
    }

    /**
     * assertEquals(expected, actual) — for primitives, creates PrimitiveAssertion.
     * Handles optional message arg and optional delta for floating point.
     */
    private void handleAssertEquals(List<Expression> args) {
        if (args.size() < 2) {
            return;
        }

        // Determine expected and actual.
        // JUnit convention: assertEquals(expected, actual) — the "actual" is usually a variable.
        // With 2 args: assertEquals(expected, actual)
        // With 3 args: either assertEquals(msg, expected, actual) [JUnit4] or
        //              assertEquals(expected, actual, delta/msg)
        // With 4 args: assertEquals(msg, expected, actual, delta) [JUnit4]
        Expression expectedExpr;
        Expression actualExpr;

        if (args.size() == 2) {
            expectedExpr = args.get(0);
            actualExpr = args.get(1);
        } else if (args.size() == 3) {
            // Heuristic: if first arg is a String literal, it's a JUnit4 message
            if (args.get(0) instanceof StringLiteralExpr) {
                expectedExpr = args.get(1);
                actualExpr = args.get(2);
            } else {
                // Could be assertEquals(expected, actual, delta) for doubles
                expectedExpr = args.get(0);
                actualExpr = args.get(1);
            }
        } else if (args.size() == 4) {
            // JUnit4: assertEquals(message, expected, actual, delta)
            expectedExpr = args.get(1);
            actualExpr = args.get(2);
        } else {
            return;
        }

        VariableReference sourceRef = resolveAssertionVariable(actualExpr);
        if (sourceRef == null) {
            return;
        }

        Object expectedValue = extractLiteralValue(expectedExpr);
        if (expectedValue != null) {
            Class<?> sourceClass = sourceRef.getVariableClass();
            if (sourceClass == boolean.class || sourceClass == Boolean.class) {
                Object coerced = coerceBooleanExpectedValue(expectedValue);
                if (coerced == null) {
                    // Unsupported literal for boolean expected/actual pair.
                    // Keep robust fallback path instead of creating an invalid PrimitiveAssertion.
                    return;
                }
                expectedValue = coerced;
            }
            PrimitiveAssertion assertion = new PrimitiveAssertion();
            assertion.setSource(sourceRef);
            assertion.setValue(expectedValue);
            attachAssertionToSource(sourceRef, assertion);
        }
    }

    private void handleAssertNotEquals(List<Expression> args) {
        if (args.size() < 2) {
            return;
        }

        // Same arg-parsing logic as handleAssertEquals
        Expression expectedExpr;
        Expression actualExpr;

        if (args.size() == 2) {
            expectedExpr = args.get(0);
            actualExpr = args.get(1);
        } else if (args.size() == 3) {
            if (args.get(0) instanceof StringLiteralExpr) {
                expectedExpr = args.get(1);
                actualExpr = args.get(2);
            } else {
                expectedExpr = args.get(0);
                actualExpr = args.get(1);
            }
        } else if (args.size() == 4) {
            expectedExpr = args.get(1);
            actualExpr = args.get(2);
        } else {
            return;
        }

        VariableReference actualRef = resolveAssertionVariable(actualExpr);
        if (actualRef == null) {
            return;
        }

        // If expected is a literal, use PrimitiveAssertion — the getCode() for
        // EqualsAssertion with value=false emits assertFalse(a.equals(b)) which
        // is not ideal for primitive literals. Instead we skip (no direct
        // PrimitiveAssertion negation exists). Fall through to UninterpretedStatement.
        Object expectedValue = extractLiteralValue(expectedExpr);
        if (expectedValue != null) {
            // No negated PrimitiveAssertion in EvoSuite; let the default fallback handle it
            return;
        }

        // Both are variables — use EqualsAssertion with value=false
        VariableReference expectedRef = resolveAssertionVariable(expectedExpr);
        if (expectedRef == null) {
            return;
        }

        EqualsAssertion assertion = new EqualsAssertion();
        assertion.setSource(actualRef);
        assertion.setDest(expectedRef);
        assertion.setValue(false);
        attachAssertionToSource(actualRef, assertion);
    }

    /**
     * assertSame(expected, actual) / assertNotSame(expected, actual).
     * Uses SameAssertion with value=true for same, false for notSame.
     */
    private void handleAssertSame(List<Expression> args, boolean same) {
        if (args.size() < 2) {
            return;
        }

        Expression expectedExpr;
        Expression actualExpr;

        if (args.size() == 2) {
            expectedExpr = args.get(0);
            actualExpr = args.get(1);
        } else if (args.size() == 3) {
            // 3-arg: message first (JUnit4) or message last (JUnit5)
            if (args.get(0) instanceof StringLiteralExpr) {
                expectedExpr = args.get(1);
                actualExpr = args.get(2);
            } else {
                expectedExpr = args.get(0);
                actualExpr = args.get(1);
            }
        } else {
            return;
        }

        VariableReference actualRef = resolveAssertionVariable(actualExpr);
        VariableReference expectedRef = resolveAssertionVariable(expectedExpr);
        if (actualRef == null || expectedRef == null) {
            return;
        }

        SameAssertion assertion = new SameAssertion();
        assertion.setSource(actualRef);
        assertion.setDest(expectedRef);
        assertion.setValue(same);
        attachAssertionToSource(actualRef, assertion);
    }

    /**
     * assertArrayEquals — preserved as UninterpretedStatement since EvoSuite's
     * ArrayEqualsAssertion requires runtime trace data we don't have from source.
     */
    private void handleAssertArrayEquals(MethodCallExpr assertCall, List<Expression> args) {
        // Materialize any inline method call arguments so they become real statements
        for (Expression arg : args) {
            if (arg instanceof MethodCallExpr) {
                handleMethodCall((MethodCallExpr) arg, null, null);
            }
        }
        // Avoid emitting uncompilable uninterpreted assertions with unresolved identifiers.
        if (!validateAssertionArgumentNames(assertCall, args)) {
            return;
        }
        testCase.addStatement(createUninterpretedStatement(assertCall, assertCall.toString() + ";"));
    }

    private boolean validateAssertionArgumentNames(MethodCallExpr assertCall, List<Expression> args) {
        for (Expression arg : args) {
            for (String token : collectReferencedSimpleNames(arg)) {
                if (scope.resolve(token) != null) {
                    continue;
                }
                boolean packageQualifier = false;
                for (NameExpr nameExpr : arg.findAll(NameExpr.class)) {
                    if (token.equals(nameExpr.getNameAsString()) && isLikelyPackageQualifier(nameExpr)) {
                        packageQualifier = true;
                        break;
                    }
                }
                if (packageQualifier) {
                    continue;
                }
                try {
                    typeResolver.resolveClass(token);
                    continue;
                } catch (ClassNotFoundException ignored) {
                    // fall through
                }
                addError(assertCall, "Unresolved variable in assertion argument: " + token);
                return false;
            }
        }
        return true;
    }

    private boolean isLikelyPackageQualifier(NameExpr nameExpr) {
        if (nameExpr == null) {
            return false;
        }
        if (nameExpr.getNameAsString().isEmpty()
                || !Character.isLowerCase(nameExpr.getNameAsString().charAt(0))) {
            return false;
        }
        if (!nameExpr.getParentNode().isPresent()) {
            return false;
        }
        com.github.javaparser.ast.Node parent = nameExpr.getParentNode().get();
        return parent instanceof FieldAccessExpr && ((FieldAccessExpr) parent).getScope() == nameExpr;
    }

    /**
     * assertThrows(ExceptionClass.class, () -> { ... }) — extract the lambda body
     * as regular statements. The exception class is recorded on the
     * {@link ParseResult} (mirroring JUnit 4 {@code @Test(expected=...)}) so
     * downstream code can treat the test as exception-expected.
     * Handles both block lambdas and expression lambdas.
     */
    private boolean handleAssertThrows(List<Expression> args) {
        if (args.size() < 2) {
            return false;
        }

        // Record the expected exception class if we can find one. First argument
        // is conventionally the ExceptionClass.class literal; scan all args to be
        // robust to message-first/message-last variants. Only the first
        // assertThrows in a test wins (matches the single-slot ParseResult API).
        if (result.getExpectedExceptionClass() == null) {
            for (Expression arg : args) {
                if (arg instanceof ClassExpr) {
                    result.setExpectedExceptionClass(resolveAssertionExceptionClassName((ClassExpr) arg));
                    break;
                }
            }
        }

        // Find the lambda argument (could be arg 1 in 2-arg form, or arg 2 in 3-arg with message)
        LambdaExpr lambda = null;
        for (Expression arg : args) {
            if (arg instanceof LambdaExpr) {
                lambda = (LambdaExpr) arg;
                break;
            }
        }

        if (lambda == null) {
            // No inline lambda found — maybe it's a method reference or an Executable variable.
            // Let the caller preserve the raw assertThrows(...) statement so generated
            // JUnit remains compilable instead of flattening it incorrectly.
            return false;
        }

        // Parse the lambda body as regular statements
        com.github.javaparser.ast.stmt.Statement body = lambda.getBody();
        if (body instanceof BlockStmt) {
            for (com.github.javaparser.ast.stmt.Statement stmt : ((BlockStmt) body).getStatements()) {
                parseStatement(stmt);
            }
        } else if (body instanceof ExpressionStmt) {
            handleExpressionStatement(((ExpressionStmt) body).getExpression());
        } else {
            // Single expression lambda: () -> expr
            // The body is an ExpressionStmt wrapping the expression
            parseStatement(body);
        }
        return true;
    }

    private Expression normalizeAssertionExpressionForPreservation(Expression expression) {
        if (!(expression instanceof MethodCallExpr)) {
            return expression;
        }
        MethodCallExpr preserved = ((MethodCallExpr) expression).clone();
        if ("assertThrows".equals(preserved.getNameAsString())) {
            normalizeAssertThrowsClassLiteral(preserved);
        }
        return preserved;
    }

    private void normalizeAssertThrowsClassLiteral(MethodCallExpr assertThrowsCall) {
        if (assertThrowsCall == null) {
            return;
        }
        for (Expression arg : assertThrowsCall.getArguments()) {
            if (!(arg instanceof ClassExpr)) {
                continue;
            }
            String resolvedTypeName = resolveAssertionExceptionClassName((ClassExpr) arg);
            if (resolvedTypeName == null || resolvedTypeName.isEmpty()) {
                return;
            }
            ((ClassExpr) arg).setType(StaticJavaParser.parseType(resolvedTypeName));
            return;
        }
    }

    private String resolveAssertionExceptionClassName(ClassExpr classExpr) {
        if (classExpr == null) {
            return null;
        }
        String originalTypeName = classExpr.getTypeAsString();
        try {
            Class<?> resolvedClass = typeResolver.resolveClass(originalTypeName);
            if (resolvedClass == null || resolvedClass.isPrimitive()) {
                return originalTypeName;
            }
            String canonicalName = resolvedClass.getCanonicalName();
            if (canonicalName == null || canonicalName.isEmpty()) {
                return originalTypeName;
            }
            if (canonicalName.startsWith("java.lang.")) {
                return originalTypeName;
            }
            return canonicalName;
        } catch (ClassNotFoundException e) {
            return originalTypeName;
        }
    }

    /**
     * assertDoesNotThrow(() -> { ... }) — extract the lambda body as regular
     * statements, identical to assertThrows handling but without an expected
     * exception class argument.
     */
    private boolean handleAssertDoesNotThrow(List<Expression> args) {
        if (args.isEmpty()) {
            return false;
        }

        LambdaExpr lambda = null;
        for (Expression arg : args) {
            if (arg instanceof LambdaExpr) {
                lambda = (LambdaExpr) arg;
                break;
            }
        }

        if (lambda == null) {
            return false;
        }

        com.github.javaparser.ast.stmt.Statement body = lambda.getBody();
        if (body instanceof BlockStmt) {
            for (com.github.javaparser.ast.stmt.Statement stmt : ((BlockStmt) body).getStatements()) {
                parseStatement(stmt);
            }
        } else if (body instanceof ExpressionStmt) {
            handleExpressionStatement(((ExpressionStmt) body).getExpression());
        } else {
            parseStatement(body);
        }
        return true;
    }

    /**
     * Pick the argument that's a variable name from a 2-arg assertion call.
     * Returns null if neither is a simple NameExpr.
     */
    private Expression pickVariableArg(List<Expression> args) {
        // For 2-arg calls like assertTrue(msg, cond) or assertTrue(cond, msg),
        // prefer the NameExpr (variable reference) over the literal/string
        for (Expression arg : args) {
            if (arg instanceof NameExpr && scope.isDefined(((NameExpr) arg).getNameAsString())) {
                return arg;
            }
        }
        return null;
    }

    /**
     * Resolve an assertion argument expression to a VariableReference.
     * For simple variable names, looks up the scope. For method calls nested
     * inside assertions (e.g. {@code assertTrue(c.testMe(42))}), parses the
     * method call as a real statement first, then uses its return value.
     */
    private VariableReference resolveAssertionVariable(Expression expr) {
        if (expr instanceof NameExpr) {
            return scope.resolve(((NameExpr) expr).getNameAsString());
        }
        if (expr instanceof MethodCallExpr) {
            return handleMethodCall((MethodCallExpr) expr, null, null);
        }
        return null;
    }

    /**
     * Extract a literal value from an expression for assertion expected values.
     */
    private Object extractLiteralValue(Expression expr) {
        if (expr instanceof IntegerLiteralExpr) {
            return ((IntegerLiteralExpr) expr).asNumber().intValue();
        } else if (expr instanceof LongLiteralExpr) {
            return ((LongLiteralExpr) expr).asNumber().longValue();
        } else if (expr instanceof DoubleLiteralExpr) {
            return Double.parseDouble(((DoubleLiteralExpr) expr).getValue());
        } else if (expr instanceof BooleanLiteralExpr) {
            return ((BooleanLiteralExpr) expr).getValue();
        } else if (expr instanceof CharLiteralExpr) {
            return ((CharLiteralExpr) expr).asChar();
        } else if (expr instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) expr).getValue();
        } else if (expr instanceof NullLiteralExpr) {
            return null;
        } else if (expr instanceof UnaryExpr) {
            UnaryExpr unary = (UnaryExpr) expr;
            if (unary.getOperator() == UnaryExpr.Operator.MINUS) {
                Object inner = extractLiteralValue(unary.getExpression());
                if (inner instanceof Integer) {
                    return -(Integer) inner;
                }
                if (inner instanceof Long) {
                    return -(Long) inner;
                }
                if (inner instanceof Double) {
                    return -(Double) inner;
                }
                if (inner instanceof Float) {
                    return -(Float) inner;
                }
            }
        } else if (expr instanceof NameExpr) {
            // If it's a variable, resolve and get the statement's value if it's a primitive
            VariableReference ref = scope.resolve(((NameExpr) expr).getNameAsString());
            if (ref != null) {
                Statement stmt = testCase.getStatement(ref.getStPosition());
                if (stmt instanceof PrimitiveStatement) {
                    return ((PrimitiveStatement<?>) stmt).getValue();
                }
            }
        }
        return null;
    }

    private Object coerceBooleanExpectedValue(Object expectedValue) {
        if (expectedValue instanceof Boolean) {
            return expectedValue;
        }
        if (expectedValue instanceof Number) {
            int v = ((Number) expectedValue).intValue();
            if (v == 0) {
                return Boolean.FALSE;
            }
            if (v == 1) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    /**
     * Attach an assertion to the statement that defined the source variable.
     */
    private void attachAssertionToSource(VariableReference sourceRef, org.evosuite.assertion.Assertion assertion) {
        int pos = sourceRef.getStPosition();
        if (pos >= 0 && pos < testCase.size()) {
            testCase.getStatement(pos).addAssertion(assertion);
        }
    }

    // ========================================================================
    // Field access: obj.field or Class.FIELD
    // ========================================================================

    private VariableReference handleFieldAccess(FieldAccessExpr expr, Type declaredType, String targetVarName) {
        try {
            String fieldName = expr.getNameAsString();
            Expression scopeExpr = expr.getScope();
            VariableReference source = null;
            Class<?> targetClass;

            // Try as variable first (instance field)
            source = resolveCalleeOrClass(scopeExpr);
            if (source != null) {
                targetClass = getRawClass(source.getType());
            } else {
                // Static field: Class.FIELD
                targetClass = resolveClassFromExpression(scopeExpr);
                if (targetClass == null) {
                    return failOrTypedFallback(expr, declaredType, null, targetVarName,
                            "Cannot resolve field scope: " + scopeExpr);
                }
            }

            // Check for enum constant
            if (targetClass.isEnum()) {
                return handleEnumConstant(targetClass, fieldName, expr);
            }

            Field field;
            try {
                field = targetClass.getField(fieldName);
            } catch (NoSuchFieldException nsfe) {
                try {
                    field = targetClass.getDeclaredField(fieldName);
                } catch (NoSuchFieldException missingField) {
                    return failOrTypedFallback(expr, declaredType, null, targetVarName,
                            "Unknown field " + fieldName + " in " + targetClass.getSimpleName());
                }
            }
            if (!isAccessibleMember(field)) {
                return failOrTypedFallback(expr, declaredType, field.getGenericType(), targetVarName,
                        field.getName() + " has private access in " + targetClass.getSimpleName());
            }
            TestClusterUtils.makeAccessible(field);
            GenericClass<?> ownerClass = GenericClassFactory.get(targetClass);
            GenericField genericField = new GenericField(field, ownerClass);

            FieldStatement stmt = new FieldStatement(testCase, genericField, source);
            return testCase.addStatement(stmt);

        } catch (Exception e) {
            addError(expr, "Failed to parse field access: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private VariableReference handleEnumConstant(Class<?> enumClass, String constantName, Expression expr) {
        try {
            Enum<?> enumValue = Enum.valueOf((Class<Enum>) enumClass, constantName);
            EnumPrimitiveStatement stmt = new EnumPrimitiveStatement(testCase, enumValue);
            return testCase.addStatement(stmt);
        } catch (Exception e) {
            addError(expr, "Failed to resolve enum constant: " + enumClass.getName() + "." + constantName);
            return null;
        }
    }

    // ========================================================================
    // Argument resolution
    // ========================================================================

    /**
     * Thrown by {@link #resolveArguments} when at least one argument cannot be
     * resolved. Callers catch this (via their generic {@code catch (Exception)})
     * and fall back to an uninterpreted statement or an ERROR diagnostic,
     * rather than silently continuing with dummy null arguments that would
     * cascade into misleading "no matching method" errors downstream.
     */
    private static final class UnresolvedArgumentException extends RuntimeException {
        UnresolvedArgumentException(String message) {
            super(message);
        }
    }

    /**
     * Resolve a list of argument expressions into VariableReferences.
     * Inline literals and null are materialized as auto-created statements.
     *
     * <p>If any argument cannot be resolved, an ERROR diagnostic has already been
     * recorded by {@link #resolveArgument} and this method throws
     * {@link UnresolvedArgumentException} so the calling handler aborts the
     * enclosing call instead of papering over the failure with a dummy null.
     *
     * @param args         the argument expression list
     * @param paramTypes   parameter types from the resolved method/constructor (for null typing), or null
     * @param resolvedExec the resolved method/constructor (for parameter types), or null
     * @return list of VariableReferences (same size as {@code args})
     */
    List<VariableReference> resolveArguments(List<Expression> args,
                                             Class<?>[] paramTypes,
                                             Object resolvedExec) {
        List<VariableReference> refs = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Expression arg = args.get(i);
            Type paramType = (paramTypes != null && i < paramTypes.length)
                    ? paramTypes[i] : Object.class;

            VariableReference ref = resolveArgument(arg, paramType);
            if (ref == null) {
                throw new UnresolvedArgumentException(
                        "Unresolved argument " + i + ": " + arg);
            }
            refs.add(ref);
        }
        return refs;
    }

    /**
     * Resolve a single argument expression to a VariableReference.
     * Returns {@code null} if the expression could not be resolved; in that case
     * an ERROR diagnostic has already been added.
     */
    private VariableReference resolveArgument(Expression arg, Type paramType) {
        // Direct variable reference
        if (arg instanceof NameExpr) {
            String name = ((NameExpr) arg).getNameAsString();
            VariableReference ref = scope.resolve(name);
            if (ref != null) {
                return ref;
            }
            // Not in scope. Emit a clear diagnostic whether or not the token happens
            // to also be a class name — a bare class reference is not a valid
            // argument expression.
            boolean isClassName;
            try {
                typeResolver.resolveClass(name);
                isClassName = true;
            } catch (ClassNotFoundException e) {
                isClassName = false;
            }
            String msg = isClassName
                    ? "Bare class name used as argument: " + name
                            + " (did you mean " + name + ".class?)"
                    : "Unresolved variable: " + name;
            addError(arg, msg);
            return null;
        }

        // Inline literal or complex expression — create a synthetic statement
        String syntheticName = "__arg" + syntheticVarCounter++;
        Type effectiveType = paramType;
        if (paramType == null || paramType == Object.class) {
            effectiveType = inferTypeForUntypedArgument(arg);
        }
        return handleExpression(syntheticName, arg, effectiveType);
    }

    /**
     * Best-effort type inference for arguments resolved before method/constructor signature
     * resolution. This avoids degrading common numeric expressions (e.g. {@code 48 + 1})
     * to {@code Object}, which later causes avoidable signature mismatches and uncompilable
     * synthetic assignments.
     */
    private Type inferTypeForUntypedArgument(Expression arg) {
        if (arg instanceof EnclosedExpr) {
            return inferTypeForUntypedArgument(((EnclosedExpr) arg).getInner());
        }
        if (arg instanceof ClassExpr) {
            return Class.class;
        }
        if (arg instanceof CastExpr) {
            try {
                return typeResolver.resolveType(((CastExpr) arg).getType());
            } catch (Exception ignored) {
                return Object.class;
            }
        }
        if (arg instanceof NameExpr) {
            VariableReference ref = scope.resolve(((NameExpr) arg).getNameAsString());
            return ref != null ? ref.getType() : Object.class;
        }
        if (arg instanceof MethodCallExpr) {
            return inferMethodCallReturnType((MethodCallExpr) arg);
        }
        if (arg instanceof NullLiteralExpr) {
            return Void.class;
        }
        if (arg instanceof BooleanLiteralExpr) {
            return boolean.class;
        }
        if (arg instanceof CharLiteralExpr) {
            return char.class;
        }
        if (arg instanceof StringLiteralExpr || arg instanceof TextBlockLiteralExpr) {
            return String.class;
        }
        if (arg instanceof IntegerLiteralExpr) {
            String lit = ((IntegerLiteralExpr) arg).getValue();
            String normalized = lit == null ? "" : lit.replace("_", "");
            if (normalized.endsWith("L") || normalized.endsWith("l")) {
                return long.class;
            }
            return int.class;
        }
        if (arg instanceof DoubleLiteralExpr) {
            String lit = ((DoubleLiteralExpr) arg).getValue();
            String normalized = lit == null ? "" : lit.replace("_", "");
            if (normalized.endsWith("F") || normalized.endsWith("f")) {
                return float.class;
            }
            return double.class;
        }
        if (arg instanceof UnaryExpr) {
            return inferTypeForUntypedArgument(((UnaryExpr) arg).getExpression());
        }
        if (arg instanceof BinaryExpr) {
            BinaryExpr bin = (BinaryExpr) arg;
            BinaryExpr.Operator op = bin.getOperator();
            if (isStringConcat(bin, Object.class) || containsStringOperand(bin)) {
                return String.class;
            }
            switch (op) {
                case OR:
                case AND:
                case EQUALS:
                case NOT_EQUALS:
                case LESS:
                case GREATER:
                case LESS_EQUALS:
                case GREATER_EQUALS:
                    return boolean.class;
                case BINARY_AND:
                case BINARY_OR:
                case XOR: {
                    Type left = inferTypeForUntypedArgument(bin.getLeft());
                    Type right = inferTypeForUntypedArgument(bin.getRight());
                    Class<?> leftRaw = getRawClass(left);
                    Class<?> rightRaw = getRawClass(right);
                    if ((leftRaw == boolean.class || leftRaw == Boolean.class)
                            && (rightRaw == boolean.class || rightRaw == Boolean.class)) {
                        return boolean.class;
                    }
                    if (leftRaw == long.class || leftRaw == Long.class
                            || rightRaw == long.class || rightRaw == Long.class) {
                        return long.class;
                    }
                    return int.class;
                }
                default:
                    Type left = inferTypeForUntypedArgument(bin.getLeft());
                    Type right = inferTypeForUntypedArgument(bin.getRight());
                    Class<?> leftRaw = getRawClass(left);
                    Class<?> rightRaw = getRawClass(right);
                    if (leftRaw == double.class || leftRaw == Double.class
                            || rightRaw == double.class || rightRaw == Double.class) {
                        return double.class;
                    }
                    if (leftRaw == float.class || leftRaw == Float.class
                            || rightRaw == float.class || rightRaw == Float.class) {
                        return float.class;
                    }
                    if (leftRaw == long.class || leftRaw == Long.class
                            || rightRaw == long.class || rightRaw == Long.class) {
                        return long.class;
                    }
                    return int.class;
            }
        }
        return Object.class;
    }

    private boolean isFunctionalInterfaceType(Type type) {
        if (type == null) {
            return false;
        }
        Class<?> raw = getRawClass(type);
        if (raw == null || !raw.isInterface()) {
            return false;
        }
        int abstractMethodCount = 0;
        for (Method method : raw.getMethods()) {
            if (!isFunctionalAbstractMethod(method)) {
                continue;
            }
            abstractMethodCount++;
            if (abstractMethodCount > 1) {
                return false;
            }
        }
        return abstractMethodCount == 1;
    }

    private boolean isFunctionalAbstractMethod(Method method) {
        if (method == null || method.getDeclaringClass() == Object.class) {
            return false;
        }
        int modifiers = method.getModifiers();
        if (!Modifier.isAbstract(modifiers) || Modifier.isStatic(modifiers)
                || method.isBridge() || method.isSynthetic()) {
            return false;
        }
        return !method.isDefault();
    }

    private Type inferMethodCallReturnType(MethodCallExpr expr) {
        try {
            String methodName = expr.getNameAsString();
            Class<?> targetClass;

            if (expr.getScope().isPresent()) {
                Expression scopeExpr = expr.getScope().get();
                if (scopeExpr instanceof NameExpr) {
                    VariableReference scopeVar = scope.resolve(((NameExpr) scopeExpr).getNameAsString());
                    if (scopeVar == null) {
                        return Object.class;
                    }
                    targetClass = getRawClass(scopeVar.getType());
                } else {
                    targetClass = resolveClassFromExpression(scopeExpr);
                    if (targetClass == null) {
                        return Object.class;
                    }
                }
            } else {
                String staticClass = typeResolver.resolveStaticImportClass(methodName);
                if (staticClass == null) {
                    return Object.class;
                }
                targetClass = typeResolver.resolveClass(staticClass);
            }

            Class<?>[] argTypes = new Class<?>[expr.getArguments().size()];
            for (int i = 0; i < expr.getArguments().size(); i++) {
                argTypes[i] = getRawClass(inferTypeForUntypedArgument(expr.getArgument(i)));
            }

            try {
                Method method = resolveMethod(targetClass, methodName, argTypes);
                return method.getGenericReturnType();
            } catch (NoSuchMethodException e) {
                Method loose = resolveMethodByNameLoose(targetClass, methodName);
                if (loose != null) {
                    return loose.getGenericReturnType();
                }
            }
        } catch (Exception ignored) {
            // best-effort only
        }
        return Object.class;
    }

    /**
     * Validate that resolved arguments are type-compatible with method/constructor parameters.
     * Checks for:
     * <ul>
     *   <li>Generic collection mismatches (e.g. ArrayList&lt;IState&gt; passed as List&lt;Transition&gt;)</li>
     *   <li>Object-to-subtype mismatches (e.g. Object passed where CharConfig is expected)</li>
     * </ul>
     *
     * @return an error message if a mismatch is found, or null if all arguments are compatible
     */
    /**
     * Re-type any Void-typed NullStatement arguments to match the actual parameter types
     * of the resolved method or constructor.  During initial argument resolution the
     * parameter types are unknown, so null literals are given the sentinel type Void.
     * Once the executable is resolved we can fix them up to avoid generating
     * uncompilable casts like {@code (String) void0}.
     */
    private void retypeNullArguments(List<VariableReference> argRefs, Class<?>[] paramTypes, boolean isVarArgs) {
        Class<?>[] argTypes = getArgTypes(argRefs);
        boolean explicitVarArgArray = usesExplicitVarArgsArray(paramTypes, isVarArgs, argTypes);
        for (int i = 0; i < argRefs.size(); i++) {
            VariableReference ref = argRefs.get(i);
            if (ref.getVariableClass() != Void.class) {
                continue;
            }
            Class<?> formalType = getFormalTypeForArgument(paramTypes, isVarArgs, i, argRefs.size(), explicitVarArgArray);
            if (formalType != null && !formalType.isPrimitive()) {
                ref.setType(formalType);
            }
        }
    }

    private String validateArgumentTypes(List<VariableReference> argRefs,
                                         Class<?>[] paramTypes,
                                         Type[] genericParamTypes,
                                         Expression expr,
                                         boolean isVarArgs) {
        Class<?>[] argTypes = getArgTypes(argRefs);
        boolean explicitVarArgArray = usesExplicitVarArgsArray(paramTypes, isVarArgs, argTypes);
        for (int i = 0; i < argRefs.size(); i++) {
            VariableReference argRef = argRefs.get(i);
            Class<?> argClass = argRef.getVariableClass();
            Class<?> paramClass = getFormalTypeForArgument(paramTypes, isVarArgs, i, argRefs.size(), explicitVarArgArray);
            if (paramClass == null) {
                continue;
            }

            // Skip if directly assignable (including primitives, autoboxing handled elsewhere)
            if (isAssignableFrom(paramClass, argClass)) {
                // Check generic type arguments for collection types
                int genericParamIndex = i;
                if (isVarArgs && !explicitVarArgArray && i >= paramTypes.length - 1) {
                    genericParamIndex = paramTypes.length - 1;
                }
                if (genericParamTypes != null && genericParamIndex < genericParamTypes.length
                        && genericParamTypes[genericParamIndex] instanceof java.lang.reflect.ParameterizedType) {
                    String genericError = checkGenericCompatibility(
                            argRef, (java.lang.reflect.ParameterizedType) genericParamTypes[genericParamIndex], i);
                    if (genericError != null) {
                        return genericError;
                    }
                }
                continue;
            }

            // Object (or other supertype) passed where specific subtype is expected
            if (!paramClass.isPrimitive() && argClass == Object.class && paramClass != Object.class) {
                return "Argument " + i + " is Object but parameter expects "
                        + paramClass.getSimpleName() + " — implicit cast not safe";
            }

            // General type mismatch — already handled by method resolution compatibility,
            // but catch stragglers
            if (!paramClass.isPrimitive() && !paramClass.isAssignableFrom(argClass)) {
                return "Argument " + i + " type " + argClass.getSimpleName()
                        + " is not compatible with parameter type " + paramClass.getSimpleName();
            }
        }
        return null;
    }

    /**
     * For varargs calls parsed in expanded form (e.g. {@code m("a", "b")}),
     * materialize the trailing arguments into an explicit array variable so the
     * resulting Statement has one argument per declared parameter.
     */
    private List<VariableReference> normalizeVarArgsArguments(List<VariableReference> argRefs,
                                                              Class<?>[] paramTypes,
                                                              boolean isVarArgs) {
        if (!isVarArgs || paramTypes.length == 0) {
            return argRefs;
        }
        Class<?>[] argTypes = getArgTypes(argRefs);
        boolean explicitVarArgArray = usesExplicitVarArgsArray(paramTypes, true, argTypes);
        if (explicitVarArgArray) {
            return argRefs;
        }

        int fixedCount = paramTypes.length - 1;
        int varArgCount = argRefs.size() - fixedCount;
        if (varArgCount < 0) {
            return argRefs;
        }

        Class<?> varArgArrayType = paramTypes[paramTypes.length - 1];
        ArrayStatement arrayStatement = new ArrayStatement(testCase, varArgArrayType, new int[]{varArgCount});
        VariableReference varArgArrayRef = testCase.addStatement(arrayStatement);

        if (!(varArgArrayRef instanceof ArrayReference)) {
            return argRefs;
        }

        ArrayReference arrayReference = (ArrayReference) varArgArrayRef;
        for (int i = 0; i < varArgCount; i++) {
            VariableReference valueRef = argRefs.get(fixedCount + i);
            ArrayIndex arrayIndex = new ArrayIndex(testCase, arrayReference, i);
            testCase.addStatement(new AssignmentStatement(testCase, arrayIndex, valueRef));
        }

        List<VariableReference> normalized = new ArrayList<>(paramTypes.length);
        for (int i = 0; i < fixedCount; i++) {
            normalized.add(argRefs.get(i));
        }
        normalized.add(varArgArrayRef);
        return normalized;
    }

    /**
     * Check generic type parameter compatibility between an argument's tracked generic type
     * and the formal parameter's generic type. Detects cases like passing
     * ArrayList&lt;IState&gt; to a parameter expecting List&lt;Transition&gt;.
     *
     * @return an error message if incompatible, or null if compatible or check is inconclusive
     */
    private String checkGenericCompatibility(VariableReference argRef,
                                             java.lang.reflect.ParameterizedType paramGenericType,
                                             int argIndex) {
        // Look up the argument's generic type from the scope tracking
        int stPos = argRef.getStPosition();
        if (stPos < 0 || stPos >= testCase.size()) {
            return null;
        }

        // Find the variable name from scope that maps to this ref
        // and check its tracked GenericClass
        GenericClass<?> argGeneric = findGenericTypeForRef(argRef);
        if (argGeneric == null || !(argGeneric.getType() instanceof java.lang.reflect.ParameterizedType)) {
            return null; // Can't check — no generic info tracked
        }

        java.lang.reflect.ParameterizedType argParamType =
                (java.lang.reflect.ParameterizedType) argGeneric.getType();
        Type[] argTypeArgs = argParamType.getActualTypeArguments();
        Type[] paramTypeArgs = paramGenericType.getActualTypeArguments();

        if (argTypeArgs.length != paramTypeArgs.length) {
            return null; // Different arity — can't compare meaningfully
        }

        for (int j = 0; j < argTypeArgs.length; j++) {
            Class<?> argTArg = getRawClass(argTypeArgs[j]);
            Class<?> paramTArg = getRawClass(paramTypeArgs[j]);
            // Skip if param type arg is a TypeVariable (unresolved generic)
            if (paramTypeArgs[j] instanceof java.lang.reflect.TypeVariable) {
                continue;
            }
            if (argTArg != Object.class && paramTArg != Object.class
                    && !paramTArg.isAssignableFrom(argTArg) && !argTArg.isAssignableFrom(paramTArg)) {
                return "Generic type mismatch at argument " + argIndex
                        + ": " + argTArg.getSimpleName() + " is not compatible with "
                        + paramTArg.getSimpleName();
            }
        }
        return null;
    }

    /**
     * Find the GenericClass tracked in scope for a given VariableReference.
     */
    private GenericClass<?> findGenericTypeForRef(VariableReference ref) {
        return scope.findGenericTypeForRef(ref);
    }

    // ========================================================================
    // Class literal: Foo.class
    // ========================================================================

    private VariableReference handleClassExpression(ClassExpr expr) {
        try {
            Class<?> clazz = typeResolver.resolveClass(expr.getTypeAsString());
            ClassPrimitiveStatement stmt = new ClassPrimitiveStatement(testCase, clazz);
            return testCase.addStatement(stmt);
        } catch (ClassNotFoundException e) {
            if (markParsedFromLlm) {
                return fallbackForUnresolvedExpression(
                        expr,
                        Class.class,
                        "Cannot resolve class literal: " + e.getMessage());
            }
            addError(expr, "Cannot resolve class literal: " + e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // Array creation: new Type[n]
    // ========================================================================

    private VariableReference handleArrayCreation(ArrayCreationExpr expr) {
        try {
            // Resolve the component type
            Type componentType = typeResolver.resolveType(expr.getElementType());

            // Check for initializer: new int[]{1, 2, 3} or new double[][]{{1.0}}
            if (expr.getInitializer().isPresent()) {
                ArrayInitializerExpr init = expr.getInitializer().get();
                // Build the full array type by wrapping for each dimension level.
                // For "new double[][]{{1.0}}", elementType=double and levels=2,
                // so arrayType must be double[][] (not just double[]).
                Type arrayType = componentType;
                for (int i = 0; i < expr.getLevels().size(); i++) {
                    arrayType = java.lang.reflect.Array.newInstance(
                            getRawClass(arrayType), 0).getClass();
                }
                // The component type of the outermost array (e.g. double[] for double[][])
                Type outerComponentType = ((Class<?>) arrayType).getComponentType();
                return createArrayWithInitializer(init, arrayType, outerComponentType);
            }

            // Get dimensions
            List<ArrayCreationLevel> levels = expr.getLevels();
            int[] lengths = new int[levels.size()];
            for (int i = 0; i < levels.size(); i++) {
                if (levels.get(i).getDimension().isPresent()) {
                    Expression dimExpr = levels.get(i).getDimension().get();
                    Integer resolvedLength = evaluateIntExpression(dimExpr);
                    if (resolvedLength != null) {
                        lengths[i] = resolvedLength;
                    } else {
                        addWarning(dimExpr, "Non-literal array dimension, defaulting to 0: " + dimExpr);
                        lengths[i] = 0;
                    }
                }
            }

            // Build the array type
            Type arrayType = componentType;
            for (int i = 0; i < levels.size(); i++) {
                arrayType = java.lang.reflect.Array.newInstance(getRawClass(arrayType), 0).getClass();
            }

            ArrayStatement stmt = new ArrayStatement(testCase, arrayType, lengths);
            return testCase.addStatement(stmt);

        } catch (Exception e) {
            addError(expr, "Failed to parse array creation: " + e.getMessage());
            return null;
        }
    }

    /**
     * Handle array access read: arr[i] → ArrayIndex reference.
     */
    private VariableReference handleArrayAccessRead(ArrayAccessExpr expr) {
        try {
            return resolveArrayAccess(expr, expr);
        } catch (Exception e) {
            addError(expr, "Failed to parse array access: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolve nested array access expressions such as arr[i] and arr[i][j]
     * to an {@link ArrayIndex} with one or more indices.
     */
    private ArrayIndex resolveArrayAccess(ArrayAccessExpr expr, Expression diagnosticExpr) {
        List<Integer> indices = new ArrayList<>();
        Expression current = expr;

        while (current instanceof ArrayAccessExpr) {
            ArrayAccessExpr level = (ArrayAccessExpr) current;
            indices.add(0, resolveArrayIndex(level.getIndex()));
            current = level.getName();
        }

        VariableReference arrayRef = resolveArrayRootReference(current);
        if (arrayRef == null) {
            String description = (current instanceof NameExpr)
                    ? ((NameExpr) current).getNameAsString()
                    : current.toString();
            if (markParsedFromLlm) {
                addWarning(diagnosticExpr, "Unknown array variable: " + description);
            } else {
                addError(diagnosticExpr, "Unknown array variable: " + description);
            }
            return null;
        }
        if (!(arrayRef instanceof ArrayReference)) {
            String description = (current instanceof NameExpr)
                    ? ((NameExpr) current).getNameAsString()
                    : current.toString();
            if (markParsedFromLlm) {
                addWarning(diagnosticExpr, "Variable is not an array: " + description);
            } else {
                addError(diagnosticExpr, "Variable is not an array: " + description);
            }
            return null;
        }

        return new ArrayIndex(testCase, (ArrayReference) arrayRef, indices);
    }

    /**
     * Resolve the root expression of an array access chain.
     * Supports regular local names and LLM salvage for {@code this.field} access
     * when field initializers have been lifted into method scope.
     */
    private VariableReference resolveArrayRootReference(Expression root) {
        if (root instanceof NameExpr) {
            return scope.resolve(((NameExpr) root).getNameAsString());
        }
        if (root instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccess = (FieldAccessExpr) root;
            if (fieldAccess.getScope() instanceof ThisExpr) {
                return scope.resolve(fieldAccess.getNameAsString());
            }
        }
        return null;
    }

    /**
     * Resolve an array index expression to an integer.
     * Supports integer literals and variable references to numeric primitives.
     */
    private int resolveArrayIndex(Expression indexExpr) {
        Integer resolved = evaluateIntExpression(indexExpr);
        if (resolved != null) {
            return resolved;
        }
        addWarning(indexExpr, "Non-literal array index, defaulting to 0: " + indexExpr);
        return 0;
    }

    /**
     * Evaluate an expression as an integer using parser-known constants/variables only.
     * Returns null when the expression cannot be resolved without executing code.
     */
    private Integer evaluateIntExpression(Expression expr) {
        if (expr instanceof IntegerLiteralExpr) {
            return ((IntegerLiteralExpr) expr).asNumber().intValue();
        }
        if (expr instanceof EnclosedExpr) {
            return evaluateIntExpression(((EnclosedExpr) expr).getInner());
        }
        if (expr instanceof UnaryExpr) {
            UnaryExpr unaryExpr = (UnaryExpr) expr;
            Integer inner = evaluateIntExpression(unaryExpr.getExpression());
            if (inner == null) {
                return null;
            }
            if (unaryExpr.getOperator() == UnaryExpr.Operator.MINUS) {
                return -inner;
            }
            if (unaryExpr.getOperator() == UnaryExpr.Operator.PLUS) {
                return inner;
            }
            return null;
        }
        if (expr instanceof NameExpr) {
            VariableReference ref = scope.resolve(((NameExpr) expr).getNameAsString());
            return readNumericVariableValue(ref);
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr binaryExpr = (BinaryExpr) expr;
            Integer left = evaluateIntExpression(binaryExpr.getLeft());
            Integer right = evaluateIntExpression(binaryExpr.getRight());
            if (left == null || right == null) {
                return null;
            }
            switch (binaryExpr.getOperator()) {
                case PLUS:
                    return left + right;
                case MINUS:
                    return left - right;
                case MULTIPLY:
                    return left * right;
                case DIVIDE:
                    if (right == 0) {
                        return null;
                    }
                    return left / right;
                case REMAINDER:
                    if (right == 0) {
                        return null;
                    }
                    return left % right;
                default:
                    return null;
            }
        }
        if (expr instanceof MethodCallExpr) {
            MethodCallExpr methodCallExpr = (MethodCallExpr) expr;
            if ("length".equals(methodCallExpr.getNameAsString())
                    && methodCallExpr.getArguments().isEmpty()
                    && methodCallExpr.getScope().isPresent()) {
                Expression scopeExpr = methodCallExpr.getScope().get();
                VariableReference ref = resolveArrayRootReference(scopeExpr);
                if (ref == null && scopeExpr instanceof NameExpr) {
                    ref = scope.resolve(((NameExpr) scopeExpr).getNameAsString());
                }
                if (ref instanceof ArrayReference) {
                    return ((ArrayReference) ref).getStructuralArrayLength();
                }
                Statement valueStmt = getStatementForReference(ref);
                if (valueStmt instanceof StringPrimitiveStatement) {
                    String value = ((StringPrimitiveStatement) valueStmt).getValue();
                    return value == null ? null : value.length();
                }
            }
            return null;
        }
        if (expr instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccessExpr = (FieldAccessExpr) expr;
            if ("length".equals(fieldAccessExpr.getNameAsString())) {
                VariableReference ref = resolveArrayRootReference(fieldAccessExpr.getScope());
                if (ref instanceof ArrayReference) {
                    return ((ArrayReference) ref).getStructuralArrayLength();
                }
            }
            return null;
        }
        return null;
    }

    private Integer readNumericVariableValue(VariableReference ref) {
        Statement valueStmt = getStatementForReference(ref);
        if (valueStmt instanceof PrimitiveStatement) {
            Object val = ((PrimitiveStatement<?>) valueStmt).getValue();
            if (val instanceof Number) {
                return ((Number) val).intValue();
            }
            if (val instanceof Character) {
                return (int) ((Character) val);
            }
        }
        return null;
    }

    private Statement getStatementForReference(VariableReference ref) {
        if (ref == null) {
            return null;
        }
        int pos = ref.getStPosition();
        if (pos >= 0 && pos < testCase.size()) {
            return testCase.getStatement(pos);
        }
        return null;
    }

    /**
     * Handle standalone array initializer expression (rare — usually inside ArrayCreationExpr).
     */
    private VariableReference handleArrayInitializer(ArrayInitializerExpr expr, Type declaredType) {
        try {
            Type componentType = declaredType;
            if (declaredType instanceof Class && ((Class<?>) declaredType).isArray()) {
                componentType = ((Class<?>) declaredType).getComponentType();
            }
            return createArrayWithInitializer(expr, declaredType, componentType);
        } catch (Exception e) {
            addError(expr, "Failed to parse array initializer: " + e.getMessage());
            return null;
        }
    }

    /**
     * Create an ArrayStatement with initial values from an ArrayInitializerExpr.
     * Produces: ArrayStatement + value statements + AssignmentStatements.
     */
    private VariableReference createArrayWithInitializer(ArrayInitializerExpr init,
                                                         Type arrayType, Type componentType) {
        List<Expression> values = init.getValues();
        int[] lengths = inferArrayLengthsFromInitializer(init, arrayType);
        ArrayStatement arrayStmt = new ArrayStatement(testCase, arrayType, lengths);
        VariableReference arrayRef = testCase.addStatement(arrayStmt);

        for (int i = 0; i < values.size(); i++) {
            VariableReference valueRef = handleExpression(
                    "__init" + syntheticVarCounter++, values.get(i), componentType);
            if (valueRef != null) {
                ArrayIndex arrayIndex = new ArrayIndex(testCase,
                        (ArrayReference) arrayRef, i);
                AssignmentStatement assignStmt = new AssignmentStatement(
                        testCase, arrayIndex, valueRef);
                testCase.addStatement(assignStmt);
            }
        }
        return arrayRef;
    }

    /**
     * Infer array dimensions for {@code new T[][] { ... }} style initializers.
     * This is needed so ArrayStatement allocates the correct runtime array rank.
     */
    private int[] inferArrayLengthsFromInitializer(ArrayInitializerExpr init, Type arrayType) {
        Class<?> rawArray = getRawClass(arrayType);
        int dimensions = getArrayDimensions(rawArray);
        if (dimensions <= 1) {
            return new int[]{init.getValues().size()};
        }

        int[] lengths = new int[dimensions];
        lengths[0] = init.getValues().size();
        if (!init.getValues().isEmpty()) {
            int[] nested = inferNestedArrayLengths(init.getValues().get(0), dimensions - 1);
            System.arraycopy(nested, 0, lengths, 1, Math.min(nested.length, dimensions - 1));
        }
        return lengths;
    }

    private int[] inferNestedArrayLengths(Expression expr, int remainingDimensions) {
        int[] lengths = new int[Math.max(remainingDimensions, 0)];
        if (remainingDimensions <= 0) {
            return lengths;
        }

        if (expr instanceof ArrayInitializerExpr) {
            ArrayInitializerExpr nestedInit = (ArrayInitializerExpr) expr;
            lengths[0] = nestedInit.getValues().size();
            if (remainingDimensions > 1 && !nestedInit.getValues().isEmpty()) {
                int[] deeper = inferNestedArrayLengths(nestedInit.getValues().get(0), remainingDimensions - 1);
                System.arraycopy(deeper, 0, lengths, 1, Math.min(deeper.length, remainingDimensions - 1));
            }
            return lengths;
        }

        if (expr instanceof ArrayCreationExpr) {
            ArrayCreationExpr creationExpr = (ArrayCreationExpr) expr;
            if (creationExpr.getInitializer().isPresent()) {
                return inferNestedArrayLengths(creationExpr.getInitializer().get(), remainingDimensions);
            }
            List<ArrayCreationLevel> levels = creationExpr.getLevels();
            for (int i = 0; i < remainingDimensions && i < levels.size(); i++) {
                if (levels.get(i).getDimension().isPresent()
                        && levels.get(i).getDimension().get() instanceof IntegerLiteralExpr) {
                    lengths[i] = ((IntegerLiteralExpr) levels.get(i).getDimension().get()).asNumber().intValue();
                }
            }
            return lengths;
        }

        return lengths;
    }

    private int getArrayDimensions(Class<?> rawClass) {
        int dimensions = 0;
        Class<?> cursor = rawClass;
        while (cursor != null && cursor.isArray()) {
            dimensions++;
            cursor = cursor.getComponentType();
        }
        return dimensions;
    }

    // ========================================================================
    // Binary expression: a + b, x == y → UninterpretedStatement
    // ========================================================================

    /**
     * Binary expressions are preserved as UninterpretedStatements.
     * Rejects the expression if any referenced variable is not in scope.
     */
    private VariableReference handleBinaryExpression(String varName, BinaryExpr expr, Type declaredType) {
        // Validate all variable references in the expression are resolvable
        for (String name : collectReferencedSimpleNames(expr)) {
            if (!scope.isDefined(name)) {
                // Check if it's a class name rather than a variable
                try {
                    typeResolver.resolveClass(name);
                } catch (ClassNotFoundException e) {
                    addError(expr, "Unresolved variable in expression: " + name);
                    return null;
                }
            }
        }

        // String concatenation: try to evaluate "a" + b + c into a single String literal
        if (!markParsedFromLlm
                && expr.getOperator() == BinaryExpr.Operator.PLUS
                && isStringConcat(expr, declaredType)) {
            String result = evaluateStringConcat(expr);
            if (result != null) {
                StringPrimitiveStatement stmt = new StringPrimitiveStatement(testCase, result);
                return testCase.addStatement(stmt);
            }
        }

        // Reconstruct the source: "type varName = left op right;"
        String typeName = getSimpleTypeName(declaredType);
        String code = typeName + " " + varName + " = " + expr.toString() + ";";
        UninterpretedStatement stmt = createUninterpretedStatement(declaredType, code, varName, expr);
        return testCase.addStatement(stmt);
    }

    /**
     * Check if a binary PLUS expression is string concatenation
     * (declared type is String, or any operand in the chain is a String literal).
     */
    private boolean isStringConcat(BinaryExpr expr, Type declaredType) {
        if (declaredType == String.class) {
            return true;
        }
        return containsStringOperand(expr);
    }

    private boolean containsStringOperand(Expression expr) {
        if (expr instanceof StringLiteralExpr || expr instanceof TextBlockLiteralExpr) {
            return true;
        }
        if (expr instanceof EnclosedExpr) {
            return containsStringOperand(((EnclosedExpr) expr).getInner());
        }
        if (expr instanceof CastExpr) {
            try {
                Type castType = typeResolver.resolveType(((CastExpr) expr).getType());
                if (getRawClass(castType) == String.class) {
                    return true;
                }
            } catch (Exception ignored) {
                // best-effort only
            }
            return containsStringOperand(((CastExpr) expr).getExpression());
        }
        if (expr instanceof NameExpr) {
            VariableReference ref = scope.resolve(((NameExpr) expr).getNameAsString());
            return ref != null && getRawClass(ref.getType()) == String.class;
        }
        if (expr instanceof MethodCallExpr) {
            return getRawClass(inferMethodCallReturnType((MethodCallExpr) expr)) == String.class;
        }
        if (expr instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccessExpr = (FieldAccessExpr) expr;
            try {
                Class<?> owner = resolveClassFromExpression(fieldAccessExpr.getScope());
                if (owner != null) {
                    Field field;
                    try {
                        field = owner.getField(fieldAccessExpr.getNameAsString());
                    } catch (NoSuchFieldException e) {
                        field = owner.getDeclaredField(fieldAccessExpr.getNameAsString());
                    }
                    return field.getType() == String.class;
                }
            } catch (Exception ignored) {
                // best-effort only
            }
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr bin = (BinaryExpr) expr;
            return containsStringOperand(bin.getLeft()) || containsStringOperand(bin.getRight());
        }
        return false;
    }

    /**
     * Evaluate a string concatenation expression by collecting all operands
     * and converting them to strings. Returns null if any operand can't be resolved.
     */
    private String evaluateStringConcat(BinaryExpr expr) {
        List<Expression> operands = new ArrayList<>();
        flattenConcatOperands(expr, operands);

        StringBuilder sb = new StringBuilder();
        for (Expression op : operands) {
            Object val = evaluateConcatOperand(op);
            if (val == null) {
                return null;
            }
            sb.append(val);
        }
        return sb.toString();
    }

    private void flattenConcatOperands(Expression expr, List<Expression> operands) {
        if (expr instanceof BinaryExpr) {
            BinaryExpr bin = (BinaryExpr) expr;
            if (bin.getOperator() == BinaryExpr.Operator.PLUS) {
                flattenConcatOperands(bin.getLeft(), operands);
                flattenConcatOperands(bin.getRight(), operands);
                return;
            }
        }
        operands.add(expr);
    }

    /**
     * Evaluate a single operand in a string concatenation chain.
     * Returns the value as an Object (whose toString() gives the right string),
     * or null if unresolvable.
     */
    private Object evaluateConcatOperand(Expression expr) {
        if (expr instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) expr).asString();
        }
        if (expr instanceof IntegerLiteralExpr) {
            return ((IntegerLiteralExpr) expr).asNumber();
        }
        if (expr instanceof LongLiteralExpr) {
            return ((LongLiteralExpr) expr).asNumber();
        }
        if (expr instanceof DoubleLiteralExpr) {
            return Double.parseDouble(((DoubleLiteralExpr) expr).getValue());
        }
        if (expr instanceof CharLiteralExpr) {
            return ((CharLiteralExpr) expr).asChar();
        }
        if (expr instanceof BooleanLiteralExpr) {
            return ((BooleanLiteralExpr) expr).getValue();
        }
        // Variable reference: look up its value from the TestCase
        if (expr instanceof NameExpr) {
            VariableReference ref = scope.resolve(((NameExpr) expr).getNameAsString());
            if (ref != null) {
                int pos = ref.getStPosition();
                if (pos >= 0 && pos < testCase.size()) {
                    Statement s = testCase.getStatement(pos);
                    if (s instanceof PrimitiveStatement) {
                        return ((PrimitiveStatement<?>) s).getValue();
                    }
                }
            }
        }
        return null;
    }

    private String getSimpleTypeName(Type type) {
        if (type instanceof Class) {
            return ((Class<?>) type).getSimpleName();
        }
        return type.getTypeName();
    }

    // ========================================================================
    // Assignment: array[i] = value, obj.field = value
    // ========================================================================

    private void handleAssignment(AssignExpr assignExpr) {
        try {
            Expression target = assignExpr.getTarget();
            Expression value = assignExpr.getValue();

            if (target instanceof NameExpr) {
                String targetName = ((NameExpr) target).getNameAsString();
                if (pendingLlmDeclarations.containsKey(targetName)) {
                    Type declaredType = pendingLlmDeclarations.remove(targetName);
                    VariableReference valueRef = handleExpression(targetName, value, declaredType);
                    if (valueRef != null) {
                        valueRef = coerceIncompatibleAliasDeclaration(targetName, value, declaredType, valueRef);
                    }
                    if (valueRef == null) {
                        return;
                    }
                    GenericClass<?> genericType = null;
                    if (declaredType instanceof java.lang.reflect.ParameterizedType) {
                        genericType = GenericClassFactory.get(declaredType);
                    }
                    scope.register(targetName, valueRef, genericType);
                    return;
                }

                VariableReference lhs = scope.resolve(targetName);
                if (lhs == null) {
                    addError(assignExpr, "Unknown variable for assignment: " + targetName);
                    return;
                }
                VariableReference rhs = handleExpression(
                        "__val" + syntheticVarCounter++, value, lhs.getType());
                if (rhs == null) {
                    return;
                }
                AssignmentStatement stmt = new AssignmentStatement(testCase, lhs, rhs);
                if (!stmt.isValid()) {
                    if (markParsedFromLlm) {
                        addWarning(assignExpr, "Invalid variable assignment; preserved as uninterpreted: "
                                + assignExpr);
                        testCase.addStatement(createUninterpretedStatement(assignExpr,
                                assignExpr.toString() + ";"));
                    } else {
                        addError(assignExpr, "Invalid assignment: " + assignExpr);
                    }
                    return;
                }
                testCase.addStatement(stmt);
            } else if (target instanceof ArrayAccessExpr) {
                // array[i] = value
                ArrayAccessExpr arrayAccess = (ArrayAccessExpr) target;
                ArrayIndex arrayIndex = resolveArrayAccess(arrayAccess, assignExpr);
                if (arrayIndex == null) {
                    if (markParsedFromLlm) {
                        addWarning(assignExpr, "Preserved unresolved array assignment as uninterpreted: "
                                + assignExpr);
                        testCase.addStatement(createUninterpretedStatement(assignExpr,
                                assignExpr.toString() + ";"));
                    }
                    return;
                }

                // Resolve the value
                VariableReference valueRef = handleExpression(
                        "__val" + syntheticVarCounter++, value,
                        arrayIndex.getType() != null ? arrayIndex.getType() : Object.class);
                if (valueRef == null) {
                    return;
                }

                // Create ArrayIndex and AssignmentStatement
                AssignmentStatement stmt = new AssignmentStatement(testCase, arrayIndex, valueRef);
                if (!stmt.isValid()) {
                    if (markParsedFromLlm) {
                        addWarning(assignExpr, "Invalid array assignment for modeled bounds; preserved as "
                                + "uninterpreted: " + assignExpr);
                        testCase.addStatement(createUninterpretedStatement(assignExpr,
                                assignExpr.toString() + ";"));
                    } else {
                        addError(assignExpr, "Invalid assignment: " + assignExpr);
                    }
                    return;
                }
                testCase.addStatement(stmt);
            } else if (target instanceof FieldAccessExpr) {
                // obj.field = value
                FieldAccessExpr fieldAccess = (FieldAccessExpr) target;
                String fieldName = fieldAccess.getNameAsString();
                Expression scopeExpr = fieldAccess.getScope();
                String scopeName = scopeExpr.toString();

                // Resolve the object that owns the field
                VariableReference sourceRef = scope.resolve(scopeName);
                Class<?> ownerClass;
                if (sourceRef != null) {
                    ownerClass = sourceRef.getVariableClass();
                } else {
                    ownerClass = resolveClassFromExpression(scopeExpr);
                    if (ownerClass == null) {
                        addError(assignExpr, "Unknown variable for field access: " + scopeName);
                        return;
                    }
                }

                // Look up the field via reflection
                java.lang.reflect.Field field;
                try {
                    field = ownerClass.getField(fieldName);
                } catch (NoSuchFieldException e) {
                    try {
                        field = ownerClass.getDeclaredField(fieldName);
                    } catch (NoSuchFieldException e2) {
                        addError(assignExpr, "No such field: " + ownerClass.getName() + "." + fieldName);
                        return;
                    }
                }

                if (!isAccessibleMember(field)) {
                    addError(assignExpr, field.getName() + " has private access in "
                            + ownerClass.getSimpleName());
                    return;
                }
                boolean isStaticField = java.lang.reflect.Modifier.isStatic(field.getModifiers());
                if (sourceRef == null && !isStaticField) {
                    addError(assignExpr, "Non-static field " + ownerClass.getSimpleName() + "."
                            + field.getName() + " requires an instance");
                    return;
                }

                // Resolve the value being assigned
                VariableReference valueRef = handleExpression(
                        "__val" + syntheticVarCounter++, value, field.getType());
                if (valueRef == null) {
                    return;
                }

                // Class-qualified static writes (e.g. ClassName.FIELD = value) do not have
                // an owning instance variable in scope. Modeling them via AssignmentStatement
                // + FieldReference(source=null) can fail position checks during insertion.
                // Use reflective field-write statement, which is also robust for non-public fields.
                if (sourceRef == null && isStaticField) {
                    VariableReference nullOwner = testCase.addStatement(new NullStatement(testCase, ownerClass));
                    testCase.addStatement(new PrivateFieldStatement(
                            testCase, ownerClass, field.getName(), nullOwner, valueRef));
                    return;
                }

                // Create FieldReference + AssignmentStatement
                GenericField genericField = new GenericField(field, ownerClass);
                FieldReference fieldRef = new FieldReference(testCase, genericField, sourceRef);
                AssignmentStatement stmt = new AssignmentStatement(testCase, fieldRef, valueRef);
                if (!stmt.isValid()) {
                    if (markParsedFromLlm) {
                        addWarning(assignExpr, "Invalid field assignment; preserved as uninterpreted: "
                                + assignExpr);
                        testCase.addStatement(createUninterpretedStatement(assignExpr,
                                assignExpr.toString() + ";"));
                    } else {
                        addError(assignExpr, "Invalid assignment: " + assignExpr);
                    }
                    return;
                }
                testCase.addStatement(stmt);
            } else {
                addWarning(assignExpr, "Unsupported assignment target: " + target.getClass().getSimpleName());
            }
        } catch (Throwable e) {
            addError(assignExpr, "Failed to parse assignment: " + e.getMessage());
        }
    }

    // ========================================================================
    // Method/Constructor resolution helpers
    // ========================================================================

    /**
     * Find the matching constructor for the given class and argument types.
     */
    private Constructor<?> resolveConstructor(Class<?> clazz, Class<?>[] argTypes)
            throws NoSuchMethodException {
        // Try exact match first
        try {
            return clazz.getDeclaredConstructor(argTypes);
        } catch (NoSuchMethodException ignored) {
            // Ignore and try compatibility match
        }

        // Try compatibility match with autoboxing/widening
        Constructor<?> best = null;
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (!isCompatible(c.getParameterTypes(), c.isVarArgs(), argTypes)) {
                continue;
            }
            if (best == null || isBetterExecutableMatch(c.getParameterTypes(), c.isVarArgs(),
                    best.getParameterTypes(), best.isVarArgs(), argTypes)) {
                best = c;
            }
        }
        if (best != null) {
            return best;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("No matching constructor found for ").append(clazz.getName())
           .append(" with args ").append(formatTypes(argTypes));
        Constructor<?>[] declared = clazz.getDeclaredConstructors();
        if (declared.length > 0) {
            msg.append(". Available constructors: ");
            for (int i = 0; i < declared.length; i++) {
                if (i > 0) {
                    msg.append("; ");
                }
                msg.append(clazz.getSimpleName()).append(formatTypes(declared[i].getParameterTypes()));
                if (declared[i].isVarArgs()) {
                    msg.append(" varargs");
                }
            }
        }
        throw new NoSuchMethodException(msg.toString());
    }

    /**
     * Find the matching method for the given class, name, and argument types.
     */
    private Method resolveMethod(Class<?> clazz, String name, Class<?>[] argTypes)
            throws NoSuchMethodException {
        // Try exact match first
        try {
            return clazz.getMethod(name, argTypes);
        } catch (NoSuchMethodException ignored) {
            // Ignore and try compatibility match
        }

        // Try compatibility match with autoboxing/widening on public methods.
        // Skip bridge methods and prefer the most specific overload so that the
        // code visitor does not emit incorrect casts (e.g. (Object) on a
        // Comparable.compareTo bridge instead of the real compareTo(Argument)).
        Method bestPublic = findMostSpecificMethod(clazz.getMethods(), name, argTypes);
        if (bestPublic != null) {
            return bestPublic;
        }

        // Also try declared methods (including private/protected) for the class itself
        Method bestDeclared = findMostSpecificMethod(clazz.getDeclaredMethods(), name, argTypes);
        if (bestDeclared != null) {
            return bestDeclared;
        }

        throw new NoSuchMethodException("No matching method found: " + clazz.getName()
                + "." + name + " with args " + formatTypes(argTypes));
    }

    private boolean hasMethodNamed(Class<?> clazz, String name, boolean requireStatic) {
        for (Method method : clazz.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (requireStatic && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            return true;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (requireStatic && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Among the given methods, find the most specific one matching the name and arg types.
     * Bridge methods are skipped so that we resolve to the real typed method rather than
     * the erased bridge (e.g. {@code compareTo(Argument)} instead of {@code compareTo(Object)}).
     * When multiple non-bridge methods match, the one with the most specific parameter types wins.
     */
    private Method findMostSpecificMethod(Method[] methods, String name, Class<?>[] argTypes) {
        Method best = null;
        for (Method m : methods) {
            if (m.isBridge() || !m.getName().equals(name)
                    || !isCompatible(m.getParameterTypes(), m.isVarArgs(), argTypes)) {
                continue;
            }
            if (best == null || isBetterExecutableMatch(m.getParameterTypes(), m.isVarArgs(),
                    best.getParameterTypes(), best.isVarArgs(), argTypes)) {
                best = m;
            }
        }
        return best;
    }

    /**
     * Returns true if {@code a} is more specific than {@code b}, i.e. every parameter
     * type in {@code a} is assignable to the corresponding parameter type in {@code b}.
     */
    private boolean isMoreSpecific(Class<?>[] a, Class<?>[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!b[i].isAssignableFrom(a[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if actual argument types are compatible with formal parameter types,
     * considering autoboxing and widening.
     */
    private boolean isCompatible(Class<?>[] formalTypes, boolean isVarArgs, Class<?>[] actualTypes) {
        if (!isVarArgs) {
            if (formalTypes.length != actualTypes.length) {
                return false;
            }
            for (int i = 0; i < formalTypes.length; i++) {
                if (!isAssignableFrom(formalTypes[i], actualTypes[i])) {
                    return false;
                }
            }
            return true;
        }

        if (formalTypes.length == 0) {
            return actualTypes.length == 0;
        }

        int fixedCount = formalTypes.length - 1;
        if (actualTypes.length < fixedCount) {
            return false;
        }

        for (int i = 0; i < fixedCount; i++) {
            if (!isAssignableFrom(formalTypes[i], actualTypes[i])) {
                return false;
            }
        }

        Class<?> varArgArrayType = formalTypes[formalTypes.length - 1];
        Class<?> componentType = varArgArrayType.getComponentType();
        if (componentType == null) {
            return false;
        }

        // Explicit array form: method(..., String[])
        if (usesExplicitVarArgsArray(formalTypes, true, actualTypes)) {
            return true;
        }

        // Expanded varargs: method(..., "a", "b")
        for (int i = fixedCount; i < actualTypes.length; i++) {
            if (!isAssignableFrom(componentType, actualTypes[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean usesExplicitVarArgsArray(Class<?>[] formalTypes, boolean isVarArgs, Class<?>[] actualTypes) {
        if (!isVarArgs || formalTypes.length == 0 || actualTypes.length != formalTypes.length) {
            return false;
        }
        Class<?> varArgArrayType = formalTypes[formalTypes.length - 1];
        Class<?> lastActual = actualTypes[actualTypes.length - 1];
        return isAssignableFrom(varArgArrayType, lastActual);
    }

    private Class<?> getFormalTypeForArgument(Class<?>[] formalTypes,
                                              boolean isVarArgs,
                                              int argIndex,
                                              int actualCount,
                                              boolean explicitVarArgArray) {
        if (formalTypes == null || formalTypes.length == 0) {
            return null;
        }
        if (!isVarArgs) {
            return argIndex < formalTypes.length ? formalTypes[argIndex] : null;
        }

        int fixedCount = formalTypes.length - 1;
        if (argIndex < fixedCount) {
            return formalTypes[argIndex];
        }
        if (argIndex >= actualCount) {
            return null;
        }
        Class<?> varArgArrayType = formalTypes[formalTypes.length - 1];
        if (explicitVarArgArray && argIndex == formalTypes.length - 1) {
            return varArgArrayType;
        }
        Class<?> componentType = varArgArrayType.getComponentType();
        return componentType != null ? componentType : varArgArrayType;
    }

    private boolean isBetterExecutableMatch(Class<?>[] candidateFormalTypes,
                                            boolean candidateVarArgs,
                                            Class<?>[] bestFormalTypes,
                                            boolean bestVarArgs,
                                            Class<?>[] actualTypes) {
        if (candidateVarArgs != bestVarArgs) {
            return !candidateVarArgs;
        }
        int candidateScore = compatibilityScore(candidateFormalTypes, candidateVarArgs, actualTypes);
        int bestScore = compatibilityScore(bestFormalTypes, bestVarArgs, actualTypes);
        if (candidateScore != bestScore) {
            return candidateScore < bestScore;
        }
        if (candidateFormalTypes.length == bestFormalTypes.length
                && isMoreSpecific(candidateFormalTypes, bestFormalTypes)) {
            return true;
        }
        return false;
    }

    private int compatibilityScore(Class<?>[] formalTypes, boolean isVarArgs, Class<?>[] actualTypes) {
        if (!isCompatible(formalTypes, isVarArgs, actualTypes)) {
            return Integer.MAX_VALUE;
        }

        boolean explicitVarArgArray = usesExplicitVarArgsArray(formalTypes, isVarArgs, actualTypes);
        int score = isVarArgs ? 10 : 0;
        for (int i = 0; i < actualTypes.length; i++) {
            Class<?> formal = getFormalTypeForArgument(formalTypes, isVarArgs, i, actualTypes.length, explicitVarArgArray);
            if (formal == null) {
                score += 100;
                continue;
            }
            Class<?> actual = actualTypes[i];
            if (formal.equals(actual)) {
                continue;
            }
            if (actual == Void.class && !formal.isPrimitive()) {
                score += 1;
                continue;
            }
            if (formal.isPrimitive() && box(formal) == actual) {
                score += 1;
                continue;
            }
            if (actual.isPrimitive() && box(actual) == formal) {
                score += 1;
                continue;
            }
            if (formal.isAssignableFrom(actual)) {
                score += 2;
                continue;
            }
            score += 3;
        }
        return score;
    }

    /**
     * Check if a value of actualType can be assigned to a parameter of formalType,
     * considering autoboxing and widening.
     */
    static boolean isAssignableFrom(Class<?> formal, Class<?> actual) {
        if (formal.isAssignableFrom(actual)) {
            return true;
        }

        // Autoboxing: primitive actual → boxed, then check assignability
        if (actual.isPrimitive()) {
            Class<?> boxed = box(actual);
            if (boxed != null && formal.isAssignableFrom(boxed)) {
                return true;
            }
        }
        // Unboxing: boxed actual → primitive, then check
        if (formal.isPrimitive()) {
            Class<?> actualUnboxed = unbox(actual);
            if (actualUnboxed != null && formal == actualUnboxed) {
                return true;
            }
        }

        // Widening between primitives (including through autoboxing)
        Class<?> formalUnboxed = unbox(formal);
        Class<?> actualUnboxed = unbox(actual);
        if (formalUnboxed != null && actualUnboxed != null) {
            if (formalUnboxed == actualUnboxed) {
                return true;
            }
            if (isWidenable(formalUnboxed, actualUnboxed)) {
                return true;
            }
        }

        // null type (Void) is assignable to any reference type
        if (actual == Void.class && !formal.isPrimitive()) {
            return true;
        }

        return false;
    }

    private static Class<?> box(Class<?> clazz) {
        if (clazz == int.class) {
            return Integer.class;
        }
        if (clazz == long.class) {
            return Long.class;
        }
        if (clazz == double.class) {
            return Double.class;
        }
        if (clazz == float.class) {
            return Float.class;
        }
        if (clazz == boolean.class) {
            return Boolean.class;
        }
        if (clazz == char.class) {
            return Character.class;
        }
        if (clazz == byte.class) {
            return Byte.class;
        }
        if (clazz == short.class) {
            return Short.class;
        }
        return null;
    }

    private static Class<?> unbox(Class<?> clazz) {
        if (clazz == Integer.class) {
            return int.class;
        }
        if (clazz == Long.class) {
            return long.class;
        }
        if (clazz == Double.class) {
            return double.class;
        }
        if (clazz == Float.class) {
            return float.class;
        }
        if (clazz == Boolean.class) {
            return boolean.class;
        }
        if (clazz == Character.class) {
            return char.class;
        }
        if (clazz == Byte.class) {
            return byte.class;
        }
        if (clazz == Short.class) {
            return short.class;
        }
        if (clazz.isPrimitive()) {
            return clazz;
        }
        return null;
    }

    private static boolean isWidenable(Class<?> target, Class<?> source) {
        // Numeric widening conversions
        if (target == short.class) {
            return source == byte.class;
        }
        if (target == int.class) {
            return source == byte.class || source == short.class || source == char.class;
        }
        if (target == long.class) {
            return source == byte.class || source == short.class
                    || source == char.class || source == int.class;
        }
        if (target == float.class) {
            return source == byte.class || source == short.class
                    || source == char.class || source == int.class || source == long.class;
        }
        if (target == double.class) {
            return source == byte.class || source == short.class
                    || source == char.class || source == int.class || source == long.class
                    || source == float.class;
        }
        return false;
    }

    // ========================================================================
    // Failure handling — centralized so the five+ resolution sites stay consistent.
    // ========================================================================

    /**
     * Pick the best available type to hang a fallback statement on:
     * the declared (LHS) type if informative, otherwise the known type from
     * resolution (e.g. a raw class, method return, field type), else Object.
     */
    private static Type chooseFallbackType(Type declaredType, Type knownType) {
        if (declaredType != null && declaredType != Object.class) {
            return declaredType;
        }
        if (knownType != null && knownType != Object.class) {
            return knownType;
        }
        if (declaredType != null) {
            return declaredType;
        }
        return knownType != null ? knownType : Object.class;
    }

    /**
     * Unified "resolution failed" path for LLM-best-effort vs strict modes.
     * In LLM mode, preserves the original source as a typed UninterpretedStatement;
     * in strict mode records an ERROR and returns {@code null}.
     */
    private VariableReference failOrFallback(Expression expr,
                                             Type declaredType,
                                             Type knownType,
                                             String errorMsg) {
        if (markParsedFromLlm) {
            return fallbackForUnresolvedExpression(expr, chooseFallbackType(declaredType, knownType), errorMsg);
        }
        addError(expr, errorMsg);
        return null;
    }

    /**
     * Unified "member-inaccessible" path: emits a typed default value in LLM mode
     * (e.g. 0, false, null) so the enclosing expression still has a well-typed
     * argument; in strict mode records an ERROR and returns {@code null}.
     */
    private VariableReference failOrTypedFallback(Expression expr,
                                                  Type declaredType,
                                                  Type knownType,
                                                  String targetVarName,
                                                  String errorMsg) {
        if (markParsedFromLlm) {
            if (!isSyntheticArgumentTarget(targetVarName)
                    && shouldSkipTypedFallbackForDeclaration(errorMsg)) {
                addWarning(expr, errorMsg + " — unresolved declaration value skipped "
                        + "(no synthetic null/0 fallback in LLM mode)");
                return null;
            }
            return fallbackForInaccessibleMember(expr, errorMsg, chooseFallbackType(declaredType, knownType));
        }
        addError(expr, errorMsg);
        return null;
    }

    private boolean isSyntheticArgumentTarget(String targetVarName) {
        return targetVarName != null && targetVarName.startsWith("__arg");
    }

    private boolean shouldSkipTypedFallbackForDeclaration(String errorMsg) {
        if (errorMsg == null) {
            return false;
        }
        return errorMsg.startsWith("No matching method:")
                || errorMsg.startsWith("No method named ")
                || errorMsg.startsWith("No static method named ")
                || errorMsg.startsWith("Method argument mismatch:");
    }

    private VariableReference fallbackForInaccessibleMember(Expression expr,
                                                            String message,
                                                            Type expectedType) {
        addWarning(expr, message + " — using typed fallback value");
        Class<?> raw = getRawClass(expectedType);
        if (raw == void.class || raw == Void.class) {
            return testCase.addStatement(createUninterpretedStatement(expr, ";"));
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return testCase.addStatement(new BooleanPrimitiveStatement(testCase, false));
        }
        if (raw == byte.class || raw == Byte.class) {
            return testCase.addStatement(new BytePrimitiveStatement(testCase, (byte) 0));
        }
        if (raw == short.class || raw == Short.class) {
            return testCase.addStatement(new ShortPrimitiveStatement(testCase, (short) 0));
        }
        if (raw == int.class || raw == Integer.class) {
            return testCase.addStatement(new IntPrimitiveStatement(testCase, 0));
        }
        if (raw == long.class || raw == Long.class) {
            return testCase.addStatement(new LongPrimitiveStatement(testCase, 0L));
        }
        if (raw == float.class || raw == Float.class) {
            return testCase.addStatement(new FloatPrimitiveStatement(testCase, 0.0f));
        }
        if (raw == double.class || raw == Double.class) {
            return testCase.addStatement(new DoublePrimitiveStatement(testCase, 0.0d));
        }
        if (raw == char.class || raw == Character.class) {
            return testCase.addStatement(new CharPrimitiveStatement(testCase, '\0'));
        }
        if (raw == String.class) {
            return testCase.addStatement(new StringPrimitiveStatement(testCase, null));
        }
        return testCase.addStatement(new NullStatement(testCase,
                expectedType == null ? Object.class : expectedType));
    }

    private VariableReference fallbackForUnresolvedExpression(Expression expr,
                                                              Type expectedType,
                                                              String message) {
        Type fallbackType = expectedType == null ? Object.class : expectedType;
        addWarning(expr, message + " — replaced with compilable fallback");
        Class<?> raw = getRawClass(fallbackType);
        if (raw == void.class || raw == Void.TYPE) {
            // Do not preserve unresolved void expressions as raw source (eg. helper calls
            // with invented types), because that can produce uncompilable output.
            return testCase.addStatement(createUninterpretedStatement(expr, ";"));
        }
        String fallbackVarName = "__llm_fallback" + syntheticVarCounter++;
        String fallbackValue = getDefaultFallbackLiteral(fallbackType);
        String fallbackCode = getFallbackTypeName(fallbackType) + " " + fallbackVarName
                + " = " + fallbackValue + ";";
        // Do not attach expression bindings to synthetic fallback declarations.
        // They don't reference source variables, and carrying bindings can corrupt
        // fully-qualified type names during later variable-name substitution.
        return testCase.addStatement(createUninterpretedStatement(
                fallbackType,
                fallbackCode,
                fallbackVarName,
                null));
    }

    private String getFallbackTypeName(Type type) {
        if (type instanceof Class<?>) {
            Class<?> raw = (Class<?>) type;
            if (raw.isPrimitive()) {
                return raw.getSimpleName();
            }
            if (raw.isArray()) {
                Class<?> component = raw;
                int dims = 0;
                while (component.isArray()) {
                    component = component.getComponentType();
                    dims++;
                }
                StringBuilder sb = new StringBuilder();
                String componentName = getFallbackClassName(component);
                sb.append(componentName);
                for (int i = 0; i < dims; i++) {
                    sb.append("[]");
                }
                return sb.toString();
            }
            return getFallbackClassName(raw);
        }
        if (type instanceof java.lang.reflect.ParameterizedType) {
            return getSafeParameterizedFallbackTypeName((java.lang.reflect.ParameterizedType) type);
        }
        return getSimpleTypeName(type);
    }

    private String getSafeParameterizedFallbackTypeName(java.lang.reflect.ParameterizedType type) {
        Type rawType = type.getRawType();
        if (!(rawType instanceof Class<?>)) {
            return type.getTypeName();
        }
        Class<?> rawClass = (Class<?>) rawType;
        String rawName = getFallbackClassName(rawClass);
        Type[] typeParameters = rawClass.getTypeParameters();
        if (typeParameters == null || typeParameters.length == 0) {
            // Raw class is not generic; erase invalid parameterization to keep fallback compilable.
            return rawName;
        }
        Type[] actualArgs = type.getActualTypeArguments();
        if (actualArgs == null || actualArgs.length == 0) {
            return rawName;
        }
        int argCount = Math.min(typeParameters.length, actualArgs.length);
        StringBuilder builder = new StringBuilder(rawName).append("<");
        for (int i = 0; i < argCount; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(getFallbackTypeName(actualArgs[i]));
        }
        builder.append(">");
        return builder.toString();
    }

    private String getFallbackClassName(Class<?> raw) {
        String canonical = raw.getCanonicalName();
        if (canonical == null || canonical.isEmpty()) {
            return raw.getName();
        }
        String simple = raw.getSimpleName();
        if (simple != null && !simple.isEmpty()) {
            try {
                Class<?> resolved = typeResolver.resolveClass(simple);
                if (raw.equals(resolved)) {
                    return simple;
                }
            } catch (ClassNotFoundException ignored) {
                // Fall back to the canonical name below.
            }
        }
        return canonical;
    }

    private String getDefaultFallbackLiteral(Type type) {
        Class<?> raw = getRawClass(type);
        if (raw == boolean.class || raw == Boolean.class) {
            return "false";
        }
        if (raw == byte.class || raw == Byte.class) {
            return "(byte)0";
        }
        if (raw == short.class || raw == Short.class) {
            return "(short)0";
        }
        if (raw == int.class || raw == Integer.class) {
            return "0";
        }
        if (raw == long.class || raw == Long.class) {
            return "0L";
        }
        if (raw == float.class || raw == Float.class) {
            return "0.0f";
        }
        if (raw == double.class || raw == Double.class) {
            return "0.0d";
        }
        if (raw == char.class || raw == Character.class) {
            return "'\\0'";
        }
        return "null";
    }

    private Type inferFallbackMethodReturnType(Class<?> targetClass,
                                               String methodName,
                                               Type declaredType) {
        if (declaredType != null && declaredType != Object.class) {
            return declaredType;
        }
        for (Method method : targetClass.getMethods()) {
            if (method.getName().equals(methodName)) {
                return method.getGenericReturnType();
            }
        }
        for (Method method : targetClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method.getGenericReturnType();
            }
        }
        return Object.class;
    }

    // ========================================================================
    // Callee / class resolution helpers
    // ========================================================================

    /**
     * Try to resolve an expression as a variable reference (for instance method calls).
     * Returns null if it's not a known variable (could be a class name for static access).
     * Handles chained method calls by decomposing into intermediate statements.
     */
    private VariableReference resolveCalleeOrClass(Expression scopeExpr) {
        if (scopeExpr instanceof ClassExpr) {
            return handleClassExpression((ClassExpr) scopeExpr);
        }
        if (scopeExpr instanceof NameExpr) {
            String name = ((NameExpr) scopeExpr).getNameAsString();
            return scope.resolve(name);
        }
        // Chained method call: obj.getX().method() → decompose obj.getX() first
        if (scopeExpr instanceof MethodCallExpr) {
            return handleMethodCall((MethodCallExpr) scopeExpr, Object.class, null);
        }
        // Chained field access used as callee: obj.field.method()
        // But first check if it's a fully-qualified class name (e.g. java.util.Arrays)
        if (scopeExpr instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccess = (FieldAccessExpr) scopeExpr;
            if ("class".equals(fieldAccess.getNameAsString())) {
                Class<?> classLiteral = resolveClassFromExpression(scopeExpr);
                if (classLiteral != null) {
                    ClassPrimitiveStatement stmt = new ClassPrimitiveStatement(testCase, classLiteral);
                    return testCase.addStatement(stmt);
                }
                return null;
            }
            // Try as class name first — if it resolves, return null so caller uses static path
            String fullName = scopeExpr.toString();
            try {
                typeResolver.resolveClass(fullName);
                return null; // Let caller handle as static method
            } catch (ClassNotFoundException ignored) {
                // Not a class name — treat as field access chain
            }
            return handleFieldAccess((FieldAccessExpr) scopeExpr, Object.class, null);
        }
        // Enclosed expression used as callee: (expr).method()
        if (scopeExpr instanceof EnclosedExpr) {
            return handleExpression("__chain" + syntheticVarCounter++,
                    ((EnclosedExpr) scopeExpr).getInner(), Object.class);
        }
        // Value-producing receiver expressions such as string literals and constructor
        // calls are legal Java method scopes (eg, "x".getBytes(...), new Foo().bar()).
        // Materialize them into synthetic variables so normal instance-call parsing applies.
        return handleExpression("__chain" + syntheticVarCounter++, scopeExpr, Object.class);
    }

    /**
     * Try to resolve an expression as a class name (for static method/field access).
     */
    private Class<?> resolveClassFromExpression(Expression scopeExpr) {
        if (scopeExpr instanceof ClassExpr) {
            try {
                return typeResolver.resolveClass(((ClassExpr) scopeExpr).getTypeAsString());
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        if (scopeExpr instanceof NameExpr) {
            String name = ((NameExpr) scopeExpr).getNameAsString();
            // Only resolve as class if it's NOT a known variable
            if (!scope.isDefined(name)) {
                try {
                    return typeResolver.resolveClass(name);
                } catch (ClassNotFoundException e) {
                    return null;
                }
            }
        }
        if (scopeExpr instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccess = (FieldAccessExpr) scopeExpr;
            if ("class".equals(fieldAccess.getNameAsString())) {
                return resolveClassFromExpression(fieldAccess.getScope());
            }
            // Could be a qualified class name like java.util.Collections
            String fullName = scopeExpr.toString();
            try {
                return typeResolver.resolveClass(fullName);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        return null;
    }

    // ========================================================================
    // Utility helpers
    // ========================================================================

    private Class<?>[] getArgTypes(List<VariableReference> argRefs) {
        Class<?>[] types = new Class<?>[argRefs.size()];
        for (int i = 0; i < argRefs.size(); i++) {
            types[i] = argRefs.get(i).getVariableClass();
        }
        return types;
    }

    static Class<?> getRawClass(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof java.lang.reflect.ParameterizedType) {
            return (Class<?>) ((java.lang.reflect.ParameterizedType) type).getRawType();
        }
        if (type instanceof java.lang.reflect.GenericArrayType) {
            Class<?> component = getRawClass(
                    ((java.lang.reflect.GenericArrayType) type).getGenericComponentType());
            return java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        return Object.class;
    }

    private String formatTypes(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    /**
     * Returns true if the member is accessible from a test class (public or package-private).
     * Private and protected members cannot be accessed from compiled test source.
     */
    private boolean isAccessibleMember(java.lang.reflect.Member member) {
        int mod = member.getModifiers();
        if (Modifier.isPublic(mod)) {
            return true;
        }
        if (Modifier.isPrivate(mod) || Modifier.isProtected(mod)) {
            return false;
        }
        // Package-private: allow (test may be in same package)
        return true;
    }

    private void addError(com.github.javaparser.ast.Node node, String message) {
        String enriched = enrichDiagnosticForLlmRepair(message);
        int line = node.getBegin().map(p -> p.line).orElse(0);
        result.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.ERROR,
                enriched,
                line,
                node.toString()));
    }

    private void addWarning(Expression expr, String message) {
        String enriched = enrichDiagnosticForLlmRepair(message);
        int line = expr.getBegin().map(p -> p.line).orElse(0);
        result.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.WARNING, enriched, line, expr.toString()));
    }

    private String enrichDiagnosticForLlmRepair(String message) {
        if (!markParsedFromLlm || message == null || message.isEmpty()
                || message.contains("LLM_REPAIR_ACTION_REQUIRED:")) {
            return message;
        }

        String lower = message.toLowerCase();
        String action = null;
        if (lower.contains("cannot resolve method scope")) {
            action = "declare the receiver variable before this call, or use an existing static call "
                    + "in ClassName.method(...) form.";
        } else if (lower.contains("cannot resolve unscoped method call")) {
            action = "avoid bare helper calls; either inline the helper logic or call through a declared "
                    + "instance/class method that exists in SUT/JDK.";
        } else if (lower.contains("unknown array variable")) {
            action = "declare the array variable before indexing it (including dimensions), e.g. "
                    + "double[][] data = new double[1][1];";
        } else if (lower.contains("variable is not an array")) {
            action = "remove [] indexing for this variable or change its declaration to an array type.";
        } else if (lower.contains("unknown variable for field access")
                || lower.contains("cannot resolve field scope")) {
            action = "declare the instance variable before field access, or use ClassName.FIELD for static fields.";
        } else if (lower.contains("has private access") || lower.contains("has protected access")) {
            action = "do not access private/protected members directly; use public/package-private API "
                    + "(constructors, setters, methods) or assertions on observable behavior.";
        } else if (lower.contains("cannot resolve class")
                || (lower.contains("cannot resolve type") && !lower.contains("class literal"))
                || (lower.contains("failed to parse constructor") && lower.contains("class"))) {
            action = "do not invent local/helper types (e.g., Target, Input, Helper) in test code; "
                    + "instantiate only real SUT/JDK/dependency types from context, or pass null/Object "
                    + "when the API accepts it.";
        } else if (lower.contains("unresolved variable")) {
            action = "declare the variable earlier in the test and ensure the name matches exactly.";
        } else if (lower.contains("cannot resolve class literal")) {
            action = "use ExistingType.class where ExistingType is a real SUT/JDK class and imported.";
        }

        if (action == null) {
            return message;
        }
        String enriched = message + " LLM_REPAIR_ACTION_REQUIRED: " + action;
        if ((lower.contains("cannot resolve class")
                || lower.contains("cannot resolve type")
                || (lower.contains("failed to parse constructor") && lower.contains("class")))
                && !enriched.contains("cannot resolve class")) {
            enriched += " (cannot resolve class)";
        }
        return enriched;
    }

    private void handleTryStatement(TryStmt tryStmt) {
        if (tryStmt == null) {
            return;
        }
        NodeList<com.github.javaparser.ast.stmt.Statement> stmts = tryStmt.getTryBlock().getStatements();
        for (int i = 0; i < stmts.size(); i++) {
            parseStatement(stmts.get(i), stmts, i);
        }
    }

    UninterpretedStatement createUninterpretedStatementFromAst(com.github.javaparser.ast.stmt.Statement astStmt) {
        return createUninterpretedStatement(astStmt, astStmt.toString());
    }

    private UninterpretedStatement createUninterpretedStatement(
            com.github.javaparser.ast.Node bindingNode, String code) {
        return new UninterpretedStatement(testCase, code, collectBindings(bindingNode));
    }

    private UninterpretedStatement createUninterpretedStatement(Type returnType,
                                                                String code,
                                                                String returnExpression,
                                                                com.github.javaparser.ast.Node bindingNode) {
        return new UninterpretedStatement(testCase, returnType, code, collectBindings(bindingNode), returnExpression);
    }

    private Map<String, VariableReference> collectBindings(com.github.javaparser.ast.Node node) {
        Map<String, VariableReference> bindings = new LinkedHashMap<>();
        if (node == null) {
            return bindings;
        }
        for (String token : collectReferencedSimpleNames(node)) {
            VariableReference ref = scope.resolve(token);
            if (ref != null) {
                bindings.put(token, ref);
            }
        }
        return bindings;
    }

    private java.util.LinkedHashSet<String> collectReferencedSimpleNames(com.github.javaparser.ast.Node node) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (node == null) {
            return names;
        }
        for (NameExpr nameExpr : node.findAll(NameExpr.class)) {
            names.add(nameExpr.getNameAsString());
        }
        for (MethodReferenceExpr methodReferenceExpr : node.findAll(MethodReferenceExpr.class)) {
            Expression scopeExpr = methodReferenceExpr.getScope();
            if (scopeExpr == null) {
                continue;
            }
            String scopeText = scopeExpr.toString().trim();
            if (scopeText.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                names.add(scopeText);
            }
            if (scopeExpr instanceof NameExpr) {
                names.add(((NameExpr) scopeExpr).getNameAsString());
                continue;
            }
            for (NameExpr scopeNameExpr : scopeExpr.findAll(NameExpr.class)) {
                names.add(scopeNameExpr.getNameAsString());
            }
        }
        return names;
    }

    /**
     * Seed a parsed literal value into the dynamic constant pool so the search
     * can reuse LLM-chosen values. Does nothing for boolean or null.
     */
    private static void seedConstantPool(Object value) {
        if (value == null) {
            return;
        }
        try {
            ConstantPoolManager.getInstance().addDynamicConstant(value);
        } catch (Exception e) {
            // Constant pool may not be initialized in all contexts (e.g. unit tests)
            logger.debug("Could not seed constant pool: {}", e.getMessage());
        }
    }
}
