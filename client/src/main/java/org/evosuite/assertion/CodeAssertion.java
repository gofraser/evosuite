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
package org.evosuite.assertion;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.execution.ExecutableSnippetEngine;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assertion that holds a raw JUnit assertion code string. Used as a fallback
 * for LLM-generated assertions that cannot be parsed into typed Assertion
 * objects (e.g., assertions containing method calls like
 * {@code assertEquals("foo", obj.getName())}).
 *
 * <p>{@link #getCode()} returns the code verbatim. {@link #evaluate(Scope)}
 * executes the assertion via an in-memory compiled snippet.
 */
public class CodeAssertion extends Assertion {

    private static final long serialVersionUID = 1L;

    private final String codeString;
    private transient Map<String, VariableReference> cachedBindings;
    private transient TestCase cachedBindingsTestCase;
    private transient int cachedBindingsTestSize = -1;

    /**
     * @param codeString the raw JUnit assertion code (e.g. {@code assertEquals(42, result);})
     */
    public CodeAssertion(String codeString) {
        this.codeString = codeString;
        // Set value to the code string so the default isValid() check (source != null && value != null) passes
        this.value = codeString;
    }

    @Override
    public String getCode() {
        return codeString;
    }

    @Override
    public Assertion copy(TestCase newTestCase, int offset) {
        CodeAssertion copy = new CodeAssertion(codeString);
        if (source != null) {
            copy.source = source.copy(newTestCase, offset);
        }
        copy.comment = comment;
        copy.killedMutants.addAll(killedMutants);
        return copy;
    }

    @Override
    public boolean evaluate(Scope scope) {
        if (scope == null) {
            return false;
        }
        try {
            Map<String, VariableReference> varBindings = buildVariableBindings();
            Map<String, Type> variableTypes = new LinkedHashMap<>();
            Map<String, Object> variableValues = new LinkedHashMap<>();
            for (Map.Entry<String, VariableReference> entry : varBindings.entrySet()) {
                VariableReference ref = entry.getValue();
                variableTypes.put(entry.getKey(), ref.getType());
                variableValues.put(entry.getKey(), ref.getObject(scope));
            }
            return ExecutableSnippetEngine.INSTANCE.evaluateAssertion(
                    codeString, variableTypes, variableValues);
        } catch (AssertionError assertionFailed) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isValid() {
        return source != null && codeString != null && !codeString.isEmpty();
    }

    public String getCodeString() {
        return codeString;
    }

    private Map<String, VariableReference> buildVariableBindings() {
        if (cachedBindings != null
                && cachedBindingsTestCase != null
                && statement != null
                && statement.getTestCase() == cachedBindingsTestCase
                && cachedBindingsTestCase.size() == cachedBindingsTestSize) {
            return cachedBindings;
        }

        Map<String, VariableReference> map = new LinkedHashMap<>();
        if (statement == null || statement.getTestCase() == null) {
            if (source != null) {
                map.put("var" + source.getStPosition(), source);
            }
            return map;
        }

        TestCase tc = statement.getTestCase();
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        for (int i = 0; i < tc.size(); i++) {
            VariableReference ref = tc.getStatement(i).getReturnValue();
            if (ref == null) {
                continue;
            }
            String varName = visitor.getVariableName(ref);
            if (varName != null && !varName.isEmpty()) {
                map.put(varName, ref);
            }
        }
        if (source != null && !map.containsValue(source)) {
            String sourceName = visitor.getVariableName(source);
            if (sourceName != null && !sourceName.isEmpty()) {
                map.put(sourceName, source);
            }
        }
        cachedBindings = map;
        cachedBindingsTestCase = tc;
        cachedBindingsTestSize = tc.size();
        return map;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (codeString != null ? codeString.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!super.equals(obj)) return false;
        if (getClass() != obj.getClass()) return false;
        CodeAssertion other = (CodeAssertion) obj;
        return codeString != null ? codeString.equals(other.codeString) : other.codeString == null;
    }
}
