package org.evosuite.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmBudgetCoordinatorTest {

    @Test
    void finiteBudgetIsConsumedAtomically() {
        LlmBudgetCoordinator.Local coordinator = new LlmBudgetCoordinator.Local(2);

        assertTrue(coordinator.tryAcquire());
        assertTrue(coordinator.tryAcquire());
        assertFalse(coordinator.tryAcquire());
        assertEquals(0, coordinator.getRemaining());
    }

    @Test
    void unlimitedBudgetAlwaysAcquires() {
        LlmBudgetCoordinator.Local coordinator = new LlmBudgetCoordinator.Local(0);

        for (int i = 0; i < 100; i++) {
            assertTrue(coordinator.tryAcquire());
        }
        assertEquals(-1, coordinator.getRemaining());
    }
}
