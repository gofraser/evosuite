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
import org.evosuite.testcase.TestCase;
import org.evosuite.utils.Randomness;


/**
 * Primitive statement for character values.
 *
 * @author fraser
 */
public class CharPrimitiveStatement extends NumericalPrimitiveStatement<Character> {

    private static final long serialVersionUID = -1960567565801078784L;

    /**
     * Constructs a new CharPrimitiveStatement with the given value.
     *
     * @param tc    a {@link org.evosuite.testcase.TestCase} object.
     * @param value the initial value.
     */
    public CharPrimitiveStatement(TestCase tc, Character value) {
        super(tc, char.class, value);
    }

    /**
     * Constructs a new CharPrimitiveStatement with default value.
     *
     * @param tc a {@link org.evosuite.testcase.TestCase} object.
     */
    public CharPrimitiveStatement(TestCase tc) {
        super(tc, char.class, (char) 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void zero() {
        value = (char) 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delta() {
        int delta = Randomness.nextInt(2 * Properties.MAX_DELTA) - Properties.MAX_DELTA;
        value = (char) (value + delta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment(long delta) {
        value = (char) (value + delta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void randomize() {
        value = Randomness.nextChar();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment() {
        increment((char) 1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMid(Character min, Character max) {
        value = (char) (min + ((max - min) / 2));
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
        // chars are always positive
        return true;
    }
}
