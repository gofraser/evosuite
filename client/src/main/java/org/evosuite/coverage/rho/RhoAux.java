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
package org.evosuite.coverage.rho;

import org.evosuite.Properties;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.instrumentation.LinePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RhoAux class.
 *
 * @author José Campos
 */
public class RhoAux {

    private static final Logger logger = LoggerFactory.getLogger(RhoAux.class);

    private static boolean isTargetClass(String className) {
        if (Properties.TARGET_CLASS == null) {
            return false;
        }
        return Properties.TARGET_CLASS.equals("") || (className.equals(Properties.TARGET_CLASS)
                || className.startsWith(Properties.TARGET_CLASS + "$"));
    }

    /**
     * Returns the list of lines goals of the CUT
     *
     * @return list of line coverage goals
     */
    public static List<LineCoverageTestFitness> getLineGoals() {
        List<LineCoverageTestFitness> goals = new ArrayList<>();

        for (String className : LinePool.getKnownClasses()) {
            // Only lines in CUT
            if (!isTargetClass(className)) {
                continue;
            }

            for (String methodName : LinePool.getKnownMethodsFor(className)) {
                if (!Properties.TARGET_METHOD.isEmpty() && !methodName.equals(Properties.TARGET_METHOD)) {
                    continue;
                }
                for (Integer line : LinePool.getLines(className, methodName)) {
                    logger.debug("Adding line {} for class '{}'", line, className);
                    goals.add(new LineCoverageTestFitness(className, methodName, line));
                }
            }
        }

        return goals;
    }

    /**
     * Calculates the Rho score (deviation from 0.5).
     *
     * @param numberOfOnes      total number of covered goals
     * @param numberOfTestCases total number of test cases
     * @param numberOfGoals     total number of goals
     * @return the absolute difference between 0.5 and the raw rho score
     */
    public static double calculateRho(int numberOfOnes, int numberOfTestCases, int numberOfGoals) {
        if (numberOfTestCases == 0) {
            return 1.0;
        }
        double rho = ((double) numberOfOnes) / ((double) numberOfTestCases) / ((double) numberOfGoals);
        return Math.abs(0.5 - rho);
    }
}
