/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.symbolic.vm;

import org.evosuite.dse.AbstractVM;
import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.bv.IntegerBinaryExpression;
import org.evosuite.symbolic.expr.bv.IntegerComparison;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.bv.IntegerUnaryExpression;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.bv.RealComparison;
import org.evosuite.symbolic.expr.bv.RealToIntegerCast;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.expr.fp.IntegerToRealCast;
import org.evosuite.symbolic.expr.fp.RealBinaryExpression;
import org.evosuite.symbolic.expr.fp.RealUnaryExpression;
import org.evosuite.symbolic.expr.fp.RealValue;

/**
 * ByteCode instructions that pop operands off the stack, perform some
 * computation, and optionally push the result back onto the stack. - No heap
 * access - No local variable access - No branching.
 *
 * @author csallner@uta.edu (Christoph Csallner)
 */
public final class ArithmeticVM extends AbstractVM {

    private final SymbolicEnvironment env;

    private final PathConditionCollector pathConstraint;

    public ArithmeticVM(SymbolicEnvironment env, PathConditionCollector pathConstraint) {
        this.env = env;
        this.pathConstraint = pathConstraint;
    }

    private boolean zeroViolation(IntegerValue value, long valueConcrete) {
        IntegerConstant zero = ExpressionFactory.ICONST_0;
        IntegerConstraint zeroCheck;
        if (valueConcrete == 0) {
            zeroCheck = ConstraintFactory.eq(value, zero);
        } else {
            zeroCheck = ConstraintFactory.neq(value, zero);
        }

        if (zeroCheck.getLeftOperand().containsSymbolicVariable()
                || zeroCheck.getRightOperand().containsSymbolicVariable()) {
            pathConstraint.appendSupportingConstraint(zeroCheck);
        }

        // JVM will throw an exception
        return valueConcrete == 0;
    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc11.html#pop
     */
    @Override
    public void POP() {
        OperandStack stack = env.topFrame().operandStack;
        Operand a = stack.popOperand();
        if (!(a instanceof SingleWordOperand)) {
            throw new IllegalStateException(
                    "pop should be applied iif top is SingleWordOperand");
        }
    }

    /**
     * Pops two category-1 operands or one category-2 operand from the stack.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc11.html#pop2</p>
     */
    @Override
    public void POP2() {
        OperandStack stack = env.topFrame().operandStack;
        Operand top = stack.popOperand();

        if (top instanceof DoubleWordOperand) {
            /* Form 2 */
            return;
        }

        /* Form 1 */
        stack.popOperand();
    }

    /**
     * Duplicates the top operand on the stack.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc3.html#dup</p>
     */
    @Override
    public void DUP() {
        Operand x = env.topFrame().operandStack.peekOperand();
        env.topFrame().operandStack.pushOperand(x);
    }

    /**
     * Duplicates the top operand on the stack and inserts the copy two values down.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc3.html#dup_x1</p>
     */
    @Override
    public void DUP_X1() {
        OperandStack stack = env.topFrame().operandStack;

        Operand a = stack.popOperand();
        Operand b = stack.popOperand();

        stack.pushOperand(a);
        stack.pushOperand(b);
        stack.pushOperand(a);
    }

    /**
     * Duplicates the top operand on the stack and inserts the copy three values down.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc3.html#dup_x2</p>
     */
    @Override
    public void DUP_X2() {
        OperandStack stack = env.topFrame().operandStack;

        Operand a = stack.popOperand();
        Operand b = stack.popOperand();

        if (b instanceof SingleWordOperand) {
            Operand c = stack.popOperand();
            stack.pushOperand(a);
            stack.pushOperand(c);
            stack.pushOperand(b);
            stack.pushOperand(a);
        } else {
            stack.pushOperand(a);
            stack.pushOperand(b);
            stack.pushOperand(a);
        }
    }

    /**
     * Duplicates the top two category-1 operands or one category-2 operand on the stack.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc3.html#dup2</p>
     */
    @Override
    public void DUP2() {
        OperandStack stack = env.topFrame().operandStack;
        Operand a = stack.popOperand();

        if (a instanceof SingleWordOperand) {
            /* Form 1 */
            Operand b = stack.popOperand();
            stack.pushOperand(b);
            stack.pushOperand(a);
            stack.pushOperand(b);
            stack.pushOperand(a);
        } else {
            /* Form 2 */
            stack.pushOperand(a);
            stack.pushOperand(a);
        }

    }

    /**
     * Duplicates the top two category-1 operands or one category-2 operand on the stack
     * and inserts the copy three values down.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc3.html#dup2_x1</p>
     */
    @Override
    public void DUP2_X1() {
        OperandStack stack = env.topFrame().operandStack;

        Operand expression = stack.popOperand();

        if (expression instanceof SingleWordOperand) {
            /* Form 1 */
            Operand a = expression;
            Operand b = stack.popOperand();
            Operand c = stack.popOperand();
            stack.pushOperand(b);
            stack.pushOperand(a);
            stack.pushOperand(c);
            stack.pushOperand(b);
            stack.pushOperand(a);
        } else {
            /* Form 2 */
            Operand a = expression;
            Operand b = stack.popOperand();
            stack.pushOperand(a);
            stack.pushOperand(b);
            stack.pushOperand(a);
        }

    }

    /**
     * Duplicates the top two category-1 operands or one category-2 operand on the stack
     * and inserts the copy four values down.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc3.html#dup2_x2</p>
     */
    @Override
    public void DUP2_X2() {
        OperandStack stack = env.topFrame().operandStack;

        Operand first = stack.popOperand();
        Operand second = stack.popOperand();

        if (first instanceof DoubleWordOperand) {
            Operand a = first;

            if (second instanceof DoubleWordOperand) {
                /* Form 4 */
                Operand b = second;
                stack.pushOperand(a);
                stack.pushOperand(b);
                stack.pushOperand(a);
            } else {
                /* Form 2 */
                Operand b = second;
                Operand c = stack.popOperand();
                stack.pushOperand(a);
                stack.pushOperand(c);
                stack.pushOperand(b);
                stack.pushOperand(a);
            }
        } else {
            Operand a = first;
            Operand b = second;
            Operand third = stack.popOperand();

            if (third instanceof DoubleWordOperand) {
                /* Form 3 */
                Operand c = third;
                stack.pushOperand(b);
                stack.pushOperand(a);
                stack.pushOperand(c);
                stack.pushOperand(b);
                stack.pushOperand(a);
            } else {
                /* Form 1 */
                Operand c = third;
                Operand d = stack.popOperand();
                stack.pushOperand(b);
                stack.pushOperand(a);
                stack.pushOperand(d);
                stack.pushOperand(c);
                stack.pushOperand(b);
                stack.pushOperand(a);
            }
        }

    }

    /**
     * Swaps the top two category-1 operands on the stack.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc13.html#swap</p>
     */
    @Override
    public void SWAP() {
        OperandStack stack = env.topFrame().operandStack;
        Operand a = stack.popOperand();
        Operand b = stack.popOperand();
        stack.pushOperand(a);
        stack.pushOperand(b);
    }

    /**
     * Integer addition.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#iadd</p>
     */
    @Override
    public void IADD() {
        IntegerValue right = env.topFrame().operandStack.popBv32();
        IntegerValue left = env.topFrame().operandStack.popBv32();

        int leftConcVal = left.getConcreteValue().intValue();
        int rightConcVal = right.getConcreteValue().intValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        int con = leftConcVal + rightConcVal;

        IntegerValue intExpr = ExpressionFactory.add(left, right, con);

        env.topFrame().operandStack.pushBv32(intExpr);

    }

    /**
     * Long addition.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc8.html#ladd</p>
     */
    @Override
    public void LADD() {
        IntegerValue right = env.topFrame().operandStack.popBv64();
        IntegerValue left = env.topFrame().operandStack.popBv64();

        long leftConcVal = left.getConcreteValue();
        long rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        long con = leftConcVal + rightConcVal;

        IntegerValue intExpr = ExpressionFactory.add(left, right, con);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    /**
     * Float addition.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc4.html#fadd</p>
     */
    @Override
    public void FADD() {
        RealValue right = env.topFrame().operandStack.popFp32();
        RealValue left = env.topFrame().operandStack.popFp32();

        float leftConcVal = left.getConcreteValue()
                .floatValue();
        float rightConcVal = right.getConcreteValue()
                .floatValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        float con = leftConcVal + rightConcVal;

        RealValue realExpr = ExpressionFactory.add(left, right, con);

        env.topFrame().operandStack.pushFp32(realExpr);

    }

    @Override
    public void DADD() {
        RealValue right = env.topFrame().operandStack.popFp64();
        RealValue left = env.topFrame().operandStack.popFp64();

        double leftConcVal = left.getConcreteValue();
        double rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        double con = leftConcVal + rightConcVal;

        RealValue realExpr = ExpressionFactory.add(left, right, con);

        env.topFrame().operandStack.pushFp64(realExpr);
    }

    /**
     * Integer subtraction.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#isub</p>
     */
    @Override
    public void ISUB() {
        IntegerValue right = env.topFrame().operandStack.popBv32();
        IntegerValue left = env.topFrame().operandStack.popBv32();

        int leftConcVal = left.getConcreteValue().intValue();
        int rightConcVal = right.getConcreteValue().intValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        int con = leftConcVal - rightConcVal;

        IntegerValue intExpr = new IntegerBinaryExpression(left,
                Operator.MINUS, right, (long) con);

        env.topFrame().operandStack.pushBv32(intExpr);

    }

    /**
     * Long subtraction.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc8.html#lsub</p>
     */
    @Override
    public void LSUB() {
        IntegerValue right = env.topFrame().operandStack.popBv64();
        IntegerValue left = env.topFrame().operandStack.popBv64();

        long leftConcVal = left.getConcreteValue();
        long rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        long con = leftConcVal - rightConcVal;

        IntegerValue intExpr = new IntegerBinaryExpression(left,
                Operator.MINUS, right, con);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    /**
     * Float Subtraction.
     */
    @Override
    public void FSUB() {
        RealValue right = env.topFrame().operandStack.popFp32();
        RealValue left = env.topFrame().operandStack.popFp32();

        float leftConcVal = left.getConcreteValue()
                .floatValue();
        float rightConcVal = right.getConcreteValue()
                .floatValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        float con = leftConcVal - rightConcVal;

        RealValue realExpr = new RealBinaryExpression(left, Operator.MINUS,
                right, (double) con);

        env.topFrame().operandStack.pushFp32(realExpr);
    }

    @Override
    public void DSUB() {
        RealValue right = env.topFrame().operandStack.popFp64();
        RealValue left = env.topFrame().operandStack.popFp64();

        double leftConcVal = left.getConcreteValue();
        double rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        double con = leftConcVal - rightConcVal;

        RealValue realExpr = new RealBinaryExpression(left, Operator.MINUS,
                right, con);

        env.topFrame().operandStack.pushFp64(realExpr);
    }

    /**
     * Integer multiplication.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#imul</p>
     */
    @Override
    public void IMUL() {
        IntegerValue right = env.topFrame().operandStack.popBv32();
        IntegerValue left = env.topFrame().operandStack.popBv32();

        int leftConcVal = left.getConcreteValue().intValue();
        int rightConcVal = right.getConcreteValue().intValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        int con = leftConcVal * rightConcVal;

        IntegerValue intExpr = ExpressionFactory.mul(left, right, con);

        env.topFrame().operandStack.pushBv32(intExpr);

    }

    /**
     * Long multiplication.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc8.html#lmul</p>
     */
    @Override
    public void LMUL() {
        IntegerValue right = env.topFrame().operandStack.popBv64();
        IntegerValue left = env.topFrame().operandStack.popBv64();

        long leftConcVal = left.getConcreteValue();
        long rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        long con = leftConcVal * rightConcVal;

        IntegerValue intExpr = ExpressionFactory.mul(left, right, con);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    @Override
    public void FMUL() {
        RealValue right = env.topFrame().operandStack.popFp32();
        RealValue left = env.topFrame().operandStack.popFp32();

        float leftConcVal = left.getConcreteValue()
                .floatValue();
        float rightConcVal = right.getConcreteValue()
                .floatValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        float con = leftConcVal * rightConcVal;

        RealValue realExpr = ExpressionFactory.mul(left, right, con);

        env.topFrame().operandStack.pushFp32(realExpr);
    }

    @Override
    public void DMUL() {
        RealValue right = env.topFrame().operandStack.popFp64();
        RealValue left = env.topFrame().operandStack.popFp64();

        double leftConcVal = left.getConcreteValue();
        double rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        double con = leftConcVal * rightConcVal;

        RealValue realExpr = ExpressionFactory.mul(left, right, con);

        env.topFrame().operandStack.pushFp64(realExpr);
    }

    /**
     * Divide integers.
     *
     * <p>If {@code b == 0} throw exception (clear stack, push exception),
     * else actual division (compute, push result).</p>
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#idiv</p>
     */
    @Override
    public void IDIV(int rhsValue) {
        // consume all operands in stack
        IntegerValue right = env.topFrame().operandStack.popBv32();
        IntegerValue left = env.topFrame().operandStack.popBv32();

        if (zeroViolation(right, rhsValue)) {
            return;
        }

        int leftConcVal = left.getConcreteValue().intValue();
        int rightConcVal = right.getConcreteValue().intValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        int con = leftConcVal / rightConcVal;

        IntegerValue intExpr = ExpressionFactory.div(left, right, con);

        env.topFrame().operandStack.pushBv32(intExpr);

    }

    /**
     * Divide long.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc8.html#ldiv</p>
     */
    @Override
    public void LDIV(long rhsValue) {
        IntegerValue right = env.topFrame().operandStack.popBv64();
        IntegerValue left = env.topFrame().operandStack.popBv64();

        if (zeroViolation(right, rhsValue)) {
            return;
        }

        long leftConcVal = left.getConcreteValue();
        long rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        long con = leftConcVal / rightConcVal;

        IntegerValue intExpr = ExpressionFactory.div(left, right, con);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    @Override
    public void FDIV(float rhsValue) {
        RealValue right = env.topFrame().operandStack.popFp32();
        RealValue left = env.topFrame().operandStack.popFp32();

        float leftConcVal = left.getConcreteValue()
                .floatValue();
        float rightConcVal = right.getConcreteValue()
                .floatValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        float con = leftConcVal / rightConcVal;

        RealValue realExpr = ExpressionFactory.div(left, right, con);

        env.topFrame().operandStack.pushFp32(realExpr);
    }

    @Override
    public void DDIV(double rhsValue) {
        RealValue right = env.topFrame().operandStack.popFp64();
        RealValue left = env.topFrame().operandStack.popFp64();

        double leftConcVal = left.getConcreteValue();
        double rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        double con = leftConcVal / rightConcVal;

        RealValue realExpr = ExpressionFactory.div(left, right, con);

        env.topFrame().operandStack.pushFp64(realExpr);
    }

    /**
     * Modulo -- Remainder -- %.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#irem</p>
     */
    @Override
    public void IREM(int rhsValue) {
        IntegerValue right = env.topFrame().operandStack.popBv32();
        IntegerValue left = env.topFrame().operandStack.popBv32();

        if (zeroViolation(right, rhsValue)) {
            return;
        }

        int leftConcVal = left.getConcreteValue().intValue();
        int rightConcVal = right.getConcreteValue().intValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        int con = leftConcVal % rightConcVal;

        IntegerValue intExpr = ExpressionFactory.rem(left, right, con);

        env.topFrame().operandStack.pushBv32(intExpr);

    }

    @Override
    public void FREM(float rhs) {
        RealValue right = env.topFrame().operandStack.popFp32();
        RealValue left = env.topFrame().operandStack.popFp32();

        float leftConcVal = left.getConcreteValue()
                .floatValue();
        float rightConcVal = right.getConcreteValue()
                .floatValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        float con = leftConcVal % rightConcVal;

        RealValue realExpr = ExpressionFactory.rem(left, right, con);

        env.topFrame().operandStack.pushFp32(realExpr);
    }

    @Override
    public void DREM(double rhs) {
        RealValue right = env.topFrame().operandStack.popFp64();
        RealValue left = env.topFrame().operandStack.popFp64();

        double leftConcVal = left.getConcreteValue();
        double rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        double con = leftConcVal % rightConcVal;

        RealValue realExpr = ExpressionFactory.rem(left, right, con);

        env.topFrame().operandStack.pushFp64(realExpr);
    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#ineg
     */
    @Override
    public void INEG() {
        IntegerValue param = env.topFrame().operandStack.popBv32();

        int paramConcVal = param.getConcreteValue().intValue();

        if (!param.containsSymbolicVariable()) {
            param = ExpressionFactory
                    .buildNewIntegerConstant(paramConcVal);
        }

        int con = -paramConcVal;

        IntegerValue intExpr = new IntegerUnaryExpression(param, Operator.NEG,
                (long) con);

        env.topFrame().operandStack.pushBv32(intExpr);

    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc8.html#lneg
     */
    @Override
    public void LNEG() {
        IntegerValue param = env.topFrame().operandStack.popBv64();

        long paramConcVal = param.getConcreteValue();

        if (!param.containsSymbolicVariable()) {
            param = ExpressionFactory
                    .buildNewIntegerConstant(paramConcVal);
        }

        long con = -paramConcVal;

        IntegerValue intExpr = new IntegerUnaryExpression(param, Operator.NEG,
                con);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    @Override
    public void FNEG() {
        RealValue param = env.topFrame().operandStack.popFp32();

        float paramConcVal = param.getConcreteValue()
                .floatValue();

        if (!param.containsSymbolicVariable()) {
            param = ExpressionFactory
                    .buildNewRealConstant(paramConcVal);
        }
        float con = -paramConcVal;

        RealValue realExpr = new RealUnaryExpression(param, Operator.NEG,
                (double) con);

        env.topFrame().operandStack.pushFp32(realExpr);
    }

    @Override
    public void DNEG() {
        RealValue param = env.topFrame().operandStack.popFp64();

        double paramConcVal = param.getConcreteValue();

        if (!param.containsSymbolicVariable()) {
            param = ExpressionFactory
                    .buildNewRealConstant(paramConcVal);
        }
        double con = -paramConcVal;

        RealValue realExpr = new RealUnaryExpression(param, Operator.NEG, con);

        env.topFrame().operandStack.pushFp64(realExpr);
    }

    /**
     * Stack=value1(int)|value2(int).
     *
     * <p>Pops two ints off the stack. Shifts value2 left by the amount indicated
     * in the five low bits of value1. The int result is then pushed back onto
     * the stack.</p>
     */
    @Override
    public void ISHL() {
        IntegerValue rightExpr = env.topFrame().operandStack
                .popBv32();
        IntegerValue leftExpr = env.topFrame().operandStack
                .popBv32();

        int leftConcVal = leftExpr.getConcreteValue()
                .intValue();
        int rightConcVal = rightExpr.getConcreteValue()
                .intValue();

        int concreteValue = leftConcVal << (rightConcVal & 0x001F);

        IntegerBinaryExpression intExpr = new IntegerBinaryExpression(
                leftExpr, Operator.SHL, rightExpr, (long) concreteValue);

        env.topFrame().operandStack.pushBv32(intExpr);
    }

    /**
     * Stack=value1(int)|value2(int).
     *
     * <p>Pops two ints off the stack. Shifts value2 left by the amount indicated
     * in the five low bits of value1. The int result is then pushed back onto
     * the stack.</p>
     */
    @Override
    public void ISHR() {
        IntegerValue rightExpr = env.topFrame().operandStack
                .popBv32();
        IntegerValue leftExpr = env.topFrame().operandStack
                .popBv32();

        int leftConcVal = leftExpr.getConcreteValue()
                .intValue();
        int rightConcVal = rightExpr.getConcreteValue()
                .intValue();

        int concreteValue = leftConcVal >> (rightConcVal & 0x001F);

        IntegerBinaryExpression intExpr = new IntegerBinaryExpression(
                leftExpr, Operator.SHR, rightExpr, (long) concreteValue);

        env.topFrame().operandStack.pushBv32(intExpr);
    }

    /**
     * Stack=value1(int)|value2(int).
     *
     * <p>Pops two ints off the operand stack. Shifts value1 right by the amount
     * indicated in the five low bits of value2. The int result is then pushed
     * back onto the stack. value1 is shifted logically (ignoring the sign
     * extension - useful for unsigned values).</p>
     */
    @Override
    public void IUSHR() {
        IntegerValue rightExpr = env.topFrame().operandStack
                .popBv32();
        IntegerValue leftExpr = env.topFrame().operandStack
                .popBv32();

        int leftConcVal = leftExpr.getConcreteValue()
                .intValue();
        int rightConcVal = rightExpr.getConcreteValue()
                .intValue();

        int concreteValue = leftConcVal >>> (rightConcVal & 0x001F);

        IntegerBinaryExpression intExpr = new IntegerBinaryExpression(
                leftExpr, Operator.USHR, rightExpr, (long) concreteValue);

        env.topFrame().operandStack.pushBv32(intExpr);
    }

    /**
     * Stack=value1(int)|value2(long).
     *
     * <p>Pops an integer and a long integer and from the stack. Shifts value2 (the
     * long integer) right by the amount indicated in the low six bits of value1
     * (an int). The long integer result is then pushed back onto the stack. The
     * value is shifted logically (ignoring the sign extension - useful for
     * unsigned values).</p>
     */
    @Override
    public void LUSHR() {
        IntegerValue rightExpr = env.topFrame().operandStack
                .popBv32();
        IntegerValue leftExpr = env.topFrame().operandStack
                .popBv64();

        long leftConcVal = leftExpr.getConcreteValue();
        int rightConcVal = rightExpr.getConcreteValue()
                .intValue();

        long concreteValue = leftConcVal >>> (rightConcVal & 0x001F);

        IntegerBinaryExpression intExpr = new IntegerBinaryExpression(
                leftExpr, Operator.USHR, rightExpr, concreteValue);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    /**
     * Stack=value1(int)|value2(long).
     *
     * <p>Pops an int and a long integer from the stack. Shifts value2 (the long
     * integer) right by the amount indicated in the low six bits of value1 (an
     * int). The long integer result is then pushed back onto the stack. The
     * value is shifted arithmetically (preserving the sign extension).</p>
     */
    @Override
    public void LSHR() {
        IntegerValue rightExpr = env.topFrame().operandStack
                .popBv32();
        IntegerValue leftExpr = env.topFrame().operandStack
                .popBv64();

        long leftConcVal = leftExpr.getConcreteValue();
        int rightConcVal = rightExpr.getConcreteValue()
                .intValue();

        long concreteValue = leftConcVal >> (rightConcVal & 0x001F);

        IntegerBinaryExpression intExpr = new IntegerBinaryExpression(
                leftExpr, Operator.SHL, rightExpr, concreteValue);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    /**
     * Stack=value1(int)|value2(long).
     *
     * <p>Pops a long integer and an int from the stack. Shifts value2 (the long
     * integer) left by the amount indicated in the low six bits of value1 (an
     * int). The long integer result is then pushed back onto the stack.</p>
     */
    @Override
    public void LSHL() {
        IntegerValue rightExpr = env.topFrame().operandStack
                .popBv32();
        IntegerValue leftExpr = env.topFrame().operandStack
                .popBv64();

        long leftConcVal = leftExpr.getConcreteValue();
        int rightConcVal = rightExpr.getConcreteValue()
                .intValue();

        long concreteValue = leftConcVal << (rightConcVal & 0x001F);

        IntegerBinaryExpression intExpr = new IntegerBinaryExpression(
                leftExpr, Operator.SHL, rightExpr, concreteValue);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    /**
     * bitwise AND.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#iand</p>
     */
    @Override
    public void IAND() {
        IntegerValue right = env.topFrame().operandStack.popBv32();
        IntegerValue left = env.topFrame().operandStack.popBv32();

        int leftConcVal = left.getConcreteValue().intValue();
        int rightConcVal = right.getConcreteValue().intValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        int con = leftConcVal & rightConcVal;

        IntegerValue intExpr = new IntegerBinaryExpression(left, Operator.IAND,
                right, (long) con);

        env.topFrame().operandStack.pushBv32(intExpr);

    }

    /**
     * bitwise OR.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#ior</p>
     */
    @Override
    public void IOR() {
        IntegerValue right = env.topFrame().operandStack.popBv32();
        IntegerValue left = env.topFrame().operandStack.popBv32();

        int leftConcVal = left.getConcreteValue().intValue();
        int rightConcVal = right.getConcreteValue().intValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        int con = leftConcVal | rightConcVal;

        IntegerValue intExpr = new IntegerBinaryExpression(left, Operator.IOR,
                right, (long) con);

        env.topFrame().operandStack.pushBv32(intExpr);

    }

    /**
     * bitwise XOR.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#ixor</p>
     */
    @Override
    public void IXOR() {
        IntegerValue right = env.topFrame().operandStack.popBv32();
        IntegerValue left = env.topFrame().operandStack.popBv32();

        int leftConcVal = left.getConcreteValue().intValue();
        int rightConcVal = right.getConcreteValue().intValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        int con = leftConcVal ^ rightConcVal;

        IntegerValue intExpr = new IntegerBinaryExpression(left, Operator.IXOR,
                right, (long) con);

        env.topFrame().operandStack.pushBv32(intExpr);

    }

    /**
     * Bitwise AND.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc8.html#land</p>
     */
    @Override
    public void LAND() {
        IntegerValue right = env.topFrame().operandStack.popBv64();
        IntegerValue left = env.topFrame().operandStack.popBv64();

        long leftConcVal = left.getConcreteValue();
        long rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        long con = leftConcVal & rightConcVal;

        IntegerValue intExpr = new IntegerBinaryExpression(left, Operator.IAND,
                right, con);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    /**
     * Bitwise OR.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc8.html#lor</p>
     */
    @Override
    public void LOR() {
        IntegerValue right = env.topFrame().operandStack.popBv64();
        IntegerValue left = env.topFrame().operandStack.popBv64();

        long leftConcVal = left.getConcreteValue();
        long rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        long con = leftConcVal | rightConcVal;

        IntegerValue intExpr = new IntegerBinaryExpression(left, Operator.IOR,
                right, con);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    /**
     * Bitwise XOR.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc8.html#lxor</p>
     */
    @Override
    public void LXOR() {
        IntegerValue right = env.topFrame().operandStack.popBv64();
        IntegerValue left = env.topFrame().operandStack.popBv64();

        long leftConcVal = left.getConcreteValue();
        long rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        long con = leftConcVal ^ rightConcVal;

        IntegerValue intExpr = new IntegerBinaryExpression(left, Operator.IXOR,
                right, con);

        env.topFrame().operandStack.pushBv64(intExpr);
    }

    /**
     * Increment i-th local (int) variable by constant (int) value.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#iinc</p>
     */
    @Override
    public void IINC(int i, int value) {
        IntegerConstant right = ExpressionFactory
                .buildNewIntegerConstant(value);
        IntegerValue left = env.topFrame().localsTable.getBv32Local(i);

        int leftConcVal = left.getConcreteValue().intValue();
        int rightConcVal = value;

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }

        int con = leftConcVal + rightConcVal;

        IntegerValue intExpr = ExpressionFactory.add(left, right, con);

        env.topFrame().localsTable.setBv32Local(i, intExpr);
    }

    /**
     * Compare long.
     *
     * <pre>
     * (a &gt; b)  ==&gt;  1
     * (a == b) ==&gt;  0
     * (a &lt; b)  ==&gt; -1
     * </pre>
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc8.html#lcmp</p>
     */
    @Override
    public void LCMP() {
        IntegerValue right = env.topFrame().operandStack.popBv64();
        IntegerValue left = env.topFrame().operandStack.popBv64();

        long leftConcVal = left.getConcreteValue();
        long rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        int concVal = 0;
        if (leftConcVal == rightConcVal) {
            concVal = 0;
        } else if (leftConcVal > rightConcVal) {
            concVal = 1;
        } else {
            assert leftConcVal < rightConcVal;
            concVal = -1;
        }

        IntegerComparison intComp = new IntegerComparison(left, right,
                (long) concVal);

        env.topFrame().operandStack.pushBv32(intComp);
    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc4.html#fcmpop
     */
    @Override
    public void FCMPL() {
        RealValue right = env.topFrame().operandStack.popFp32();
        RealValue left = env.topFrame().operandStack.popFp32();

        float leftConcVal = left.getConcreteValue()
                .floatValue();
        float rightConcVal = right.getConcreteValue()
                .floatValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        int concVal;
        if (new Double(leftConcVal).isNaN()
                || new Double(rightConcVal).isNaN()) {
            concVal = 1;
        } else if (leftConcVal == rightConcVal) {
            concVal = 0;
        } else if (leftConcVal > rightConcVal) {
            concVal = 1;
        } else {
            assert leftConcVal < rightConcVal;
            concVal = -1;
        }

        RealComparison ret = new RealComparison(left, right,
                (long) concVal);

        env.topFrame().operandStack.pushBv32(ret);
    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc4.html#fcmpop
     */
    @Override
    public void FCMPG() {
        FCMPL(); // TODO: NaN treatment differs
    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc3.html#dcmpop
     */
    @Override
    public void DCMPL() {
        RealValue right = env.topFrame().operandStack.popFp64();
        RealValue left = env.topFrame().operandStack.popFp64();

        double leftConcVal = left.getConcreteValue();
        double rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory.buildNewRealConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewRealConstant(rightConcVal);
        }

        int concVal;
        if (new Double(leftConcVal).isNaN()
                || new Double(rightConcVal).isNaN()) {
            concVal = 1;
        } else if (leftConcVal == rightConcVal) {
            concVal = 0;
        } else if (leftConcVal > rightConcVal) {
            concVal = 1;
        } else {
            assert leftConcVal < rightConcVal;
            concVal = -1;
        }

        RealComparison ret = new RealComparison(left, right,
                (long) concVal);

        env.topFrame().operandStack.pushBv32(ret);
    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc3.html#dcmpop
     */
    @Override
    public void DCMPG() {
        DCMPL(); // FIXME: NaN treatment differs
    }

    /**
     * int --&gt; long.
     *
     * <p>This conversion is exact = preserve all information.</p>
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#i2l</p>
     */
    @Override
    public void I2L() {
        IntegerValue intExpr = env.topFrame().operandStack.popBv32();
        env.topFrame().operandStack.pushBv64(intExpr);
    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#i2f
     */
    @Override
    public void I2F() {
        IntegerValue intExpr = env.topFrame().operandStack.popBv32();
        int intVal = intExpr.getConcreteValue().intValue();
        RealValue realExpr;
        float concVal = (float) intVal;
        if (!intExpr.containsSymbolicVariable()) {
            realExpr = ExpressionFactory.buildNewRealConstant(concVal);
        } else {
            realExpr = new IntegerToRealCast(intExpr,
                    (double) concVal);
        }
        env.topFrame().operandStack.pushFp32(realExpr);
    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc6.html#i2d
     */
    @Override
    public void I2D() {
        IntegerValue intExpr = env.topFrame().operandStack.popBv32();
        int intVal = intExpr.getConcreteValue().intValue();
        RealValue realExpr;
        double concVal = intVal;
        if (!intExpr.containsSymbolicVariable()) {
            realExpr = ExpressionFactory.buildNewRealConstant(concVal);
        } else {
            realExpr = new IntegerToRealCast(intExpr,
                    concVal);
        }
        env.topFrame().operandStack.pushFp64(realExpr);
    }

    @Override
    public void L2I() {
        IntegerValue intExpr = env.topFrame().operandStack.popBv64();
        env.topFrame().operandStack.pushBv32(intExpr);
    }

    @Override
    public void L2F() {
        IntegerValue intExpr = env.topFrame().operandStack.popBv64();
        long longVal = intExpr.getConcreteValue();
        RealValue realExpr;
        float concVal = (float) longVal;
        if (!intExpr.containsSymbolicVariable()) {
            realExpr = ExpressionFactory.buildNewRealConstant(concVal);
        } else {
            realExpr = new IntegerToRealCast(intExpr,
                    (double) concVal);
        }
        env.topFrame().operandStack.pushFp32(realExpr);
    }

    @Override
    public void L2D() {
        IntegerValue intExpr = env.topFrame().operandStack.popBv64();
        long longVal = intExpr.getConcreteValue();
        RealValue realExpr;
        double concVal = (double) longVal;
        if (!intExpr.containsSymbolicVariable()) {
            realExpr = ExpressionFactory.buildNewRealConstant(concVal);
        } else {
            realExpr = new IntegerToRealCast(intExpr,
                    concVal);
        }
        env.topFrame().operandStack.pushFp64(realExpr);
    }

    @Override
    public void F2I() {
        RealValue realExpr = env.topFrame().operandStack.popFp32();
        float floatVal = realExpr.getConcreteValue().floatValue();
        IntegerValue intExpr;
        int concVal = (int) floatVal;
        if (!realExpr.containsSymbolicVariable()) {
            intExpr = ExpressionFactory.buildNewIntegerConstant(concVal);
        } else {
            intExpr = new RealToIntegerCast(realExpr, (long) concVal);
        }
        env.topFrame().operandStack.pushBv32(intExpr);
    }

    @Override
    public void F2L() {
        RealValue realExpr = env.topFrame().operandStack.popFp32();
        float floatVal = realExpr.getConcreteValue().floatValue();
        IntegerValue intExpr;
        long concVal = (long) floatVal;
        if (!realExpr.containsSymbolicVariable()) {
            intExpr = ExpressionFactory.buildNewIntegerConstant(concVal);
        } else {
            intExpr = new RealToIntegerCast(realExpr, concVal);
        }
        env.topFrame().operandStack.pushBv64(intExpr);
    }

    @Override
    public void F2D() {
        RealValue e = env.topFrame().operandStack.popFp32();
        env.topFrame().operandStack.pushFp64(e);
    }

    @Override
    public void D2I() {
        RealValue realExpr = env.topFrame().operandStack.popFp64();
        double doubleVal = realExpr.getConcreteValue();
        IntegerValue intExpr;
        int concVal = (int) doubleVal;
        if (!realExpr.containsSymbolicVariable()) {
            intExpr = ExpressionFactory.buildNewIntegerConstant(concVal);
        } else {
            intExpr = new RealToIntegerCast(realExpr, (long) concVal);
        }
        env.topFrame().operandStack.pushBv32(intExpr);
    }

    @Override
    public void D2L() {
        RealValue realExpr = env.topFrame().operandStack.popFp64();
        double doubleVal = realExpr.getConcreteValue();
        IntegerValue intExpr;
        long concVal = (long) doubleVal;
        if (!realExpr.containsSymbolicVariable()) {
            intExpr = ExpressionFactory.buildNewIntegerConstant(concVal);
        } else {
            intExpr = new RealToIntegerCast(realExpr, concVal);
        }
        env.topFrame().operandStack.pushBv64(intExpr);
    }

    @Override
    public void D2F() {
        RealValue e = env.topFrame().operandStack.popFp64();
        env.topFrame().operandStack.pushFp32(e);
    }

    @Override
    public void I2B() {
        // ignore I2B
    }

    @Override
    public void I2C() {
        // ignore I2C
    }

    @Override
    public void I2S() {
        // ignore I2S
    }

    @Override
    public void LREM(long rhs) {
        IntegerValue right = env.topFrame().operandStack.popBv64();
        IntegerValue left = env.topFrame().operandStack.popBv64();

        if (zeroViolation(right, rhs)) {
            return;
        }

        long leftConcVal = left.getConcreteValue();
        long rightConcVal = right.getConcreteValue();

        if (!left.containsSymbolicVariable()) {
            left = ExpressionFactory
                    .buildNewIntegerConstant(leftConcVal);
        }
        if (!right.containsSymbolicVariable()) {
            right = ExpressionFactory
                    .buildNewIntegerConstant(rightConcVal);
        }

        long con = leftConcVal % rightConcVal;

        IntegerValue intExpr = ExpressionFactory.rem(left, right, con);

        env.topFrame().operandStack.pushBv64(intExpr);
    }
}
