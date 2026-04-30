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

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.EqualsAssertion;
import org.evosuite.assertion.NullAssertion;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.assertion.SameAssertion;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

class AssertionParser {

    private static final Logger logger = LoggerFactory.getLogger(AssertionParser.class);

    private final DefaultTestCase testCase;
    private final TypeResolver typeResolver;
    private final VariableScope scope;
    private final ParseResult result;
    private final StatementParser parser;

    AssertionParser(DefaultTestCase testCase,
                    TypeResolver typeResolver,
                    VariableScope scope,
                    ParseResult result,
                    StatementParser parser) {
        this.testCase = testCase;
        this.typeResolver = typeResolver;
        this.scope = scope;
        this.result = result;
        this.parser = parser;
    }

    boolean shouldPreserveAssertionReturningDeclaration(Expression initializer) {
        if (!(initializer instanceof MethodCallExpr)) {
            return false;
        }
        String methodName = ((MethodCallExpr) initializer).getNameAsString();
        return "assertThrows".equals(methodName);
    }

    boolean isAssertionMethodName(String name) {
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

    void handleAssertStatement(AssertStmt assertStmt) {
        try {
            Expression condition = assertStmt.getCheck();
            VariableReference var = parser.handleExpression(
                    parser.nextSyntheticName("__assert_cond"), condition, boolean.class);
            if (var != null) {
                PrimitiveAssertion assertion = new PrimitiveAssertion();
                assertion.setSource(var);
                assertion.setValue(true);
                attachAssertionToSource(var, assertion);
            } else {
                parser.addError(assertStmt, DiagnosticKind.ASSERTION_CONDITION_UNRESOLVED,
                        condition.toString());
            }
        } catch (Exception e) {
            parser.addError(assertStmt, DiagnosticKind.PARSE_FAILURE,
                    "assert statement: " + e.getMessage());
        }
    }

    void handleAssertionCall(MethodCallExpr assertCall) {
        String name = assertCall.getNameAsString();
        List<Expression> args = assertCall.getArguments();
        boolean handled = false;
        int checkpointSize = testCase.size();
        List<MethodCallResolutionAttempt> methodCallResolutionAttempts = new ArrayList<>();

        try {
            switch (name) {
                case "assertTrue":
                    handled = handleAssertBoolean(args, true, methodCallResolutionAttempts);
                    break;
                case "assertFalse":
                    handled = handleAssertBoolean(args, false, methodCallResolutionAttempts);
                    break;
                case "assertNull":
                    handled = handleAssertNull(args, true, methodCallResolutionAttempts);
                    break;
                case "assertNotNull":
                    handled = handleAssertNull(args, false, methodCallResolutionAttempts);
                    break;
                case "assertEquals":
                    handled = handleAssertEquals(args, methodCallResolutionAttempts);
                    break;
                case "assertNotEquals":
                    handled = handleAssertNotEquals(args, methodCallResolutionAttempts);
                    break;
                case "assertSame":
                    handled = handleAssertSame(args, true, methodCallResolutionAttempts);
                    break;
                case "assertNotSame":
                    handled = handleAssertSame(args, false, methodCallResolutionAttempts);
                    break;
                case "assertArrayEquals":
                    handled = handleAssertArrayEquals(assertCall, args);
                    break;
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

        if (handled) {
            return;
        }

        if (parser.isMarkParsedFromLlm() && containsMemberAccess(args)) {
            parser.addWarning(assertCall, DiagnosticKind.ASSERTION_ARGUMENT_UNRESOLVED,
                    "Nested member access in assertion could not be safely preserved in LLM mode; dropping assertion");
            return;
        }

        boolean introducedTransientStatements = testCase.size() > checkpointSize;
        if (introducedTransientStatements) {
            parser.rollbackTo(checkpointSize);
        }

        if (parser.isMarkParsedFromLlm()
                && shouldDropAssertionPreservation(methodCallResolutionAttempts)) {
            return;
        }

        if (!validateAssertionArgumentNames(assertCall, args)) {
            return;
        }

        Expression preservedAssertion = normalizeAssertionExpressionForPreservation(assertCall);
        testCase.addStatement(parser.createUninterpretedStatement(
                assertCall, preservedAssertion.toString() + ";"));
    }

    private boolean containsMemberAccess(List<Expression> args) {
        for (Expression arg : args) {
            if (arg == null) {
                continue;
            }
            if (!arg.findAll(MethodCallExpr.class).isEmpty() || !arg.findAll(FieldAccessExpr.class).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    Expression normalizeAssertionExpressionForPreservation(Expression expression) {
        if (!(expression instanceof MethodCallExpr)) {
            return expression;
        }
        MethodCallExpr preserved = StatementParser.copySyntheticRange(
                ((MethodCallExpr) expression).clone(), expression);
        if ("assertThrows".equals(preserved.getNameAsString())) {
            normalizeAssertThrowsClassLiteral(preserved);
        }
        return preserved;
    }

    private boolean handleAssertBoolean(List<Expression> args,
                                        boolean expectedValue,
                                        List<MethodCallResolutionAttempt> methodCallResolutionAttempts) {
        if (args.isEmpty()) {
            return false;
        }
        Expression conditionExpr = args.size() == 1 ? args.get(0) : pickVariableArg(args);
        if (conditionExpr == null) {
            conditionExpr = args.get(args.size() - 1);
        }

        VariableReference sourceRef = resolveAssertionVariable(conditionExpr, methodCallResolutionAttempts);
        if (sourceRef == null) {
            return false;
        }

        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(sourceRef);
        assertion.setValue(expectedValue);
        attachAssertionToSource(sourceRef, assertion);
        return true;
    }

    private boolean handleAssertNull(List<Expression> args,
                                     boolean isNull,
                                     List<MethodCallResolutionAttempt> methodCallResolutionAttempts) {
        if (args.isEmpty()) {
            return false;
        }
        Expression objExpr = args.size() == 1 ? args.get(0) : pickVariableArg(args);
        if (objExpr == null) {
            objExpr = args.get(args.size() - 1);
        }

        VariableReference sourceRef = resolveAssertionVariable(objExpr, methodCallResolutionAttempts);
        if (sourceRef == null) {
            return false;
        }

        NullAssertion assertion = new NullAssertion();
        assertion.setSource(sourceRef);
        assertion.setValue(isNull);
        attachAssertionToSource(sourceRef, assertion);
        return true;
    }

    private boolean handleAssertEquals(List<Expression> args,
                                       List<MethodCallResolutionAttempt> methodCallResolutionAttempts) {
        AssertionArgs assertionArgs = AssertionArgs.split(args);
        if (assertionArgs == null) {
            return false;
        }

        VariableReference sourceRef = resolveAssertionVariable(assertionArgs.actual, methodCallResolutionAttempts);
        if (sourceRef == null) {
            return false;
        }

        Object expectedValue = extractLiteralValue(assertionArgs.expected);
        if (expectedValue == null) {
            return false;
        }
        Class<?> sourceClass = sourceRef.getVariableClass();
        if (sourceClass == boolean.class || sourceClass == Boolean.class) {
            Object coerced = coerceBooleanExpectedValue(expectedValue);
            if (coerced == null) {
                return false;
            }
            expectedValue = coerced;
        }
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(sourceRef);
        assertion.setValue(expectedValue);
        attachAssertionToSource(sourceRef, assertion);
        return true;
    }

    private boolean handleAssertNotEquals(List<Expression> args,
                                          List<MethodCallResolutionAttempt> methodCallResolutionAttempts) {
        AssertionArgs assertionArgs = AssertionArgs.split(args);
        if (assertionArgs == null) {
            return false;
        }

        VariableReference actualRef = resolveAssertionVariable(assertionArgs.actual, methodCallResolutionAttempts);
        if (actualRef == null) {
            return false;
        }

        Object expectedValue = extractLiteralValue(assertionArgs.expected);
        if (expectedValue != null) {
            return false;
        }

        VariableReference expectedRef = resolveAssertionVariable(assertionArgs.expected, methodCallResolutionAttempts);
        if (expectedRef == null) {
            return false;
        }

        EqualsAssertion assertion = new EqualsAssertion();
        assertion.setSource(actualRef);
        assertion.setDest(expectedRef);
        assertion.setValue(false);
        attachAssertionToSource(actualRef, assertion);
        return true;
    }

    private boolean handleAssertSame(List<Expression> args,
                                     boolean same,
                                     List<MethodCallResolutionAttempt> methodCallResolutionAttempts) {
        if (args.size() > 3) {
            return false;
        }
        AssertionArgs assertionArgs = AssertionArgs.split(args);
        if (assertionArgs == null) {
            return false;
        }

        VariableReference actualRef = resolveAssertionVariable(assertionArgs.actual, methodCallResolutionAttempts);
        VariableReference expectedRef = resolveAssertionVariable(assertionArgs.expected, methodCallResolutionAttempts);
        if (actualRef == null || expectedRef == null) {
            return false;
        }

        SameAssertion assertion = new SameAssertion();
        assertion.setSource(actualRef);
        assertion.setDest(expectedRef);
        assertion.setValue(same);
        attachAssertionToSource(actualRef, assertion);
        return true;
    }

    private static final class AssertionArgs {
        private final Expression expected;
        private final Expression actual;

        private AssertionArgs(Expression expected, Expression actual) {
            this.expected = expected;
            this.actual = actual;
        }

        private static AssertionArgs split(List<Expression> args) {
            if (args.size() < 2) {
                return null;
            }
            if (args.size() == 2) {
                return new AssertionArgs(args.get(0), args.get(1));
            }
            if (args.size() == 3) {
                if (args.get(0) instanceof StringLiteralExpr) {
                    return new AssertionArgs(args.get(1), args.get(2));
                }
                return new AssertionArgs(args.get(0), args.get(1));
            }
            if (args.size() == 4) {
                return new AssertionArgs(args.get(1), args.get(2));
            }
            return null;
        }
    }

    private boolean handleAssertArrayEquals(MethodCallExpr assertCall, List<Expression> args) {
        int checkpoint = testCase.size();
        for (Expression arg : args) {
            if (arg instanceof MethodCallExpr) {
                parser.handleMethodCall((MethodCallExpr) arg, null, null);
            }
        }
        if (!validateAssertionArgumentNames(assertCall, args)) {
            parser.rollbackTo(checkpoint);
            return true;
        }
        testCase.addStatement(parser.createUninterpretedStatement(assertCall, assertCall.toString() + ";"));
        return true;
    }

    private boolean validateAssertionArgumentNames(MethodCallExpr assertCall, List<Expression> args) {
        for (Expression arg : args) {
            for (String token : parser.collectReferencedSimpleNames(arg)) {
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
                if (parser.isMarkParsedFromLlm()) {
                    parser.addWarning(assertCall, DiagnosticKind.ASSERTION_ARGUMENT_UNRESOLVED,
                            token + " — dropping assertion in LLM best-effort mode");
                } else {
                    parser.addError(assertCall, DiagnosticKind.ASSERTION_ARGUMENT_UNRESOLVED, token);
                }
                return false;
            }
        }
        return true;
    }

    private boolean shouldDropAssertionPreservation(List<MethodCallResolutionAttempt> methodCallResolutionAttempts) {
        for (MethodCallResolutionAttempt attempt : methodCallResolutionAttempts) {
            if (attempt.hasAssertionBreakingDiagnostic(result.getDiagnostics())) {
                return true;
            }
        }
        return false;
    }

    private static final class MethodCallResolutionAttempt {
        private final String sourceSnippet;
        private final int diagnosticsStart;
        private final int diagnosticsEnd;

        private MethodCallResolutionAttempt(String sourceSnippet, int diagnosticsStart, int diagnosticsEnd) {
            this.sourceSnippet = sourceSnippet;
            this.diagnosticsStart = diagnosticsStart;
            this.diagnosticsEnd = diagnosticsEnd;
        }

        private boolean hasAssertionBreakingDiagnostic(List<ParseDiagnostic> diagnostics) {
            int upperBound = Math.min(diagnosticsEnd, diagnostics.size());
            for (int i = diagnosticsStart; i < upperBound; i++) {
                ParseDiagnostic diagnostic = diagnostics.get(i);
                if (sourceSnippet.equals(diagnostic.getSourceSnippet())) {
                    return true;
                }
            }
            return false;
        }
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
        if (!(parent instanceof FieldAccessExpr) || ((FieldAccessExpr) parent).getScope() != nameExpr) {
            return false;
        }
        FieldAccessExpr outermost = (FieldAccessExpr) parent;
        while (outermost.getParentNode().isPresent()) {
            com.github.javaparser.ast.Node ancestor = outermost.getParentNode().get();
            if (!(ancestor instanceof FieldAccessExpr) || ((FieldAccessExpr) ancestor).getScope() != outermost) {
                break;
            }
            outermost = (FieldAccessExpr) ancestor;
        }
        return parser.resolveClassFromExpression(outermost.getScope()) != null;
    }

    private boolean handleAssertThrows(List<Expression> args) {
        if (args.size() < 2) {
            return false;
        }

        if (result.getExpectedExceptionClass() == null) {
            for (Expression arg : args) {
                if (arg instanceof ClassExpr) {
                    result.setExpectedExceptionClass(resolveAssertionExceptionClassName((ClassExpr) arg));
                    break;
                }
            }
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
                parser.parseStatement(stmt);
            }
        } else {
            parser.parseStatement(body);
        }
        return true;
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
            ((ClassExpr) arg).setType(parser.parseType(resolvedTypeName, arg));
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

    private boolean handleAssertDoesNotThrow(List<Expression> args) {
        if (args.isEmpty()) {
            return false;
        }

        LambdaExpr lambda = null;
        for (Expression arg : args) {
            LambdaExpr extracted = extractLambdaExpr(arg);
            if (extracted != null) {
                lambda = extracted;
                break;
            }
        }

        if (lambda == null) {
            return false;
        }

        com.github.javaparser.ast.stmt.Statement body = lambda.getBody();
        if (body instanceof BlockStmt) {
            for (com.github.javaparser.ast.stmt.Statement stmt : ((BlockStmt) body).getStatements()) {
                parser.parseStatement(stmt);
            }
        } else {
            parser.parseStatement(body);
        }
        return true;
    }

    private LambdaExpr extractLambdaExpr(Expression expr) {
        Expression current = expr;
        while (current != null) {
            if (current instanceof LambdaExpr) {
                return (LambdaExpr) current;
            }
            if (current instanceof EnclosedExpr) {
                current = ((EnclosedExpr) current).getInner();
                continue;
            }
            if (current instanceof CastExpr) {
                current = ((CastExpr) current).getExpression();
                continue;
            }
            return null;
        }
        return null;
    }

    private Expression pickVariableArg(List<Expression> args) {
        for (Expression arg : args) {
            if (arg instanceof NameExpr && scope.isDefined(((NameExpr) arg).getNameAsString())) {
                return arg;
            }
        }
        return null;
    }

    private VariableReference resolveAssertionVariable(Expression expr,
                                                       List<MethodCallResolutionAttempt> methodCallResolutionAttempts) {
        if (expr instanceof NameExpr) {
            return scope.resolve(((NameExpr) expr).getNameAsString());
        }
        if (expr instanceof FieldAccessExpr) {
            return parser.handleExpression(parser.nextSyntheticName("__assert_field"), expr, Object.class);
        }
        if (expr instanceof MethodCallExpr) {
            MethodCallExpr methodCallExpr = (MethodCallExpr) expr;
            int diagnosticsStart = result.getDiagnostics().size();
            VariableReference resolved = parser.handleMethodCall(methodCallExpr, null, null);
            methodCallResolutionAttempts.add(new MethodCallResolutionAttempt(
                    methodCallExpr.toString(), diagnosticsStart, result.getDiagnostics().size()));
            return resolved;
        }
        return null;
    }

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
        } else if (expr instanceof FieldAccessExpr) {
            Object enumConstant = resolveEnumConstant((FieldAccessExpr) expr);
            if (enumConstant != null) {
                return enumConstant;
            }
        } else if (expr instanceof NameExpr) {
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object resolveEnumConstant(FieldAccessExpr expr) {
        Class<?> ownerClass = parser.resolveClassFromExpression(expr.getScope());
        if (ownerClass == null || !ownerClass.isEnum()) {
            return null;
        }
        try {
            return Enum.valueOf((Class<Enum>) ownerClass, expr.getNameAsString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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

    private void attachAssertionToSource(VariableReference sourceRef, Assertion assertion) {
        int pos = sourceRef.getStPosition();
        if (pos >= 0 && pos < testCase.size()) {
            testCase.getStatement(pos).addAssertion(assertion);
        }
    }

}
