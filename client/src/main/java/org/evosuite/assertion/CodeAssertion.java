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
import org.evosuite.testcase.execution.Scope;

/**
 * Assertion that holds a raw JUnit assertion code string. Used as a fallback
 * for LLM-generated assertions that cannot be parsed into typed Assertion
 * objects (e.g., assertions containing method calls like
 * {@code assertEquals("foo", obj.getName())}).
 *
 * <p>{@link #getCode()} returns the code verbatim. {@link #evaluate(Scope)}
 * always returns {@code true} since the assertion cannot be verified at runtime
 * without expression evaluation.
 */
public class CodeAssertion extends Assertion {

    private static final long serialVersionUID = 1L;

    private final String codeString;

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

    /**
     * Cannot evaluate raw code at runtime; always returns true.
     */
    @Override
    public boolean evaluate(Scope scope) {
        return true;
    }

    @Override
    public boolean isValid() {
        return source != null && codeString != null && !codeString.isEmpty();
    }

    public String getCodeString() {
        return codeString;
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
