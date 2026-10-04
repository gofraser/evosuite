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

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionObserver;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * {@link ContainsTraceObserver} must only propose a {@code contains()} assertion when the
 * variable's *declared* (static) type actually exposes a {@code contains} method. A variable
 * declared {@code Iterable<String>} whose runtime value happens to be a {@code Collection} is
 * not enough: {@code Iterable} has no {@code contains}, so the rendered assertion would fail to
 * compile (see {@code ContainsAssertion#getCode()}).
 */
class ContainsTraceObserverTest {

    @AfterEach
    void tearDown() {
        ExecutionObserver.setCurrentTest(null);
    }

    @SuppressWarnings("unchecked")
    private VariableReference buildCollectionVariable(Class<?> declaredVariableClass) throws Exception {
        Collection<String> runtimeValue = new ArrayList<>(Collections.singletonList("x"));

        GenericClass<?> elementType = GenericClassFactory.get(String.class);
        GenericClass genericClass = mock(GenericClass.class);
        doReturn(Collections.singletonList(elementType)).when(genericClass).getParameterClasses();

        VariableReference var = mock(VariableReference.class);
        doReturn(runtimeValue).when(var).getObject(any());
        doReturn(declaredVariableClass).when(var).getVariableClass();
        doReturn(genericClass).when(var).getGenericClass();
        doReturn(0).when(var).getStPosition();
        return var;
    }

    private MethodStatement buildSingleStatementTest() {
        MethodStatement statement = mock(MethodStatement.class);
        doReturn(0).when(statement).getPosition();
        doReturn(Collections.emptySet()).when(statement).getVariableReferences();

        TestCase testCase = mock(TestCase.class);
        doReturn(statement).when(testCase).getStatement(0);
        ExecutionObserver.setCurrentTest(testCase);

        return statement;
    }

    @Test
    void declaredIterableWithCollectionRuntimeValueProducesNoTraceEntry() throws Exception {
        MethodStatement statement = buildSingleStatementTest();
        Scope scope = mock(Scope.class);
        doReturn(Collections.emptyList()).when(scope).getElements(any());

        VariableReference var = buildCollectionVariable(Iterable.class);

        ContainsTraceObserver observer = new ContainsTraceObserver();
        observer.visit(statement, scope, var);

        assertFalse(observer.getTrace().containsEntry(0, var),
                "Iterable<String> has no contains() method; no entry should be recorded even "
                        + "though the runtime object is a Collection");
    }

    @Test
    void declaredCollectionWithCollectionRuntimeValueStillProducesTraceEntry() throws Exception {
        MethodStatement statement = buildSingleStatementTest();
        Scope scope = mock(Scope.class);
        doReturn(Collections.emptyList()).when(scope).getElements(any());

        VariableReference var = buildCollectionVariable(Collection.class);

        ContainsTraceObserver observer = new ContainsTraceObserver();
        observer.visit(statement, scope, var);

        assertTrue(observer.getTrace().containsEntry(0, var),
                "Collection<String> exposes contains(); the fix must not disable the feature "
                        + "for variables that are legitimately typed as a Collection");
    }

    @Test
    void declaredListWithCollectionRuntimeValueStillProducesTraceEntry() throws Exception {
        MethodStatement statement = buildSingleStatementTest();
        Scope scope = mock(Scope.class);
        doReturn(Collections.emptyList()).when(scope).getElements(any());

        VariableReference var = buildCollectionVariable(List.class);

        ContainsTraceObserver observer = new ContainsTraceObserver();
        observer.visit(statement, scope, var);

        assertTrue(observer.getTrace().containsEntry(0, var),
                "List<String> exposes contains(); the fix must not disable the feature for "
                        + "variables that are legitimately typed as a List");
    }
}
