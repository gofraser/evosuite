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
package org.evosuite.symbolic.expr;

import org.evosuite.Properties;
import org.evosuite.symbolic.ConstraintTooLongException;
import org.evosuite.symbolic.expr.bv.IntegerComparison;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.bv.IntegerUnaryExpression;
import org.evosuite.symbolic.expr.bv.IntegerVariable;
import org.evosuite.symbolic.expr.bv.RealToIntegerCast;
import org.evosuite.symbolic.expr.bv.RealUnaryToIntegerExpression;
import org.evosuite.symbolic.expr.bv.StringBinaryToIntegerExpression;
import org.evosuite.symbolic.expr.fp.RealConstant;
import org.evosuite.symbolic.expr.fp.RealUnaryExpression;
import org.evosuite.symbolic.expr.str.StringBinaryExpression;
import org.evosuite.symbolic.expr.str.StringConstant;
import org.evosuite.symbolic.expr.str.StringMultipleExpression;
import org.evosuite.symbolic.expr.str.StringVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks what the composite expressions inherit from their shared base
 * classes: size, equality, hash code, text, variables, constants and the
 * length limit.
 */
public class CompositeExpressionTest {

    private int constraintLength;

    @BeforeEach
    public void saveConstraintLength() {
        constraintLength = Properties.DSE_CONSTRAINT_LENGTH;
    }

    @AfterEach
    public void restoreConstraintLength() {
        Properties.DSE_CONSTRAINT_LENGTH = constraintLength;
    }

    private static IntegerVariable intVar(String name) {
        return new IntegerVariable(name, 3L, 0L, 10L);
    }

    @Test
    public void unaryExpressionDelegatesToItsOperand() {
        IntegerVariable x = intVar("x");
        IntegerUnaryExpression neg = new IntegerUnaryExpression(x, Operator.NEG, -3L);

        assertEquals(2, neg.getSize());
        assertTrue(neg.containsSymbolicVariable());
        assertSame(x, neg.getOperand());
        assertEquals(Operator.NEG, neg.getOperator());
        assertEquals(x.hashCode() + Operator.NEG.hashCode(), neg.hashCode());
        assertEquals("(" + Operator.NEG + "(" + x + "))", neg.toString());
        assertEquals(x.getVariables(), neg.getVariables());
        assertEquals(x.getConstants(), neg.getConstants());
    }

    @Test
    public void unaryEqualityComparesClassOperatorAndOperand() {
        IntegerVariable x = intVar("x");
        IntegerUnaryExpression neg = new IntegerUnaryExpression(x, Operator.NEG, -3L);

        assertEquals(neg, new IntegerUnaryExpression(x, Operator.NEG, 99L));
        assertNotEquals(neg, new IntegerUnaryExpression(x, Operator.ABS, 3L));
        assertNotEquals(neg, new IntegerUnaryExpression(intVar("y"), Operator.NEG, -3L));
        assertNotEquals(neg, null);
        assertNotEquals(neg, x);
    }

    @Test
    public void realUnaryEqualityAndHashCodeIncludeTheSize() {
        RealConstant one = new RealConstant(1.0);
        RealUnaryExpression abs = new RealUnaryExpression(one, Operator.ABS, 1.0);
        RealUnaryToIntegerExpression toInt =
                new RealUnaryToIntegerExpression(one, Operator.ABS, 1L);

        assertEquals(one.hashCode() + Operator.ABS.hashCode() + abs.getSize(), abs.hashCode());
        assertEquals(abs, new RealUnaryExpression(one, Operator.ABS, 1.0));
        assertEquals(one.hashCode() + Operator.ABS.hashCode() + toInt.getSize(), toInt.hashCode());
        assertNotEquals(abs, toInt);
    }

    @Test
    public void castHasNoOperatorInItsHashCode() {
        RealConstant one = new RealConstant(1.0);
        RealToIntegerCast cast = new RealToIntegerCast(one, 1L);

        assertEquals(one.hashCode(), cast.hashCode());
        assertSame(one, cast.getArgument());
        assertEquals("((INT)" + one + ")", cast.toString());
        assertEquals(cast, new RealToIntegerCast(one, 2L));
        assertNotEquals(cast, new RealToIntegerCast(new RealConstant(2.0), 2L));
    }

    @Test
    public void binaryExpressionCombinesBothOperands() {
        StringVariable s = new StringVariable("s", "abc");
        StringConstant c = new StringConstant("b");
        StringBinaryToIntegerExpression index =
                new StringBinaryToIntegerExpression(s, Operator.INDEXOFS, c, 1L);

        assertEquals(3, index.getSize());
        assertEquals(s.hashCode() + Operator.INDEXOFS.hashCode() + c.hashCode(), index.hashCode());
        assertEquals("(" + s + Operator.INDEXOFS + c + ")", index.toString());
        assertEquals(s.getVariables(), index.getVariables());
        Set<Object> constants = new HashSet<>(s.getConstants());
        constants.addAll(c.getConstants());
        assertEquals(constants, index.getConstants());
        assertEquals(index, new StringBinaryToIntegerExpression(s, Operator.INDEXOFS, c, 7L));
        assertNotEquals(index,
                new StringBinaryToIntegerExpression(s, Operator.LASTINDEXOFS, c, 1L));
    }

    @Test
    public void indexOfCharacterPrintsTheCharacter() {
        StringConstant s = new StringConstant("abc");
        IntegerConstant ch = new IntegerConstant('b');

        assertEquals("(" + s + Operator.INDEXOFC + "'b')",
                new StringBinaryToIntegerExpression(s, Operator.INDEXOFC, ch, 1L).toString());
    }

    @Test
    public void expressionsOfDifferentClassesAreNotEqual() {
        StringConstant a = new StringConstant("a");
        StringConstant b = new StringConstant("b");

        assertNotEquals(new StringBinaryExpression(a, Operator.CONCAT, b, "ab"),
                new StringBinaryToIntegerExpression(a, Operator.CONCAT, b, 1L));
    }

    @Test
    public void comparisonHasNoOperator() {
        IntegerVariable x = intVar("x");
        IntegerConstant one = new IntegerConstant(1);
        IntegerComparison cmp = new IntegerComparison(x, one, 1L);

        assertEquals(x.hashCode() + one.hashCode(), cmp.hashCode());
        assertEquals("(" + x + " cmp " + one + ")", cmp.toString());
        assertSame(x, cmp.getLeftOperand());
        assertSame(one, cmp.getRightOperand());
    }

    @Test
    public void multipleExpressionIncludesTheFurtherOperands() {
        StringVariable s = new StringVariable("s", "abc");
        StringConstant from = new StringConstant("a");
        IntegerVariable end = intVar("end");
        ArrayList<Expression<?>> other = new ArrayList<>(Collections.singletonList(end));
        StringMultipleExpression sub =
                new StringMultipleExpression(s, Operator.SUBSTRING, from, other, "b");

        assertEquals(4, sub.getSize());
        assertSame(other, sub.getOther());
        assertEquals(Operator.SUBSTRING.hashCode() + s.hashCode() + from.hashCode()
                + other.hashCode(), sub.hashCode());
        assertEquals("(" + s + Operator.SUBSTRING + from + " " + end + ")", sub.toString());
        Set<Variable<?>> variables = new HashSet<>(s.getVariables());
        variables.addAll(end.getVariables());
        assertEquals(variables, sub.getVariables());
        assertEquals(sub, new StringMultipleExpression(s, Operator.SUBSTRING, from,
                new ArrayList<>(other), "x"));
        assertNotEquals(sub, new StringMultipleExpression(s, Operator.SUBSTRING, from,
                new ArrayList<>(), "x"));
    }

    @Test
    public void expressionLongerThanTheLimitIsRejected() {
        IntegerVariable x = intVar("x");
        Properties.DSE_CONSTRAINT_LENGTH = 2;
        IntegerUnaryExpression neg = new IntegerUnaryExpression(x, Operator.NEG, -3L);

        assertThrows(ConstraintTooLongException.class,
                () -> new IntegerUnaryExpression(neg, Operator.NEG, 3L));
        assertThrows(ConstraintTooLongException.class,
                () -> new RealToIntegerCast(new RealUnaryExpression(
                        new RealConstant(1.0), Operator.ABS, 1.0), 1L));
    }
}
