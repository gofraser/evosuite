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
package org.evosuite.coverage.io.input;

import org.evosuite.coverage.io.IOCoverageSuiteFitness;

/**
 * Fitness function for evaluating test suite coverage of method input parameters.
 * Computes fitness based on how well the test suite covers various input value categories.
 *
 * @author Jose Miguel Rojas
 */
public class InputCoverageSuiteFitness extends IOCoverageSuiteFitness<InputCoverageTestFitness> {

    private static final long serialVersionUID = -6571466037158036014L;

    /**
     * Initializes a new InputCoverageSuiteFitness, adding the InputObserver to the executor.
     */
    public InputCoverageSuiteFitness() {
        super("Input", InputObserver.class, InputObserver::new, new InputCoverageFactory());
    }

}
