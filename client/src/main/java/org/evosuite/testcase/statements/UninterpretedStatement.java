/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.statements;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutableSnippetEngine;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericAccessibleObject;

import java.io.PrintStream;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A fallback statement that preserves raw Java source code verbatim.
 *
 * <p>When the test parser encounters a statement that cannot be mapped to a specific
 * EvoSuite statement type (e.g. complex expressions, try-catch blocks, custom
 * assertion patterns), it wraps the original source in an UninterpretedStatement.
 *
 * <p>This statement is executed through the runtime snippet engine against the
 * current execution scope, and can also be emitted back as Java source via
 * TestCodeVisitor. This allows parsed tests to preserve constructs that don't
 * have a direct EvoSuite model while remaining executable.
 *
 * <p>UninterpretedStatements are excluded from mutation/crossover by the genetic
 * algorithm (they are treated as immutable).
 */
public class UninterpretedStatement extends AbstractStatement {

    private static final long serialVersionUID = 1L;

    /** The raw Java source code for this statement (one or more lines). */
    private final String sourceCode;
    /** Variable bindings captured at parse time: source-name -> EvoSuite variable. */
    private final Map<String, VariableReference> bindings;
    /** Optional expression to return from snippet execution. */
    private final String returnExpression;

    /**
     * Creates an UninterpretedStatement with a void return type.
     *
     * @param tc         the test case
     * @param sourceCode the raw Java source code
     */
    public UninterpretedStatement(TestCase tc, String sourceCode) {
        this(tc, void.class, sourceCode, new LinkedHashMap<String, VariableReference>(), null);
    }

    /**
     * Creates an UninterpretedStatement with a specified return type.
     *
     * @param tc         the test case
     * @param returnType the type of the value produced by this statement
     * @param sourceCode the raw Java source code
     */
    public UninterpretedStatement(TestCase tc, Type returnType, String sourceCode) {
        this(tc, returnType, sourceCode, new LinkedHashMap<String, VariableReference>(), null);
    }

    public UninterpretedStatement(TestCase tc, String sourceCode,
                                Map<String, VariableReference> bindings) {
        this(tc, void.class, sourceCode, bindings, null);
    }

    /**
     * Constructs an uninterpreted statement with a return type, source code,
     * variable bindings, and return expression.
     */
    public UninterpretedStatement(TestCase tc, Type returnType, String sourceCode,
                                Map<String, VariableReference> bindings,
                                String returnExpression) {
        super(tc, new VariableReferenceImpl(tc, returnType));
        this.sourceCode = Objects.requireNonNull(sourceCode);
        this.bindings = new LinkedHashMap<>(Objects.requireNonNull(bindings));
        this.returnExpression = returnExpression;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    /**
     * Returns the variable bindings captured at parse time.
     * Keys are original source-code variable names; values are the
     * corresponding EvoSuite VariableReferences.
     */
    public Map<String, VariableReference> getBindings() {
        return bindings;
    }

    public String getReturnExpression() {
        return returnExpression;
    }

    @Override
    public Statement copy(TestCase newTestCase, int offset) {
        Map<String, VariableReference> copiedBindings = new LinkedHashMap<>();
        for (Map.Entry<String, VariableReference> entry : bindings.entrySet()) {
            copiedBindings.put(entry.getKey(), entry.getValue().copy(newTestCase, offset));
        }
        Statement copy = new UninterpretedStatement(newTestCase, retval.getType(), sourceCode,
                copiedBindings, returnExpression);
        copyProvenanceFrom(copy, this);
        return copy;
    }

    @Override
    public Throwable execute(Scope scope, PrintStream out)
            throws IllegalArgumentException {
        try {
            Map<String, Type> bindingTypes = new LinkedHashMap<>();
            Map<String, Object> bindingValues = new LinkedHashMap<>();
            for (Map.Entry<String, VariableReference> entry : bindings.entrySet()) {
                bindingTypes.put(entry.getKey(), entry.getValue().getType());
                bindingValues.put(entry.getKey(), entry.getValue().getObject(scope));
            }

            ExecutableSnippetEngine.StatementResult result = ExecutableSnippetEngine.INSTANCE.executeStatement(
                    sourceCode, bindingTypes, bindingValues, returnExpression);

            for (Map.Entry<String, Object> entry : result.getUpdatedValues().entrySet()) {
                VariableReference reference = bindings.get(entry.getKey());
                if (reference != null) {
                    reference.setObject(scope, entry.getValue());
                }
            }

            if (returnExpression != null && !returnExpression.trim().isEmpty()) {
                retval.setObject(scope, result.getReturnValue());
            }
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Override
    public GenericAccessibleObject<?> getAccessibleObject() {
        return null;
    }

    @Override
    public String getCode() {
        return sourceCode;
    }

    @Override
    public Set<VariableReference> getVariableReferences() {
        Set<VariableReference> refs = new LinkedHashSet<>();
        refs.add(retval);
        refs.addAll(bindings.values());
        return refs;
    }

    @Override
    public boolean isAssignmentStatement() {
        return !retval.isVoid();
    }

    @Override
    public void replace(VariableReference oldVar, VariableReference newVar) {
        if (retval.equals(oldVar)) {
            retval = newVar;
        }
        for (Map.Entry<String, VariableReference> entry : bindings.entrySet()) {
            if (entry.getValue().equals(oldVar)) {
                entry.setValue(newVar);
            }
        }
    }

    @Override
    public boolean same(Statement s) {
        if (this == s) {
            return true;
        }
        if (s == null || getClass() != s.getClass()) {
            return false;
        }
        UninterpretedStatement that = (UninterpretedStatement) s;
        return sourceCode.equals(that.sourceCode)
                && Objects.equals(returnExpression, that.returnExpression);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UninterpretedStatement that = (UninterpretedStatement) o;
        return sourceCode.equals(that.sourceCode)
                && Objects.equals(bindings, that.bindings)
                && Objects.equals(returnExpression, that.returnExpression)
                && Objects.equals(retval, that.retval);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceCode, bindings, returnExpression, retval);
    }

    @Override
    public String toString() {
        return sourceCode;
    }
}
