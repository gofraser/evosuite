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

import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.ref.NullReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;

import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * Represents the operand stack in the symbolic virtual machine.
 *
 * @author galeotti
 */
public final class OperandStack implements Iterable<Operand> {

    private final Deque<Operand> stack = new LinkedList<>();

    public OperandStack() {
    }

    /**
     * Pushes a 32-bit bitvector operand onto the stack.
     *
     * @param e the integer value to push
     */
    public void pushBv32(IntegerValue e) {
        stack.push(new Bv32Operand(e));
    }

    /**
     * Pushes a 64-bit bitvector operand onto the stack.
     *
     * @param e the integer value to push
     */
    public void pushBv64(IntegerValue e) {
        stack.push(new Bv64Operand(e));
    }

    /**
     * Pushes a 32-bit floating point operand onto the stack.
     *
     * @param e the real value to push
     */
    public void pushFp32(RealValue e) {
        stack.push(new Fp32Operand(e));
    }

    /**
     * Pushes a 64-bit floating point operand onto the stack.
     *
     * @param e the real value to push
     */
    public void pushFp64(RealValue e) {
        stack.push(new Fp64Operand(e));
    }

    /**
     * Pushes a reference operand onto the stack.
     *
     * @param r the reference expression to push
     */
    public void pushRef(ReferenceExpression r) {
        stack.push(new ReferenceOperand(r));
    }

    /**
     * Pushes a null reference operand onto the stack.
     */
    public void pushNullRef() {
        NullReferenceConstant nullExpression = ExpressionFactory.NULL_REFERENCE;
        this.stack.push(new ReferenceOperand(nullExpression));
    }

    /**
     * Pops a reference operand from the stack.
     *
     * @return the reference expression
     */
    public ReferenceExpression popRef() {
        Operand retVal = this.popOperand();
        ReferenceOperand ref = (ReferenceOperand) retVal;
        return ref.getReference();
    }

    /**
     * Pops a 32-bit bitvector operand from the stack.
     *
     * @return the integer value
     */
    public IntegerValue popBv32() {
        Operand x = this.popOperand();
        Bv32Operand e = (Bv32Operand) x;
        return e.getIntegerExpression();
    }

    /**
     * Pops a 64-bit bitvector operand from the stack.
     *
     * @return the integer value
     */
    public IntegerValue popBv64() {
        Operand x = this.popOperand();
        Bv64Operand e = (Bv64Operand) x;
        return e.getIntegerExpression();
    }

    /**
     * Pops a 32-bit floating point operand from the stack.
     *
     * @return the real value
     */
    public RealValue popFp32() {
        Operand x = this.popOperand();
        Fp32Operand e = (Fp32Operand) x;
        return e.getRealExpression();
    }

    /**
     * Pops a 64-bit floating point operand from the stack.
     *
     * @return the real value
     */
    public RealValue popFp64() {
        Operand x = this.popOperand();
        Fp64Operand e = (Fp64Operand) x;
        return e.getRealExpression();
    }

    /**
     * Pops an operand from the stack.
     *
     * @return the operand
     */
    public Operand popOperand() {
        Operand retVal = this.stack.pop();
        return retVal;
    }

    /**
     * Pushes an operand onto the stack.
     *
     * @param operand the operand to push
     */
    public void pushOperand(Operand operand) {
        if (operand == null) {
            throw new IllegalArgumentException("Cannot push a null operand into OperandStack");
        }
        stack.push(operand);
    }

    /**
     * Peeks a 64-bit floating point operand from the stack.
     *
     * @return the real value
     */
    public RealValue peekFp64() {
        Operand operand = stack.peek();
        Fp64Operand fp64 = (Fp64Operand) operand;
        return fp64.getRealExpression();
    }

    /**
     * Peeks a 32-bit floating point operand from the stack.
     *
     * @return the real value
     */
    public RealValue peekFp32() {
        Operand operand = stack.peek();
        Fp32Operand fp32 = (Fp32Operand) operand;
        return fp32.getRealExpression();
    }

    /**
     * Peeks a 64-bit bitvector operand from the stack.
     *
     * @return the integer value
     */
    public IntegerValue peekBv64() {
        Operand operand = stack.peek();
        Bv64Operand bv64 = (Bv64Operand) operand;
        return bv64.getIntegerExpression();
    }

    /**
     * Peeks a 32-bit bitvector operand from the stack.
     *
     * @return the integer value
     */
    public IntegerValue peekBv32() {
        Operand operand = stack.peek();
        Bv32Operand bv32 = (Bv32Operand) operand;
        return bv32.getIntegerExpression();
    }

    /**
     * Peeks an operand from the stack.
     *
     * @return the operand
     */
    public Operand peekOperand() {
        return stack.peek();
    }

    /**
     * Returns an iterator over the operands on the stack.
     *
     * @return an iterator
     */
    public Iterator<Operand> iterator() {
        return stack.iterator();
    }

    /**
     * Peeks a reference operand from the stack.
     *
     * @return the reference expression
     */
    public ReferenceExpression peekRef() {
        Operand operand = this.peekOperand();
        if (!(operand instanceof ReferenceOperand)) {
            throw new ClassCastException(
                    "top of stack is not a reference but an operand of type " + operand.getClass().getCanonicalName());
        }
        ReferenceOperand refOp = (ReferenceOperand) operand;
        ReferenceExpression ref = refOp.getReference();
        return ref;
    }

    @Override
    public String toString() {
        if (this.stack.isEmpty()) {
            return "<<EMPTY_OPERAND_STACK>>";
        }

        StringBuffer buff = new StringBuffer();
        for (Operand operand : this) {
            buff.append(operand.toString() + "\n");
        }
        return buff.toString();
    }

    /**
     * Returns the number of operands on the stack.
     *
     * @return the stack size
     */
    public int size() {
        return stack.size();
    }

    /**
     * Returns whether the stack is empty.
     *
     * @return true if empty, false otherwise
     */
    public boolean isEmpty() {
        return stack.isEmpty();
    }

    /**
     * Clears all operands from the stack.
     */
    public void clearOperands() {
        stack.clear();
    }
}
