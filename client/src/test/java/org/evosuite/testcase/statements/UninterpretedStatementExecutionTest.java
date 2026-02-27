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

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.execution.ExecutableSnippetEngine;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UninterpretedStatementExecutionTest {

    @Test
    void execute_updatesBoundVariableInScope() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference intRef = tc.addStatement(new IntPrimitiveStatement(tc, 1));

        Map<String, VariableReference> bindings = new LinkedHashMap<>();
        bindings.put("int0", intRef);
        UninterpretedStatement interpreted = new UninterpretedStatement(
                tc, "int0 = int0 + 5;", bindings);

        Scope scope = new Scope();
        intRef.setObject(scope, 1);
        Throwable thrown = interpreted.execute(scope, System.out);

        assertNull(thrown);
        assertEquals(6, intRef.getObject(scope));
    }

    @Test
    void execute_setsReturnValueWhenReturnExpressionProvided() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference intRef = tc.addStatement(new IntPrimitiveStatement(tc, 3));

        Map<String, VariableReference> bindings = new LinkedHashMap<>();
        bindings.put("int0", intRef);
        UninterpretedStatement interpreted = new UninterpretedStatement(
                tc, int.class, "int __tmp = int0 * 2;", bindings, "__tmp");

        Scope scope = new Scope();
        intRef.setObject(scope, 3);
        Throwable thrown = interpreted.execute(scope, System.out);

        assertNull(thrown);
        assertEquals(6, interpreted.getReturnValue().getObject(scope));
    }

    @Test
    void execute_compileFailure_updatesSnippetFailureMetrics() throws Exception {
        ExecutableSnippetEngine.INSTANCE.resetMetricsForTesting();

        DefaultTestCase tc = new DefaultTestCase();
        VariableReference intRef = tc.addStatement(new IntPrimitiveStatement(tc, 1));

        Map<String, VariableReference> bindings = new LinkedHashMap<>();
        bindings.put("int0", intRef);
        UninterpretedStatement interpreted = new UninterpretedStatement(
                tc, "int0 = ;", bindings);

        Scope scope = new Scope();
        intRef.setObject(scope, 1);
        Throwable thrown = interpreted.execute(scope, System.out);

        assertNotNull(thrown);
        assertEquals(1, ExecutableSnippetEngine.INSTANCE.getCompileFailures());
        assertEquals(1, ExecutableSnippetEngine.INSTANCE.getStatementExecutionFailures());
    }

    @Test
    void execute_userRuntimeFailure_doesNotIncrementEngineRuntimeFailureMetric() throws Exception {
        ExecutableSnippetEngine.INSTANCE.resetMetricsForTesting();

        DefaultTestCase tc = new DefaultTestCase();
        VariableReference intRef = tc.addStatement(new IntPrimitiveStatement(tc, 1));

        Map<String, VariableReference> bindings = new LinkedHashMap<>();
        bindings.put("int0", intRef);
        UninterpretedStatement interpreted = new UninterpretedStatement(
                tc, "int __x = 1 / 0;", bindings);

        Scope scope = new Scope();
        intRef.setObject(scope, 1);
        Throwable thrown = interpreted.execute(scope, System.out);

        assertNotNull(thrown);
        assertEquals(0, ExecutableSnippetEngine.INSTANCE.getCompileFailures());
        assertEquals(0, ExecutableSnippetEngine.INSTANCE.getRuntimeFailures());
        assertEquals(1, ExecutableSnippetEngine.INSTANCE.getStatementExecutionFailures());
    }

    @Test
    void generatedCode_bridgesReturnExpressionToEvoSuiteVariableName() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference intRef = tc.addStatement(new IntPrimitiveStatement(tc, 1));

        Map<String, VariableReference> bindings = new LinkedHashMap<>();
        bindings.put("int0", intRef);
        UninterpretedStatement statement = new UninterpretedStatement(
                tc, int.class, "int x = int0 + 1;", bindings, "x");
        tc.addStatement(statement);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("int x = int0 + 1;"));
        assertTrue(code.contains("int int1 = x;"));
        assertTrue(statement.isAssignmentStatement());
    }

    @Test
    void execute_canCompileAgainstEvoSuiteRuntimeClasspath() {
        DefaultTestCase tc = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(
                tc, "org.evosuite.runtime.util.Inputs.checkNull(\"ok\");");

        Scope scope = new Scope();
        Throwable thrown = statement.execute(scope, System.out);

        assertNull(thrown);
    }
}
