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
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Accumulates per-target {@link TargetSnapshot}s plus the batch-wide union of lineages/card
 * types/goals used to attribute an unmatched response. Captured on the search thread, before a
 * batch's call goes out, so {@link LlmOperatorExecutor} has everything it needs to attribute
 * whatever the worker thread eventually publishes.
 *
 * <p>Each target is registered as its own {@link org.evosuite.llm.search.RepeatedInjectionMemory}
 * attempt (see {@link #add}), so a target missing from the response can be closed with a zero
 * gain without also closing — or leaving dangling — any other target's attempt in the same batch.
 */
final class OperatorBatchAttribution {

    private final List<TargetSnapshot> snapshots;
    private final Set<Long> lineages = new TreeSet<>();
    private final List<ProblemCardType> cardTypes = new ArrayList<>();
    private final List<TestFitnessFunction> goals = new ArrayList<>();

    OperatorBatchAttribution(int size) {
        snapshots = new ArrayList<>(size);
    }

    /**
     * Adds one target, already registered as its own attempt.
     *
     * @param target    the target being added
     * @param attemptId that target's own attempt id ({@code ""} when it could not be registered,
     *                  e.g. no memory is wired up)
     */
    void add(OperatorTarget target, String attemptId) {
        Set<Long> targetLineages = new TreeSet<>(target.getParent().effectiveLineages());
        target.getPartner().ifPresent(partner -> targetLineages.addAll(partner.effectiveLineages()));
        ProblemCardType cardType = target.getCard().map(ProblemCard::getType).orElse(null);
        String safeAttemptId = attemptId == null ? "" : attemptId;
        snapshots.add(new TargetSnapshot(targetLineages, target.getGoal(), cardType, safeAttemptId));
        lineages.addAll(targetLineages);
        if (cardType != null) {
            cardTypes.add(cardType);
        }
        goals.add(target.getGoal());
    }

    /** Number of targets accumulated so far. */
    int size() {
        return snapshots.size();
    }

    /** Per-target snapshot at {@code index} (0-based), in the order targets were added. */
    TargetSnapshot snapshot(int index) {
        return snapshots.get(index);
    }

    /** Union of lineages across every target in the batch. */
    Set<Long> getLineages() {
        return lineages;
    }

    /** Card types across every target in the batch that carried one. */
    List<ProblemCardType> getCardTypes() {
        return cardTypes;
    }

    /** Goals across every target in the batch. */
    List<TestFitnessFunction> getGoals() {
        return goals;
    }

    /** This batch's per-target attempt ids, in the order targets were added (may contain {@code ""}). */
    List<String> getAttemptIds() {
        List<String> ids = new ArrayList<>(snapshots.size());
        for (TargetSnapshot snapshot : snapshots) {
            ids.add(snapshot.getAttemptId());
        }
        return ids;
    }

    /** Per-target attribution captured on the search thread before the call goes out. */
    static final class TargetSnapshot {
        private final Set<Long> lineages;
        private final TestFitnessFunction goal;
        private final ProblemCardType cardType;
        private final String attemptId;

        private TargetSnapshot(Set<Long> lineages, TestFitnessFunction goal, ProblemCardType cardType,
                               String attemptId) {
            this.lineages = lineages;
            this.goal = goal;
            this.cardType = cardType;
            this.attemptId = attemptId;
        }

        Set<Long> getLineages() {
            return lineages;
        }

        TestFitnessFunction getGoal() {
            return goal;
        }

        ProblemCardType getCardType() {
            return cardType;
        }

        /** This target's own attempt id ({@code ""} when it could not be registered). */
        String getAttemptId() {
            return attemptId;
        }
    }
}
