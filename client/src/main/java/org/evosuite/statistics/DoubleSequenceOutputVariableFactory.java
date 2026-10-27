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
package org.evosuite.statistics;

import org.evosuite.testsuite.TestSuiteChromosome;

import java.util.function.Function;

public abstract class DoubleSequenceOutputVariableFactory extends SequenceOutputVariableFactory<Double> {

    public DoubleSequenceOutputVariableFactory(RuntimeVariable variable) {
        super(variable);
    }

    /**
     * Creates a factory that reads the value from a test suite with the given function.
     *
     * @param variable the timeline variable
     * @param value    extracts the value from a test suite
     * @return the factory
     */
    public static DoubleSequenceOutputVariableFactory of(RuntimeVariable variable,
                                                         Function<TestSuiteChromosome, Double> value) {
        return new DoubleSequenceOutputVariableFactory(variable) {
            @Override
            protected Double getValue(TestSuiteChromosome individual) {
                return value.apply(individual);
            }
        };
    }

    @Override
    protected Double interpolate(Double v1, Double v2, double ratio) {
        return v1 + (v2 - v1) * ratio;
    }

    @Override
    protected Double getZeroValue() {
        return 0.0;
    }
}
