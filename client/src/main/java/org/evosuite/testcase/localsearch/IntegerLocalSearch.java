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

package org.evosuite.testcase.localsearch;

import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.numeric.NumericalPrimitiveStatement;

/**
 * <p>
 * IntegerLocalSearch class.
 * </p>
 *
 * @author Gordon Fraser
 */
public class IntegerLocalSearch<T> extends NumericalLocalSearch<T> {

    @Override
    protected boolean executeSearch(TestChromosome test, int statement,
                                    LocalSearchObjective<TestChromosome> objective,
                                    NumericalPrimitiveStatement<T> p) {
        return performAVM(test, statement, objective, 1.0, 2.0, p);
    }

}
