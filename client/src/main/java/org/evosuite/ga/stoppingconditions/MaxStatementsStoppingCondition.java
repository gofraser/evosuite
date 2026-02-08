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
/*
 * Copyright (C) 2010 Saarland University
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License along with
 * EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */

package org.evosuite.ga.stoppingconditions;

import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>
 * MaxStatementsStoppingCondition class.
 * </p>
 * <p>
 * Note: This class tracks the number of executed statements globally via a static counter.
 * This design is coupled with {@link org.evosuite.testcase.execution.TestCaseExecutor}.
 * </p>
 *
 * @author Gordon Fraser
 */
public class MaxStatementsStoppingCondition<T extends Chromosome<T>> extends StoppingConditionImpl<T> {

    private static final long serialVersionUID = 8521297417505862683L;

    @SuppressWarnings({"unused"})
    private static final Logger logger = LoggerFactory.getLogger(MaxStatementsStoppingCondition.class);

    /**
     * Maximum number of iterations.
     */
    protected static final AtomicLong currentStatement = new AtomicLong(0);

    public MaxStatementsStoppingCondition() {
        // empty constructor
    }

    public MaxStatementsStoppingCondition(MaxStatementsStoppingCondition<?> that) {
        // empty copy constructor
    }

    @Override
    public MaxStatementsStoppingCondition<T> clone() {
        return new MaxStatementsStoppingCondition<>(this);
    }

    /**
     * Add a given number of executed statements.
     *
     * @param num a int.
     */
    public static void statementsExecuted(int num) {
        currentStatement.addAndGet(num);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Finished, if the maximum number of statements has been reached.</p>
     */
    @Override
    public boolean isFinished() {
        return currentStatement.get() >= Properties.SEARCH_BUDGET;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reset counter.</p>
     */
    @Override
    public void reset() {
        currentStatement.set(0);
    }

    /**
     * <p>getNumExecutedStatements.</p>
     *
     * @return a long.
     */
    public static long getNumExecutedStatements() {
        return currentStatement.get();
    }

    /**
     * <p>setNumExecutedStatements.</p>
     *
     * @param value a long.
     */
    public static void setNumExecutedStatements(long value) {
        currentStatement.set(value);
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.StoppingCondition#getCurrentValue()
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCurrentValue() {
        return currentStatement.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLimit() {
        return Properties.SEARCH_BUDGET;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forceCurrentValue(long value) {
        currentStatement.set(value);
    }

    @Override
    public void setLimit(long limit) {
        // No-op
        // The limit should be set by setting Properties.SEARCH_BUDGET
    }

}
