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
package org.evosuite.assertion;

import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * {@link ContainsAssertion#getCode()} is called directly by several callers that never go
 * through {@link ContainsTraceObserver} (debug logging, mutation-based assertion generation, LLM
 * postprocessing's fallback candidate projection, a deserialized archive entry). It must defend
 * itself against being asked to render {@code .contains(...)} on a variable whose declared type
 * does not have that method, rather than handing back code that fails to compile.
 */
class ContainsAssertionTest {

    private VariableReference variable(Class<?> declaredVariableClass, String name) {
        VariableReference var = mock(VariableReference.class);
        doReturn(declaredVariableClass).when(var).getVariableClass();
        doReturn(name).when(var).getName();
        return var;
    }

    @Test
    void getCodeDropsTheAssertionWhenDeclaredTypeHasNoContainsMethod() {
        ContainsAssertion assertion = new ContainsAssertion();
        assertion.source = variable(Iterable.class, "iterable0");
        assertion.containedVariable = variable(String.class, "string0");

        assertEquals("", assertion.getCode(),
                "Iterable has no contains(Object); getCode() must not render a call to it");
    }

    @Test
    void getCodeStillRendersWhenDeclaredTypeIsACollection() {
        ContainsAssertion assertion = new ContainsAssertion();
        assertion.source = variable(List.class, "list0");
        assertion.containedVariable = variable(String.class, "string0");

        String code = assertion.getCode();
        assertTrue(code.contains("list0.contains(string0)"), code);
    }

    @Test
    void getCodeStillRendersForPlainCollectionDeclaration() {
        ContainsAssertion assertion = new ContainsAssertion();
        assertion.source = variable(Collection.class, "collection0");
        assertion.containedVariable = variable(String.class, "string0");

        String code = assertion.getCode();
        assertTrue(code.contains("collection0.contains(string0)"), code);
    }
}
