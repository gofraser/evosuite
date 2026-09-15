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
package org.evosuite.llm.search.operator;

import org.evosuite.llm.search.ProblemCard;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a one-shot goal-to-card lookup from a single {@link
 * OperatorTargetSelector.CardSource} call: a goal's card is the first (i.e.
 * highest-priority, since cards arrive ranked) card whose related goals
 * include it. A failing card source yields an empty index rather than
 * aborting selection, since cards are an enrichment, not a requirement.
 */
final class ProblemCardGoalIndex {

    private ProblemCardGoalIndex() {
    }

    static Map<TestFitnessFunction, ProblemCard> build(OperatorTargetSelector.CardSource cardSource,
                                                        Collection<TestFitnessFunction> currentGoals,
                                                        List<TestChromosome> population,
                                                        int maxCards,
                                                        Logger logger) {
        List<ProblemCard> cards;
        try {
            cards = cardSource.cards(currentGoals, population, maxCards);
        } catch (RuntimeException e) {
            logger.debug("Problem card lookup failed during operator target selection: {}", e.getMessage());
            return Collections.emptyMap();
        }
        if (cards == null || cards.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<TestFitnessFunction, ProblemCard> byGoal = new HashMap<>();
        for (ProblemCard card : cards) {
            if (card == null) {
                continue;
            }
            for (TestFitnessFunction goal : card.getRelatedGoals()) {
                byGoal.putIfAbsent(goal, card);
            }
        }
        return byGoal;
    }
}
