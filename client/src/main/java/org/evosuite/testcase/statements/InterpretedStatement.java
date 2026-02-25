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
package org.evosuite.testcase.statements;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericAccessibleObject;

import java.io.PrintStream;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A fallback statement that preserves raw Java source code verbatim.
 *
 * <p>When the test parser encounters a statement that cannot be mapped to a specific
 * EvoSuite statement type (e.g. complex expressions, try-catch blocks, custom
 * assertion patterns), it wraps the original source in an InterpretedStatement.
 *
 * <p>This statement cannot be executed by EvoSuite's internal test executor, but it
 * can be emitted back as Java source via TestCodeVisitor. This allows parsed tests
 * to preserve constructs that don't have a direct EvoSuite model, while still
 * being part of the TestCase structure.
 *
 * <p>InterpretedStatements are excluded from mutation/crossover by the genetic
 * algorithm (they are treated as immutable).
 */
public class InterpretedStatement extends AbstractStatement {

    private static final long serialVersionUID = 1L;

    /** The raw Java source code for this statement (one or more lines). */
    private final String sourceCode;

    /**
     * Creates an InterpretedStatement with a void return type.
     *
     * @param tc         the test case
     * @param sourceCode the raw Java source code
     */
    public InterpretedStatement(TestCase tc, String sourceCode) {
        this(tc, void.class, sourceCode);
    }

    /**
     * Creates an InterpretedStatement with a specified return type.
     *
     * @param tc         the test case
     * @param returnType the type of the value produced by this statement
     * @param sourceCode the raw Java source code
     */
    public InterpretedStatement(TestCase tc, Type returnType, String sourceCode) {
        super(tc, new VariableReferenceImpl(tc, returnType));
        this.sourceCode = Objects.requireNonNull(sourceCode);
    }

    public String getSourceCode() {
        return sourceCode;
    }

    @Override
    public Statement copy(TestCase newTestCase, int offset) {
        return new InterpretedStatement(newTestCase, retval.getType(), sourceCode);
    }

    @Override
    public Throwable execute(Scope scope, PrintStream out)
            throws IllegalArgumentException {
        // InterpretedStatements cannot be executed — they are source-only.
        // Return null (no exception) so the test case can still be "executed"
        // for the purposes of code generation, even though this statement
        // contributes no runtime behavior.
        return null;
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
        return refs;
    }

    @Override
    public boolean isAssignmentStatement() {
        return false;
    }

    @Override
    public void replace(VariableReference oldVar, VariableReference newVar) {
        if (retval.equals(oldVar)) {
            retval = newVar;
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
        InterpretedStatement that = (InterpretedStatement) s;
        return sourceCode.equals(that.sourceCode);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        InterpretedStatement that = (InterpretedStatement) o;
        return sourceCode.equals(that.sourceCode)
                && Objects.equals(retval, that.retval);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceCode, retval);
    }

    @Override
    public String toString() {
        return sourceCode;
    }
}
