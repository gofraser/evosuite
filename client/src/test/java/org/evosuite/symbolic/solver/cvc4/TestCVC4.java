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
package org.evosuite.symbolic.solver.cvc4;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

public abstract class TestCVC4 {

    private static final String DEFAULT_CVC4_PATH = Properties.CVC4_PATH;

    @BeforeAll
    public static void configureCVC4Path() {
        String cvc4_path = System.getenv("cvc4_path");
        if (cvc4_path != null) {
            Properties.CVC4_PATH = cvc4_path;
        }
    }

    @BeforeEach
    public void checkCVC4() {
        Assumptions.assumeTrue(Properties.CVC4_PATH != null);
    }

    @AfterAll
    public static void restoreCVC4Path() {
        Properties.CVC4_PATH = DEFAULT_CVC4_PATH;
    }

}
