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
package org.evosuite.symbolic.solver;

import org.evosuite.symbolic.expr.Constraint;
import org.evosuite.symbolic.expr.DistanceCalculator;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.expr.constraint.RealConstraint;
import org.evosuite.symbolic.expr.constraint.StringConstraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Estimator for the distance of a collection of constraints.
 *
 * @author krusev
 */
public abstract class DistanceEstimator {

    static Logger log = LoggerFactory.getLogger(DistanceEstimator.class);

    // static Logger log =
    // JPF.getLogger("org.evosuite.symbolic.search.DistanceEstimator");

    private static double normalize(double x) {
        return x / (x + 1.0);
    }

    /**
     * Returns the normalized distance for a collection of constraints.
     *
     * @param constraints a {@link java.util.Collection} object.
     * @return normalized distance in [0,1]
     */
    public static double getDistance(Collection<Constraint<?>> constraints) {
        double result = 0;

        DistanceCalculator distanceCalculator = new DistanceCalculator();
        try {
            for (Constraint<?> c : constraints) {

                if (c instanceof StringConstraint) {
                    StringConstraint stringConstraint = (StringConstraint) c;

                    try {
                        double strD = (double) stringConstraint.accept(
                                distanceCalculator, null);
                        result += normalize(strD);
                        log.debug("S: " + stringConstraint + " strDist "
                                + strD);
                    } catch (Throwable t) {
                        log.debug("S: " + stringConstraint + " strDist " + t);
                        result += 1.0;
                    }

                } else if (c instanceof IntegerConstraint) {

                    IntegerConstraint integerConstraint = (IntegerConstraint) c;
                    long intD = (long) integerConstraint.accept(
                            distanceCalculator, null);
                    result += normalize(intD);
                    log.debug("C: " + integerConstraint + " intDist " + intD);

                } else if (c instanceof RealConstraint) {
                    RealConstraint realConstraint = (RealConstraint) c;
                    double realD = (double) realConstraint.accept(
                            distanceCalculator, null);

                    result += normalize(realD);
                    log.debug("C: " + realConstraint + " realDist " + realD);

                } else {
                    throw new IllegalArgumentException(
                            "DistanceCalculator: got an unknown constraint: "
                                    + c);
                }
            }
            log.debug("Resulting distance: " + result);
            return Math.abs(result);

        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }

}
