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
 * Primitive statement for double values.
 *
 * @author fraser
 */
public class DoublePrimitiveStatement extends NumericalPrimitiveStatement<Double> {

    private static final long serialVersionUID = 6229514439946892566L;

    /**
     * Constructs a new DoublePrimitiveStatement with the given value.
     *
     * @param tc    a {@link org.evosuite.testcase.TestCase} object.
     * @param value the initial value.
     */
    public DoublePrimitiveStatement(TestCase tc, Double value) {
        super(tc, double.class, value);
    }

    /**
     * Constructs a new DoublePrimitiveStatement with default value 0.0.
     *
     * @param tc a {@link org.evosuite.testcase.TestCase} object.
     */
    public DoublePrimitiveStatement(TestCase tc) {
        super(tc, double.class, 0.0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void zero() {
        value = 0.0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delta() {
        double probability = Randomness.nextDouble();
        if (probability < 1d / 3d) {
            value += Randomness.nextGaussian() * Properties.MAX_DELTA;
        } else if (probability < 2d / 3d) {
            value += Randomness.nextGaussian();
        } else {
            int precision = Randomness.nextInt(15);
            chopPrecision(precision);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment(long delta) {
        value = value + delta;
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
        this.value = bd.doubleValue();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment(double delta) {
        value = value + delta;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void randomize() {
        if (Randomness.nextDouble() >= Properties.PRIMITIVE_POOL) {
            value = Randomness.nextGaussian() * Properties.MAX_INT;
            int precision = Randomness.nextInt(15);
            chopPrecision(precision);
        } else {
            ConstantPool constantPool = ConstantPoolManager.getInstance().getConstantPool();
            value = constantPool.getRandomDouble();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment() {
        increment(1.0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMid(Double min, Double max) {
        value = min + ((max - min) / 2);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrement() {
        increment(-1.0);
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
        value = -value;
    }
}
