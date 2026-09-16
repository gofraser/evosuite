/*
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
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LlmBudgetCoordinator.FeatureShare}, which reserves a
 * share of the total LLM call budget for the LLM operator calls
 * ({@link LlmFeature#SEMANTIC_MUTATION}, {@link LlmFeature#SEMANTIC_CROSSOVER})
 * so they cannot be crowded out by (or crowd out) the other LLM features,
 * chiefly {@link LlmFeature#STAGNATION}.
 */
class LlmBudgetCoordinatorFeatureShareTest {

    private final int savedMaxCalls = Properties.LLM_MAX_CALLS;
    private final double savedShare = Properties.LLM_OPERATOR_CALL_SHARE;
    private final boolean savedStagnation = Properties.LLM_ON_STAGNATION;
    private final Properties.LlmOperatorMode savedOperatorMode = Properties.LLM_OPERATOR_MODE;
    private final int savedCallsPerGeneration = Properties.LLM_OPERATOR_CALLS_PER_GENERATION;
    private final double savedMutationProbability = Properties.LLM_MUTATION_PROBABILITY;
    private final double savedCrossoverProbability = Properties.LLM_CROSSOVER_PROBABILITY;
    private final boolean savedOperatorCrossover = Properties.LLM_OPERATOR_CROSSOVER;

    @AfterEach
    void restoreProperties() {
        Properties.LLM_MAX_CALLS = savedMaxCalls;
        Properties.LLM_OPERATOR_CALL_SHARE = savedShare;
        Properties.LLM_ON_STAGNATION = savedStagnation;
        Properties.LLM_OPERATOR_MODE = savedOperatorMode;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = savedCallsPerGeneration;
        Properties.LLM_MUTATION_PROBABILITY = savedMutationProbability;
        Properties.LLM_CROSSOVER_PROBABILITY = savedCrossoverProbability;
        Properties.LLM_OPERATOR_CROSSOVER = savedOperatorCrossover;
    }

    @Test
    void operatorQuotaIsExhaustedIndependentlyOfOtherFeatures() {
        LlmBudgetCoordinator.FeatureShare share = new LlmBudgetCoordinator.FeatureShare(
                new LlmBudgetCoordinator.Local(10), 10, 0.5);

        for (int i = 0; i < 5; i++) {
            assertTrue(share.tryAcquire(LlmFeature.SEMANTIC_MUTATION));
        }
        assertFalse(share.tryAcquire(LlmFeature.SEMANTIC_MUTATION));
        assertFalse(share.tryAcquire(LlmFeature.SEMANTIC_CROSSOVER));

        // The stagnation feature still has its own, untouched quota.
        assertTrue(share.tryAcquire(LlmFeature.STAGNATION));
    }

    @Test
    void nonOperatorFeaturesShareOneCombinedQuota() {
        LlmBudgetCoordinator.FeatureShare share = new LlmBudgetCoordinator.FeatureShare(
                new LlmBudgetCoordinator.Local(10), 10, 0.5);

        int acquired = 0;
        while (share.tryAcquire(LlmFeature.STAGNATION)) {
            acquired++;
        }
        assertEquals(5, acquired);
        assertFalse(share.tryAcquire(LlmFeature.SEEDING));

        // Operator quota is unaffected.
        assertTrue(share.tryAcquire(LlmFeature.SEMANTIC_CROSSOVER));
    }

    @Test
    void getRemainingReportsQuotaBoundedValue() {
        LlmBudgetCoordinator.FeatureShare share = new LlmBudgetCoordinator.FeatureShare(
                new LlmBudgetCoordinator.Local(10), 10, 0.5);

        assertEquals(5, share.getRemaining(LlmFeature.SEMANTIC_MUTATION));
        assertEquals(5, share.getRemaining(LlmFeature.STAGNATION));

        assertTrue(share.tryAcquire(LlmFeature.SEMANTIC_MUTATION));
        assertEquals(4, share.getRemaining(LlmFeature.SEMANTIC_MUTATION));
        assertEquals(5, share.getRemaining(LlmFeature.STAGNATION));
    }

    @Test
    void fullShareLeavesNothingForOtherFeatures() {
        LlmBudgetCoordinator.FeatureShare share = new LlmBudgetCoordinator.FeatureShare(
                new LlmBudgetCoordinator.Local(10), 10, 1.0);

        assertEquals(10, share.getRemaining(LlmFeature.SEMANTIC_MUTATION));
        assertEquals(0, share.getRemaining(LlmFeature.STAGNATION));
        assertFalse(share.tryAcquire(LlmFeature.STAGNATION));
    }

    @Test
    void zeroShareLeavesNothingForOperators() {
        LlmBudgetCoordinator.FeatureShare share = new LlmBudgetCoordinator.FeatureShare(
                new LlmBudgetCoordinator.Local(10), 10, 0.0);

        assertEquals(0, share.getRemaining(LlmFeature.SEMANTIC_MUTATION));
        assertFalse(share.tryAcquire(LlmFeature.SEMANTIC_CROSSOVER));
        assertEquals(10, share.getRemaining(LlmFeature.STAGNATION));
    }

    @Test
    void oddMaxCallsRoundsOperatorShareToNearestInteger() {
        LlmBudgetCoordinator.FeatureShare share = new LlmBudgetCoordinator.FeatureShare(
                new LlmBudgetCoordinator.Local(7), 7, 0.5);

        assertEquals(4, share.getRemaining(LlmFeature.SEMANTIC_MUTATION));
        assertEquals(3, share.getRemaining(LlmFeature.STAGNATION));
    }

    @Test
    void fromPropertiesReturnsLocalWhenStagnationIsOff() {
        Properties.LLM_MAX_CALLS = 10;
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_ON_STAGNATION = false;

        assertTrue(LlmBudgetCoordinator.fromProperties() instanceof LlmBudgetCoordinator.Local);
    }

    @Test
    void fromPropertiesReturnsLocalWhenMaxCallsIsUnlimited() {
        Properties.LLM_MAX_CALLS = 0;
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_ON_STAGNATION = true;

        assertTrue(LlmBudgetCoordinator.fromProperties() instanceof LlmBudgetCoordinator.Local);
    }

    @Test
    void fromPropertiesReturnsLocalWhenOperatorsAreOff() {
        Properties.LLM_MAX_CALLS = 10;
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.OFF;
        Properties.LLM_ON_STAGNATION = true;

        assertTrue(LlmBudgetCoordinator.fromProperties() instanceof LlmBudgetCoordinator.Local);
    }

    @Test
    void fromPropertiesReturnsFeatureShareWhenAllThreeConditionsHold() {
        Properties.LLM_MAX_CALLS = 10;
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_ON_STAGNATION = true;
        Properties.LLM_MUTATION_PROBABILITY = 0.1;
        Properties.LLM_CROSSOVER_PROBABILITY = 0.1;

        assertTrue(LlmBudgetCoordinator.fromProperties() instanceof LlmBudgetCoordinator.FeatureShare);
    }

    @Test
    void fromPropertiesReturnsLocalWhenAsyncOperatorsCannotSpendAnyCall() {
        Properties.LLM_MAX_CALLS = 10;
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        Properties.LLM_ON_STAGNATION = true;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = 0;

        // Reserving half the budget for an executor that never submits a call would just
        // strand it, unreachable by stagnation or any other LLM feature.
        assertFalse(LlmBudgetCoordinator.operatorsCanSpend());
        assertTrue(LlmBudgetCoordinator.fromProperties() instanceof LlmBudgetCoordinator.Local);
    }

    @Test
    void fromPropertiesReturnsLocalWhenSyncOperatorProbabilitiesAreBothZero() {
        Properties.LLM_MAX_CALLS = 10;
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_ON_STAGNATION = true;
        Properties.LLM_MUTATION_PROBABILITY = 0.0;
        Properties.LLM_CROSSOVER_PROBABILITY = 0.0;

        // Neither breeding-loop hook can ever fire, so the SYNC operators can never spend
        // their reserved share either.
        assertFalse(LlmBudgetCoordinator.operatorsCanSpend());
        assertTrue(LlmBudgetCoordinator.fromProperties() instanceof LlmBudgetCoordinator.Local);
    }

    @Test
    void fromPropertiesReturnsLocalWhenCrossoverProbabilityIsPositiveButCrossoverTargetingIsOff() {
        Properties.LLM_MAX_CALLS = 10;
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_ON_STAGNATION = true;
        Properties.LLM_MUTATION_PROBABILITY = 0.0;
        Properties.LLM_CROSSOVER_PROBABILITY = 0.5;
        Properties.LLM_OPERATOR_CROSSOVER = false;

        // A positive crossover probability spends nothing when the selector never hands out a
        // crossover target to begin with, so reserving a share for it would just strand it.
        assertFalse(LlmBudgetCoordinator.operatorsCanSpend());
        assertTrue(LlmBudgetCoordinator.fromProperties() instanceof LlmBudgetCoordinator.Local);
    }
}
