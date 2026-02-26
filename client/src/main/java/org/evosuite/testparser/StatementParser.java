/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testparser;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.evosuite.assertion.EqualsAssertion;
import org.evosuite.assertion.NullAssertion;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.assertion.SameAssertion;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.FunctionalMockForAbstractClassStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.numeric.*;
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
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts JavaParser AST statement/expression nodes into EvoSuite Statement objects
 * and adds them to a TestCase.
 *
 * <p>This is the core conversion logic of the test parser. Each handler method maps
 * a specific expression type to the corresponding EvoSuite statement type.
 */
public class StatementParser {

    private static final Logger logger = LoggerFactory.getLogger(StatementParser.class);

    private final DefaultTestCase testCase;
    private final TypeResolver typeResolver;
    private final VariableScope scope;
    private final ParseResult result;

    /** Counter for generating unique names for synthetic variables (inline literals, etc.) */
    private int syntheticVarCounter = 0;
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
        } else {
            // Fallback: preserve as UninterpretedStatement
            int line = astStmt.getBegin().map(p -> p.line).orElse(0);
            result.addDiagnostic(new ParseDiagnostic(
                    ParseDiagnostic.Severity.WARNING,
                    "Unsupported statement type, preserved as UninterpretedStatement: "
                            + astStmt.getClass().getSimpleName(),
                    line,
                    astStmt.toString()));
            testCase.addStatement(createUninterpretedStatement(astStmt, astStmt.toString()));
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
        } else if (expr instanceof AssignExpr) {
            handleAssignment((AssignExpr) expr);
            return 1;
        } else {
            // Fallback: preserve as UninterpretedStatement
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

            if (!declarator.getInitializer().isPresent()) {
                // Declaration without initializer — skip
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
                int line = declarator.getBegin().map(p -> p.line).orElse(0);
                result.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.ERROR,
                        "Cannot resolve type: " + declarator.getType() + " — " + e.getMessage(),
                        line,
                        declarator.toString()));
                continue;
            }

            VariableReference varRef = handleExpression(varName, initializer, declaredType);
            if (varRef != null) {
                GenericClass<?> genericType = null;
                if (declaredType instanceof java.lang.reflect.ParameterizedType) {
                    genericType = GenericClassFactory.get(declaredType);
                }
                scope.register(varName, varRef, genericType);
            }
        }
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
                if (!FunctionalMockStatement.canBeFunctionalMockedIncludingSUT(mockTargetClass)) {
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
        Expression firstArg = mockCall.getArgument(0);
        if (!(firstArg instanceof ClassExpr)) {
            return null;
        }
        try {
            return typeResolver.resolveClass(((ClassExpr) firstArg).getTypeAsString());
        } catch (ClassNotFoundException e) {
            logger.debug("Cannot resolve mock target class: {}", firstArg);
            return null;
        }
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

        // Extract the return values from doReturn() arguments
        List<VariableReference> returnValues = resolveReturnValueArguments(doReturnCall.getArguments());

        // Resolve the method on the target class
        Method method = resolveMethodByNameLoose(targetClass, stubbedMethodName);
        if (method == null) {
            return null;
        }

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

        // Extract return values from thenReturn arguments
        List<VariableReference> returnValues = resolveReturnValueArguments(outerCall.getArguments());

        // Resolve the method on the target class
        Method method = resolveMethodByNameLoose(targetClass, stubbedMethodName);
        if (method == null) {
            return null;
        }

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
    private List<VariableReference> resolveReturnValueArguments(List<Expression> args) {
        List<VariableReference> refs = new ArrayList<>();
        for (Expression arg : args) {
            VariableReference ref = resolveArgument(arg, Object.class);
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
            try {
                Type castType = typeResolver.resolveType(castExpr.getType());
                return handleExpression(varName, castExpr.getExpression(), castType);
            } catch (ClassNotFoundException e) {
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
            return handleObjectCreation((ObjectCreationExpr) expr, declaredType);
        }

        // Method call: obj.method(args) or Class.staticMethod(args)
        if (expr instanceof MethodCallExpr) {
            return handleMethodCall((MethodCallExpr) expr, declaredType);
        }

        // Field access: obj.field or Class.FIELD
        if (expr instanceof FieldAccessExpr) {
            return handleFieldAccess((FieldAccessExpr) expr, declaredType);
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
            VariableReference ref = scope.resolve(((NameExpr) expr).getNameAsString());
            if (ref != null) {
                return ref;
            }
            // Could be a class name — fall through to unsupported
        }

        // Unary expression: -5, +3, !flag
        if (expr instanceof UnaryExpr) {
            return handleUnaryExpression(varName, (UnaryExpr) expr, declaredType);
        }

        // Enclosed expression: (expr)
        if (expr instanceof EnclosedExpr) {
            return handleExpression(varName, ((EnclosedExpr) expr).getInner(), declaredType);
        }

        // Lambda expression: preserve as UninterpretedStatement
        if (expr instanceof LambdaExpr) {
            addWarning(expr, "Lambda expression preserved as UninterpretedStatement");
            UninterpretedStatement stmt = createUninterpretedStatement(expr, expr.toString());
            return testCase.addStatement(stmt);
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
        if (declaredType == float.class || declaredType == Float.class) {
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
        // Other unary operators (!, ~, ++, --): preserve as UninterpretedStatement
        addWarning(expr, "Unsupported unary operator preserved as UninterpretedStatement: " + expr.getOperator());
        UninterpretedStatement stmt = createUninterpretedStatement(expr, expr.toString());
        return testCase.addStatement(stmt);
    }

    private VariableReference handleNullLiteral(Type declaredType) {
        Class<?> rawClass = getRawClass(declaredType);

        // String null → StringPrimitiveStatement(null), not NullStatement
        if (rawClass == String.class) {
            StringPrimitiveStatement stmt = new StringPrimitiveStatement(testCase, null);
            return testCase.addStatement(stmt);
        }

        NullStatement stmt = new NullStatement(testCase, declaredType);
        return testCase.addStatement(stmt);
    }

    // ========================================================================
    // Constructor: new Type(args)
    // ========================================================================

    private VariableReference handleObjectCreation(ObjectCreationExpr expr, Type declaredType) {
        try {
            // Resolve the class being constructed
            String typeName = expr.getType().getNameAsString();
            Class<?> rawClass = typeResolver.resolveClass(typeName);

            // Resolve constructor arguments
            List<VariableReference> argRefs = resolveArguments(expr.getArguments(), null, null);

            // Find matching constructor
            Class<?>[] argTypes = getArgTypes(argRefs);
            Constructor<?> constructor = resolveConstructor(rawClass, argTypes);

            // Handle diamond type inference
            Type constructedType;
            if (expr.getType().getTypeArguments().isPresent()
                    && expr.getType().getTypeArguments().get().isEmpty()) {
                // Diamond: new HashMap<>() — infer from LHS
                constructedType = typeResolver.inferDiamondType(rawClass, declaredType);
            } else {
                constructedType = typeResolver.resolveType(expr.getType());
            }

            GenericClass<?> ownerClass = GenericClassFactory.get(constructedType);
            GenericConstructor genericConstructor = new GenericConstructor(constructor, ownerClass);

            ConstructorStatement stmt = new ConstructorStatement(testCase, genericConstructor, argRefs);
            return testCase.addStatement(stmt);

        } catch (Exception e) {
            addError(expr, "Failed to parse constructor: " + e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // Method call: obj.method(args) or Class.staticMethod(args)
    // ========================================================================

    private VariableReference handleMethodCall(MethodCallExpr expr, Type declaredType) {
        try {
            String methodName = expr.getNameAsString();
            VariableReference callee = null;
            Class<?> targetClass;

            if (expr.getScope().isPresent()) {
                Expression scopeExpr = expr.getScope().get();
                callee = resolveCalleeOrClass(scopeExpr);

                if (callee != null) {
                    // Instance method: callee.method(args)
                    targetClass = getRawClass(callee.getType());
                } else {
                    // Static method: Class.method(args) — scopeExpr is a class name
                    targetClass = resolveClassFromExpression(scopeExpr);
                    if (targetClass == null) {
                        addError(expr, "Cannot resolve method scope: " + scopeExpr);
                        return null;
                    }
                }
            } else {
                // Unscoped method call — could be static import
                String staticClass = typeResolver.resolveStaticImportClass(methodName);
                if (staticClass != null) {
                    targetClass = typeResolver.resolveClass(staticClass);
                } else {
                    addError(expr, "Cannot resolve unscoped method call: " + methodName);
                    return null;
                }
            }

            // Resolve arguments
            List<VariableReference> argRefs = resolveArguments(expr.getArguments(), null, null);

            // Find matching method
            Class<?>[] argTypes = getArgTypes(argRefs);
            Method method = resolveMethod(targetClass, methodName, argTypes);

            GenericClass<?> ownerClass = GenericClassFactory.get(targetClass);
            GenericMethod genericMethod = new GenericMethod(method, ownerClass);

            MethodStatement stmt = new MethodStatement(testCase, genericMethod, callee, argRefs);
            return testCase.addStatement(stmt);

        } catch (Exception e) {
            addError(expr, "Failed to parse method call: " + e.getMessage());
            return null;
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
        } else {
            handleMethodCall(methodCall, void.class);
        }
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
                    handleAssertThrows(args);
                    return;
                default:
                    break;
            }
        } catch (Exception e) {
            logger.debug("Could not parse assertion {}: {}", assertCall, e.getMessage());
        }

        // Fallback: preserve as UninterpretedStatement
        testCase.addStatement(createUninterpretedStatement(assertCall, assertCall.toString() + ";"));
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
                handleMethodCall((MethodCallExpr) arg, null);
            }
        }
        testCase.addStatement(createUninterpretedStatement(assertCall, assertCall.toString() + ";"));
    }

    /**
     * assertThrows(ExceptionClass.class, () -> { ... }) — extract the lambda body
     * as regular statements. The exception class is noted but not modeled as an
     * assertion since EvoSuite doesn't have a direct exception-assertion type.
     * Handles both block lambdas and expression lambdas.
     */
    private void handleAssertThrows(List<Expression> args) {
        if (args.size() < 2) {
            return;
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
            // No lambda found — maybe it's a method reference or variable; preserve as interpreted
            return;
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
            return handleMethodCall((MethodCallExpr) expr, null);
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

    private VariableReference handleFieldAccess(FieldAccessExpr expr, Type declaredType) {
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
                    addError(expr, "Cannot resolve field scope: " + scopeExpr);
                    return null;
                }
            }

            // Check for enum constant
            if (targetClass.isEnum()) {
                return handleEnumConstant(targetClass, fieldName, expr);
            }

            Field field = targetClass.getField(fieldName);
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
     * Resolve a list of argument expressions into VariableReferences.
     * Inline literals and null are materialized as auto-created statements.
     *
     * @param args         the argument expression list
     * @param paramTypes   parameter types from the resolved method/constructor (for null typing), or null
     * @param resolvedExec the resolved method/constructor (for parameter types), or null
     * @return list of VariableReferences
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
            if (ref != null) {
                refs.add(ref);
            } else {
                // Create a dummy NullStatement to keep argument count consistent
                NullStatement nullStmt = new NullStatement(testCase, paramType);
                refs.add(testCase.addStatement(nullStmt));
            }
        }
        return refs;
    }

    /**
     * Resolve a single argument expression to a VariableReference.
     */
    private VariableReference resolveArgument(Expression arg, Type paramType) {
        // Direct variable reference
        if (arg instanceof NameExpr) {
            VariableReference ref = scope.resolve(((NameExpr) arg).getNameAsString());
            if (ref != null) {
                return ref;
            }
        }

        // Inline literal or complex expression — create a synthetic statement
        String syntheticName = "__arg" + syntheticVarCounter++;
        return handleExpression(syntheticName, arg, paramType);
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

            // Check for initializer: new int[]{1, 2, 3}
            if (expr.getInitializer().isPresent()) {
                ArrayInitializerExpr init = expr.getInitializer().get();
                Type arrayType = java.lang.reflect.Array.newInstance(
                        getRawClass(componentType), 0).getClass();
                return createArrayWithInitializer(init, arrayType, componentType);
            }

            // Get dimensions
            List<ArrayCreationLevel> levels = expr.getLevels();
            int[] lengths = new int[levels.size()];
            for (int i = 0; i < levels.size(); i++) {
                if (levels.get(i).getDimension().isPresent()) {
                    Expression dimExpr = levels.get(i).getDimension().get();
                    if (dimExpr instanceof IntegerLiteralExpr) {
                        lengths[i] = ((IntegerLiteralExpr) dimExpr).asNumber().intValue();
                    } else if (dimExpr instanceof NameExpr) {
                        // Try to resolve variable dimension from scope
                        VariableReference dimRef = scope.resolve(((NameExpr) dimExpr).getNameAsString());
                        if (dimRef != null) {
                            Statement dimStmt = testCase.getStatement(dimRef.getStPosition());
                            if (dimStmt instanceof PrimitiveStatement) {
                                Object val = ((PrimitiveStatement<?>) dimStmt).getValue();
                                if (val instanceof Number) {
                                    lengths[i] = ((Number) val).intValue();
                                } else {
                                    addWarning(dimExpr, "Non-numeric array dimension variable, defaulting to 0");
                                    lengths[i] = 0;
                                }
                            } else {
                                addWarning(dimExpr, "Non-literal array dimension, defaulting to 0: " + dimExpr);
                                lengths[i] = 0;
                            }
                        } else {
                            addWarning(dimExpr, "Unresolved array dimension variable, defaulting to 0: " + dimExpr);
                            lengths[i] = 0;
                        }
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
            String arrayName = expr.getName().toString();
            VariableReference arrayRef = scope.resolve(arrayName);
            if (arrayRef == null) {
                addError(expr, "Unknown array variable: " + arrayName);
                return null;
            }

            int index = 0;
            Expression indexExpr = expr.getIndex();
            if (indexExpr instanceof IntegerLiteralExpr) {
                index = ((IntegerLiteralExpr) indexExpr).asNumber().intValue();
            } else if (indexExpr instanceof NameExpr) {
                // Variable index — resolve its value if it's a known constant
                VariableReference indexRef = scope.resolve(((NameExpr) indexExpr).getNameAsString());
                if (indexRef != null) {
                    int pos = indexRef.getStPosition();
                    if (pos >= 0 && pos < testCase.size()) {
                        Statement s = testCase.getStatement(pos);
                        if (s instanceof PrimitiveStatement) {
                            Object val = ((PrimitiveStatement<?>) s).getValue();
                            if (val instanceof Number) {
                                index = ((Number) val).intValue();
                            }
                        }
                    }
                }
            }

            return new ArrayIndex(testCase, (ArrayReference) arrayRef, index);
        } catch (Exception e) {
            addError(expr, "Failed to parse array access: " + e.getMessage());
            return null;
        }
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
        int[] lengths = new int[]{values.size()};
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

    // ========================================================================
    // Binary expression: a + b, x == y → UninterpretedStatement
    // ========================================================================

    /**
     * Binary expressions are preserved as UninterpretedStatements.
     */
    private VariableReference handleBinaryExpression(String varName, BinaryExpr expr, Type declaredType) {
        // String concatenation: try to evaluate "a" + b + c into a single String literal
        if (expr.getOperator() == BinaryExpr.Operator.PLUS && isStringConcat(expr, declaredType)) {
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
        return containsStringLiteral(expr);
    }

    private boolean containsStringLiteral(Expression expr) {
        if (expr instanceof StringLiteralExpr) {
            return true;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr bin = (BinaryExpr) expr;
            return containsStringLiteral(bin.getLeft()) || containsStringLiteral(bin.getRight());
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

            if (target instanceof ArrayAccessExpr) {
                // array[i] = value
                ArrayAccessExpr arrayAccess = (ArrayAccessExpr) target;
                String arrayName = arrayAccess.getName().toString();
                VariableReference arrayRef = scope.resolve(arrayName);
                if (arrayRef == null) {
                    addError(assignExpr, "Unknown array variable: " + arrayName);
                    return;
                }

                // Resolve the index
                Expression indexExpr = arrayAccess.getIndex();
                int index = 0;
                if (indexExpr instanceof IntegerLiteralExpr) {
                    index = ((IntegerLiteralExpr) indexExpr).asNumber().intValue();
                }

                // Resolve the value
                Type componentType = arrayRef.getComponentType();
                VariableReference valueRef = handleExpression(
                        "__val" + syntheticVarCounter++, value,
                        componentType != null ? componentType : Object.class);
                if (valueRef == null) {
                    return;
                }

                // Create ArrayIndex and AssignmentStatement
                ArrayIndex arrayIndex = new ArrayIndex(testCase,
                        (ArrayReference) arrayRef, index);
                AssignmentStatement stmt = new AssignmentStatement(testCase, arrayIndex, valueRef);
                testCase.addStatement(stmt);
            } else if (target instanceof FieldAccessExpr) {
                // obj.field = value
                FieldAccessExpr fieldAccess = (FieldAccessExpr) target;
                String fieldName = fieldAccess.getNameAsString();
                Expression scopeExpr = fieldAccess.getScope();
                String scopeName = scopeExpr.toString();

                // Resolve the object that owns the field
                VariableReference sourceRef = scope.resolve(scopeName);
                if (sourceRef == null) {
                    addError(assignExpr, "Unknown variable for field access: " + scopeName);
                    return;
                }

                // Look up the field via reflection
                Class<?> ownerClass = sourceRef.getVariableClass();
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

                // Resolve the value being assigned
                VariableReference valueRef = handleExpression(
                        "__val" + syntheticVarCounter++, value, field.getType());
                if (valueRef == null) {
                    return;
                }

                // Create FieldReference + AssignmentStatement
                GenericField genericField = new GenericField(field, ownerClass);
                FieldReference fieldRef = new FieldReference(testCase, genericField, sourceRef);
                AssignmentStatement stmt = new AssignmentStatement(testCase, fieldRef, valueRef);
                testCase.addStatement(stmt);
            } else {
                addWarning(assignExpr, "Unsupported assignment target: " + target.getClass().getSimpleName());
            }
        } catch (Exception e) {
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
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (isCompatible(c.getParameterTypes(), argTypes)) {
                return c;
            }
        }

        throw new NoSuchMethodException("No matching constructor found for " + clazz.getName()
                + " with args " + formatTypes(argTypes));
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

        // Try compatibility match with autoboxing/widening on public methods
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && isCompatible(m.getParameterTypes(), argTypes)) {
                return m;
            }
        }

        // Also try declared methods (including private/protected) for the class itself
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && isCompatible(m.getParameterTypes(), argTypes)) {
                return m;
            }
        }

        throw new NoSuchMethodException("No matching method found: " + clazz.getName()
                + "." + name + " with args " + formatTypes(argTypes));
    }

    /**
     * Check if actual argument types are compatible with formal parameter types,
     * considering autoboxing and widening.
     */
    private boolean isCompatible(Class<?>[] formalTypes, Class<?>[] actualTypes) {
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
    // Callee / class resolution helpers
    // ========================================================================

    /**
     * Try to resolve an expression as a variable reference (for instance method calls).
     * Returns null if it's not a known variable (could be a class name for static access).
     * Handles chained method calls by decomposing into intermediate statements.
     */
    private VariableReference resolveCalleeOrClass(Expression scopeExpr) {
        if (scopeExpr instanceof NameExpr) {
            String name = ((NameExpr) scopeExpr).getNameAsString();
            return scope.resolve(name);
        }
        // Chained method call: obj.getX().method() → decompose obj.getX() first
        if (scopeExpr instanceof MethodCallExpr) {
            return handleMethodCall((MethodCallExpr) scopeExpr, Object.class);
        }
        // Chained field access used as callee: obj.field.method()
        // But first check if it's a fully-qualified class name (e.g. java.util.Arrays)
        if (scopeExpr instanceof FieldAccessExpr) {
            // Try as class name first — if it resolves, return null so caller uses static path
            String fullName = scopeExpr.toString();
            try {
                typeResolver.resolveClass(fullName);
                return null; // Let caller handle as static method
            } catch (ClassNotFoundException ignored) {
                // Not a class name — treat as field access chain
            }
            return handleFieldAccess((FieldAccessExpr) scopeExpr, Object.class);
        }
        // Enclosed expression used as callee: (expr).method()
        if (scopeExpr instanceof EnclosedExpr) {
            return handleExpression("__chain" + syntheticVarCounter++,
                    ((EnclosedExpr) scopeExpr).getInner(), Object.class);
        }
        return null;
    }

    /**
     * Try to resolve an expression as a class name (for static method/field access).
     */
    private Class<?> resolveClassFromExpression(Expression scopeExpr) {
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

    private void addError(Expression expr, String message) {
        int line = expr.getBegin().map(p -> p.line).orElse(0);
        result.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.ERROR, message, line, expr.toString()));
    }

    private void addWarning(Expression expr, String message) {
        int line = expr.getBegin().map(p -> p.line).orElse(0);
        result.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.WARNING, message, line, expr.toString()));
    }

    UninterpretedStatement createUninterpretedStatementFromAst(com.github.javaparser.ast.stmt.Statement astStmt) {
        return createUninterpretedStatement(astStmt, astStmt.toString());
    }

    private UninterpretedStatement createUninterpretedStatement(com.github.javaparser.ast.Node bindingNode, String code) {
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
        for (NameExpr nameExpr : node.findAll(NameExpr.class)) {
            String token = nameExpr.getNameAsString();
            VariableReference ref = scope.resolve(token);
            if (ref != null) {
                bindings.put(token, ref);
            }
        }
        return bindings;
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
