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

import org.objectweb.asm.util.Printer;

import java.util.Objects;

/**
 * The operands of one evaluation of a branch predicate, rendered to text at recording time so
 * that no reference to a SUT object is ever retained (a held object could keep it alive, run
 * user {@code toString()} at the wrong time, or capture a mutable object whose state has since
 * changed).
 *
 * @author EvoSuite
 */
public final class BranchOperands {

    private final int leftInt;
    private final int rightInt;
    private final String leftRendered;
    private final String rightRendered;
    private final boolean intOperands;
    private final boolean hasRight;
    private final int opcode;

    private BranchOperands(int leftInt, int rightInt, String leftRendered, String rightRendered,
                            boolean intOperands, boolean hasRight, int opcode) {
        this.leftInt = leftInt;
        this.rightInt = rightInt;
        this.leftRendered = leftRendered;
        this.rightRendered = rightRendered;
        this.intOperands = intOperands;
        this.hasRight = hasRight;
        this.opcode = opcode;
    }

    public static BranchOperands ofInts(int left, int right, int opcode) {
        return new BranchOperands(left, right, null, null, true, true, opcode);
    }

    public static BranchOperands ofInt(int value, int opcode) {
        return new BranchOperands(value, 0, null, null, true, false, opcode);
    }

    public static BranchOperands ofObjects(Object left, Object right, int opcode) {
        return new BranchOperands(0, 0, render(left), render(right), false, true, opcode);
    }

    public static BranchOperands ofObject(Object value, int opcode) {
        return new BranchOperands(0, 0, render(value), null, false, false, opcode);
    }

    static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            String s = (String) value;
            s = s.replace("\n", "\\n").replace("\"", "\\\"");
            if (s.length() > 40) {
                s = s.substring(0, 40) + "...";
            }
            return "\"" + s + "\"";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value.toString();
        }
        String simpleName = value.getClass().getSimpleName();
        return simpleName.isEmpty() ? value.getClass().getName() : simpleName;
    }

    public int getOpcode() {
        return opcode;
    }

    public String getLeft() {
        return intOperands ? Integer.toString(leftInt) : leftRendered;
    }

    public String getRight() {
        if (!hasRight) {
            return null;
        }
        return intOperands ? Integer.toString(rightInt) : rightRendered;
    }

    public boolean hasRight() {
        return hasRight;
    }

    public String getOpcodeName() {
        if (opcode >= 0 && opcode < Printer.OPCODES.length) {
            return Printer.OPCODES[opcode];
        }
        return Integer.toString(opcode);
    }

    @Override
    public String toString() {
        return hasRight
                ? getLeft() + " vs " + getRight() + " (" + getOpcodeName() + ")"
                : getLeft() + " (" + getOpcodeName() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BranchOperands)) {
            return false;
        }
        BranchOperands other = (BranchOperands) o;
        return opcode == other.opcode
                && intOperands == other.intOperands
                && hasRight == other.hasRight
                && leftInt == other.leftInt
                && rightInt == other.rightInt
                && Objects.equals(leftRendered, other.leftRendered)
                && Objects.equals(rightRendered, other.rightRendered);
    }

    @Override
    public int hashCode() {
        return Objects.hash(leftInt, rightInt, leftRendered, rightRendered, intOperands, hasRight, opcode);
    }
}
