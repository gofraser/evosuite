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
import org.evosuite.symbolic.expr.ref.ReferenceExpression;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the table of local variables in a stack frame.
 *
 * @author galeotti
 */
public final class LocalsTable {

    /**
     * List of local variables.
     */
    private final List<Operand> locals = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param maxLocals maximum number of local variables
     */
    public LocalsTable(int maxLocals) {
        for (int i = 0; i < maxLocals; i++) {
            locals.add(null);
        }
    }

    /**
     * Sets an operand at the specified index in the locals table.
     *
     * @param i the index
     * @param operand the operand
     */
    public void setOperand(int i, Operand operand) {
        locals.set(i, operand);
    }

    /**
     * Sets a 64-bit floating point local variable.
     *
     * @param i the index
     * @param r the real value
     */
    public void setFp64Local(int i, RealValue r) {
        locals.set(i, new Fp64Operand(r));
    }

    /**
     * Sets a 32-bit floating point local variable.
     *
     * @param i the index
     * @param r the real value
     */
    public void setFp32Local(int i, RealValue r) {
        locals.set(i, new Fp32Operand(r));
    }

    /**
     * Sets a 32-bit bitvector local variable.
     *
     * @param i the index
     * @param e the integer value
     */
    public void setBv32Local(int i, IntegerValue e) {
        locals.set(i, new Bv32Operand(e));
    }

    /**
     * Sets a 64-bit bitvector local variable.
     *
     * @param i the index
     * @param e the integer value
     */
    public void setBv64Local(int i, IntegerValue e) {
        locals.set(i, new Bv64Operand(e));
    }

    /**
     * Sets a reference local variable.
     *
     * @param i the index
     * @param o the reference expression
     */
    public void setRefLocal(int i, ReferenceExpression o) {
        locals.set(i, new ReferenceOperand(o));
    }

    /**
     * Returns a reference local variable.
     *
     * @param i the index
     * @return the reference expression
     */
    public ReferenceExpression getRefLocal(int i) {
        Operand x = locals.get(i);
        ReferenceOperand refOp = (ReferenceOperand) x;
        return refOp.getReference();
    }

    /**
     * Returns an operand local variable.
     *
     * @param i the index
     * @return the operand
     */
    public Operand getOperand(int i) {
        Operand x = locals.get(i);
        return x;
    }

    /**
     * Returns a 64-bit bitvector local variable.
     *
     * @param i the index
     * @return the integer value
     */
    public IntegerValue getBv64Local(int i) {
        Operand x = locals.get(i);
        Bv64Operand bv64 = (Bv64Operand) x;
        return bv64.getIntegerExpression();
    }

    /**
     * Returns a 32-bit bitvector local variable.
     *
     * @param i the index
     * @return the integer value
     */
    public IntegerValue getBv32Local(int i) {
        Operand x = locals.get(i);
        Bv32Operand bv32 = (Bv32Operand) x;
        return bv32.getIntegerExpression();
    }

    /**
     * Returns a 32-bit floating point local variable.
     *
     * @param i the index
     * @return the real value
     */
    public RealValue getFp32Local(int i) {
        Operand x = locals.get(i);
        Fp32Operand fp32 = (Fp32Operand) x;
        return fp32.getRealExpression();
    }

    /**
     * Returns a 64-bit floating point local variable.
     *
     * @param i the index
     * @return the real value
     */
    public RealValue getFp64Local(int i) {
        Operand x = locals.get(i);
        Fp64Operand fp64 = (Fp64Operand) x;
        return fp64.getRealExpression();
    }
}
