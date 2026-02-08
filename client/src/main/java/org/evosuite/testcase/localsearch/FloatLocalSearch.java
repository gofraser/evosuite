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

import org.evosuite.ga.localsearch.LocalSearchBudget;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.numeric.NumericalPrimitiveStatement;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <p>
 * FloatLocalSearch class.
 * </p>
 *
 * @author Gordon Fraser
 */
public class FloatLocalSearch<T extends Number> extends NumericalLocalSearch<T> {

    @SuppressWarnings("unchecked")
    @Override
    protected boolean executeSearch(TestChromosome test, int statement,
                                    LocalSearchObjective<TestChromosome> objective,
                                    NumericalPrimitiveStatement<T> p) {

        double value = p.getValue().doubleValue();
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return false;
        }

        boolean improved = performAVM(test, statement, objective, 1.0, 2.0, p);

        if (!improved) {
            logger.info("Stopping search as variable doesn't influence fitness");
            return false;
        }

        logger.info("Checking after the comma: " + p.getCode());

        int maxPrecision = p.getValue().getClass().equals(Float.class) ? 7 : 15;
        for (int precision = 1; precision <= maxPrecision; precision++) {
            if (LocalSearchBudget.getInstance().isFinished()) {
                break;
            }

            roundPrecision(test, objective, precision, p);
            logger.debug("Current precision: " + precision);
            if (performAVM(test, statement, objective, Math.pow(10.0, -precision), 2.0, p)) {
                improved = true;
            }
        }

        return improved;
    }

    @SuppressWarnings("unchecked")
    private boolean roundPrecision(TestChromosome test,
                                   LocalSearchObjective<TestChromosome> objective, int precision,
                                   NumericalPrimitiveStatement<T> p) {
        double value = p.getValue().doubleValue();
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return false;
        }

        BigDecimal bd = new BigDecimal(value).setScale(precision, RoundingMode.HALF_EVEN);
        if (bd.doubleValue() == value) {
            return false;
        }

        double newValue = bd.doubleValue();
        oldValue = p.getValue();
        ExecutionResult oldResult = test.getLastExecutionResult();

        if (p.getValue().getClass().equals(Float.class)) {
            p.setValue((T) Float.valueOf((float)newValue));
        } else
             {
            p.setValue((T) Double.valueOf(newValue));
        }

        logger.info("Trying to chop precision " + precision + ": " + value + " -> "
                + newValue);

        if (objective.hasNotWorsened(test)) {
            return true;
        } else {
            logger.info("Restoring old value: " + value);
            p.setValue(oldValue);
            test.setLastExecutionResult(oldResult);
            test.setChanged(false);
            return false;
        }

    }
}
