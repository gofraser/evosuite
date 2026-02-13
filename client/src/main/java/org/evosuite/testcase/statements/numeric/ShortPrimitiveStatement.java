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

package org.evosuite.testcase.statements.numeric;

import org.evosuite.Properties;
import org.evosuite.seeding.ConstantPool;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.testcase.TestCase;
import org.evosuite.utils.Randomness;

/**
 * Primitive statement for short values.
 *
 * @author fraser
 */
public class ShortPrimitiveStatement extends NumericalPrimitiveStatement<Short> {

    private static final long serialVersionUID = -1041008456902695964L;

    /**
     * Constructs a new ShortPrimitiveStatement with the given value.
     *
     * @param tc    a {@link org.evosuite.testcase.TestCase} object.
     * @param value the initial value.
     */
    public ShortPrimitiveStatement(TestCase tc, Short value) {
        super(tc, short.class, value);
    }

    /**
     * Constructs a new ShortPrimitiveStatement with default value 0.
     *
     * @param tc a {@link org.evosuite.testcase.TestCase} object.
     */
    public ShortPrimitiveStatement(TestCase tc) {
        super(tc, short.class, (short) 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void zero() {
        value = (short) 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delta() {
        short delta = (short) Math.floor(Randomness.nextGaussian() * Properties.MAX_DELTA);
        value = (short) (value + delta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment(long delta) {
        value = (short) (value + (short) delta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void randomize() {
        short max = (short) Math.min(Properties.MAX_INT, 32767);
        if (Randomness.nextDouble() >= Properties.PRIMITIVE_POOL) {
            value = (short) ((Randomness.nextGaussian() * max));
        } else {
            ConstantPool constantPool = ConstantPoolManager.getInstance().getConstantPool();
            value = (short) constantPool.getRandomInt();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment() {
        increment((short) 1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMid(Short min, Short max) {
        value = (short) (min + ((max - min) / 2));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrement() {
        increment(-1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isPositive() {
        return value >= 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void negate() {
        value = (short) -value;
    }
}
