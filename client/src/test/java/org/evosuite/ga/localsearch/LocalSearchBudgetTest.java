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
