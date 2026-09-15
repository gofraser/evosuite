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

/**
 * Mutable, reusable holder for the operands of the most recent evaluation of one branch
 * predicate. {@link ExecutionTraceImpl} keeps one of these per branch id and overwrites it in
 * place on every evaluation, so recording an int comparison (the overwhelming majority of
 * branches) writes a few fields and allocates nothing. Only an object comparison allocates,
 * for the bounded text {@link BranchOperands#render(Object)} produces.
 *
 * <p>An immutable {@link BranchOperands} snapshot is built from a slot only on demand, when a
 * prompt is assembled — never on the execution hot path.</p>
 */
final class BranchOperandSlot {

    static final byte INTS = 0;
    static final byte INT = 1;
    static final byte OBJECTS = 2;
    static final byte OBJECT = 3;

    private int opcode;
    private int leftInt;
    private int rightInt;
    private String leftText;
    private String rightText;
    private byte shape;

    void setInts(int left, int right, int opcode) {
        this.leftInt = left;
        this.rightInt = right;
        this.opcode = opcode;
        this.shape = INTS;
    }

    void setInt(int value, int opcode) {
        this.leftInt = value;
        this.opcode = opcode;
        this.shape = INT;
    }

    void setObjects(Object left, Object right, int opcode) {
        this.leftText = BranchOperands.render(left);
        this.rightText = BranchOperands.render(right);
        this.opcode = opcode;
        this.shape = OBJECTS;
    }

    void setObject(Object value, int opcode) {
        this.leftText = BranchOperands.render(value);
        this.opcode = opcode;
        this.shape = OBJECT;
    }

    /** Every field is a primitive or an immutable String, so a field-by-field copy is a deep copy. */
    BranchOperandSlot copy() {
        BranchOperandSlot copy = new BranchOperandSlot();
        copy.opcode = opcode;
        copy.leftInt = leftInt;
        copy.rightInt = rightInt;
        copy.leftText = leftText;
        copy.rightText = rightText;
        copy.shape = shape;
        return copy;
    }

    /** Snapshots the current fields into an immutable value, for exposure outside the trace. */
    BranchOperands toBranchOperands() {
        switch (shape) {
            case INTS:
                return BranchOperands.ofInts(leftInt, rightInt, opcode);
            case INT:
                return BranchOperands.ofInt(leftInt, opcode);
            case OBJECTS:
                return BranchOperands.ofRendered(leftText, rightText, true, opcode);
            case OBJECT:
                return BranchOperands.ofRendered(leftText, null, false, opcode);
            default:
                throw new IllegalStateException("Unrecorded branch operand slot has no shape");
        }
    }
}
