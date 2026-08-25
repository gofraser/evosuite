/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Isolates MOSA unit tests from coverage configuration that requires a target project.
 */
abstract class MosaTestSupport {

    private Properties.Criterion[] savedCriteria;
    private Properties.SelectionFunction savedSelectionFunction;

    @BeforeEach
    void isolateMosaConfiguration() {
        savedCriteria = Properties.CRITERION;
        savedSelectionFunction = Properties.SELECTION_FUNCTION;
        Properties.CRITERION = new Properties.Criterion[0];
        Properties.SELECTION_FUNCTION = Properties.SelectionFunction.RANK_CROWD_DISTANCE_TOURNAMENT;
    }

    @AfterEach
    void restoreMosaConfiguration() {
        Properties.CRITERION = savedCriteria;
        Properties.SELECTION_FUNCTION = savedSelectionFunction;
    }
}
