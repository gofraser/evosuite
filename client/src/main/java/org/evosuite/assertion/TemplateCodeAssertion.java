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
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.assertion;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.execution.ExecutableSnippetEngine;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical assertion template produced by unified LLM post-processing.
 *
 * <p>The template stores expressions with symbolic {@code vN} variable IDs and
 * position-based bindings. Rendering resolves those IDs through the active
 * {@link TestCodeVisitor}, so accepted LLM variable names are applied
 * consistently.</p>
 */
public class TemplateCodeAssertion extends Assertion {

    private static final long serialVersionUID = 1L;

    private static final Pattern EXCEPTION_ID = Pattern.compile("\\be0\\b");

    public enum Placement {
        END_OF_TEST,
        BEFORE_TRY,
        IN_CATCH,
        AFTER_CATCH
    }

    private final String assertionId;
    private final TemplateAssertionKind kind;
    private final String expectedExpression;
    private final String actualExpression;
    private final String deltaExpression;
    private final Map<String, Integer> bindings;
    private final String purpose;
    private final Placement placement;
    /**
     * Cached {@link #operandShape(String)} of the expected and actual expressions.
     */
    private transient Object expectedShape;
    private transient Object actualShape;

    /**
     * Creates an assertion that is placed at the end of the test.
     *
     * @param assertionId        the identifier of the assertion
     * @param kind               the kind of assertion
     * @param expectedExpression the expression for the expected value
     * @param actualExpression   the expression for the actual value
     * @param deltaExpression    the expression for the delta, may be null
     * @param bindings           the symbolic variable bindings
     * @param purpose            the purpose of the assertion
     */
    public TemplateCodeAssertion(String assertionId,
                                 Enum<?> kind,
                                 String expectedExpression,
                                 String actualExpression,
                                 String deltaExpression,
                                 Map<String, Integer> bindings,
                                 String purpose) {
        this(assertionId, kind, expectedExpression, actualExpression, deltaExpression, bindings,
                purpose, Placement.END_OF_TEST);
    }

    /**
     * Creates an assertion with an explicit placement.
     *
     * @param assertionId        the identifier of the assertion
     * @param kind               the kind of assertion
     * @param expectedExpression the expression for the expected value
     * @param actualExpression   the expression for the actual value
     * @param deltaExpression    the expression for the delta, may be null
     * @param bindings           the symbolic variable bindings
     * @param purpose            the purpose of the assertion
     * @param placement          where the assertion is placed in the test, null for the end of the test
     */
    public TemplateCodeAssertion(String assertionId,
                                 Enum<?> kind,
                                 String expectedExpression,
                                 String actualExpression,
                                 String deltaExpression,
                                 Map<String, Integer> bindings,
                                 String purpose,
                                 Placement placement) {
        this.assertionId = assertionId;
        this.kind = toTemplateKind(kind);
        this.expectedExpression = normalize(expectedExpression);
        this.actualExpression = normalize(actualExpression);
        this.deltaExpression = normalize(deltaExpression);
        this.bindings = bindings == null
                ? Collections.<String, Integer>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        this.purpose = purpose == null ? "" : purpose;
        this.placement = placement == null ? Placement.END_OF_TEST : placement;
        this.value = assertionId == null ? "" : assertionId;
    }

    public String getAssertionId() {
        return assertionId;
    }

    public TemplateAssertionKind getKind() {
        return kind;
    }

    public Map<String, Integer> getBindings() {
        return bindings;
    }

    public Placement getPlacement() {
        return placement;
    }

    public String getPurpose() {
        return purpose;
    }

    @Override
    public String getCode() {
        return render(null);
    }

    /**
     * Renders the assertion as source code.
     *
     * @param visitor the visitor that resolves variable names
     * @return the assertion source code
     */
    public String render(TestCodeVisitor visitor) {
        return render(visitor, null);
    }

    /**
     * Renders the assertion as source code, resolving exception identifiers against a catch variable.
     *
     * @param visitor           the visitor that resolves variable names
     * @param catchVariableName the name of the catch variable standing in for the exception, may be null
     * @return the assertion source code
     */
    public String render(TestCodeVisitor visitor, String catchVariableName) {
        String actual = resolveExceptionId(resolveExpression(actualExpression, visitor), catchVariableName);
        String expected = resolveExceptionId(resolveExpression(expectedExpression, visitor), catchVariableName);
        String delta = resolveExceptionId(resolveExpression(deltaExpression, visitor), catchVariableName);
        boolean arrayEquality = kind == TemplateAssertionKind.EQUALS
                && delta == null
                && (isArrayOperand(expectedShape()) || isArrayOperand(actualShape()));
        return CanonicalAssertionRenderer.forConfiguredFormat().render(kind, expected, actual, delta, arrayEquality);
    }

    @Override
    public Assertion copy(TestCase newTestCase, int offset) {
        Map<String, Integer> copiedBindings = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : bindings.entrySet()) {
            copiedBindings.put(entry.getKey(), entry.getValue() + offset);
        }
        TemplateCodeAssertion copy = new TemplateCodeAssertion(assertionId, kind,
                expectedExpression, actualExpression, deltaExpression, copiedBindings, purpose, placement);
        copy.comment = comment;
        copy.killedMutants.addAll(killedMutants);
        return copy;
    }

    @Override
    public boolean evaluate(Scope scope) {
        if (scope == null || statement == null || statement.getTestCase() == null) {
            return false;
        }
        try {
            Map<String, VariableReference> variableBindings = resolveBindings(statement.getTestCase());
            Map<String, Type> variableTypes = new LinkedHashMap<>();
            Map<String, Object> variableValues = new LinkedHashMap<>();
            for (Map.Entry<String, VariableReference> entry : variableBindings.entrySet()) {
                variableTypes.put(entry.getKey(), entry.getValue().getType());
                variableValues.put(entry.getKey(), entry.getValue().getObject(scope));
            }
            return ExecutableSnippetEngine.INSTANCE.evaluateAssertion(
                    render(null), variableTypes, variableValues);
        } catch (AssertionError assertionFailed) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public Set<VariableReference> getReferencedVariables() {
        if (statement == null || statement.getTestCase() == null) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(resolveBindings(statement.getTestCase()).values());
    }

    @Override
    public boolean isValid() {
        return assertionId != null && !assertionId.trim().isEmpty()
                && kind != null
                && actualExpression != null && !actualExpression.isEmpty()
                && bindings != null;
    }

    @Override
    public void changeClassLoader(ClassLoader loader) {
        // Template assertions store symbolic expressions, not classloader-owned expected values.
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, expectedExpression, actualExpression, deltaExpression, bindings, placement);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TemplateCodeAssertion)) {
            return false;
        }
        TemplateCodeAssertion other = (TemplateCodeAssertion) obj;
        return kind == other.kind
                && Objects.equals(expectedExpression, other.expectedExpression)
                && Objects.equals(actualExpression, other.actualExpression)
                && Objects.equals(deltaExpression, other.deltaExpression)
                && Objects.equals(bindings, other.bindings)
                && placement == other.placement;
    }

    public static Set<String> extractSymbolicVariables(String expression) {
        return SymbolicExpressionUtils.extractSymbolicVariables(expression);
    }

    private static TemplateAssertionKind toTemplateKind(Enum<?> kind) {
        return kind == null ? null : TemplateAssertionKind.valueOf(kind.name());
    }

    private String resolveExpression(String expression, TestCodeVisitor visitor) {
        if (expression == null) {
            return null;
        }
        if (visitor == null || statement == null || statement.getTestCase() == null) {
            return expression;
        }
        try {
            Expression parsed = StaticJavaParser.parseExpression(expression);
            for (NameExpr nameExpr : parsed.findAll(NameExpr.class)) {
                String id = nameExpr.getNameAsString();
                Integer position = bindings.get(id);
                if (position == null) {
                    continue;
                }
                VariableReference variable = statement.getTestCase().getStatement(position).getReturnValue();
                nameExpr.setName(visitor.getVariableName(variable));
            }
            return parsed.toString();
        } catch (RuntimeException ignored) {
            String rendered = expression;
            for (Map.Entry<String, Integer> entry : bindings.entrySet()) {
                VariableReference variable = statement.getTestCase().getStatement(entry.getValue()).getReturnValue();
                rendered = rendered.replaceAll("\\b" + Pattern.quote(entry.getKey()) + "\\b",
                        Matcher.quoteReplacement(visitor.getVariableName(variable)));
            }
            return rendered;
        }
    }

    private static String resolveExceptionId(String expression, String catchVariableName) {
        if (expression == null || catchVariableName == null) {
            return expression;
        }
        return EXCEPTION_ID.matcher(expression).replaceAll(Matcher.quoteReplacement(catchVariableName));
    }

    private boolean isArrayOperand(Object shape) {
        if (shape instanceof Boolean) {
            return (Boolean) shape;
        }
        Integer position = bindings.get((String) shape);
        if (position != null && statement != null && statement.getTestCase() != null
                && position >= 0 && position < statement.getTestCase().size()) {
            VariableReference variable = statement.getTestCase().getStatement(position).getReturnValue();
            return variable != null && variable.getVariableClass().isArray();
        }
        return false;
    }

    private Object expectedShape() {
        if (expectedShape == null) {
            expectedShape = operandShape(expectedExpression);
        }
        return expectedShape;
    }

    private Object actualShape() {
        if (actualShape == null) {
            actualShape = operandShape(actualExpression);
        }
        return actualShape;
    }

    /**
     * What parsing an operand tells about array equality, independent of the test it is bound
     * to: {@code Boolean} when that settles it, or the name a bare variable operand refers to.
     */
    private static Object operandShape(String expression) {
        if (expression == null || expression.isEmpty()) {
            return Boolean.FALSE;
        }
        try {
            Expression parsed = StaticJavaParser.parseExpression(expression);
            if (parsed instanceof ArrayCreationExpr) {
                return Boolean.TRUE;
            }
            if (parsed instanceof NameExpr) {
                return ((NameExpr) parsed).getNameAsString();
            }
            return Boolean.FALSE;
        } catch (RuntimeException ignored) {
            return expression.trim().startsWith("new ") && expression.contains("[]");
        }
    }

    private Map<String, VariableReference> resolveBindings(TestCase testCase) {
        Map<String, VariableReference> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : bindings.entrySet()) {
            int position = entry.getValue();
            if (position >= 0 && position < testCase.size()) {
                VariableReference variable = testCase.getStatement(position).getReturnValue();
                if (variable != null) {
                    resolved.put(entry.getKey(), variable);
                }
            }
        }
        return resolved;
    }

    private static String normalize(String expression) {
        return expression == null ? null : expression.trim();
    }
}
