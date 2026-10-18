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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.cbranch.CBranchTestFitness;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.setup.CallContext;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.BranchOperands;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins down the last-evaluation wording {@link BranchOperandHint} builds from a recorded
 * {@link BranchOperands} and a branch goal's target polarity.
 */
class BranchOperandHintTest {

    private boolean originalFlag;

    @BeforeEach
    void enableHints() {
        originalFlag = Properties.LLM_BRANCH_OPERAND_HINTS;
        Properties.LLM_BRANCH_OPERAND_HINTS = true;
    }

    @AfterEach
    void restoreFlag() {
        Properties.LLM_BRANCH_OPERAND_HINTS = originalFlag;
    }

    private static Branch branch(int id, boolean isDefault, BytecodeInstruction instruction) {
        Branch branch = mock(Branch.class);
        when(branch.getActualBranchId()).thenReturn(id);
        when(branch.isDefaultCase()).thenReturn(isDefault);
        when(branch.getInstruction()).thenReturn(instruction);
        return branch;
    }

    private static BranchCoverageTestFitness goal(Branch branch, boolean value) {
        BranchCoverageTestFitness goal = mock(BranchCoverageTestFitness.class);
        when(goal.getBranch()).thenReturn(branch);
        when(goal.getValue()).thenReturn(value);
        return goal;
    }

    private static CBranchTestFitness cBranchGoal(Branch branch, boolean value, CallContext context) {
        CBranchTestFitness goal = mock(CBranchTestFitness.class);
        when(goal.getBranch()).thenReturn(branch);
        when(goal.getValue()).thenReturn(value);
        when(goal.getContext()).thenReturn(context);
        return goal;
    }

    private static Branch switchCaseBranch(int id, BytecodeInstruction instruction) {
        Branch branch = mock(Branch.class);
        when(branch.getActualBranchId()).thenReturn(id);
        when(branch.isDefaultCase()).thenReturn(false);
        when(branch.isSwitchCaseBranch()).thenReturn(true);
        when(branch.getInstruction()).thenReturn(instruction);
        return branch;
    }

    private static BytecodeInstruction compareResultSource(int compareOpcode) {
        // A real (concrete) ASM node rather than a mock: AbstractInsnNode is not mock-friendly,
        // and a plain InsnNode already reports its opcode without any stubbing.
        BytecodeInstruction source = mock(BytecodeInstruction.class);
        when(source.getASMNode()).thenReturn(new InsnNode(compareOpcode));
        return source;
    }

    private static ExecutionTrace traceWith(int branchId, BranchOperands operands) {
        Map<Integer, BranchOperands> byBranch = new HashMap<>();
        byBranch.put(branchId, operands);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getLastBranchOperands()).thenReturn(byBranch);
        when(trace.getLastBranchOperands(anyInt())).thenAnswer(inv -> byBranch.get(inv.<Integer>getArgument(0)));
        return trace;
    }

    private static BytecodeInstruction fieldUseSource(String qualifiedFieldName) {
        BytecodeInstruction source = mock(BytecodeInstruction.class);
        when(source.isFieldUse()).thenReturn(true);
        when(source.getVariableName()).thenReturn(qualifiedFieldName);
        return source;
    }

    private static BytecodeInstruction methodCallSource(String methodName) {
        BytecodeInstruction source = mock(BytecodeInstruction.class);
        when(source.isMethodCall()).thenReturn(true);
        when(source.getCalledMethodName()).thenReturn(methodName);
        return source;
    }

    // ---- gating --------------------------------------------------------------

    @Test
    void propertyOffReturnsNull() {
        Properties.LLM_BRANCH_OPERAND_HINTS = false;
        Branch branch = branch(1, false, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(1, 2, Opcodes.IF_ICMPLT));

        assertNull(BranchOperandHint.forGoal(goal(branch, true), trace));
    }

    @Test
    void nonBranchGoalReturnsNull() {
        TestFitnessFunction other = mock(TestFitnessFunction.class);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(1, 2, Opcodes.IF_ICMPLT));

        assertNull(BranchOperandHint.forGoal(other, trace));
    }

    @Test
    void branchGoalWithoutTraceEntryReturnsNull() {
        Branch branch = branch(5, false, null);
        ExecutionTrace trace = traceWith(6, BranchOperands.ofInts(1, 2, Opcodes.IF_ICMPLT));

        assertNull(BranchOperandHint.forGoal(goal(branch, true), trace));
    }

    @Test
    void switchDefaultCaseReturnsNull() {
        Branch branch = branch(1, true, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(1, 2, Opcodes.IF_ICMPEQ));

        assertNull(BranchOperandHint.forGoal(goal(branch, true), trace));
    }

    @Test
    void nullExemplarReturnsNull() {
        assertNull(BranchOperandHint.forGoal(goal(branch(1, false, null), true), (TestChromosome) null));
    }

    @Test
    void exemplarWithoutExecutionResultReturnsNull() {
        TestChromosome exemplar = mock(TestChromosome.class);
        when(exemplar.getLastExecutionResult()).thenReturn(null);

        assertNull(BranchOperandHint.forGoal(goal(branch(1, false, null), true), exemplar));
    }

    @Test
    void exemplarDelegatesToItsTrace() {
        Branch branch = branch(1, false, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(1, 0, Opcodes.IF_ICMPGT));
        ExecutionResult result = mock(ExecutionResult.class);
        when(result.getTrace()).thenReturn(trace);
        TestChromosome exemplar = mock(TestChromosome.class);
        when(exemplar.getLastExecutionResult()).thenReturn(result);

        assertEquals("Last evaluation: 1 vs 0 (IF_ICMPGT); need left > right",
                BranchOperandHint.forGoal(goal(branch, true), exemplar));
    }

    // ---- two-operand int -------------------------------------------------------

    @Test
    void twoOperandIntTruePolarityUsesGenericWordsWithoutNames() {
        Branch branch = branch(1, false, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(50, 7, Opcodes.IF_ICMPLT));

        assertEquals("Last evaluation: 50 vs 7 (IF_ICMPLT); need left < right",
                BranchOperandHint.forGoal(goal(branch, true), trace));
    }

    @Test
    void twoOperandIntFalsePolarityNegatesTheRelation() {
        Branch branch = branch(1, false, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(50, 0, Opcodes.IF_ICMPLE));

        assertEquals("Last evaluation: 50 vs 0 (IF_ICMPLE); need left > right",
                BranchOperandHint.forGoal(goal(branch, false), trace));
    }

    // ---- one-operand int -------------------------------------------------------

    @Test
    void oneOperandIntFalsePolarityOfIfeqNegatesToNotEqual() {
        Branch branch = branch(1, false, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInt(0, Opcodes.IFEQ));

        assertEquals("Last evaluation: 0 (IFEQ); need != 0",
                BranchOperandHint.forGoal(goal(branch, false), trace));
    }

    // ---- reference compares and null checks -------------------------------------

    @Test
    void ifAcmpEqTruePolarityAddsSameObjectSuffix() {
        Branch branch = branch(1, false, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofObjects("abc", null, Opcodes.IF_ACMPEQ));

        assertEquals("Last evaluation: \"abc\" vs null (IF_ACMPEQ); need left == right (same object)",
                BranchOperandHint.forGoal(goal(branch, true), trace));
    }

    @Test
    void ifAcmpEqFalsePolarityHasNoSameObjectSuffix() {
        Branch branch = branch(1, false, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofObjects("abc", null, Opcodes.IF_ACMPEQ));

        assertEquals("Last evaluation: \"abc\" vs null (IF_ACMPEQ); need left != right",
                BranchOperandHint.forGoal(goal(branch, false), trace));
    }

    @Test
    void ifNonNullFalsePolarityChecksForNull() {
        Branch branch = branch(1, false, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofObject(null, Opcodes.IFNONNULL));

        assertEquals("Last evaluation: null (IFNONNULL); need == null",
                BranchOperandHint.forGoal(goal(branch, false), trace));
    }

    // ---- operand names -----------------------------------------------------------

    @Test
    void fieldUseSourceNamesTheOperand() {
        BytecodeInstruction jump = mock(BytecodeInstruction.class);
        BytecodeInstruction source = fieldUseSource("com.example.Foo.price");
        when(jump.getSourceOfStackInstruction(0)).thenReturn(source);
        Branch branch = branch(1, false, jump);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInt(50, Opcodes.IFLE));

        assertEquals("Last evaluation: price=50 (IFLE); need price <= 0",
                BranchOperandHint.forGoal(goal(branch, true), trace));
    }

    @Test
    void methodCallSourceNamesTheOperand() {
        BytecodeInstruction jump = mock(BytecodeInstruction.class);
        BytecodeInstruction source = methodCallSource("size");
        when(jump.getSourceOfStackInstruction(1)).thenReturn(source);
        Branch branch = branch(1, false, jump);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(3, 5, Opcodes.IF_ICMPLT));

        assertEquals("Last evaluation: size()=3 vs 5 (IF_ICMPLT); need size() < 5",
                BranchOperandHint.forGoal(goal(branch, true), trace));
    }

    @Test
    void sourceLookupFailureStillYieldsAnUnnamedHint() {
        BytecodeInstruction jump = mock(BytecodeInstruction.class);
        when(jump.getSourceOfStackInstruction(0)).thenThrow(new IllegalStateException("no CFG frame"));
        Branch branch = branch(1, false, jump);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInt(0, Opcodes.IFEQ));

        assertEquals("Last evaluation: 0 (IFEQ); need != 0",
                BranchOperandHint.forGoal(goal(branch, false), trace));
    }

    // ---- switch-case branches -----------------------------------------------------

    @Test
    void switchCaseNamesOnlyTheSelectorNotTheCaseConstant() {
        BytecodeInstruction jump = mock(BytecodeInstruction.class);
        BytecodeInstruction selectorSource = fieldUseSource("com.example.Foo.code");
        when(jump.getSourceOfStackInstruction(0)).thenReturn(selectorSource);
        Branch branch = switchCaseBranch(1, jump);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(3, 5, Opcodes.IF_ICMPEQ));

        assertEquals("Last evaluation: code=3 vs 5 (IF_ICMPEQ); need code == 5",
                BranchOperandHint.forGoal(goal(branch, true), trace));
        assertEquals("Last evaluation: code=3 vs 5 (IF_ICMPEQ); need code != 5",
                BranchOperandHint.forGoal(goal(branch, false), trace));
    }

    @Test
    void switchCaseWithoutResolvableSelectorNameUsesGenericWords() {
        BytecodeInstruction jump = mock(BytecodeInstruction.class);
        Branch branch = switchCaseBranch(1, jump);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(3, 5, Opcodes.IF_ICMPEQ));

        assertEquals("Last evaluation: 3 vs 5 (IF_ICMPEQ); need left == right",
                BranchOperandHint.forGoal(goal(branch, true), trace));
    }

    // ---- context-sensitive goals ----------------------------------------------------

    @Test
    void cBranchWithNonEmptyContextReturnsNull() {
        Branch branch = branch(1, false, null);
        CallContext context = new CallContext("com.example.Caller", "caller()V");
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(1, 2, Opcodes.IF_ICMPLT));

        assertNull(BranchOperandHint.forGoal(cBranchGoal(branch, true, context), trace));
    }

    @Test
    void cBranchWithEmptyContextStillYieldsAHint() {
        Branch branch = branch(1, false, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(1, 2, Opcodes.IF_ICMPLT));

        assertEquals("Last evaluation: 1 vs 2 (IF_ICMPLT); need left < right",
                BranchOperandHint.forGoal(cBranchGoal(branch, true, new CallContext()), trace));
    }

    // ---- long/float/double compare sentinel ------------------------------------------

    @Test
    void longCompareSentinelYieldsNoHint() {
        BytecodeInstruction jump = mock(BytecodeInstruction.class);
        BytecodeInstruction source = compareResultSource(Opcodes.LCMP);
        when(jump.getSourceOfStackInstruction(0)).thenReturn(source);
        Branch branch = branch(1, false, jump);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInt(1, Opcodes.IFLE));

        assertNull(BranchOperandHint.forGoal(goal(branch, true), trace));
    }

    @Test
    void oneOperandFromAFieldStillYieldsAHint() {
        BytecodeInstruction jump = mock(BytecodeInstruction.class);
        BytecodeInstruction source = fieldUseSource("com.example.Foo.count");
        when(jump.getSourceOfStackInstruction(0)).thenReturn(source);
        Branch branch = branch(1, false, jump);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInt(1, Opcodes.IFLE));

        assertEquals("Last evaluation: count=1 (IFLE); need count <= 0",
                BranchOperandHint.forGoal(goal(branch, true), trace));
    }

    // ---- unsupported opcode --------------------------------------------------------

    @Test
    void unknownOpcodeYieldsNoNeedClause() {
        Branch branch = branch(1, false, null);
        ExecutionTrace trace = traceWith(1, BranchOperands.ofInts(1, 2, Opcodes.GOTO));

        String hint = BranchOperandHint.forGoal(goal(branch, true), trace);

        assertEquals("Last evaluation: 1 vs 2 (GOTO)", hint);
        assertFalse(hint.contains("need"));
    }
}
