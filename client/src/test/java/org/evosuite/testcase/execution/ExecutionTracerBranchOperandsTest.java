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

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ExecutionTracer#passedBranch(int, int, int, int, int)} records operand
 * hints only when {@link Properties#LLM_BRANCH_OPERAND_HINTS} is on.
 */
public class ExecutionTracerBranchOperandsTest {

    @AfterEach
    public void tearDown() {
        Properties.LLM_BRANCH_OPERAND_HINTS = false;
        ExecutionTracer.setCheckCallerThread(true);
        ExecutionTracer.getExecutionTracer().clear();
        ExecutionTracer.disable();
    }

    @Test
    public void testRecordsLastOperandsWhenPropertyOn() {
        Properties.LLM_BRANCH_OPERAND_HINTS = true;
        ExecutionTracer.enable();
        ExecutionTracer.setCheckCallerThread(false);
        ExecutionTracer.getExecutionTracer().clear();

        ExecutionTracer.passedBranch(5, 7, Opcodes.IF_ICMPLT, 42, 1);

        Map<Integer, BranchOperands> lastSeen =
                ExecutionTracer.getExecutionTracer().getTrace().getLastBranchOperands();
        assertEquals("5 vs 7 (IF_ICMPLT)", lastSeen.get(42).toString());
    }

    @Test
    public void testDoesNotRecordWhenPropertyOff() {
        Properties.LLM_BRANCH_OPERAND_HINTS = false;
        ExecutionTracer.enable();
        ExecutionTracer.setCheckCallerThread(false);
        ExecutionTracer.getExecutionTracer().clear();

        ExecutionTracer.passedBranch(5, 7, Opcodes.IF_ICMPLT, 42, 1);

        assertTrue(ExecutionTracer.getExecutionTracer().getTrace().getLastBranchOperands().isEmpty());
    }

    @Test
    public void testRecordsSingleIntOperand() {
        Properties.LLM_BRANCH_OPERAND_HINTS = true;
        ExecutionTracer.enable();
        ExecutionTracer.setCheckCallerThread(false);
        ExecutionTracer.getExecutionTracer().clear();

        ExecutionTracer.passedBranch(0, Opcodes.IFEQ, 7, 1);

        Map<Integer, BranchOperands> lastSeen =
                ExecutionTracer.getExecutionTracer().getTrace().getLastBranchOperands();
        assertEquals("0 (IFEQ)", lastSeen.get(7).toString());
    }

    @Test
    public void testRecordsTwoObjectOperandsThroughTheTrace() {
        Properties.LLM_BRANCH_OPERAND_HINTS = true;
        ExecutionTracer.enable();
        ExecutionTracer.setCheckCallerThread(false);
        ExecutionTracer.getExecutionTracer().clear();

        ExecutionTracer.passedBranch("left", "right", Opcodes.IF_ACMPEQ, 13, 1);

        Map<Integer, BranchOperands> lastSeen =
                ExecutionTracer.getExecutionTracer().getTrace().getLastBranchOperands();
        assertEquals("\"left\" vs \"right\" (IF_ACMPEQ)", lastSeen.get(13).toString());
    }

    @Test
    public void testRecordsSingleObjectOperandThroughTheTrace() {
        Properties.LLM_BRANCH_OPERAND_HINTS = true;
        ExecutionTracer.enable();
        ExecutionTracer.setCheckCallerThread(false);
        ExecutionTracer.getExecutionTracer().clear();

        ExecutionTracer.passedBranch(null, Opcodes.IFNULL, 21, 1);

        Map<Integer, BranchOperands> lastSeen =
                ExecutionTracer.getExecutionTracer().getTrace().getLastBranchOperands();
        assertEquals("null (IFNULL)", lastSeen.get(21).toString());
    }

    @Test
    public void testProxyDelegatesToTheUnderlyingTrace() {
        Properties.LLM_BRANCH_OPERAND_HINTS = true;
        ExecutionTracer.enable();
        ExecutionTracer.setCheckCallerThread(false);
        ExecutionTracer.getExecutionTracer().clear();

        ExecutionTracer.passedBranch(1, 2, Opcodes.IF_ICMPLE, 8, 1);
        ExecutionTrace proxy = ExecutionTracer.getExecutionTracer().getTrace();

        assertTrue(proxy instanceof ExecutionTraceProxy);
        assertEquals("1 vs 2 (IF_ICMPLE)", proxy.getLastBranchOperands().get(8).toString());
    }
}
