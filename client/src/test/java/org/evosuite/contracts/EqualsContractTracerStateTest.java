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
package org.evosuite.contracts;

import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EqualsContractTracerStateTest {

    private boolean tracerWasEnabled;
    private Scope scope;
    private Collection<Contract.Pair<VariableReference>> pairs;

    @BeforeEach
    void setUp() {
        tracerWasEnabled = ExecutionTracer.isEnabled();
        VariableReference first = mock(VariableReference.class);
        VariableReference second = mock(VariableReference.class);
        scope = mock(Scope.class);
        when(scope.getObject(first)).thenReturn(new String("a"));
        when(scope.getObject(second)).thenReturn(new String("a"));
        pairs = Collections.singletonList(new Contract.Pair<>(first, second));
    }

    @AfterEach
    void tearDown() {
        if (tracerWasEnabled) {
            ExecutionTracer.enable();
        } else {
            ExecutionTracer.disable();
        }
    }

    private Contract symmetricContract() {
        return new EqualsSymmetricContract() {
            @Override
            protected Collection<Pair<VariableReference>> getAllVariablePairs(Scope s) {
                return pairs;
            }
        };
    }

    private Contract hashcodeContract() {
        return new EqualsHashcodeContract() {
            @Override
            protected Collection<Pair<VariableReference>> getAllVariablePairs(Scope s) {
                return pairs;
            }
        };
    }

    @Test
    void symmetricContractKeepsDisabledTracerDisabled() {
        ExecutionTracer.disable();

        assertNull(symmetricContract().check(mock(Statement.class), scope, null));

        assertFalse(ExecutionTracer.isEnabled());
    }

    @Test
    void hashcodeContractKeepsDisabledTracerDisabled() {
        ExecutionTracer.disable();

        assertNull(hashcodeContract().check(mock(Statement.class), scope, null));

        assertFalse(ExecutionTracer.isEnabled());
    }

    @Test
    void contractsLeaveEnabledTracerEnabled() {
        ExecutionTracer.enable();

        assertNull(symmetricContract().check(mock(Statement.class), scope, null));
        assertNull(hashcodeContract().check(mock(Statement.class), scope, null));

        assertTrue(ExecutionTracer.isEnabled());
    }
}
