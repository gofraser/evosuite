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

    public String render(TestCodeVisitor visitor) {
        return render(visitor, null);
    }

    public String render(TestCodeVisitor visitor, String catchVariableName) {
        String actual = resolveExceptionId(resolveExpression(actualExpression, visitor), catchVariableName);
        String expected = resolveExceptionId(resolveExpression(expectedExpression, visitor), catchVariableName);
        String delta = resolveExceptionId(resolveExpression(deltaExpression, visitor), catchVariableName);
        boolean arrayEquality = kind == TemplateAssertionKind.EQUALS
                && delta == null
                && (isArrayOperand(expectedExpression) || isArrayOperand(actualExpression));
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
        return expression.replaceAll("\\be0\\b", Matcher.quoteReplacement(catchVariableName));
    }

    private boolean isArrayOperand(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return false;
        }
        try {
            Expression parsed = StaticJavaParser.parseExpression(expression);
            if (parsed instanceof ArrayCreationExpr) {
                return true;
            }
            if (parsed instanceof NameExpr) {
                Integer position = bindings.get(((NameExpr) parsed).getNameAsString());
                if (position != null && statement != null && statement.getTestCase() != null
                        && position >= 0 && position < statement.getTestCase().size()) {
                    VariableReference variable = statement.getTestCase().getStatement(position).getReturnValue();
                    return variable != null && variable.getVariableClass().isArray();
                }
            }
        } catch (RuntimeException ignored) {
            return expression.trim().startsWith("new ") && expression.contains("[]");
        }
        return false;
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
