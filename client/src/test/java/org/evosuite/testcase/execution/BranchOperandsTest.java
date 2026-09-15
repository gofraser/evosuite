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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BranchOperandsTest {

    /** A stand-in for a SUT object whose toString() must never be called. */
    private static final class Poisoned {
        @Override
        public String toString() {
            throw new AssertionError("toString() must not be called on operand objects");
        }
    }

    @Test
    public void testRenderNull() {
        assertEquals("null", BranchOperands.render(null));
    }

    @Test
    public void testRenderShortString() {
        assertEquals("\"hi\"", BranchOperands.render("hi"));
    }

    @Test
    public void testRenderLongStringIsTruncated() {
        String s = "0123456789012345678901234567890123456789extra";
        String rendered = BranchOperands.render(s);
        assertEquals("\"" + s.substring(0, 40) + "...\"", rendered);
    }

    @Test
    public void testRenderNumber() {
        assertEquals("42", BranchOperands.render(42));
    }

    @Test
    public void testSutNumberSubclassIsRenderedByClassName() {
        class Price extends Number {
            @Override public int intValue() { throw new AssertionError("must not be called"); }
            @Override public long longValue() { throw new AssertionError("must not be called"); }
            @Override public float floatValue() { throw new AssertionError("must not be called"); }
            @Override public double doubleValue() { throw new AssertionError("must not be called"); }
            @Override public String toString() { throw new AssertionError("must not be called"); }
        }
        assertEquals("Price", BranchOperands.render(new Price()));
    }

    @Test
    public void testRenderCustomObjectUsesSimpleNameNotToString() {
        assertEquals("Poisoned", BranchOperands.render(new Poisoned()));
    }

    @Test
    public void testOfIntsRendersOperandsAndOpcode() {
        BranchOperands operands = BranchOperands.ofInts(50, 0, Opcodes.IF_ICMPLE);
        assertEquals("50", operands.getLeft());
        assertEquals("0", operands.getRight());
        assertTrue(operands.hasRight());
        assertEquals("IF_ICMPLE", operands.getOpcodeName());
        assertEquals("50 vs 0 (IF_ICMPLE)", operands.toString());
    }

    @Test
    public void testOfIntHidesImplicitRightOperand() {
        BranchOperands operands = BranchOperands.ofInt(3, Opcodes.IFLE);
        assertEquals("3", operands.getLeft());
        assertNull(operands.getRight());
        assertFalse(operands.hasRight());
    }

    @Test
    public void testOfObjectSingleOperand() {
        BranchOperands operands = BranchOperands.ofObject(null, Opcodes.IFNONNULL);
        assertEquals("null", operands.getLeft());
        assertFalse(operands.hasRight());
        assertEquals("IFNONNULL", operands.getOpcodeName());
        assertEquals("null (IFNONNULL)", operands.toString());
    }

    @Test
    public void testOfObjectsDoesNotRetainSutObjects() {
        Poisoned left = new Poisoned();
        Poisoned right = new Poisoned();
        BranchOperands operands = BranchOperands.ofObjects(left, right, Opcodes.IF_ACMPEQ);
        // rendering happened eagerly; calling getLeft()/getRight() again must not touch toString()
        assertEquals("Poisoned", operands.getLeft());
        assertEquals("Poisoned", operands.getRight());
    }

    @Test
    public void testUnknownOpcodeFallsBackToNumber() {
        BranchOperands operands = BranchOperands.ofInt(1, 9999);
        assertEquals("9999", operands.getOpcodeName());
    }

    @Test
    public void testEqualsAndHashCode() {
        BranchOperands a = BranchOperands.ofInts(5, 7, Opcodes.IF_ICMPLT);
        BranchOperands b = BranchOperands.ofInts(5, 7, Opcodes.IF_ICMPLT);
        BranchOperands c = BranchOperands.ofInts(5, 8, Opcodes.IF_ICMPLT);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
    }
}
