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

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
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
import org.evosuite.Properties;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.*;
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
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts JavaParser AST statement/expression nodes into EvoSuite Statement objects
 * and adds them to a TestCase.
 *
 * <p>This is the core conversion logic of the test parser. Each handler method maps
 * a specific expression type to the corresponding EvoSuite statement type.
 */
public class StatementParser {

    private static final Logger logger = LoggerFactory.getLogger(StatementParser.class);
    private static final int MAX_RECURSIVE_EXPRESSION_DEPTH = 64;

    private final DefaultTestCase testCase;
    private final JavaParser javaParser;
    private final TypeResolver typeResolver;
    private final VariableScope scope;
    private final ParseResult result;
    private final AssertionParser assertionParser;
    private final OverloadResolver overloadResolver;
    private final Map<String, MethodDeclaration> inlineHelperMethods = new LinkedHashMap<>();
    /** LLM-mode declarations without initializer; consumed by subsequent NameExpr assignments. */
    private final Map<String, PendingLlmDeclaration> pendingLlmDeclarations = new LinkedHashMap<>();
    private final MockitoPatternParser mockitoPatternParser;

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
        this.javaParser = new JavaParser(new ParserConfiguration().setAttributeComments(false));
        this.typeResolver = typeResolver;
        this.scope = scope;
        this.result = result;
        this.overloadResolver = new OverloadResolver();
        this.assertionParser = new AssertionParser(
                testCase, typeResolver, scope, result, this);
        this.mockitoPatternParser = new MockitoPatternParser(
                testCase, typeResolver, scope, result, this);
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

    // ========================================================================
    // Internal API for AssertionParser / MockitoPatternParser
    // These package-private callbacks are intentionally exposed to the helper
    // parsers. Larger helper entry points stay near their main logic and are
    // tagged in place with the same "Internal API" wording.
    // ========================================================================

    // Internal API for helpers: discard temporary statements emitted during a failed probe.
    void rollbackTo(int checkpointSize) {
        rollbackTemporaryStatements(checkpointSize);
    }

    // Internal API for helpers: allocate a unique synthetic local name.
    String nextSyntheticName(String prefix) {
        return prefix + syntheticVarCounter++;
    }

    MockitoPatternParser getMockitoPatternParser() {
        return mockitoPatternParser;
    }

    // Internal API for helpers: share the method-resolution strategy used by the parser.
    OverloadResolver getOverloadResolver() {
        return overloadResolver;
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
            String exprText = exprStmt.toString().trim();
            if (markParsedFromLlm && exprText.contains("thenThrow(")) {
                String rewrittenThrow = mockitoPatternParser.rewriteThenThrowSource(exprText);
                if (rewrittenThrow != null) {
                    testCase.addStatement(createUninterpretedStatement(exprStmt, rewrittenThrow));
                    return 1;
                }
            }
            return handleExpressionStatement(exprStmt.getExpression(), allStatements, currentIndex);
        } else if (astStmt instanceof AssertStmt) {
            assertionParser.handleAssertStatement((AssertStmt) astStmt);
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
                if (shouldDropUnsupportedStatement(preserved)) {
                    return 1;
                }
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
            int consumed = mockitoPatternParser.handleVariableDeclarationWithLookahead(
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
                    declaredType = mockitoPatternParser.tryResolveDemockedDeclaredType(declarator.getType());
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
                    pendingLlmDeclarations.put(varName,
                            new PendingLlmDeclaration(varName, declaredType,
                                    copySyntheticRange(
                                            declarator.getParentNode().orElse(declarator).clone(),
                                            declarator.getParentNode().orElse(declarator)),
                                    declarator.getType().toString()));
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
                    declaredType = mockitoPatternParser.tryResolveDemockedDeclaredType(declarator.getType());
                    if (declaredType != null) {
                        addWarning(initializer, DiagnosticKind.MOCK_PREFIX_TYPE_INFERRED,
                                "'" + declarator.getType() + "' as '" + declaredType.getTypeName() + "'");
                    } else {
                        // LLM best-effort mode: keep parsing by downgrading unknown declared
                        // types to Object so later statements can still be repaired/mutated.
                        declaredType = Object.class;
                        addWarning(initializer, DiagnosticKind.UNRESOLVED_TYPE,
                                declarator.getType() + " — " + e.getMessage()
                                        + " — downgraded to Object for LLM best-effort parsing.");
                    }
                } else {
                    addError(declarator, DiagnosticKind.UNRESOLVED_TYPE,
                            declarator.getType() + " — " + e.getMessage());
                    continue;
                }
            }

            if (markParsedFromLlm && assertionParser.shouldPreserveAssertionReturningDeclaration(initializer)) {
                Expression preservedInitializer = assertionParser.normalizeAssertionExpressionForPreservation(initializer);
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
                        addWarning(initializer, DiagnosticKind.PRIMITIVE_INIT_WITH_NULL,
                                declarator.getType() + " " + varName + " — using typed default value");
                        VariableReference primitiveFallback = fallbackForInaccessibleMember(
                                initializer,
                                DiagnosticMessage.categorized(DiagnosticKind.PRIMITIVE_INIT_WITH_NULL,
                                        declarator.getType() + " " + varName),
                                declaredType);
                        if (primitiveFallback != null) {
                            scope.register(varName, primitiveFallback, null);
                        }
                    } else {
                        addError(initializer, DiagnosticKind.PRIMITIVE_INIT_WITH_NULL,
                                declarator.getType() + " " + varName);
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
        if (formal == null || actual == null || OverloadResolver.isAssignableFrom(formal, actual)) {
            return resolvedRef;
        }

        String rhsName = ((NameExpr) initializer).getNameAsString();
        String lhsType = getFallbackTypeName(declaredType);
        Class<?> boxedFormal = OverloadResolver.box(formal);
        String castType = formal.isPrimitive()
                ? getFallbackTypeName(boxedFormal != null ? boxedFormal : formal)
                : lhsType;
        String castExpr = "(" + castType + ") " + rhsName;
        String code = lhsType + " " + varName + " = " + castExpr + ";";

        if (!markParsedFromLlm) {
            addError(initializer, DiagnosticKind.INCOMPATIBLE_ALIAS_DECLARATION,
                    "cannot assign " + actual.getTypeName() + " to " + formal.getTypeName());
            return null;
        }

        addWarning(initializer, DiagnosticKind.INCOMPATIBLE_ALIAS_DECLARATION,
                "Inserted typed cast to preserve compilability for incompatible alias declaration: " + code);
        return testCase.addStatement(createUninterpretedStatement(
                declaredType,
                code,
                varName,
                initializer));
    }

    private static final class PendingLlmDeclaration {
        private final String variableName;
        private final Type declaredType;
        private final com.github.javaparser.ast.Node declarationNode;
        private final String declaredTypeText;

        private PendingLlmDeclaration(String variableName,
                                      Type declaredType,
                                      com.github.javaparser.ast.Node declarationNode,
                                      String declaredTypeText) {
            this.variableName = variableName;
            this.declaredType = declaredType;
            this.declarationNode = declarationNode;
            this.declaredTypeText = declaredTypeText;
        }

        private String getVariableName() {
            return variableName;
        }

        private Type getDeclaredType() {
            return declaredType;
        }

        private com.github.javaparser.ast.Node getDeclarationNode() {
            return declarationNode;
        }

        private String getDeclaredTypeText() {
            return declaredTypeText;
        }
    }

    // ========================================================================
    // Expression dispatch (returns VariableReference for the result)
    // ========================================================================

    /**
     * Internal API for helpers: materialize an expression into an EvoSuite value.
     */
    VariableReference handleExpression(String varName, Expression expr, Type declaredType) {
        return handleExpression(varName, expr, declaredType, 0, expr);
    }

    private VariableReference handleExpression(String varName,
                                               Expression expr,
                                               Type declaredType,
                                               int depth,
                                               Expression originalExpr) {
        if (depth >= MAX_RECURSIVE_EXPRESSION_DEPTH) {
            return preserveExpressionAtDepthLimit(varName, originalExpr, declaredType);
        }
        // Unwrap cast: (Type) expr → handle inner expression with cast type
        if (expr instanceof CastExpr) {
            CastExpr castExpr = (CastExpr) expr;
            if (markParsedFromLlm && castExpr.getExpression() instanceof NullLiteralExpr) {
                // LLM output frequently over-casts null literals (e.g. (Object) null),
                // which can force false argument mismatches for generic APIs and leak
                // uncompilable casts into emitted tests. Keep only the null literal and
                // let method/constructor resolution retype it to the formal parameter.
                addWarning(expr, "Ignoring cast on null literal in LLM mode: " + castExpr.getType());
                return handleExpression(varName, castExpr.getExpression(), declaredType, depth + 1, originalExpr);
            }
            try {
                Type castType = typeResolver.resolveType(castExpr.getType());
                return handleExpression(varName, castExpr.getExpression(), castType, depth + 1, originalExpr);
            } catch (ClassNotFoundException e) {
                if (markParsedFromLlm && castExpr.getExpression() instanceof NullLiteralExpr) {
                    // LLMs sometimes cast null to invented/unresolvable helper types
                    // (eg "(OutputDestination[]) null"). Keep the null literal and let
                    // method/constructor resolution retype it to the actual formal type.
                    addWarning(expr, DiagnosticKind.UNRESOLVED_CAST_TYPE,
                            "for null literal; ignoring cast in LLM mode: " + e.getMessage());
                    return handleExpression(varName, castExpr.getExpression(), declaredType, depth + 1, originalExpr);
                }
                addError(expr, DiagnosticKind.UNRESOLVED_CAST_TYPE, e.getMessage());
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
            if (isClassName) {
                addError(expr, DiagnosticKind.BARE_CLASS_NAME_AS_VALUE,
                        name + " (did you mean " + name + ".class?)");
            } else {
                addError(expr, DiagnosticKind.UNRESOLVED_VARIABLE, name);
            }
            return null;
        }

        // Unary expression: -5, +3, !flag
        if (expr instanceof UnaryExpr) {
            return handleUnaryExpression(varName, (UnaryExpr) expr, declaredType);
        }

        // Enclosed expression: (expr)
        if (expr instanceof EnclosedExpr) {
            return handleExpression(varName, ((EnclosedExpr) expr).getInner(), declaredType, depth + 1, originalExpr);
        }

        // Lambda expression: preserve as UninterpretedStatement.
        // If it appears in a declaration/assignment value position, keep the typed
        // declaration shape so later emitted JUnit remains compilable.
        if (expr instanceof LambdaExpr) {
            addWarning(expr, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                    "Lambda expression preserved as UninterpretedStatement");
            Type effectiveType = declaredType == null ? Object.class : declaredType;
            if (!isFunctionalInterfaceType(effectiveType)) {
                String message = "Lambda expression requires a functional interface target type";
                if (markParsedFromLlm) {
                    return fallbackForUnresolvedExpression(expr, effectiveType, message);
                }
                addError(expr, DiagnosticKind.LAMBDA_TARGET_TYPE_REQUIRED, effectiveType.getTypeName());
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
            addError(expr, DiagnosticKind.LAMBDA_TARGET_TYPE_REQUIRED, message);
            return null;
        }

        // Unsupported
        addWarning(expr, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                "Unsupported expression type: " + expr.getClass().getSimpleName());
        return null;
    }

    private VariableReference preserveExpressionAtDepthLimit(String varName,
                                                             Expression expr,
                                                             Type declaredType) {
        addWarning(expr, DiagnosticKind.EXPRESSION_DEPTH_EXCEEDED,
                MAX_RECURSIVE_EXPRESSION_DEPTH + "; preserved as UninterpretedStatement");
        return preserveExpressionAsUninterpreted(varName, expr, declaredType);
    }

    private VariableReference preserveExpressionAsUninterpreted(String varName,
                                                                Expression expr,
                                                                Type declaredType) {
        if (varName != null && !varName.trim().isEmpty()) {
            Type effectiveType = declaredType == null ? Object.class : declaredType;
            String code = getFallbackTypeName(effectiveType) + " " + varName + " = " + expr + ";";
            return testCase.addStatement(createUninterpretedStatement(effectiveType, code, varName, expr));
        }
        return testCase.addStatement(createUninterpretedStatement(expr, expr + ";"));
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
            return handleExpression(varName, expr.getExpression(), declaredType, 1, expr);
        }
        // Other unary operators (!, ~, ++, --): preserve as uninterpreted, but
        // keep value-producing context compilable by materializing a typed assignment.
        addWarning(expr, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                "Unsupported unary operator preserved as UninterpretedStatement: " + expr.getOperator());
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
        Type nullType = (rawClass == Object.class) ? Void.class : accessibleNullType(declaredType);
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
                        mockitoPatternParser.tryNormalizeAnonymousInterfaceCreationToMock(expr, declaredType);
                if (normalizedAnonymousMock != null) {
                    return normalizedAnonymousMock;
                }
                return preserveAnonymousObjectCreation(expr, declaredType, targetVarName);
            }
            // Resolve the class being constructed
            // Keep package qualifiers but strip generic arguments (e.g., new java.io.File(...),
            // new ArrayList<>() -> "ArrayList").
            String typeName = expr.getType().getNameWithScope();
            rawClass = mockitoPatternParser.resolveClassWithLlmMockFallback(typeName);

            // Pre-resolve arguments without type hints to find the constructor
            int argumentCheckpoint = testCase.size();
            List<VariableReference> argRefs = resolveArguments(expr.getArguments(), null, null);

            // Find matching constructor
            Class<?>[] argTypes = getArgTypes(argRefs);
            Constructor<?> constructor;
            Class<?> constructorTargetClass = mockitoPatternParser.chooseOverrideMockConstructorTarget(rawClass);
            try {
                constructor = overloadResolver.resolveConstructor(constructorTargetClass, argTypes);
            } catch (NoSuchMethodException e) {
                if (constructorTargetClass != rawClass) {
                    try {
                        constructor = overloadResolver.resolveConstructor(rawClass, argTypes);
                    } catch (NoSuchMethodException originalCtorError) {
                        return failOrFallbackWithRollback(expr, declaredType, rawClass,
                                argumentCheckpoint,
                                DiagnosticMessage.categorized(DiagnosticKind.NO_MATCHING_CONSTRUCTOR,
                                        originalCtorError.getMessage()));
                    }
                } else {
                    return failOrFallbackWithRollback(expr, declaredType, rawClass,
                            argumentCheckpoint,
                            DiagnosticMessage.categorized(DiagnosticKind.NO_MATCHING_CONSTRUCTOR,
                                    e.getMessage()));
                }
            }

            if (!isAccessibleMember(constructor)) {
                return failOrTypedFallbackWithRollback(expr, declaredType, rawClass, targetVarName,
                        argumentCheckpoint,
                        DiagnosticMessage.categorized(DiagnosticKind.INACCESSIBLE_MEMBER,
                                rawClass.getSimpleName() + " constructor in "
                                        + constructor.getDeclaringClass().getSimpleName()));
            }

            // Re-type Void-typed null arguments now that we know the parameter types
            retypeNullArguments(argRefs, constructor.getParameterTypes(), constructor.isVarArgs());

            // Validate argument types against constructor parameter types
            DiagnosticMessage mismatch = validateArgumentTypes(argRefs, constructor.getParameterTypes(),
                    constructor.getGenericParameterTypes(), expr, constructor.isVarArgs());
            if (mismatch != null) {
                return failOrFallbackWithRollback(expr, declaredType, rawClass,
                        argumentCheckpoint,
                        mismatch.withContextPrefix("Constructor argument mismatch: "));
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

    private VariableReference preserveAnonymousObjectCreation(ObjectCreationExpr expr,
                                                              Type declaredType,
                                                              String targetVarName) {
        Type preservedType = declaredType;
        if (preservedType == null || preservedType == Object.class) {
            try {
                preservedType = typeResolver.resolveType(expr.getType());
            } catch (ClassNotFoundException e) {
                Type democked = mockitoPatternParser.tryResolveDemockedDeclaredType(expr.getType());
                preservedType = democked != null ? democked : Object.class;
            }
        }

        Class<?> preservedRawClass = getRawClass(preservedType);
        if (shouldFallbackAnonymousObjectCreation(preservedRawClass)) {
            return fallbackAnonymousObjectCreationToTypedNull(expr, preservedType, targetVarName);
        }

        String preservedTypeName = getFallbackTypeName(preservedType);
        ObjectCreationExpr preservedExpr = copySyntheticRange(expr.clone(), expr);
        preservedExpr.setType(parseClassOrInterfaceType(preservedTypeName, expr.getType()));
        copySyntheticRange(preservedExpr, expr);

        addWarning(expr, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                "Preserved anonymous class implementation as raw Java source");
        if (targetVarName != null && !targetVarName.trim().isEmpty()) {
            String code = preservedTypeName + " " + targetVarName + " = " + preservedExpr + ";";
            return testCase.addStatement(
                    createUninterpretedStatement(preservedType, code, targetVarName, expr));
        }

        return testCase.addStatement(createUninterpretedStatement(expr, preservedExpr + ";"));
    }

    private boolean shouldFallbackAnonymousObjectCreation(Class<?> rawClass) {
        if (rawClass == null || rawClass == Object.class) {
            return true;
        }
        return rawClass.isInterface() || Modifier.isAbstract(rawClass.getModifiers());
    }

    private VariableReference fallbackAnonymousObjectCreationToTypedNull(ObjectCreationExpr expr,
                                                                         Type preservedType,
                                                                         String targetVarName) {
        String preservedTypeName = getFallbackTypeName(preservedType);
        addWarning(expr,
                "Dropped unsupported anonymous implementation in LLM best-effort mode; using typed null fallback");
        if (targetVarName != null && !targetVarName.trim().isEmpty()) {
            String code = preservedTypeName + " " + targetVarName + " = null;";
            return testCase.addStatement(
                    createUninterpretedStatement(preservedType, code, targetVarName, expr));
        }

        return fallbackForInaccessibleMember(
                expr,
                DiagnosticMessage.categorized(DiagnosticKind.INACCESSIBLE_MEMBER,
                        "anonymous implementation for " + preservedTypeName),
                preservedType);
    }

    // ========================================================================
    // Method call: obj.method(args) or Class.staticMethod(args)
    // ========================================================================

    /**
     * Internal API for helpers: parse a method call using the standard parser pipeline.
     */
    VariableReference handleMethodCall(MethodCallExpr expr, Type declaredType, String targetVarName) {
        int expressionCheckpoint = testCase.size();
        try {
            VariableReference rewrittenAssertDoesNotThrow =
                    tryRewriteAssertDoesNotThrowSupplierAssignment(expr, declaredType, targetVarName);
            if (rewrittenAssertDoesNotThrow != null) {
                return rewrittenAssertDoesNotThrow;
            }

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
                                DiagnosticMessage.categorized(DiagnosticKind.NO_METHOD_SCOPE,
                                        scopeExpr.toString()));
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
                            Expression inlinedReturn =
                                    instantiateInlineHelperReturn(inlineHelper, expr.getArguments(), expr);
                            if (inlinedReturn != null) {
                                Type helperType = (declaredType == null) ? Object.class : declaredType;
                                return handleExpression("__inlinedHelper" + syntheticVarCounter++,
                                        inlinedReturn, helperType);
                            }
                            Expression inlinedSideEffect =
                                    instantiateInlineHelperExpression(inlineHelper, expr.getArguments(), expr);
                            if (inlinedSideEffect != null && isVoidContext(declaredType, targetVarName)) {
                                handleExpressionStatement(inlinedSideEffect);
                                return null;
                            }
                        }
                    }
                    return failOrFallback(expr, declaredType, null,
                            DiagnosticMessage.categorized(DiagnosticKind.NO_UNSCOPED_METHOD, methodName));
                }
            }

            if (staticCall) {
                if (!overloadResolver.hasMethodNamed(targetClass, methodName, true)) {
                    DiagnosticMessage diagnostic = DiagnosticMessage.categorized(DiagnosticKind.NO_MATCHING_METHOD,
                            "No static method named " + methodName + " in " + targetClass.getSimpleName());
                    VariableReference preserved = preserveUnresolvedTopLevelChainedMethodCall(
                            expr, declaredType, targetVarName, expressionCheckpoint, diagnostic);
                    if (preserved != null) {
                        return preserved;
                    }
                    return failOrTypedFallbackWithRollback(
                            expr, declaredType, null, targetVarName, expressionCheckpoint, diagnostic);
                }
            } else {
                if (!overloadResolver.hasMethodNamed(targetClass, methodName, false)) {
                    DiagnosticMessage diagnostic = DiagnosticMessage.categorized(DiagnosticKind.NO_MATCHING_METHOD,
                            "No method named " + methodName + " in " + targetClass.getSimpleName());
                    VariableReference preserved = preserveUnresolvedTopLevelChainedMethodCall(
                            expr, declaredType, targetVarName, expressionCheckpoint, diagnostic);
                    if (preserved != null) {
                        return preserved;
                    }
                    return failOrTypedFallbackWithRollback(
                            expr, declaredType, null, targetVarName, expressionCheckpoint, diagnostic);
                }
            }

            // Resolve arguments
            int argumentCheckpoint = testCase.size();
            List<VariableReference> argRefs = resolveArguments(expr.getArguments(), null, null);

            VariableReference normalizedMockitoMock = mockitoPatternParser.tryHandleLlmMockitoMockCall(
                    expr, methodName, targetClass, staticCall, argRefs);
            if (normalizedMockitoMock != null) {
                return normalizedMockitoMock;
            }

            // Find matching method
            Class<?>[] argTypes = getArgTypes(argRefs);
            Class<?> methodTargetClass = staticCall
                    ? mockitoPatternParser.chooseOverrideMockStaticMethodTarget(targetClass, methodName, argTypes)
                    : targetClass;
            Method method;
            try {
                method = overloadResolver.resolveMethod(methodTargetClass, methodName, argTypes);
            } catch (NoSuchMethodException e) {
                DiagnosticMessage diagnostic =
                        DiagnosticMessage.categorized(DiagnosticKind.NO_MATCHING_METHOD, e.getMessage());
                VariableReference preserved = preserveUnresolvedTopLevelChainedMethodCall(
                        expr, declaredType, targetVarName, expressionCheckpoint, diagnostic);
                if (preserved != null) {
                    return preserved;
                }
                return failOrTypedFallbackWithRollback(expr, declaredType,
                        inferFallbackMethodReturnType(methodTargetClass, methodName, declaredType),
                        targetVarName,
                        expressionCheckpoint,
                        diagnostic);
            }

            if (!isAccessibleMember(method)) {
                return failOrTypedFallbackWithRollback(expr, declaredType, method.getGenericReturnType(), targetVarName,
                        argumentCheckpoint,
                        DiagnosticMessage.categorized(DiagnosticKind.INACCESSIBLE_MEMBER,
                                method.getName() + "() in " + methodTargetClass.getSimpleName()));
            }

            // Re-type Void-typed null arguments now that we know the parameter types
            retypeNullArguments(argRefs, method.getParameterTypes(), method.isVarArgs());

            // Validate argument types against method parameter types (generics + casts)
            DiagnosticMessage mismatch = validateArgumentTypes(argRefs, method.getParameterTypes(),
                    method.getGenericParameterTypes(), expr, method.isVarArgs());
            if (mismatch != null) {
                return failOrTypedFallbackWithRollback(expr, declaredType, method.getGenericReturnType(), targetVarName,
                        argumentCheckpoint,
                        mismatch.withContextPrefix("Method argument mismatch: "));
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
            rollbackTemporaryStatements(expressionCheckpoint);
            return failOrFallback(expr, declaredType, null,
                    "Failed to parse method call: " + e.getMessage());
        }
    }

    private VariableReference tryRewriteAssertDoesNotThrowSupplierAssignment(MethodCallExpr expr,
                                                                             Type declaredType,
                                                                             String targetVarName) {
        if (!markParsedFromLlm || targetVarName == null || targetVarName.trim().isEmpty()) {
            return null;
        }
        if (!"assertDoesNotThrow".equals(expr.getNameAsString()) || expr.getArguments().isEmpty()) {
            return null;
        }

        Expression firstArg = expr.getArgument(0);
        if (!(firstArg instanceof LambdaExpr)) {
            return null;
        }

        LambdaExpr lambdaExpr = (LambdaExpr) firstArg;
        Expression returnedExpr = null;
        if (lambdaExpr.getBody() instanceof ExpressionStmt) {
            returnedExpr = ((ExpressionStmt) lambdaExpr.getBody()).getExpression();
        } else if (lambdaExpr.getBody() instanceof com.github.javaparser.ast.stmt.BlockStmt) {
            com.github.javaparser.ast.stmt.BlockStmt block =
                    (com.github.javaparser.ast.stmt.BlockStmt) lambdaExpr.getBody();
            if (block.getStatements().size() == 1
                    && block.getStatement(0).isReturnStmt()
                    && block.getStatement(0).asReturnStmt().getExpression().isPresent()) {
                returnedExpr = block.getStatement(0).asReturnStmt().getExpression().get();
            }
        }

        if (!(returnedExpr instanceof ObjectCreationExpr)) {
            return null;
        }

        addWarning(expr, DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED,
                "Rewrote Assertions.assertDoesNotThrow(supplier) assignment to direct constructor call");
        return handleExpression(targetVarName, returnedExpr, declaredType);
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

        if (!canRewriteLegacyInvokeHelperCall(
                helperName, scopeCtor.getArgument(0), expr.getArguments(), false)) {
            return false;
        }

        NodeList<Expression> syntheticArgs = new NodeList<>();
        syntheticArgs.add(scopeCtor.getArgument(0));
        syntheticArgs.addAll(expr.getArguments());
        MethodCallExpr syntheticInvokeHelperCall =
                copySyntheticRange(new MethodCallExpr(null, helperName, syntheticArgs), expr);
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
            addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                    "Could not resolve legacy helper field name literal for call: " + expr);
            return false;
        }

        try {
            if ("setField".equals(name)) {
                VariableReference owner = handleExpression(
                        "__legacy_owner" + syntheticVarCounter++, expr.getArguments().get(0), Object.class);
                if (owner == null) {
                    addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                            "Could not resolve receiver for legacy setField call: " + expr);
                    return false;
                }
                Class<?> ownerClass = owner.getVariableClass();
                Type fieldType = resolveFieldType(ownerClass, fieldName);
                VariableReference value = handleExpression(
                        "__legacy_value" + syntheticVarCounter++, expr.getArguments().get(2),
                        fieldType != null ? fieldType : Object.class);
                if (value == null) {
                    addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                            "Could not resolve value for legacy setField call: " + expr);
                    return false;
                }
                testCase.addStatement(new PrivateFieldStatement(testCase, ownerClass, fieldName, owner, value));
                addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                        "Rewrote legacy helper call setField(...) to reflective field write");
                return true;
            }

            Class<?> ownerClass = resolveOwnerClassForLegacyStaticField(expr.getArguments().get(0));
            if (ownerClass == null) {
                addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                        "Could not resolve class for legacy setStaticField call: " + expr);
                return false;
            }
            Type fieldType = resolveFieldType(ownerClass, fieldName);
            VariableReference value = handleExpression(
                    "__legacy_value" + syntheticVarCounter++, expr.getArguments().get(2),
                    fieldType != null ? fieldType : Object.class);
            if (value == null) {
                addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                        "Could not resolve value for legacy setStaticField call: " + expr);
                return false;
            }
            VariableReference nullOwner = testCase.addStatement(new NullStatement(testCase, ownerClass));
            testCase.addStatement(new PrivateFieldStatement(testCase, ownerClass, fieldName, nullOwner, value));
            addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                    "Rewrote legacy helper call setStaticField(...) to reflective field write");
            return true;
        } catch (Exception e) {
            addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                    "Failed to rewrite legacy helper call '" + name + "': " + e.getMessage());
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
                ownerClass = getRawClass(ownerType);
            } else {
                callee = handleExpression("__legacy_invoke_owner" + syntheticVarCounter++, firstArg, Object.class);
                if (callee == null) {
                    addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                            "Could not resolve receiver for legacy helper call: " + expr);
                    return false;
                }
                ownerClass = callee.getVariableClass();
            }

            if (ownerClass == null) {
                addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                        "Could not resolve owner class for legacy helper call: " + expr);
                return false;
            }

            List<Expression> helperArgs = expr.getArguments().subList(1, expr.getArguments().size());
            List<VariableReference> argRefs = resolveArguments(helperArgs, null, null);
            Class<?>[] argTypes = getArgTypes(argRefs);

            Method reflectedMethod = overloadResolver.resolveMethod(ownerClass, reflectedMethodName, argTypes);
            retypeNullArguments(argRefs, reflectedMethod.getParameterTypes(), reflectedMethod.isVarArgs());
            DiagnosticMessage mismatch = validateArgumentTypes(argRefs, reflectedMethod.getParameterTypes(),
                    reflectedMethod.getGenericParameterTypes(), expr, reflectedMethod.isVarArgs());
            if (mismatch != null) {
                addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                        "Could not rewrite legacy helper call " + helperName
                                + " due to argument mismatch: " + mismatch.render());
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
            addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
                    "Rewrote legacy helper call " + helperName + "(...) to reflective method call "
                            + reflectedMethodName + "(...)");
            return true;
        } catch (Exception e) {
            addWarning(expr, DiagnosticKind.LEGACY_HELPER_CALL,
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
                return getRawClass(ownerType);
            }
            return getRawClass(inferTypeForUntypedArgument(receiverExpr));
        } catch (Exception e) {
            return null;
        }
    }

    private Class<?>[] inferArgumentTypes(List<Expression> expressions) {
        Class<?>[] argTypes = new Class<?>[expressions.size()];
        for (int i = 0; i < expressions.size(); i++) {
            argTypes[i] = getRawClass(inferTypeForUntypedArgument(expressions.get(i)));
        }
        return argTypes;
    }

    private Class<?>[] inferArgumentTypesWithReceiver(Expression receiverExpr, List<Expression> helperArgs) {
        Class<?>[] argTypes = new Class<?>[helperArgs.size() + 1];
        argTypes[0] = getRawClass(inferTypeForUntypedArgument(receiverExpr));
        for (int i = 0; i < helperArgs.size(); i++) {
            argTypes[i + 1] = getRawClass(inferTypeForUntypedArgument(helperArgs.get(i)));
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
        if (method == null || !isAccessibleMember(method)) {
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
            return method != null && isAccessibleMember(method) && Modifier.isStatic(method.getModifiers());
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

    private VariableReference failOrTypedFallbackWithRollback(Expression expr,
                                                              Type declaredType,
                                                              Type knownType,
                                                              String targetVarName,
                                                              int checkpointSize,
                                                              DiagnosticMessage diagnostic) {
        rollbackTemporaryStatements(checkpointSize);
        return failOrTypedFallback(expr, declaredType, knownType, targetVarName, diagnostic);
    }

    private VariableReference failOrFallbackWithRollback(Expression expr,
                                                         Type declaredType,
                                                         Type knownType,
                                                         int checkpointSize,
                                                         String errorMsg) {
        rollbackTemporaryStatements(checkpointSize);
        return failOrFallback(expr, declaredType, knownType, errorMsg);
    }

    private VariableReference failOrFallbackWithRollback(Expression expr,
                                                         Type declaredType,
                                                         Type knownType,
                                                         int checkpointSize,
                                                         DiagnosticMessage diagnostic) {
        rollbackTemporaryStatements(checkpointSize);
        return failOrFallback(expr, declaredType, knownType, diagnostic);
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
                                                     List<Expression> callArgs,
                                                     Expression callSite) {
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
        Expression returnExpr = copySyntheticRange(returnStmt.getExpression().get().clone(), callSite);
        return copySyntheticRange(substituteInlineHelperParams(helper, callArgs, returnExpr), callSite);
    }

    private Expression instantiateInlineHelperExpression(MethodDeclaration helper,
                                                         List<Expression> callArgs,
                                                         Expression callSite) {
        if (!helper.getBody().isPresent()) {
            return null;
        }
        List<com.github.javaparser.ast.stmt.Statement> statements = helper.getBody().get().getStatements();
        if (statements.size() != 1 || !(statements.get(0) instanceof ExpressionStmt)) {
            return null;
        }
        Expression expr = copySyntheticRange(((ExpressionStmt) statements.get(0)).getExpression().clone(), callSite);
        return copySyntheticRange(substituteInlineHelperParams(helper, callArgs, expr), callSite);
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
                    return copySyntheticRange(actualArg.clone(), actualArg);
                }
                return super.visit(n, arg);
            }
        };
        return (Expression) template.accept(substituter, null);
    }

    private com.github.javaparser.ast.stmt.Statement inlineHelperCallsInUnsupportedStatement(
            com.github.javaparser.ast.stmt.Statement astStmt) {
        com.github.javaparser.ast.stmt.Statement cloned = copySyntheticRange(astStmt.clone(), astStmt);
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
                            Expression replacement =
                                    instantiateInlineHelperExpression(helper, call.getArguments(), call);
                            if (replacement == null) {
                                replacement = instantiateInlineHelperReturn(helper, call.getArguments(), call);
                            }
                            if (replacement != null) {
                                return copySyntheticRange(new ExpressionStmt(replacement), n);
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

    private boolean shouldDropUnsupportedStatement(com.github.javaparser.ast.stmt.Statement stmt) {
        UnsupportedMethodTarget invalid = findInvalidUnsupportedStatementMethodCall(stmt);
        if (invalid == null) {
            return false;
        }
        addWarning(invalid.call,
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
            Type democked = mockitoPatternParser.tryResolveDemockedDeclaredType(astType);
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
        Class<?> staticClass = resolveClassFromExpression(scopeExpr);
        if (staticClass != null) {
            return new UnsupportedMethodTarget(call, staticClass, true);
        }

        Type scopeType = resolveUnsupportedExpressionType(scopeExpr, localTypes);
        if (scopeType == null) {
            return new UnsupportedMethodTarget(call, null, false);
        }
        return new UnsupportedMethodTarget(call, getRawClass(scopeType), false);
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
            Class<?> clazz = resolveClassFromExpression(expr);
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
            Class<?> ownerClass = getRawClass(scopeType);
            if (ownerClass == null || ownerClass == Object.class) {
                ownerClass = resolveClassFromExpression(fieldAccessExpr.getScope());
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

    /**
     * Handle a top-level method call (standalone expression statement, e.g. void call).
     * Assertion calls are intercepted and converted to EvoSuite Assertion objects.
     */
    private void handleTopLevelMethodCall(MethodCallExpr methodCall) {
        String name = methodCall.getNameAsString();
        if (assertionParser.isAssertionMethodName(name)) {
            assertionParser.handleAssertionCall(methodCall);
        } else if (mockitoPatternParser.tryHandleCapturedWhenStubbingTerminalCall(methodCall)) {
            // Handled as a delayed terminal call for a previously captured OngoingStubbing alias
        } else if (mockitoPatternParser.tryHandleStandaloneStubbingCall(methodCall)) {
            // Handled as standalone Mockito stubbing (when/thenReturn or doReturn/when)
        } else if (mockitoPatternParser.tryPreserveStandaloneThrowStubbingCall(methodCall)) {
            // Preserve Mockito throw-stubbing chains that cannot be represented as FunctionalMockStatement
        } else {
            handleMethodCall(methodCall, void.class, null);
        }
    }

    // Internal API for helpers: synthesize a compilable fallback value of the requested type.
    VariableReference createTypedFallbackValue(Type expectedType) {
        Class<?> raw = getRawClass(expectedType);
        if (raw == void.class || raw == Void.class) {
            return testCase.addStatement(createUninterpretedStatement(new NameExpr("fallback"), ";"));
        }
        Statement primitiveFallback = defaultPrimitiveStatement(testCase, expectedType);
        if (primitiveFallback != null) {
            return testCase.addStatement(primitiveFallback);
        }
        return testCase.addStatement(new NullStatement(testCase, accessibleNullType(expectedType)));
    }

    private com.github.javaparser.ast.type.ClassOrInterfaceType parseClassOrInterfaceType(String typeName) {
        com.github.javaparser.ParseResult<com.github.javaparser.ast.type.ClassOrInterfaceType> parsed =
                javaParser.parseClassOrInterfaceType(typeName);
        return parsed.getResult()
                .orElseThrow(() -> new ParseProblemException(parsed.getProblems()));
    }

    private com.github.javaparser.ast.type.ClassOrInterfaceType parseClassOrInterfaceType(
            String typeName,
            com.github.javaparser.ast.Node sourceNode) {
        return copySyntheticRange(parseClassOrInterfaceType(typeName), sourceNode);
    }

    private com.github.javaparser.ast.type.Type parseType(String typeName) {
        com.github.javaparser.ParseResult<com.github.javaparser.ast.type.Type> parsed =
                javaParser.parseType(typeName);
        return parsed.getResult()
                .orElseThrow(() -> new ParseProblemException(parsed.getProblems()));
    }

    // Internal API for helpers: parse a type token while preserving the original source range.
    com.github.javaparser.ast.type.Type parseType(String typeName,
                                                  com.github.javaparser.ast.Node sourceNode) {
        return copySyntheticRange(parseType(typeName), sourceNode);
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
                            DiagnosticMessage.categorized(DiagnosticKind.UNKNOWN_FIELD_SCOPE,
                                    scopeExpr.toString()));
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
                        DiagnosticMessage.categorized(DiagnosticKind.INACCESSIBLE_MEMBER,
                                field.getName() + " in " + targetClass.getSimpleName()));
            }
            TestClusterUtils.makeAccessible(field);
            GenericClass<?> ownerClass = GenericClassFactory.get(targetClass);
            GenericField genericField = new GenericField(field, ownerClass);

            FieldStatement stmt = new FieldStatement(testCase, genericField, source);
            return testCase.addStatement(stmt);

        } catch (Exception e) {
            addError(expr, DiagnosticKind.PARSE_FAILURE, "field access: " + e.getMessage());
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
            addError(expr, DiagnosticKind.ENUM_CONSTANT_UNRESOLVED,
                    enumClass.getName() + "." + constantName);
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
     * Internal API for helpers: resolve one argument through the parser's normal rules.
     */
    VariableReference resolveArgument(Expression arg, Type paramType) {
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
            if (isClassName) {
                addError(arg, DiagnosticKind.BARE_CLASS_NAME_AS_VALUE,
                        name + " (did you mean " + name + ".class?)");
            } else {
                addError(arg, DiagnosticKind.UNRESOLVED_VARIABLE, name);
            }
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
        return inferTypeForUntypedArgument(arg, 0, arg);
    }

    private Type inferTypeForUntypedArgument(Expression arg, int depth, Expression originalArg) {
        if (depth >= MAX_RECURSIVE_EXPRESSION_DEPTH) {
            addWarning(originalArg, DiagnosticKind.EXPRESSION_DEPTH_EXCEEDED,
                    MAX_RECURSIVE_EXPRESSION_DEPTH + " during type inference; defaulting to Object");
            return Object.class;
        }
        if (arg instanceof EnclosedExpr) {
            return inferTypeForUntypedArgument(((EnclosedExpr) arg).getInner(), depth + 1, originalArg);
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
            return inferTypeForUntypedArgument(((UnaryExpr) arg).getExpression(), depth + 1, originalArg);
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
                    Type left = inferTypeForUntypedArgument(bin.getLeft(), depth + 1, originalArg);
                    Type right = inferTypeForUntypedArgument(bin.getRight(), depth + 1, originalArg);
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
                    Type left = inferTypeForUntypedArgument(bin.getLeft(), depth + 1, originalArg);
                    Type right = inferTypeForUntypedArgument(bin.getRight(), depth + 1, originalArg);
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
                Method method = overloadResolver.resolveMethod(targetClass, methodName, argTypes);
                return method.getGenericReturnType();
            } catch (NoSuchMethodException e) {
                Method loose = OverloadResolver.findByNameLoose(targetClass, methodName);
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
        boolean explicitVarArgArray = OverloadResolver.usesExplicitVarArgsArray(paramTypes, isVarArgs, argTypes);
        for (int i = 0; i < argRefs.size(); i++) {
            VariableReference ref = argRefs.get(i);
            if (ref.getVariableClass() != Void.class) {
                continue;
            }
            Class<?> formalType = OverloadResolver.getFormalTypeForArgument(
                    paramTypes, isVarArgs, i, argRefs.size(), explicitVarArgArray);
            if (formalType != null && !formalType.isPrimitive()) {
                ref.setType(formalType);
            }
        }
    }

    private DiagnosticMessage validateArgumentTypes(List<VariableReference> argRefs,
                                                    Class<?>[] paramTypes,
                                                    Type[] genericParamTypes,
                                                    Expression expr,
                                                    boolean isVarArgs) {
        Class<?>[] argTypes = getArgTypes(argRefs);
        boolean explicitVarArgArray = OverloadResolver.usesExplicitVarArgsArray(paramTypes, isVarArgs, argTypes);
        for (int i = 0; i < argRefs.size(); i++) {
            VariableReference argRef = argRefs.get(i);
            Class<?> argClass = argRef.getVariableClass();
            Class<?> paramClass = OverloadResolver.getFormalTypeForArgument(
                    paramTypes, isVarArgs, i, argRefs.size(), explicitVarArgArray);
            if (paramClass == null) {
                continue;
            }

            // Skip if directly assignable (including primitives, autoboxing handled elsewhere)
            if (OverloadResolver.isAssignableFrom(paramClass, argClass)) {
                // Check generic type arguments for collection types
                int genericParamIndex = i;
                if (isVarArgs && !explicitVarArgArray && i >= paramTypes.length - 1) {
                    genericParamIndex = paramTypes.length - 1;
                }
                if (genericParamTypes != null && genericParamIndex < genericParamTypes.length
                        && genericParamTypes[genericParamIndex] instanceof java.lang.reflect.ParameterizedType) {
                    DiagnosticMessage genericError = checkGenericCompatibility(
                            argRef, (java.lang.reflect.ParameterizedType) genericParamTypes[genericParamIndex], i);
                    if (genericError != null) {
                        return genericError;
                    }
                }
                continue;
            }

            // Object (or other supertype) passed where specific subtype is expected
            if (!paramClass.isPrimitive() && argClass == Object.class && paramClass != Object.class) {
                return DiagnosticMessage.categorized(DiagnosticKind.OBJECT_TO_SUBTYPE_MISMATCH,
                        "argument " + i + " expects " + paramClass.getSimpleName()
                                + " — implicit cast not safe");
            }

            // General type mismatch — already handled by method resolution compatibility,
            // but catch stragglers
            if (!paramClass.isPrimitive() && !paramClass.isAssignableFrom(argClass)) {
                return DiagnosticMessage.freeForm("Argument " + i + " type " + argClass.getSimpleName()
                        + " is not compatible with parameter type " + paramClass.getSimpleName());
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
        boolean explicitVarArgArray = OverloadResolver.usesExplicitVarArgsArray(paramTypes, true, argTypes);
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
    private DiagnosticMessage checkGenericCompatibility(VariableReference argRef,
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
                return DiagnosticMessage.categorized(DiagnosticKind.GENERIC_TYPE_MISMATCH,
                        "argument " + argIndex + ": " + argTArg.getSimpleName()
                                + " is not compatible with " + paramTArg.getSimpleName());
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
                        DiagnosticMessage.categorized(DiagnosticKind.UNRESOLVED_CLASS_LITERAL,
                                e.getMessage()));
            }
            addError(expr, DiagnosticKind.UNRESOLVED_CLASS_LITERAL, e.getMessage());
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
                        addWarning(dimExpr, DiagnosticKind.NON_LITERAL_ARRAY_DIMENSION,
                                "defaulting to 0: " + dimExpr);
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
            addError(expr, DiagnosticKind.PARSE_FAILURE, "array creation: " + e.getMessage());
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
            addError(expr, DiagnosticKind.PARSE_FAILURE, "array access: " + e.getMessage());
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
                addWarning(diagnosticExpr, DiagnosticKind.UNKNOWN_ARRAY_VAR, description);
            } else {
                addError(diagnosticExpr, DiagnosticKind.UNKNOWN_ARRAY_VAR, description);
            }
            return null;
        }
        if (!(arrayRef instanceof ArrayReference)) {
            String description = (current instanceof NameExpr)
                    ? ((NameExpr) current).getNameAsString()
                    : current.toString();
            if (markParsedFromLlm) {
                addWarning(diagnosticExpr, DiagnosticKind.VARIABLE_NOT_ARRAY, description);
            } else {
                addError(diagnosticExpr, DiagnosticKind.VARIABLE_NOT_ARRAY, description);
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
        addWarning(indexExpr, DiagnosticKind.NON_LITERAL_ARRAY_INDEX,
                "defaulting to 0: " + indexExpr);
        return 0;
    }

    /**
     * Evaluate an expression as an integer using parser-known constants/variables only.
     * Returns null when the expression cannot be resolved without executing code.
     */
    private Integer evaluateIntExpression(Expression expr) {
        return evaluateIntExpression(expr, 0, expr);
    }

    private Integer evaluateIntExpression(Expression expr, int depth, Expression originalExpr) {
        if (depth >= MAX_RECURSIVE_EXPRESSION_DEPTH) {
            addWarning(originalExpr, DiagnosticKind.EXPRESSION_DEPTH_EXCEEDED,
                    MAX_RECURSIVE_EXPRESSION_DEPTH
                            + " during integer evaluation; treating expression as non-literal");
            return null;
        }
        if (expr instanceof IntegerLiteralExpr) {
            return ((IntegerLiteralExpr) expr).asNumber().intValue();
        }
        if (expr instanceof EnclosedExpr) {
            return evaluateIntExpression(((EnclosedExpr) expr).getInner(), depth + 1, originalExpr);
        }
        if (expr instanceof UnaryExpr) {
            UnaryExpr unaryExpr = (UnaryExpr) expr;
            Integer inner = evaluateIntExpression(unaryExpr.getExpression(), depth + 1, originalExpr);
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
            Integer left = evaluateIntExpression(binaryExpr.getLeft(), depth + 1, originalExpr);
            Integer right = evaluateIntExpression(binaryExpr.getRight(), depth + 1, originalExpr);
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
            addError(expr, DiagnosticKind.PARSE_FAILURE, "array initializer: " + e.getMessage());
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
                    addError(expr, DiagnosticKind.UNRESOLVED_VARIABLE, "expression " + name);
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
                    PendingLlmDeclaration pendingDeclaration = pendingLlmDeclarations.remove(targetName);
                    Type declaredType = pendingDeclaration.getDeclaredType();
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
                    addError(assignExpr, DiagnosticKind.UNRESOLVED_VARIABLE, targetName);
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
                        addWarning(assignExpr, DiagnosticKind.INVALID_ASSIGNMENT,
                                "variable assignment preserved as uninterpreted: " + assignExpr);
                        testCase.addStatement(createUninterpretedStatement(assignExpr,
                                assignExpr.toString() + ";"));
                    } else {
                        addError(assignExpr, DiagnosticKind.INVALID_ASSIGNMENT, assignExpr.toString());
                    }
                    return;
                }
                testCase.addStatement(stmt);
                if (markParsedFromLlm) {
                    // In LLM mode, keep subsequent reads bound to the latest assigned value
                    // so assertions parsed after this assignment are emitted in the correct order.
                    scope.register(targetName, rhs, scope.resolveGenericType(targetName));
                }
            } else if (target instanceof ArrayAccessExpr) {
                // array[i] = value
                ArrayAccessExpr arrayAccess = (ArrayAccessExpr) target;
                ArrayIndex arrayIndex = resolveArrayAccess(arrayAccess, assignExpr);
                if (arrayIndex == null) {
                    if (markParsedFromLlm) {
                        addWarning(assignExpr, DiagnosticKind.INVALID_ASSIGNMENT,
                                "unresolved array assignment preserved as uninterpreted: " + assignExpr);
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
                        addWarning(assignExpr, DiagnosticKind.INVALID_ASSIGNMENT,
                                "array assignment for modeled bounds preserved as uninterpreted: " + assignExpr);
                        testCase.addStatement(createUninterpretedStatement(assignExpr,
                                assignExpr.toString() + ";"));
                    } else {
                        addError(assignExpr, DiagnosticKind.INVALID_ASSIGNMENT, assignExpr.toString());
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
                        addError(assignExpr, DiagnosticKind.UNKNOWN_FIELD_SCOPE, scopeName);
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
                        addError(assignExpr, DiagnosticKind.NO_SUCH_FIELD,
                                ownerClass.getName() + "." + fieldName);
                        return;
                    }
                }

                if (!isAccessibleMember(field)) {
                    addError(assignExpr, DiagnosticKind.INACCESSIBLE_MEMBER,
                            field.getName() + " in " + ownerClass.getSimpleName());
                    return;
                }
                boolean isStaticField = java.lang.reflect.Modifier.isStatic(field.getModifiers());
                if (sourceRef == null && !isStaticField) {
                    addError(assignExpr, DiagnosticKind.NON_STATIC_FIELD_REQUIRES_INSTANCE,
                            ownerClass.getSimpleName() + "." + field.getName());
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
                        addWarning(assignExpr, DiagnosticKind.INVALID_ASSIGNMENT,
                                "field assignment preserved as uninterpreted: " + assignExpr);
                        testCase.addStatement(createUninterpretedStatement(assignExpr,
                                assignExpr.toString() + ";"));
                    } else {
                        addError(assignExpr, DiagnosticKind.INVALID_ASSIGNMENT, assignExpr.toString());
                    }
                    return;
                }
                testCase.addStatement(stmt);
            } else {
                addWarning(assignExpr, DiagnosticKind.INVALID_ASSIGNMENT,
                        "unsupported assignment target: " + target.getClass().getSimpleName());
            }
        } catch (Throwable e) {
            addError(assignExpr, DiagnosticKind.PARSE_FAILURE, "assignment: " + e.getMessage());
        }
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
        return failOrFallback(expr, declaredType, knownType, DiagnosticMessage.freeForm(errorMsg));
    }

    private VariableReference failOrFallback(Expression expr,
                                             Type declaredType,
                                             Type knownType,
                                             DiagnosticMessage diagnostic) {
        if (markParsedFromLlm) {
            return fallbackForUnresolvedExpression(expr, chooseFallbackType(declaredType, knownType), diagnostic);
        }
        addError(expr, diagnostic);
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
        return failOrTypedFallback(expr, declaredType, knownType, targetVarName,
                DiagnosticMessage.freeForm(errorMsg));
    }

    private VariableReference failOrTypedFallback(Expression expr,
                                                  Type declaredType,
                                                  Type knownType,
                                                  String targetVarName,
                                                  DiagnosticMessage diagnostic) {
        if (markParsedFromLlm) {
            if (!isSyntheticArgumentTarget(targetVarName)
                    && shouldSkipTypedFallbackForDeclaration(diagnostic)) {
                addWarning(expr, diagnostic.withAppendedText(" — unresolved declaration value skipped "
                        + "(no synthetic null/0 fallback in LLM mode)"));
                return null;
            }
            return fallbackForInaccessibleMember(expr, diagnostic, chooseFallbackType(declaredType, knownType));
        }
        addError(expr, diagnostic);
        return null;
    }

    private boolean isSyntheticArgumentTarget(String targetVarName) {
        return targetVarName != null && targetVarName.startsWith("__arg");
    }

    private boolean shouldSkipTypedFallbackForDeclaration(DiagnosticMessage diagnostic) {
        if (diagnostic == null) {
            return false;
        }
        DiagnosticKind kind = diagnostic.getKind();
        if (kind == DiagnosticKind.NO_MATCHING_METHOD
                || kind == DiagnosticKind.OBJECT_TO_SUBTYPE_MISMATCH
                || kind == DiagnosticKind.GENERIC_TYPE_MISMATCH) {
            return true;
        }
        String errorMsg = diagnostic.rawText();
        return errorMsg.startsWith("No matching method:")
                || errorMsg.startsWith("No method named ")
                || errorMsg.startsWith("No static method named ")
                || errorMsg.startsWith("Method argument mismatch:");
    }

    private VariableReference preserveUnresolvedTopLevelChainedMethodCall(MethodCallExpr expr,
                                                                          Type declaredType,
                                                                          String targetVarName,
                                                                          int checkpointSize,
                                                                          DiagnosticMessage diagnostic) {
        if (!markParsedFromLlm
                || expr == null
                || targetVarName != null
                || !isVoidContext(declaredType, targetVarName)
                || !expr.getScope().isPresent()
                || !(expr.getScope().get() instanceof MethodCallExpr)) {
            return null;
        }
        rollbackTemporaryStatements(checkpointSize);
        addWarning(expr, diagnostic.withAppendedText(
                " — preserved full chained call as raw source to avoid dropping the terminal invocation"));
        return testCase.addStatement(createUninterpretedStatement(expr, expr.toString() + ";"));
    }

    public void finalizeParse() {
        for (PendingLlmDeclaration pendingDeclaration : pendingLlmDeclarations.values()) {
            String details = "declared variable `" + pendingDeclaration.getVariableName()
                    + "` of type `" + pendingDeclaration.getDeclaredTypeText()
                    + "` was never assigned; either remove the declaration or assign a value before use.";
            addFinalizeDiagnostic(pendingDeclaration.getDeclarationNode(), DiagnosticKind.STRANDED_DECLARATION, details);
        }
        pendingLlmDeclarations.clear();

        mockitoPatternParser.flushCapturedWhenStubbingDiagnostics();
    }

    private void addFinalizeDiagnostic(com.github.javaparser.ast.Node node, DiagnosticKind kind, String details) {
        if (markParsedFromLlm) {
            addWarning(node, kind, details);
        } else {
            addError(node, kind, details);
        }
    }

    // Internal API for helpers: replace an inaccessible/unresolvable expression with a typed fallback.
    VariableReference fallbackForInaccessibleMember(Expression expr,
                                                    String message,
                                                    Type expectedType) {
        return fallbackForInaccessibleMember(expr, DiagnosticMessage.freeForm(message), expectedType);
    }

    private VariableReference fallbackForInaccessibleMember(Expression expr,
                                                            DiagnosticMessage diagnostic,
                                                            Type expectedType) {
        addWarning(expr, diagnostic.withAppendedText(" — using typed fallback value"));
        Class<?> raw = getRawClass(expectedType);
        if (raw == void.class || raw == Void.class) {
            return testCase.addStatement(createUninterpretedStatement(expr, ";"));
        }
        Statement primitiveFallback = defaultPrimitiveStatement(testCase, expectedType);
        if (primitiveFallback != null) {
            return testCase.addStatement(primitiveFallback);
        }
        return testCase.addStatement(new NullStatement(testCase,
                accessibleNullType(expectedType)));
    }

    private VariableReference fallbackForUnresolvedExpression(Expression expr,
                                                              Type expectedType,
                                                              String message) {
        return fallbackForUnresolvedExpression(expr, expectedType, DiagnosticMessage.freeForm(message));
    }

    private VariableReference fallbackForUnresolvedExpression(Expression expr,
                                                              Type expectedType,
                                                              DiagnosticMessage diagnostic) {
        Type fallbackType = expectedType == null ? Object.class : expectedType;
        addWarning(expr, diagnostic.withAppendedText(" — replaced with compilable fallback"));
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

    String getFallbackTypeName(Type type) {
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
        if (!isAccessibleFromGeneratedTest(raw)) {
            return Object.class.getCanonicalName();
        }
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

    private boolean isAccessibleFromGeneratedTest(Class<?> raw) {
        if (raw == null) {
            return false;
        }
        if (raw.isPrimitive()) {
            return true;
        }
        Class<?> type = raw;
        while (type.isArray()) {
            type = type.getComponentType();
        }
        if (type.isPrimitive()) {
            return true;
        }

        if (!Modifier.isPublic(type.getModifiers()) && !isInTargetPackage(type)) {
            return false;
        }

        Class<?> enclosing = type.getEnclosingClass();
        while (enclosing != null) {
            if (!Modifier.isPublic(enclosing.getModifiers()) && !isInTargetPackage(enclosing)) {
                return false;
            }
            enclosing = enclosing.getEnclosingClass();
        }
        return true;
    }

    private boolean isInTargetPackage(Class<?> type) {
        if (type == null) {
            return false;
        }
        String targetClassName = Properties.TARGET_CLASS;
        if (targetClassName == null || targetClassName.trim().isEmpty()) {
            // Unit tests and standalone parser invocations may not set TARGET_CLASS.
            // In that case, keep previous permissive behavior to avoid over-downgrading.
            return true;
        }
        int idx = targetClassName.lastIndexOf('.');
        String targetPackage = idx >= 0 ? targetClassName.substring(0, idx) : "";
        Package pkg = type.getPackage();
        String packageName = pkg != null ? pkg.getName() : "";
        return targetPackage.equals(packageName);
    }

    // Downgrade a NullStatement's declared type to Object when the original
    // type is not accessible from the generated test (e.g. package-private in a
    // package other than the SUT's). Otherwise the rendered "Foo nullRef0 = null;"
    // would not compile.
    private Type accessibleNullType(Type type) {
        if (type == null) {
            return Object.class;
        }
        Class<?> raw = getRawClass(type);
        if (raw == null) {
            return type;
        }
        return isAccessibleFromGeneratedTest(raw) ? type : Object.class;
    }

    private String getDefaultFallbackLiteral(Type type) {
        String primitiveLiteral = defaultPrimitiveLiteral(type);
        return primitiveLiteral != null ? primitiveLiteral : "null";
    }

    private Statement defaultPrimitiveStatement(TestCase testCase, Type type) {
        Class<?> raw = getRawClass(type);
        if (raw == boolean.class || raw == Boolean.class) {
            return new BooleanPrimitiveStatement(testCase, false);
        }
        if (raw == byte.class || raw == Byte.class) {
            return new BytePrimitiveStatement(testCase, (byte) 0);
        }
        if (raw == short.class || raw == Short.class) {
            return new ShortPrimitiveStatement(testCase, (short) 0);
        }
        if (raw == int.class || raw == Integer.class) {
            return new IntPrimitiveStatement(testCase, 0);
        }
        if (raw == long.class || raw == Long.class) {
            return new LongPrimitiveStatement(testCase, 0L);
        }
        if (raw == float.class || raw == Float.class) {
            return new FloatPrimitiveStatement(testCase, 0.0f);
        }
        if (raw == double.class || raw == Double.class) {
            return new DoublePrimitiveStatement(testCase, 0.0d);
        }
        if (raw == char.class || raw == Character.class) {
            return new CharPrimitiveStatement(testCase, '\0');
        }
        if (raw == String.class) {
            return new StringPrimitiveStatement(testCase, null);
        }
        return null;
    }

    private String defaultPrimitiveLiteral(Type type) {
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
        return null;
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
     * Internal API for helpers: resolve a scope expression as a class for static access checks.
     */
    Class<?> resolveClassFromExpression(Expression scopeExpr) {
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

    // Internal API for helpers: erase reflective/generic Type values to a raw Class when possible.
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

    private static final class DiagnosticMessage {

        private final DiagnosticKind kind;
        private final String text;

        private DiagnosticMessage(DiagnosticKind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        private static DiagnosticMessage freeForm(String message) {
            return new DiagnosticMessage(null, message);
        }

        private static DiagnosticMessage categorized(DiagnosticKind kind, String details) {
            return new DiagnosticMessage(kind, details);
        }

        private DiagnosticKind getKind() {
            return kind;
        }

        private String rawText() {
            return text;
        }

        private String render() {
            return kind == null ? text : kind.format(text);
        }

        private DiagnosticMessage withAppendedText(String suffix) {
            return new DiagnosticMessage(kind, text + suffix);
        }

        private DiagnosticMessage withContextPrefix(String prefix) {
            return new DiagnosticMessage(kind, prefix + text);
        }
    }

    // Internal API for helpers: report an uncategorized parser error on the originating AST node.
    void addError(com.github.javaparser.ast.Node node, String message) {
        addError(node, DiagnosticMessage.freeForm(message));
    }

    // Internal API for helpers: report a categorized parser error on the originating AST node.
    void addError(com.github.javaparser.ast.Node node, DiagnosticKind kind, String details) {
        addError(node, DiagnosticMessage.categorized(kind, details));
    }

    private void addError(com.github.javaparser.ast.Node node, DiagnosticMessage diagnostic) {
        DiagnosticKind kind = resolveEffectiveDiagnosticKind(diagnostic);
        String enriched = enrichDiagnosticForLlmRepair(kind, diagnostic.render());
        int line = resolveDiagnosticLine(node);
        result.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.ERROR,
                kind,
                enriched,
                line,
                node.toString()));
    }

    // Internal API for helpers: report an uncategorized parser warning on the originating AST node.
    void addWarning(com.github.javaparser.ast.Node node, String message) {
        addWarning(node, DiagnosticMessage.freeForm(message));
    }

    // Internal API for helpers: report a categorized parser warning on the originating AST node.
    void addWarning(com.github.javaparser.ast.Node node, DiagnosticKind kind, String details) {
        addWarning(node, DiagnosticMessage.categorized(kind, details));
    }

    private void addWarning(com.github.javaparser.ast.Node node, DiagnosticMessage diagnostic) {
        DiagnosticKind kind = resolveEffectiveDiagnosticKind(diagnostic);
        String enriched = enrichDiagnosticForLlmRepair(kind, diagnostic.render());
        int line = resolveDiagnosticLine(node);
        result.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.WARNING, kind, enriched, line, node.toString()));
    }

    private int resolveDiagnosticLine(com.github.javaparser.ast.Node node) {
        com.github.javaparser.ast.Node current = node;
        while (current != null) {
            if (current.getBegin().isPresent()) {
                return current.getBegin().get().line;
            }
            current = current.getParentNode().orElse(null);
        }
        int descendantLine = resolveDiagnosticLineFromDescendants(node);
        if (descendantLine > 0) {
            return descendantLine;
        }
        return 0;
    }

    private int resolveDiagnosticLineFromDescendants(com.github.javaparser.ast.Node node) {
        if (node == null) {
            return 0;
        }
        for (com.github.javaparser.ast.Node child : node.getChildNodes()) {
            if (child.getBegin().isPresent()) {
                return child.getBegin().get().line;
            }
            int descendantLine = resolveDiagnosticLineFromDescendants(child);
            if (descendantLine > 0) {
                return descendantLine;
            }
        }
        return 0;
    }

    static <T extends com.github.javaparser.ast.Node> T copySyntheticRange(T synthesized,
                                                                           com.github.javaparser.ast.Node sourceNode) {
        if (synthesized == null || sourceNode == null) {
            return synthesized;
        }
        return copySyntheticRange(synthesized, sourceNode.getRange().orElse(null));
    }

    static <T extends com.github.javaparser.ast.Node> T copySyntheticRange(T synthesized, Range sourceRange) {
        if (synthesized == null || sourceRange == null) {
            return synthesized;
        }
        applySyntheticRange(synthesized, sourceRange);
        return synthesized;
    }

    private static void applySyntheticRange(com.github.javaparser.ast.Node node, Range sourceRange) {
        node.setRange(sourceRange);
        for (com.github.javaparser.ast.Node child : node.getChildNodes()) {
            applySyntheticRange(child, sourceRange);
        }
    }

    private String enrichDiagnosticForLlmRepair(DiagnosticKind kind, String message) {
        if (message == null || message.isEmpty()
                || message.contains(DiagnosticKind.ACTION_REQUIRED_PREFIX)) {
            return message;
        }

        DiagnosticKind effectiveKind = kind != null ? kind : inferDiagnosticKind(message);
        if (effectiveKind == null) {
            return message;
        }
        return effectiveKind.appendRepairAction(message);
    }

    private DiagnosticKind resolveEffectiveDiagnosticKind(DiagnosticMessage diagnostic) {
        if (diagnostic == null) {
            return null;
        }
        DiagnosticKind kind = diagnostic.getKind();
        return kind != null ? kind : inferDiagnosticKind(diagnostic.render());
    }

    private DiagnosticKind inferDiagnosticKind(String message) {
        logger.debug("Inferring DiagnosticKind for free-form diagnostic: {}", message);
        if (message == null) {
            return null;
        }
        String lower = message.toLowerCase();
        if (lower.contains("resolved inferred mock-prefixed type")) {
            return DiagnosticKind.MOCK_PREFIX_TYPE_INFERRED;
        }
        if (lower.contains("incompatible declaration alias")
                || lower.contains("inserted typed cast to preserve compilability for incompatible alias declaration")) {
            return DiagnosticKind.INCOMPATIBLE_ALIAS_DECLARATION;
        }
        if (lower.contains("cannot resolve cast type")) {
            return DiagnosticKind.UNRESOLVED_CAST_TYPE;
        }
        if (lower.contains("cannot resolve assertion condition")) {
            return DiagnosticKind.ASSERTION_CONDITION_UNRESOLVED;
        }
        if (lower.contains("unresolved variable in assertion argument")) {
            return DiagnosticKind.ASSERTION_ARGUMENT_UNRESOLVED;
        }
        if (lower.contains("lambda expression requires a functional interface target type")
                || lower.contains("standalone lambda expression has no declaration target type")) {
            return DiagnosticKind.LAMBDA_TARGET_TYPE_REQUIRED;
        }
        if (lower.contains("expression nesting depth exceeded")) {
            return DiagnosticKind.EXPRESSION_DEPTH_EXCEEDED;
        }
        if (lower.contains("legacy helper")) {
            return DiagnosticKind.LEGACY_HELPER_CALL;
        }
        if (lower.contains("cannot resolve method scope")) {
            return DiagnosticKind.NO_METHOD_SCOPE;
        }
        if (lower.contains("cannot resolve unscoped method call")) {
            return DiagnosticKind.NO_UNSCOPED_METHOD;
        }
        if (lower.contains("unknown array variable")) {
            return DiagnosticKind.UNKNOWN_ARRAY_VAR;
        }
        if (lower.contains("variable is not an array")) {
            return DiagnosticKind.VARIABLE_NOT_ARRAY;
        }
        if (lower.contains("unknown variable for field access")
                || lower.contains("cannot resolve field scope")) {
            return DiagnosticKind.UNKNOWN_FIELD_SCOPE;
        }
        if (lower.contains("has private access") || lower.contains("has protected access")) {
            return DiagnosticKind.INACCESSIBLE_MEMBER;
        }
        if (lower.contains("cannot resolve class literal")) {
            return DiagnosticKind.UNRESOLVED_CLASS_LITERAL;
        }
        if (lower.contains("cannot resolve class")
                || (lower.contains("cannot resolve type") && !lower.contains("class literal"))
                || (lower.contains("failed to parse constructor") && lower.contains("class"))) {
            return DiagnosticKind.UNRESOLVED_TYPE;
        }
        if (lower.contains("unresolved variable")) {
            return DiagnosticKind.UNRESOLVED_VARIABLE;
        }
        if (lower.contains("no matching constructor")) {
            return DiagnosticKind.NO_MATCHING_CONSTRUCTOR;
        }
        if (lower.contains("no matching method")
                || lower.contains("no method named ")
                || lower.contains("no static method named ")) {
            return DiagnosticKind.NO_MATCHING_METHOD;
        }
        if (lower.contains("failed to resolve enum constant")) {
            return DiagnosticKind.ENUM_CONSTANT_UNRESOLVED;
        }
        if (lower.contains("non-literal array dimension")) {
            return DiagnosticKind.NON_LITERAL_ARRAY_DIMENSION;
        }
        if (lower.contains("non-literal array index")) {
            return DiagnosticKind.NON_LITERAL_ARRAY_INDEX;
        }
        if (lower.contains("no such field")) {
            return DiagnosticKind.NO_SUCH_FIELD;
        }
        if (lower.contains("non-static field") && lower.contains("requires an instance")) {
            return DiagnosticKind.NON_STATIC_FIELD_REQUIRES_INSTANCE;
        }
        if (lower.contains("invalid variable assignment")
                || lower.contains("invalid array assignment")
                || lower.contains("invalid field assignment")
                || lower.startsWith("invalid assignment:")
                || lower.contains("unsupported assignment target")
                || lower.contains("unresolved array assignment preserved as uninterpreted")) {
            return DiagnosticKind.INVALID_ASSIGNMENT;
        }
        if (lower.contains("lambda expression preserved as uninterpretedstatement")
                || lower.contains("unsupported expression type")
                || lower.contains("unsupported unary operator preserved as uninterpretedstatement")
                || lower.contains("preserved anonymous class implementation as raw java source")
                || lower.contains("preserved mockito throw-stubbing as uninterpretedstatement")) {
            return DiagnosticKind.UNSUPPORTED_CONSTRUCT_PRESERVED;
        }
        if (lower.contains("is object but parameter expects")) {
            return DiagnosticKind.OBJECT_TO_SUBTYPE_MISMATCH;
        }
        if (lower.contains("generic type mismatch")) {
            return DiagnosticKind.GENERIC_TYPE_MISMATCH;
        }
        if (lower.contains("primitive declaration cannot be initialized with null")) {
            return DiagnosticKind.PRIMITIVE_INIT_WITH_NULL;
        }
        if (lower.contains("bare class name used as value expression")
                || lower.contains("bare class name used as argument")) {
            return DiagnosticKind.BARE_CLASS_NAME_AS_VALUE;
        }
        if (lower.contains("failed to parse")) {
            return DiagnosticKind.PARSE_FAILURE;
        }
        return null;
    }

    private void handleTryStatement(TryStmt tryStmt) {
        if (tryStmt == null) {
            return;
        }
        NodeList<com.github.javaparser.ast.stmt.Statement> stmts = tryStmt.getTryBlock().getStatements();
        for (int i = 0; i < stmts.size(); i++) {
            com.github.javaparser.ast.stmt.Statement stmt = stmts.get(i);
            try {
                parseStatement(stmt, stmts, i);
            } catch (Throwable t) {
                int line = stmt.getBegin().map(p -> p.line).orElse(0);
                result.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.WARNING,
                        "Failed to parse statement inside LLM try-block; preserving as UninterpretedStatement: "
                                + t.getClass().getSimpleName()
                                + (t.getMessage() == null ? "" : (": " + t.getMessage())),
                        line,
                        stmt.toString()));
                try {
                    testCase.addStatement(createUninterpretedStatementFromAst(stmt));
                } catch (Throwable ignored) {
                    // Keep going to salvage subsequent statements in the try block.
                }
            }
        }
    }

    // Internal API for helpers: preserve a full AST statement as raw Java source.
    UninterpretedStatement createUninterpretedStatementFromAst(com.github.javaparser.ast.stmt.Statement astStmt) {
        return createUninterpretedStatement(astStmt, astStmt.toString());
    }

    // Internal API for helpers: preserve an expression/statement node as raw Java source with bindings.
    UninterpretedStatement createUninterpretedStatement(
            com.github.javaparser.ast.Node bindingNode, String code) {
        return new UninterpretedStatement(testCase, code, collectBindings(bindingNode));
    }

    // Internal API for helpers: preserve typed raw Java source that still returns a named value.
    UninterpretedStatement createUninterpretedStatement(Type returnType,
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

    // Internal API for helpers: collect simple identifiers referenced by a synthesized/raw node.
    java.util.LinkedHashSet<String> collectReferencedSimpleNames(com.github.javaparser.ast.Node node) {
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
