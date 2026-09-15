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
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the recording of {@link BranchOperands} in {@link ExecutionTraceImpl} and its
 * propagation through {@link ExecutionTraceImpl#clear()}, {@link ExecutionTraceImpl#clone()}
 * and {@link ExecutionTraceProxy}.
 */
public class BranchOperandRecordingTest {

    @Test
    public void testKeepsOnlyLastEvaluationPerBranch() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();

        impl.branchOperandsSeen(42, BranchOperands.ofInts(1, 2, Opcodes.IF_ICMPLE));
        impl.branchOperandsSeen(42, BranchOperands.ofInts(50, 0, Opcodes.IF_ICMPLE));

        Map<Integer, BranchOperands> lastSeen = impl.getLastBranchOperands();
        assertEquals(1, lastSeen.size());
        assertEquals("50 vs 0 (IF_ICMPLE)", lastSeen.get(42).toString());
    }

    @Test
    public void testClearEmptiesTheMap() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.branchOperandsSeen(1, BranchOperands.ofInt(0, Opcodes.IFEQ));

        impl.clear();

        assertTrue(impl.getLastBranchOperands().isEmpty());
    }

    @Test
    public void testCloneCarriesOperandsAndIsIndependent() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.branchOperandsSeen(7, BranchOperands.ofObject(null, Opcodes.IFNULL));

        ExecutionTraceImpl clone = impl.clone();
        assertEquals("null (IFNULL)", clone.getLastBranchOperands().get(7).toString());

        clone.branchOperandsSeen(7, BranchOperands.ofObject("x", Opcodes.IFNONNULL));
        // the original must not see the clone's later update
        assertEquals("null (IFNULL)", impl.getLastBranchOperands().get(7).toString());
    }

    @Test
    public void testProxyDelegatesRecordingAndReading() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        ExecutionTraceProxy proxy = new ExecutionTraceProxy(impl);

        proxy.branchOperandsSeen(3, BranchOperands.ofInts(9, 9, Opcodes.IF_ICMPEQ));

        assertEquals("9 vs 9 (IF_ICMPEQ)", proxy.getLastBranchOperands().get(3).toString());
        assertEquals("9 vs 9 (IF_ICMPEQ)", impl.getLastBranchOperands().get(3).toString());
    }

    @Test
    public void testLazyCloneExposesIndependentOperandMapAfterCopyOnWrite() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.branchOperandsSeen(3, BranchOperands.ofInts(1, 1, Opcodes.IF_ICMPEQ));

        ExecutionTraceProxy proxy1 = new ExecutionTraceProxy(impl);
        ExecutionTraceProxy proxy2 = (ExecutionTraceProxy) proxy1.lazyClone();
        assertEquals("1 vs 1 (IF_ICMPEQ)", proxy2.getLastBranchOperands().get(3).toString());

        // mutating proxy1 triggers copy-on-write, detaching it from the shared impl
        proxy1.branchOperandsSeen(3, BranchOperands.ofInts(5, 5, Opcodes.IF_ICMPEQ));

        assertEquals("5 vs 5 (IF_ICMPEQ)", proxy1.getLastBranchOperands().get(3).toString());
        assertEquals("1 vs 1 (IF_ICMPEQ)", proxy2.getLastBranchOperands().get(3).toString());
    }
}
