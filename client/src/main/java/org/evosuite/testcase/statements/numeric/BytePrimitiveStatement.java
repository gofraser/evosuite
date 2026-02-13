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

import java.lang.reflect.Type;

/**
 * Primitive statement for byte values.
 *
 * @author fraser
 */
public class BytePrimitiveStatement extends NumericalPrimitiveStatement<Byte> {

    /**
     * Constructs a new BytePrimitiveStatement with the given value.
     *
     * @param tc    a {@link org.evosuite.testcase.TestCase} object.
     * @param value the initial value.
     */
    public BytePrimitiveStatement(TestCase tc, Byte value) {
        super(tc, byte.class, value);
    }

    /**
     * Constructs a new BytePrimitiveStatement with default value 0.
     *
     * @param tc a {@link org.evosuite.testcase.TestCase} object.
     */
    public BytePrimitiveStatement(TestCase tc) {
        super(tc, byte.class, (byte) 0);
    }

    /**
     * Constructs a new BytePrimitiveStatement with default value 0 and given type.
     *
     * @param tc   a {@link org.evosuite.testcase.TestCase} object.
     * @param type the type of the value.
     */
    public BytePrimitiveStatement(TestCase tc, Type type) {
        super(tc, type, (byte) 0);
    }

    private static final long serialVersionUID = -8123457944460041347L;

    /**
     * {@inheritDoc}
     */
    @Override
    public void zero() {
        value = (byte) 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delta() {
        byte delta = (byte) Math.floor(Randomness.nextGaussian() * Properties.MAX_DELTA);
        value = (byte) (value + delta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment(long delta) {
        value = (byte) (value + delta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void randomize() {
        if (Randomness.nextDouble() >= Properties.PRIMITIVE_POOL) {
            value = (byte) (Randomness.nextInt(256) - 128);
        } else {
            ConstantPool constantPool = ConstantPoolManager.getInstance().getConstantPool();
            value = (byte) constantPool.getRandomInt();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment() {
        increment((byte) 1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrement() {
        increment((byte) -1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMid(Byte min, Byte max) {
        value = (byte) (min + ((max - min) / 2));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isPositive() {
        // TODO Auto-generated method stub
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void negate() {
        value = (byte) -value;
    }
}
