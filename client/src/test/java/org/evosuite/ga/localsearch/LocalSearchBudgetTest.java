/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.localsearch;

import org.evosuite.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LocalSearchBudgetTest {

    @BeforeEach
    public void setUp() {
        // Reset properties to default
        Properties.LOCAL_SEARCH_BUDGET = 5;
        Properties.LOCAL_SEARCH_BUDGET_TYPE = Properties.LocalSearchBudgetType.TIME;

        // Reset singleton if possible - since we can't easily reset a static singleton field
        // without reflection or added method, we have to deal with shared state or add a reset method.
        // For this test, I'll rely on public methods to reset state.
        LocalSearchBudget.getInstance().localSearchStarted();
    }

    @Test
    public void testSingleton() {
        LocalSearchBudget<?> instance1 = LocalSearchBudget.getInstance();
        LocalSearchBudget<?> instance2 = LocalSearchBudget.getInstance();
        assertSame(instance1, instance2, "Instances should be the same");
    }

    @Test
    public void testBudgetFinishedByFitnessEvaluations() {
        Properties.LOCAL_SEARCH_BUDGET_TYPE = Properties.LocalSearchBudgetType.FITNESS_EVALUATIONS;
        Properties.LOCAL_SEARCH_BUDGET = 2;

        LocalSearchBudget<?> budget = LocalSearchBudget.getInstance();
        budget.localSearchStarted();

        assertFalse(budget.isFinished());
        budget.countFitnessEvaluation();
        assertFalse(budget.isFinished());
        budget.countFitnessEvaluation();
        assertTrue(budget.isFinished(), "Budget should be finished after 2 evaluations");
    }
}
