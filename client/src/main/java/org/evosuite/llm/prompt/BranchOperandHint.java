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
import org.evosuite.coverage.branch.OnlyBranchCoverageTestFitness;
import org.evosuite.coverage.cbranch.CBranchTestFitness;
import org.evosuite.coverage.ibranch.IBranchTestFitness;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.BranchOperands;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders one line telling the model what a near-miss test's last evaluation of a target branch
 * predicate actually compared, and what relation the target polarity still needs. A bare fitness
 * distance says "you are close" but not close to what; this hint gives the concrete values the
 * predicate saw so the model can change the one input that flips the branch instead of guessing.
 */
public final class BranchOperandHint {

    static final String LAST_EVALUATION_PREFIX = "Last evaluation: ";
    static final String VS = " vs ";
    static final String NEED_SEPARATOR = "; need ";
    static final String SAME_OBJECT_SUFFIX = " (same object)";
    static final String LEFT_WORD = "left";
    static final String RIGHT_WORD = "right";
    static final String ZERO_LITERAL = "0";
    static final String NULL_LITERAL = "null";

    /** Relation an opcode asks for when its jump is taken (the goal's TRUE polarity); negate for FALSE. */
    private static final Map<Integer, String> TRUE_RELATIONS = new HashMap<>();

    static {
        putRelation("==", Opcodes.IFEQ, Opcodes.IF_ICMPEQ, Opcodes.IF_ACMPEQ, Opcodes.IFNULL);
        putRelation("!=", Opcodes.IFNE, Opcodes.IF_ICMPNE, Opcodes.IF_ACMPNE, Opcodes.IFNONNULL);
        putRelation("<", Opcodes.IFLT, Opcodes.IF_ICMPLT);
        putRelation(">=", Opcodes.IFGE, Opcodes.IF_ICMPGE);
        putRelation(">", Opcodes.IFGT, Opcodes.IF_ICMPGT);
        putRelation("<=", Opcodes.IFLE, Opcodes.IF_ICMPLE);
    }

    private static void putRelation(String relation, int... opcodes) {
        for (int opcode : opcodes) {
            TRUE_RELATIONS.put(opcode, relation);
        }
    }

    private BranchOperandHint() {}

    /** The hint line for {@code goal} given the execution of {@code exemplar}, or null when there is nothing to say. */
    public static String forGoal(TestFitnessFunction goal, TestChromosome exemplar) {
        if (exemplar == null) {
            return null;
        }
        ExecutionResult result = exemplar.getLastExecutionResult();
        return forGoal(goal, result == null ? null : result.getTrace());
    }

    /** Same, from an already-obtained trace (null-safe). */
    public static String forGoal(TestFitnessFunction goal, ExecutionTrace trace) {
        if (!Properties.LLM_BRANCH_OPERAND_HINTS || trace == null) {
            return null;
        }
        Branch branch = branchOf(goal);
        if (branch == null || branch.isDefaultCase()) {
            return null;
        }
        BranchOperands operands = trace.getLastBranchOperands().get(branch.getActualBranchId());
        if (operands == null) {
            return null;
        }
        return render(operands, valueOf(goal), branch.getInstruction());
    }

    /** The branch of one of the four branch goal types, or null for any other goal (or a branchless-method root goal). */
    private static Branch branchOf(TestFitnessFunction goal) {
        return goal instanceof BranchCoverageTestFitness ? ((BranchCoverageTestFitness) goal).getBranch()
                : goal instanceof OnlyBranchCoverageTestFitness ? ((OnlyBranchCoverageTestFitness) goal).getBranch()
                : goal instanceof CBranchTestFitness ? ((CBranchTestFitness) goal).getBranch()
                : goal instanceof IBranchTestFitness ? ((IBranchTestFitness) goal).getBranch()
                : null;
    }

    /** Whether {@code goal}'s target polarity is the jump being taken; only meaningful once {@link #branchOf} is non-null. */
    private static boolean valueOf(TestFitnessFunction goal) {
        return goal instanceof BranchCoverageTestFitness ? ((BranchCoverageTestFitness) goal).getValue()
                : goal instanceof OnlyBranchCoverageTestFitness ? ((OnlyBranchCoverageTestFitness) goal).getValue()
                : goal instanceof CBranchTestFitness ? ((CBranchTestFitness) goal).getValue()
                : ((IBranchTestFitness) goal).getValue();
    }

    private static String render(BranchOperands operands, boolean truePolarity, BytecodeInstruction jump) {
        int opcode = operands.getOpcode();
        String relation = TRUE_RELATIONS.get(opcode);
        if (relation != null && !truePolarity) {
            relation = negate(relation);
        }
        StringBuilder sb = new StringBuilder(LAST_EVALUATION_PREFIX);
        if (operands.hasRight()) {
            renderTwoOperands(sb, operands, relation, opcode, jump);
        } else {
            renderOneOperand(sb, operands, relation, opcode, jump);
        }
        return sb.toString();
    }

    private static void renderTwoOperands(StringBuilder sb, BranchOperands operands, String relation, int opcode, BytecodeInstruction jump) {
        String leftName = nameFor(jump, 1);
        String rightName = nameFor(jump, 0);
        sb.append(display(leftName, operands.getLeft())).append(VS)
                .append(display(rightName, operands.getRight()))
                .append(" (").append(operands.getOpcodeName()).append(')');
        if (relation == null) {
            return;
        }
        // A named side states its name; an unnamed side states its value if the other side is
        // named (a concrete target), else a generic word (an unnamed past value is not stable).
        boolean anyName = leftName != null || rightName != null;
        String leftToken = leftName != null ? leftName : (anyName ? operands.getLeft() : LEFT_WORD);
        String rightToken = rightName != null ? rightName : (anyName ? operands.getRight() : RIGHT_WORD);
        sb.append(NEED_SEPARATOR).append(leftToken).append(' ').append(relation).append(' ').append(rightToken);
        if ((opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE) && "==".equals(relation)) {
            sb.append(SAME_OBJECT_SUFFIX);
        }
    }

    private static void renderOneOperand(StringBuilder sb, BranchOperands operands, String relation, int opcode, BytecodeInstruction jump) {
        String name = nameFor(jump, 0);
        sb.append(display(name, operands.getLeft())).append(" (").append(operands.getOpcodeName()).append(')');
        if (relation == null) {
            return;
        }
        String impliedRight = (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) ? NULL_LITERAL : ZERO_LITERAL;
        sb.append(NEED_SEPARATOR);
        if (name != null) {
            sb.append(name).append(' ');
        }
        sb.append(relation).append(' ').append(impliedRight);
    }

    private static String display(String name, String value) {
        return name == null ? value : name + "=" + value;
    }

    private static String negate(String relation) { // relation is always a TRUE_RELATIONS value, so "<=" falls through
        return "==".equals(relation) ? "!=" : "!=".equals(relation) ? "=="
                : "<".equals(relation) ? ">=" : ">=".equals(relation) ? "<"
                : ">".equals(relation) ? "<=" : ">";
    }

    /** Simple field name or {@code method()} for the operand's source instruction; null on anything else or failure. */
    private static String nameFor(BytecodeInstruction jump, int positionFromTop) {
        if (jump == null) {
            return null;
        }
        try {
            BytecodeInstruction source = jump.getSourceOfStackInstruction(positionFromTop);
            if (source == null) {
                return null;
            }
            if (source.isFieldUse()) {
                String variable = source.getVariableName();
                return variable == null ? null : variable.substring(variable.lastIndexOf('.') + 1);
            }
            if (source.isMethodCall()) {
                String called = source.getCalledMethodName();
                return called == null ? null : called + "()";
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
