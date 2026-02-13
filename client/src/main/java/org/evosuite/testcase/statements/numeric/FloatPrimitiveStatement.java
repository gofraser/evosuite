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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Primitive statement for float values.
 *
 * @author Gordon Fraser
 */
public class FloatPrimitiveStatement extends NumericalPrimitiveStatement<Float> {

    private static final long serialVersionUID = 708022695544843828L;

    /**
     * Constructs a new FloatPrimitiveStatement with the given value.
     *
     * @param tc    a {@link org.evosuite.testcase.TestCase} object.
     * @param value the initial value.
     */
    public FloatPrimitiveStatement(TestCase tc, Float value) {
        super(tc, float.class, value);
    }

    /**
     * Constructs a new FloatPrimitiveStatement with default value 0.0F.
     *
     * @param tc a {@link org.evosuite.testcase.TestCase} object.
     */
    public FloatPrimitiveStatement(TestCase tc) {
        super(tc, float.class, 0.0F);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void zero() {
        value = (float) 0.0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delta() {
        double probability = Randomness.nextDouble();
        if (probability < 1d / 3d) {
            value += (float) Randomness.nextGaussian() * Properties.MAX_DELTA;
        } else if (probability < 2d / 3d) {
            value += (float) Randomness.nextGaussian();
        } else {
            int precision = Randomness.nextInt(7);
            chopPrecision(precision);
        }
    }

    /**
     * Reduces the precision of the value.
     *
     * @param precision the number of decimal places to keep
     */
    private void chopPrecision(int precision) {
        if (value.isNaN() || value.isInfinite()) {
            return;
        }

        BigDecimal bd = new BigDecimal(value).setScale(precision, RoundingMode.HALF_EVEN);
        this.value = bd.floatValue();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment(long delta) {
        value = value + delta;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment(double delta) {
        value = value + (float) delta;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void randomize() {
        if (Randomness.nextDouble() >= Properties.PRIMITIVE_POOL) {
            value = (float) (Randomness.nextGaussian() * Properties.MAX_INT);
            int precision = Randomness.nextInt(7);
            chopPrecision(precision);
        } else {
            ConstantPool constantPool = ConstantPoolManager.getInstance().getConstantPool();
            value = constantPool.getRandomFloat();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment() {
        increment(1.0F);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMid(Float min, Float max) {
        value = min + ((max - min) / 2);
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
        return value > 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void negate() {
        value = -value;
    }
}
